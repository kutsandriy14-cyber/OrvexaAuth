# Полное Руководство: Запуск домашнего сервера NetAuth в Termux с туннелированием через Ngrok

Ты абсолютно прав! Запуск сервера внутри клиентского приложения на телефоне — это костыль. Настоящий домашний хостинг должен работать независимо. **Termux** — это полноценная среда Linux на твоем Android-устройстве, которая идеально подходит для запуска нашего Python-сервера баз данных. А **Ngrok** позволит тебе получить бесплатный публичный статический или динамический адрес с HTTPS, чтобы подключаться к своему домашнему серверу с любого устройства из любой точки мира (через мобильный интернет или любой Wi-Fi) абсолютно бесплатно!

Ниже представлено пошаговое руководство на русском языке со всеми командами, которые нужно просто скопировать и вставить в Termux.

---

## Шаг 1. Установка Termux на телефон-сервер

> **ВАЖНО:** Не устанавливай Termux из Google Play! Версия там давно устарела и репозитории в ней не работают.
1. Скачай актуальный APK-файл Termux из **F-Droid** или напрямую с GitHub:
   * Ссылка на F-Droid: [https://f-droid.org/packages/com.termux/](https://f-droid.org/packages/com.termux/)
   * Ссылка на GitHub Releases: [https://github.com/termux/termux-app/releases](https://github.com/termux/termux-app/releases) (выбирай файл `termux-app_v0.118.1+github-debug_universal.apk` или новее).
2. Установи скачанный APK на телефон, который будет выступать в роли сервера.

---

## Шаг 2. Обновление репозиториев и установка окружения

Запусти Termux и поочередно выполни следующие команды для обновления системы и установки Python, SQLite и необходимых инструментов:

```bash
# 1. Обновляем пакетную базу и системные утилиты (на все запросы нажимаем Enter или Y)
pkg update && pkg upgrade -y

# 2. Разрешаем доступ к хранилищу телефона (появится системный запрос — подтверди его)
termux-setup-storage

# 3. Устанавливаем Python, SQLite, Git и wget
pkg install python git sqlite wget -y

# 4. Устанавливаем Flask (веб-фреймворк для нашего сервера)
pip install flask
```

---

## Шаг 3. Создание и запуск Python-сервера

Создадим отдельную папку для нашего сервера баз данных и запишем туда скрипт `server.py`:

```bash
# 1. Создаем папку netauth-server и переходим в нее
mkdir -p ~/netauth-server && cd ~/netauth-server
```

Теперь создай файл `server.py`. Для этого введи команду:
```bash
cat << 'EOF' > server.py
import os
import sys
import json
import socket
import threading
import time
import base64
import shutil
from flask import Flask, request, jsonify

# Initialize Flask App
app = Flask(__name__)

# Service Key Configuration (Customize this value or load from environment variable)
SERVICE_KEY = os.environ.get("NETAUTH_SERVICE_KEY", "my_secure_secret_key")

def is_service_key_enabled():
    if not SERVICE_KEY:
        return False
    val = SERVICE_KEY.strip().lower()
    return val not in ("", "none", "disable", "disabled", "false")

# CORS Middleware for global clients
@app.after_request
def after_request(response):
    response.headers.add('Access-Control-Allow-Origin', '*')
    response.headers.add('Access-Control-Allow-Headers', 'Content-Type,X-Database-Name,X-Service-Key,X-App-Name,Authorization')
    response.headers.add('Access-Control-Allow-Methods', 'GET,PUT,POST,DELETE,OPTIONS')
    return response

@app.before_request
def check_service_key_and_options():
    # Automatically reply to OPTIONS preflight requests
    if request.method == "OPTIONS":
        response = app.make_default_options_response()
        response.headers.add('Access-Control-Allow-Origin', '*')
        response.headers.add('Access-Control-Allow-Headers', 'Content-Type,X-Database-Name,X-Service-Key,X-App-Name,Authorization')
        response.headers.add('Access-Control-Allow-Methods', 'GET,PUT,POST,DELETE,OPTIONS')
        return response, 204

    # Allow status checks without service key
    if request.path == "/api/status":
        return None

    # Verify key for other secure endpoints
    if is_service_key_enabled():
        client_key = request.headers.get("X-Service-Key")
        if client_key != SERVICE_KEY:
            return jsonify({
                "status": "error",
                "message": "Unauthorized: Invalid or missing X-Service-Key header"
            }), 403

BASE_STORAGE_DIR = "storage"

def log_app_event(db_name, email, action):
    # Retrieve app or site name from headers or body
    app_name = request.headers.get("X-App-Name")
    if not app_name:
        try:
            if request.is_json:
                app_name = request.json.get("appName")
        except Exception:
            pass
    if not app_name:
        app_name = "Unknown_App"

    # Sanitize application/site name for paths
    sanitized_app_name = "".join(c for c in app_name if c.isalnum() or c in ("-", "_")).strip() or "Unknown_App"

    # Create distinct app folder under: storage/<database_name>/apps/<app_name>/
    app_dir = os.path.join(BASE_STORAGE_DIR, db_name, "apps", sanitized_app_name)
    os.makedirs(app_dir, exist_ok=True)

    # Log event inside history.json
    history_file = os.path.join(app_dir, "history.json")
    history = []
    if os.path.exists(history_file):
        try:
            with open(history_file, "r", encoding="utf-8") as f:
                history = json.load(f)
        except Exception:
            pass

    history.append({
        "email": email,
        "action": action,
        "timestamp": int(time.time() * 1000)
    })

    # Limit history log entries to 1000
    if len(history) > 1000:
        history = history[-1000:]

    try:
        with open(history_file, "w", encoding="utf-8") as f:
            json.dump(history, f, indent=4, ensure_ascii=False)
    except Exception:
        pass

def get_db_name():
    # Retrieve dynamic database name from header
    db_name = request.headers.get("X-Database-Name", "default")
    # Sanitize database name
    return "".join(c for c in db_name if c.isalnum() or c in ("-", "_")).strip() or "default"

def get_user_account_dir(db_name, email):
    sanitized_email = email.strip().lower().replace("@", "_at_").replace(".", "_dot_")
    sanitized_email = "".join(c for c in sanitized_email if c.isalnum() or c in ("-", "_"))
    path = os.path.join(BASE_STORAGE_DIR, db_name, "accounts", sanitized_email)
    os.makedirs(path, exist_ok=True)
    return path

def get_user_storage_dir(db_name, email):
    path = os.path.join(get_user_account_dir(db_name, email), "storage")
    os.makedirs(path, exist_ok=True)
    return path

def find_email_by_id(db_name, user_id):
    accounts_dir = os.path.join(BASE_STORAGE_DIR, db_name, "accounts")
    if not os.path.exists(accounts_dir):
        return None
    for item in os.listdir(accounts_dir):
        item_path = os.path.join(accounts_dir, item)
        if os.path.isdir(item_path):
            profile_file = os.path.join(item_path, "profile.json")
            if os.path.exists(profile_file):
                try:
                    with open(profile_file, "r", encoding="utf-8") as f:
                        data = json.load(f)
                        if data.get("id") == int(user_id):
                            return data.get("email")
                except Exception:
                    pass
    return None

def get_chat_file(db_name, user1, user2):
    u1 = user1.strip().lower()
    u2 = user2.strip().lower()
    sorted_users = sorted([u1, u2])
    filename = f"chat_{sorted_users[0]}_and_{sorted_users[1]}.json"
    filename = "".join(c for c in filename if c.isalnum() or c in ("-", "_", "."))
    chats_dir = os.path.join(BASE_STORAGE_DIR, db_name, "chats")
    os.makedirs(chats_dir, exist_ok=True)
    return os.path.join(chats_dir, filename)

# UDP Auto-Discovery Responder (8888)
def start_udp_responder():
    udp_port = 8888
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.bind(('0.0.0.0', udp_port))
        print(f"[*] UDP Discovery listener active on port {udp_port}")
    except Exception as e:
        print(f"[!] Error starting UDP Discovery listener: {e}")
        return

    while True:
        try:
            data, addr = sock.recvfrom(1024)
            message = data.decode('utf-8', errors='ignore').strip()
            if message == "NETAUTH_DISCOVER":
                s = socket.socket(socket.AF_INET, socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
                try:
                    s.connect(('8.8.8.8', 80))
                    local_ip = s.getsockname()[0]
                except Exception:
                    local_ip = "127.0.0.1"
                finally:
                    s.close()

                response = f"NETAUTH_SERVER:http://{local_ip}:8080/"
                sock.sendto(response.encode('utf-8'), addr)
                print(f"[UDP] -> Sent discovery response to {addr[0]}:{addr[1]}")
        except Exception as e:
            print(f"[!] UDP socket error: {e}")
            time.sleep(1)

# API ENDPOINTS
@app.route('/api/status', methods=['GET'])
def get_status():
    db_name = get_db_name()
    return jsonify({
        "status": "ok",
        "message": f"OrvexaAuth API Active (DB: {db_name})",
        "serverTime": int(time.time() * 1000)
    }), 200

@app.route('/api/database/clear', methods=['POST'])
def clear_database():
    db_name = get_db_name()
    db_dir = os.path.join(BASE_STORAGE_DIR, db_name)
    if os.path.exists(db_dir):
        try:
            shutil.rmtree(db_dir)
            os.makedirs(db_dir, exist_ok=True)
            return jsonify({
                "status": "success",
                "message": f"Database partition '{db_name}' has been completely wiped on the server"
            }), 200
        except Exception as e:
            return jsonify({
                "status": "error",
                "message": f"Failed to clear partition: {str(e)}"
            }), 500
    return jsonify({
        "status": "success",
        "message": f"Database partition '{db_name}' was already empty"
    }), 200

@app.route('/api/users', methods=['GET'])
def get_users():
    db_name = get_db_name()
    users_list = []
    accounts_dir = os.path.join(BASE_STORAGE_DIR, db_name, "accounts")
    if os.path.exists(accounts_dir):
        for item in os.listdir(accounts_dir):
            item_path = os.path.join(accounts_dir, item)
            if os.path.isdir(item_path):
                profile_file = os.path.join(item_path, "profile.json")
                if os.path.exists(profile_file):
                    try:
                        with open(profile_file, "r", encoding="utf-8") as f:
                            users_list.append(json.load(f))
                    except Exception:
                        pass
    return jsonify(users_list), 200

@app.route('/api/register', methods=['POST'])
def register_user():
    db_name = get_db_name()
    data = request.json or {}
    email = data.get("email", "").strip().lower()
    password_hash = data.get("passwordHash", "")

    if not email or not password_hash:
        return jsonify({"status": "error", "message": "Email and passwordHash are required"}), 400

    account_dir = get_user_account_dir(db_name, email)
    profile_file = os.path.join(account_dir, "profile.json")

    if os.path.exists(profile_file):
        return jsonify({"status": "error", "message": "Email already registered"}), 409

    # Generate a deterministic unique ID based on email hash
    user_id = abs(hash(email)) % (10**9)

    profile_data = {
        "id": user_id,
        "email": email,
        "passwordHash": password_hash,
        "firstName": data.get("firstName", ""),
        "lastName": data.get("lastName", ""),
        "birthDate": data.get("birthDate", ""),
        "gender": data.get("gender", "Rather not say"),
        "avatarColor": data.get("avatarColor", -12543232),
        "phoneNumber": data.get("phoneNumber", ""),
        "recoveryEmail": data.get("recoveryEmail", ""),
        "keyProtect": data.get("keyProtect", ""),
        "createdAt": int(time.time() * 1000)
    }

    try:
        with open(profile_file, "w", encoding="utf-8") as f:
            json.dump(profile_data, f, indent=4, ensure_ascii=False)
        # Initialize storage subfolder
        get_user_storage_dir(db_name, email)
        # Log app-specific registration
        log_app_event(db_name, email, "register")
        return jsonify(profile_data), 201
    except Exception as e:
        return jsonify({"status": "error", "message": f"Failed to create profile: {str(e)}"}), 500

@app.route('/api/login', methods=['POST'])
def login_user():
    db_name = get_db_name()
    data = request.json or {}
    email = data.get("email", "").strip().lower()
    password_hash = data.get("passwordHash", "")

    account_dir = get_user_account_dir(db_name, email)
    profile_file = os.path.join(account_dir, "profile.json")

    if not os.path.exists(profile_file):
        return jsonify({"status": "error", "message": "User account not found"}), 404

    try:
        with open(profile_file, "r", encoding="utf-8") as f:
            profile_data = json.load(f)
        if profile_data.get("passwordHash") == password_hash:
            # Log app-specific login
            log_app_event(db_name, email, "login")
            return jsonify(profile_data), 200
        else:
            return jsonify({"status": "error", "message": "Invalid password"}), 401
    except Exception as e:
        return jsonify({"status": "error", "message": f"Failed to read profile: {str(e)}"}), 500

@app.route('/api/apps/<app_name>/history', methods=['GET'])
def get_app_history(app_name):
    db_name = get_db_name()
    sanitized_app_name = "".join(c for c in app_name if c.isalnum() or c in ("-", "_")).strip() or "Unknown_App"
    history_file = os.path.join(BASE_STORAGE_DIR, db_name, "apps", sanitized_app_name, "history.json")
    if os.path.exists(history_file):
        try:
            with open(history_file, "r", encoding="utf-8") as f:
                return jsonify(json.load(f)), 200
        except Exception as e:
            return jsonify({"status": "error", "message": f"Failed to read history: {str(e)}"}), 500
    return jsonify([]), 200

@app.route('/api/users/<int:user_id>', methods=['PUT'])
def update_profile(user_id):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    account_dir = get_user_account_dir(db_name, email)
    profile_file = os.path.join(account_dir, "profile.json")

    if not os.path.exists(profile_file):
        return jsonify({"status": "error", "message": "User not found"}), 404

    data = request.json or {}
    try:
        with open(profile_file, "r+", encoding="utf-8") as f:
            profile_data = json.load(f)
            profile_data["firstName"] = data.get("firstName", profile_data.get("firstName", ""))
            profile_data["lastName"] = data.get("lastName", profile_data.get("lastName", ""))
            profile_data["birthDate"] = data.get("birthDate", profile_data.get("birthDate", ""))
            profile_data["gender"] = data.get("gender", profile_data.get("gender", ""))
            profile_data["phoneNumber"] = data.get("phoneNumber", profile_data.get("phoneNumber", ""))
            profile_data["recoveryEmail"] = data.get("recoveryEmail", profile_data.get("recoveryEmail", ""))

            f.seek(0)
            json.dump(profile_data, f, indent=4, ensure_ascii=False)
            f.truncate()
        return jsonify(profile_data), 200
    except Exception as e:
        return jsonify({"status": "error", "message": f"Update failed: {str(e)}"}), 500

@app.route('/api/users/<int:user_id>/password', methods=['PUT'])
def update_password(user_id):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    account_dir = get_user_account_dir(db_name, email)
    profile_file = os.path.join(account_dir, "profile.json")

    if not os.path.exists(profile_file):
        return jsonify({"status": "error", "message": "User not found"}), 404

    data = request.json or {}
    password_hash = data.get("passwordHash")
    if not password_hash:
        return jsonify({"status": "error", "message": "passwordHash is required"}), 400

    try:
        with open(profile_file, "r+", encoding="utf-8") as f:
            profile_data = json.load(f)
            profile_data["passwordHash"] = password_hash
            f.seek(0)
            json.dump(profile_data, f, indent=4, ensure_ascii=False)
            f.truncate()
        return jsonify({"status": "success", "message": "Password successfully updated"}), 200
    except Exception as e:
        return jsonify({"status": "error", "message": f"Update failed: {str(e)}"}), 500

@app.route('/api/users/<int:user_id>', methods=['DELETE'])
def delete_user(user_id):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    account_dir = get_user_account_dir(db_name, email)
    if os.path.exists(account_dir):
        try:
            shutil.rmtree(account_dir)
            return jsonify({"status": "success", "message": "User account folder deleted successfully"}), 200
        except Exception as e:
            return jsonify({"status": "error", "message": f"Delete failed: {str(e)}"}), 500
    else:
        return jsonify({"status": "error", "message": "User folder not found"}), 404

# CLOUD STORAGE FILES
@app.route('/api/users/<int:user_id>/storage', methods=['GET'])
def get_files(user_id):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    storage_dir = get_user_storage_dir(db_name, email)
    files_list = []
    if os.path.exists(storage_dir):
        for item in os.listdir(storage_dir):
            item_path = os.path.join(storage_dir, item)
            if os.path.isfile(item_path):
                files_list.append({
                    "name": item,
                    "size": os.path.getsize(item_path),
                    "updatedAt": int(os.path.getmtime(item_path) * 1000)
                })
    return jsonify(files_list), 200

@app.route('/api/users/<int:user_id>/storage', methods=['POST'])
def upload_file(user_id):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    data = request.json or {}
    file_name = data.get("fileName", "").strip()
    content = data.get("content", "")
    content_b64 = data.get("contentBase64", "")

    if not file_name:
        return jsonify({"status": "error", "message": "fileName is required"}), 400

    file_name = os.path.basename(file_name)
    storage_dir = get_user_storage_dir(db_name, email)
    target_file = os.path.join(storage_dir, file_name)

    try:
        if content_b64:
            file_bytes = base64.b64decode(content_b64)
            with open(target_file, "wb") as f:
                f.write(file_bytes)
        else:
            with open(target_file, "w", encoding="utf-8") as f:
                f.write(content)
        return jsonify({
            "status": "success",
            "message": "File uploaded successfully",
            "file": {
                "name": file_name,
                "size": os.path.getsize(target_file),
                "updatedAt": int(os.path.getmtime(target_file) * 1000)
            }
        }), 200
    except Exception as e:
        return jsonify({"status": "error", "message": f"Upload failed: {str(e)}"}), 500

@app.route('/api/users/<int:user_id>/storage/<path:file_name>', methods=['GET'])
def download_file(user_id, file_name):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    file_name = os.path.basename(file_name)
    storage_dir = get_user_storage_dir(db_name, email)
    target_file = os.path.join(storage_dir, file_name)

    if not os.path.exists(target_file) or not os.path.isfile(target_file):
        return jsonify({"status": "error", "message": "File not found"}), 404

    try:
        from flask import send_file
        return send_file(target_file, mimetype='application/octet-stream', as_attachment=True, download_name=file_name)
    except Exception as e:
        return jsonify({"status": "error", "message": f"Download failed: {str(e)}"}), 500

@app.route('/api/users/<int:user_id>/storage/<path:file_name>', methods=['DELETE'])
def delete_file(user_id, file_name):
    db_name = get_db_name()
    email = find_email_by_id(db_name, user_id)
    if not email:
        return jsonify({"status": "error", "message": "User not found"}), 404

    file_name = os.path.basename(file_name)
    storage_dir = get_user_storage_dir(db_name, email)
    target_file = os.path.join(storage_dir, file_name)

    if os.path.exists(target_file) and os.path.isfile(target_file):
        try:
            os.remove(target_file)
            return jsonify({"status": "success", "message": "File deleted successfully"}), 200
        except Exception as e:
            return jsonify({"status": "error", "message": f"Delete failed: {str(e)}"}), 500
    else:
        return jsonify({"status": "error", "message": "File not found"}), 404

# CHAT MESSAGING
@app.route('/api/messages', methods=['GET'])
def get_messages():
    db_name = get_db_name()
    user1 = request.args.get("user1", "").strip().lower()
    user2 = request.args.get("user2", "").strip().lower()

    if not user1 or not user2:
        return jsonify([]), 200

    chat_file = get_chat_file(db_name, user1, user2)
    if os.path.exists(chat_file):
        try:
            with open(chat_file, "r", encoding="utf-8") as f:
                return jsonify(json.load(f)), 200
        except Exception:
            pass
    return jsonify([]), 200

@app.route('/api/messages', methods=['POST'])
def send_message():
    db_name = get_db_name()
    data = request.json or {}
    sender = data.get("senderEmail", "").strip().lower()
    receiver = data.get("receiverEmail", "").strip().lower()
    text = data.get("text", "").strip()

    if not sender or not receiver or not text:
        return jsonify({"status": "error", "message": "senderEmail, receiverEmail and text are required"}), 400

    chat_file = get_chat_file(db_name, sender, receiver)
    messages = []
    if os.path.exists(chat_file):
        try:
            with open(chat_file, "r", encoding="utf-8") as f:
                messages = json.load(f)
        except Exception:
            pass

    msg_id = len(messages) + 1
    new_msg = {
        "id": msg_id,
        "senderEmail": sender,
        "receiverEmail": receiver,
        "text": text,
        "timestamp": int(time.time() * 1000)
    }
    messages.append(new_msg)

    try:
        with open(chat_file, "w", encoding="utf-8") as f:
            json.dump(messages, f, indent=4, ensure_ascii=False)
        return jsonify({"status": "success", "message": "Message sent", "id": msg_id}), 200
    except Exception as e:
        return jsonify({"status": "error", "message": f"Failed to save message: {str(e)}"}), 500

if __name__ == '__main__':
    os.makedirs(BASE_STORAGE_DIR, exist_ok=True)
    udp_thread = threading.Thread(target=start_udp_responder, daemon=True)
    udp_thread.start()

    print("=" * 70)
    print("       🚀 NETAUTH DATABASE SERVER - STARTUP SUMMARY 🚀")
    print("=" * 70)
    print(f" [*] HTTP Port       : 8080 (Listen address: 0.0.0.0)")
    if is_service_key_enabled():
        print(f" [*] Service Key     : '{SERVICE_KEY}'")
        print(f"                     --> Set X-Service-Key in your Android App Settings!")
    else:
        print(f" [*] Service Key     : DISABLED (No key verification required)")

    print(f" [*] Local Storage   : {os.path.abspath(BASE_STORAGE_DIR)}")
    print(" [*] WiFi Discovery  : Active on UDP Port 8888")
    print("-" * 70)
    print("  To disable Service Key verification, set env: NETAUTH_SERVICE_KEY=none")
    print("=" * 70)
    print("\n[INFO] Starting Flask HTTP Server...")
    app.run(host='0.0.0.0', port=8080, debug=False, threaded=True)
EOF
```
```

Отлично! Файл создан. Теперь давай настроим туннель.

---

## Шаг 4. Установка и настройка Ngrok в Termux

**Ngrok** создаст безопасный зашифрованный HTTPS-туннель к твоему телефону.

1. Зарегистрируйся бесплатно на официальном сайте [https://ngrok.com](https://ngrok.com).
2. Зайди в свой личный кабинет Ngrok и скопируй свой персональный токен авторизации (Auth Token) в разделе **Your Authtoken**.
3. Установи Ngrok в Termux с помощью официального скрипта или скачав пакет напрямую:

```bash
# Скачиваем официальный стабильный бинарник ngrok для архитектуры ARM64 (большинство современных Android устройств)
cd ~
wget https://bin.equinox.io/c/b34edq8Zmqn/ngrok-v3-stable-linux-arm64.tgz

# Распаковываем архив
tar -xvzf ngrok-v3-stable-linux-arm64.tgz

# Переносим бинарник в системную директорию Termux для удобного запуска из любого места
mv ngrok ~/../usr/bin/
chmod +x ~/../usr/bin/ngrok

# Удаляем архив, чтобы не занимать место
rm ngrok-v3-stable-linux-arm64.tgz
```

4. Добавь свой скопированный токен авторизации в Ngrok:
```bash
ngrok config add-authtoken ТВОЙ_СКОПИРОВАННЫЙ_ТОКЕН_ИЗ_ЛИЧНОГО_КАБИНЕТА
```

---

## Шаг 5. Запуск сервера и туннеля (Две сессии Termux)

Для одновременного запуска сервера и туннеля нам понадобятся две сессии Termux:

### Сессия 1: Запуск Python HTTP-сервера
В первой открытой вкладке Termux введи:
```bash
cd ~/netauth-server
python server.py
```
Ты увидишь логи инициализации SQLite базы данных и сообщение: `[*] HTTP Сервер запускается на порту 8080...`.

### Сессия 2: Запуск туннеля Ngrok
1. Сделай свайп от левого края экрана Termux вправо и нажми кнопку **"New Session"** (или зажми экран в любом месте и выбери "More..." -> "New session").
2. Во второй открывшейся вкладке запусти туннель:
```bash
ngrok http 8080
```
3. На экране отобразится статус Ngrok. Найди строку **Forwarding**. Она будет выглядеть примерно так:
   `https://a8bc-123-45-67-89.ngrok-free.app`
4. **Скопируй этот адрес!** Это твой глобальный, бесплатный, безопасный HTTPS URL сервера, который работает прямо на твоем телефоне.

---

## Шаг 6. Подключение приложения-клиента

1. Открой клиентское приложение **NetAuth** на основном телефоне.
2. Перейди в раздел настроек подключения или выбери ручную настройку сервера.
3. Вставь скопированный Ngrok-адрес (например, `https://a8bc-123-45-67-89.ngrok-free.app`) в поле ввода адреса сервера и нажми "Подключиться" / "Сохранить".
4. Теперь твои мобильные клиенты, игры и сервисы будут мгновенно авторизовываться, создавать профили и сохранять игровой прогресс прямо в SQLite базу данных, хранящуюся в Termux на твоем домашнем телефоне-сервере, совершенно бесплатно!
