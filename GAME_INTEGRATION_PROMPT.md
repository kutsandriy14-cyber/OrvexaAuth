# 🎮 OrvexaAuth Beta & Custom Minecraft Launcher Connection Guide
# 🌐 Промт Авто-Подключения и Интеграции Лаунчера Майнкрафт (UDP/LAN Поиск)

This guide contains the exact technical specification for developers or AI systems to implement automatic server discovery, local network discovery, and API integration in any custom **Minecraft Launcher**, application, or game website.

---

## 🇺🇸 ENGLISH: SERVER AUTO-DISCOVERY & CONNECTION CONTRACT

### 1. BACKGROUND SERVER AUTO-DISCOVERY (HOW THE LAUNCHER FINDS THE URL)
To allow the launcher to find the NetAuth server URL automatically on the local Wi-Fi/Ethernet network without manual input, implement the following two protocols:

#### A. UDP Broadcast Discovery (Primary)
The launcher broadcasts a UDP packet on the local network. The active NetAuth Server listens on port `8888` and replies with its HTTP URL.
1. **Socket Setup**: Create a UDP Socket, enable broadcast options, and set a timeout of `2000ms`.
2. **Send Payload**: Broadcast the string payload `"NETAUTH_DISCOVER"` to IP `255.255.255.255` on Port `8888`.
3. **Listen for Response**: Read the incoming response. The server will reply with:
   `"NETAUTH_SERVER:<url_of_http_server>/"` (e.g., `"NETAUTH_SERVER:http://192.168.1.15:8080/"`).
4. **Parsing**: Strip `"NETAUTH_SERVER:"` prefix to extract the dynamic URL.

#### B. Active Subnet Scan (Fallback)
If UDP broadcast is blocked by the router, scan the current local subnet IP range on the server's default port (`8080`).
1. **Get Local IP**: Retrieve the host's local IP address (e.g., `192.168.1.45`).
2. **Generate Range**: Determine the subnet range (e.g., `192.168.1.1` to `192.168.1.254`).
3. **Parallel Ping**: Launch multi-threaded/asynchronous HTTP GET requests to `/api/status` on Port `8080` for each IP in the range (e.g., `http://192.168.1.X:8080/api/status`). Set a very short connection timeout (`150ms`).
4. **Match Success**: The first IP responding with HTTP 200 and a valid JSON structure `{"status": "ok", ...}` is your active server URL!

---

### 2. CORE API INTEGRATION & MULTIPLAYER

#### A. Mandatory Request Headers
Every request sent to the discovered server URL must include:
```http
X-Service-Key: <ADMIN_SERVICE_KEY>
X-Database-Name: <PARTITION_NAME>
X-App-Name: MinecraftLauncher_v2
```

#### B. User Login
Send a `POST` request to `/api/login` with client-side SHA-256 hashed password:
```json
{
  "email": "user@netauth.lan",
  "passwordHash": "SHA256_HEX_STRING_OF_PASSWORD",
  "appName": "MinecraftLauncher_v2"
}
```

#### C. Minecraft Content (Addons, Maps, Resource Packs)
- **Upload**: `POST` to `/api/minecraft/content` (Multipart form-data: `file` ZIP, `title`, `description`, `creatorEmail`, `type` ["map"|"addon"|"texture_pack"], `isPublic` ["true"|"false"]).
- **Browse**: `GET` to `/api/minecraft/content` with parameters `type` and `searchQuery` to retrieve downloadable packages.

#### D. Friends & Multiplayer LAN Play
- **Friend List**: `GET` to `/api/minecraft/friends/list?email=me@netauth.lan` to find active friends.
- **LAN Host Heartbeat (Host Friend)**: Every 10 seconds, send a `POST` to `/api/minecraft/multiplayer/host` containing the local IP/port of the Minecraft world opened to LAN:
  `{"hostEmail": "friend_a@netauth.lan", "localIp": "192.168.1.100", "port": 54321, "worldName": "Epic World"}`.
- **LAN Discovery (Joining Friend)**: Periodically fetch active sessions via `GET` to `/api/minecraft/multiplayer/discover?email=friend_b@netauth.lan`. The server will dynamically verify friendship and return the friend's LAN socket address, allowing the launcher to automatically display the world in the multiplayer list!

---

## 🇷🇺 RUSSIAN: АВТО-ОБНАРУЖЕНИЕ СЕРВЕРА И СПЕЦИФИКАЦИЯ ДЛЯ ЛАУНЧЕРА

### 1. АВТОМАТИЧЕСКИЙ ПОИСК СЕРВЕРА В СЕТИ (ДЛЯ АДМИНИСТРАТОРА И КЛИЕНТОВ)
Чтобы лаунчер самостоятельно находил работающий NetAuth-сервер в локальной Wi-Fi/Ethernet сети без ручного ввода IP, реализуйте два метода обнаружения:

#### А. Поиск через UDP-Бродкаст (Основной метод)
Лаунчер отправляет широковещательный запрос в сеть. Сервер слушает порт `8888` и мгновенно отвечает своим адресом.
1. **Настройка сокета**: Создайте UDP сокет, разрешите отправку широковещательных пакетов (Broadcast) и установите таймаут ожидания `2000мс`.
2. **Отправка запроса**: Отправьте строковый пакет `"NETAUTH_DISCOVER"` на IP `255.255.255.255` на порт `8888`.
3. **Получение ответа**: Сервер пришлет ответ в формате:
   `"NETAUTH_SERVER:<url_of_http_server>/"` (например: `"NETAUTH_SERVER:http://192.168.1.15:8080/"`).
4. **Обработка**: Отрежьте префикс `"NETAUTH_SERVER:"`, чтобы получить готовый URL для HTTP-запросов.

#### Б. Многопоточное сканирование подсети (Резервный метод)
Если роутер блокирует UDP-бродкаст, лаунчер выполняет быстрый перебор IP-адресов текущей подсети на стандартном порту сервера (`8080`).
1. **Определение локального IP**: Узнайте собственный IP-адрес компьютера в сети (например, `192.168.1.45`).
2. **Генерация диапазона**: Сформируйте список адресов подсети (от `192.168.1.1` до `192.168.1.254`).
3. **Параллельный опрос**: Асинхронно запустите короткие HTTP GET-запросы на эндпоинт `/api/status` на порт `8080` для каждого IP (например: `http://192.168.1.X:8080/api/status`). Таймаут соединения сокета (Connection Timeout) должен быть минимальным — `150мс`.
4. **Успешный ответ**: Первый хост, который ответит статус-кодом 200 и валидным JSON-файлом `{"status": "ok", ...}`, и является искомым сервером NetAuth!

---

### 2. ИНТЕГРАЦИЯ СЕРВЕРНЫХ ФУНКЦИЙ И СЕТЕВАЯ ИГРА

#### А. Обязательные HTTP заголовки
При отправке любого запроса к обнаруженному серверу, лаунчер должен передавать:
```http
X-Service-Key: <ADMIN_SERVICE_KEY>
X-Database-Name: <PARTITION_NAME>
X-App-Name: MinecraftLauncher_v2
```

#### Б. Авторизация (Login)
Отправьте `POST` на `/api/login` с SHA-256 хэшем пароля, вычисленным на клиенте:
```json
{
  "email": "user@netauth.lan",
  "passwordHash": "СТРОКА_ХЭША_SHA256",
  "appName": "MinecraftLauncher_v2"
}
```

#### В. Minecraft Контент (Карты, Моды, Ресурс-паки)
- **Загрузка на сервер (Upload)**: `POST` на `/api/minecraft/content` (формат Multipart: ZIP-файл `file`, `title`, `description`, `creatorEmail`, тип `type` ["map"|"addon"|"texture_pack"], приватность `isPublic` ["true"|"false"]).
- **Просмотр каталога**: `GET` на `/api/minecraft/content` с фильтрами по `type` и `searchQuery` для поиска и скачивания.

#### Г. LAN-Мультиплеер (Автоматическое подключение к друзьям по сети)
- **Список друзей**: Получите список взаимных друзей через `GET` к `/api/minecraft/friends/list?email=my@netauth.lan`.
- **Регистрация LAN мира (Хост-игрок)**: Каждые 10 секунд лаунчер хоста отправляет `POST` на `/api/minecraft/multiplayer/host` при открытии мира для сети:
  `{"hostEmail": "friend_a@netauth.lan", "localIp": "192.168.1.100", "port": 54321, "worldName": "Моё Выживание"}`.
- **Обнаружение LAN миров (Гость-игрок)**: Периодически лаунчер запрашивает активные лобби друзей через `GET` на `/api/minecraft/multiplayer/discover?email=friend_b@netauth.lan`. Сервер автоматически сопоставит статус "в друзьях" и вернет точный сокет-адрес мира (`192.168.1.100:54321`), который лаунчер мгновенно отобразит в списке сетевой игры!
