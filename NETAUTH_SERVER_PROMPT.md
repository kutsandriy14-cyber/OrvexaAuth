# NetAuth Server: Folder-Based Database Server Prompt & Deployment Guide

This document contains a complete, updated developer prompt and a deployment guide to run a lightweight, high-performance private database server on any machine (such as a PC, local server, or an Android device via **Termux** + **Ngrok**).

The server uses a **100% pure folder-and-file-based architecture** (no external SQLite or MySQL files required!). This makes the server incredibly robust, completely immune to database corruptions, and trivial to manage—each user account is a physical folder containing simple JSON files and direct storage subdirectories.

Additionally, separate isolated folders are automatically created under the `/apps/` subdirectory for each connected application/site utilizing our authentication system to keep localized event records and login histories.

---

## 🚀 SERVER-SIDE SYSTEM ARCHITECTURE (FOLDER-BASED)

Under this design, multiple separated databases are supported dynamically via the `X-Database-Name` header. Within each database directory:

```text
storage/
  ├── <database_name>/                  # e.g., "default", "work", "gaming"
  │     ├── accounts/
  │     │     └── <sanitized_email>/    # Folder created upon user registration
  │     │           ├── profile.json    # Contains credentials, profiles, password hash
  │     │           └── storage/        # Cloud storage subfolder for user's files & progress
  │     ├── chats/
  │     │     └── chat_<u1>_and_<u2>.json # Shared list of conversation messages between two users
  │     └── apps/
  │           └── <sanitized_app_name>/ # Automatically created for each connected app/site
  │                 └── history.json    # Records successful registrations and login events
```

---

## 📋 BACKEND DEV PROMPT (FEED TO AI CODING ASSISTANTS)

> **Copy and paste the prompt below into any LLM to generate or extend this server:**
>
> "Act as an expert Backend Developer. Create a lightweight REST API server using Python and Flask. The server MUST NOT use any SQLite, MySQL, or Mongo databases. Instead, it must store all user profiles, messages, and uploaded files inside a structured directory system under a root folder called `storage/`.
> 
> Here are the updated technical specifications:
>
> 1. **CORS & Preflight Handling**:
>    - Ensure all API endpoints return proper standard JSON objects and CORS headers. 
>    - Intercept `OPTIONS` preflight requests in `@app.before_request` and immediately return a standard HTTP 204 response containing the headers:
>      - `Access-Control-Allow-Origin: *`
>      - `Access-Control-Allow-Headers: Content-Type,X-Database-Name,X-Service-Key,X-App-Name,Authorization`
>      - `Access-Control-Allow-Methods: GET,PUT,POST,DELETE,OPTIONS`
> 
> 2. **Multi-Database Separations**:
>    - Read the `X-Database-Name` request header (default to 'default' if empty) to identify which active database the client wants to communicate with. Sanitise this string to prevent path traversal attacks.
> 
> 3. **Secure Service Key (API Authorization)**:
>    - Define a configurable `SERVICE_KEY` variable (loaded from an environment variable `NETAUTH_SERVICE_KEY` or config, defaulting to a secure string).
>    - On all API requests (except `/api/status` and preflights), extract the `X-Service-Key` header. If it does not match the configured key, return an HTTP 403 Forbidden error with a JSON error message.
> 
> 4. **Folder-based Account Creation**:
>    - Upon account creation (`/api/register`), create a subfolder: `storage/<database_name>/accounts/<sanitized_email>/`.
>    - Inside this folder, write a file called `profile.json` containing the user profile object (id, email, passwordHash, firstName, lastName, birthDate, gender, avatarColor, phoneNumber, recoveryEmail, and createdAt timestamp).
>    - Also create a subdirectory `storage/<database_name>/accounts/<sanitized_email>/storage/` where files uploaded by this user will be placed physically.
> 
> 5. **Dynamic App-Specific Logins & History Logging**:
>    - When a user successfully logs in (`/api/login`) or registers (`/api/register`), extract the app's name from either the `X-App-Name` request header or the JSON request body's `"appName"` field (default to `"Unknown_App"`).
>    - Sanitize this app name and create a folder on the server: `storage/<database_name>/apps/<sanitized_app_name>/`.
>    - Append a new log entry to `history.json` inside that app folder:
>      `{ "email": "user@example.com", "action": "login" | "register", "timestamp": epoch_millis }`. Limit history to the last 1000 entries.
>    - Add a `GET /api/apps/<app_name>/history` route allowing the administrator to retrieve log histories for that specific app.
> 
> 6. **Account Authentication & Operations**:
>    - `/api/login`: Read `profile.json` in the user's account folder, verify the `passwordHash`, and trigger app event logging.
>    - `/api/users`: List all registered accounts by scanning the `accounts/` folder and reading their respective `profile.json`.
>    - `/api/users/<id>` (PUT): Update details in the user's `profile.json`. Find the email of the matching user ID by checking the active database accounts.
>    - `/api/users/<id>/password` (PUT): Update password hash inside the user's `profile.json`.
>    - `/api/users/<id>` (DELETE): Delete the entire user folder (`storage/<database_name>/accounts/<sanitized_email>/`) recursively.
> 
> 7. **Cloud File Storage**:
>    - `/api/users/<id>/storage` (GET): List metadata (name, size, updatedAt) of all files inside the user's `storage/` directory.
>    - `/api/users/<id>/storage` (POST): Write files directly to the user's `storage/` directory. Support both raw textual content or Base64 binary formats.
>    - `/api/users/<id>/storage/<fileName>` (GET): Download the raw file content directly from the disk.
>    - `/api/users/<id>/storage/<fileName>` (DELETE): Remove the specified file from the user's `storage/` directory.
> 
> 8. **Chat Messages (Shared Chats)**:
>    - `/api/messages` (GET): Fetch messages between `user1` and `user2` from `storage/<database_name>/chats/chat_<user1>_and_<user2>.json` (sort the user emails alphabetically to construct a unique, shared file name).
>    - `/api/messages` (POST): Append new message objects (id, senderEmail, receiverEmail, text, timestamp) to the shared JSON file.
> 
> 9. **Local WiFi Auto-Discovery**:
>    - Start an independent background UDP thread listening on port `8888`. When it receives the query message `"NETAUTH_DISCOVER"`, it should reply with the message `"NETAUTH_SERVER:<url_of_http_server>/"` back to the sender, facilitating out-of-the-box automatic IP configuration for devices in the same Wi-Fi network.
> 
> 10. **Database Maintenance & Partition Reset**:
>     - `/api/database/clear` (POST): Completely wipes the active database partition (`X-Database-Name`) on the server. Recursively delete the directory `storage/<database_name>/` and recreate it empty so that all accounts, storage files, shared chats, and connected application histories are fully cleared for that partition.
> 
> 11. **Administrative Hardware Ban & IP Banning (RESTRICTED)**:
>     - **Strict Permission Control**: Hardware and IP banning is strictly restricted to administrators verified via `X-Service-Key`. Ordinary users are strictly forbidden from issuing hardware bans; they can only manage their personal mute/block list.
>     - `/api/bans` (POST): Admin-only. Ban a hardware identifier (IP or MAC address) for the active database partition. Accepts a JSON payload: `{"value": "192.168.1.50", "banType": "IP" | "MAC"}`. Stores bans in `storage/<database_name>/bans.json`.
>     - `/api/bans/<value>` (DELETE): Admin-only. Remove the hardware identifier from the banned list.
>     - `/api/bans` (GET): Admin-only. Retrieve all active hardware bans for the database partition.
>     - **Strict Validation**: On registration (`/api/register`) and login (`/api/login`), extract the client's IP address (from request remote address) and any optional MAC/hardware identifiers passed by the client. If any matches a banned entry in the active partition's `bans.json`, immediately block the request and return HTTP 403 Forbidden with error "Access denied: Hardware/IP Ban".
> 
> 12. **Server-Side Validation & Account Limits**:
>     - **Registration Limits**: To prevent resource abuse, the server must reject registration attempts if the number of accounts in the active database partition exceeds 3. If a 4th account registration is attempted, return HTTP 400 Bad Request with error "Maximum accounts reached on this database partition".
>     - **Duplicate Prevention**: Ensure `/api/register` validates that the email, and the combination of firstName + lastName, are not already registered in the active partition before creating a new account folder.
> 
> 13. **Full Administrative Access, Database Management & Complete Account Exports (.af & .chat)**:
>     - `/api/admin/users/export` (GET): Allows the administrator (verified via `X-Service-Key`) to export a master database backup, or individual profiles.
>     - **Full Account Backups (.af)**: An `.af` (Account File) export must contain **absolutely all data** associated with the user account to allow seamless migration or complete download:
>       - User profile metadata (id, email, password, firstName, lastName, birthDate, gender, phone, recoveryEmail, avatarColor).
>       - Password hash and KeyProtect.
>       - All uploaded cloud files (base64 encoded inside the JSON or archived together in a zip container).
>       - User's local blocklist / spam filter settings.
>       - Every single chat conversation thread involving the user (all direct message transcripts mapped into the backup structure).
>     - `/api/admin/users/import` (POST): Allows importing/restoring a `.af` account file. Automatically creates or updates account folders, registers credentials, restores blocklists, uploads their physical files to `storage/<database_name>/users/<id>/storage/`, and reconstructs their message files on the server.
>     - `/api/admin/chats` (GET): Allows retrieving all chat transcript JSONs (`chat_<u1>_and_<u2>.json`) from the partition for moderation/audit.
>     - `/api/admin/chats/export` (GET): Allows exporting chat histories as `.chat` files (a structured JSON list of messages, authors, and timestamps) for secure local backup.
>     - `/api/admin/chats/import` (POST): Allows importing `.chat` file transcripts back to the server partition.
>     - `/api/admin/chats/delete` (POST): Admin endpoint to delete specific messages or whole conversation threads.
> 
> 14. **User-Controlled Spam Prevention (Mute & Block Lists - Like Telegram)**:
>     - **Spam Block Policy**: Standard users can only block or mute other users to stop spamming (similar to Telegram blocking), without affecting the global server network or hardware access.
>     - `/api/users/<id>/blocklist` (GET): Retrieve a list of emails blocked by this user.
>     - `/api/users/<id>/blocklist` (POST): Add an email (e.g. `{"blockedEmail": "spammer@netauth.lan"}`) to the user's local blocklist (saved as `blocklist.json` in their account folder).
>     - `/api/users/<id>/blocklist/<email>` (DELETE): Remove an email from the blocklist.
>     - **Messaging Restriction**: When a client calls `/api/messages` to send a message, verify whether the receiver has the sender on their blocklist. If the sender is blocked, return HTTP 403 Forbidden with error "You have been blocked by this user".
> 
> 15. **Public File Sharing from User Cloud Disk**:
>     - `/api/users/<id>/storage/<fileName>/share` (POST): Register a file as public. Create/update a `shared_files.json` registry file in the partition root. Return a shareable key or direct URL (e.g., `/api/public/shared/<share_key>`).
>     - `/api/public/shared/<share_key>` (GET): Anyone can download this shared file without authentication headers.
>     - `/api/public/shared` (GET): Retrieve a list of all publicly shared files on this partition (name, size, uploader, description).
> 
> 16. **Minecraft Launcher API Modules (Addons, Maps, Resource Packs & Lan Play)**:
>     - `/api/minecraft/content` (POST): Upload a zip package containing a map, addon, or resource pack. Save inside `storage/<database_name>/minecraft_content/<content_type>/`. Include metadata: title, description, creatorEmail, and type ("map" | "addon" | "texture_pack").
>     - `/api/minecraft/content` (GET): Search and list uploaded Minecraft contents.
>     - `/api/minecraft/friends` (POST/GET/DELETE): A simple friend-list manager. Users can send invitations (`/api/minecraft/friends/invite`), accept invitations (`/api/minecraft/friends/accept`), and fetch their current friends list (`/api/minecraft/friends/list`).
>     - `/api/minecraft/multiplayer/host` (POST): Register/heartbeat an active hosted local LAN server (e.g. standard Minecraft world opened to LAN). Payload includes: `{"hostEmail": "user@netauth.lan", "localIp": "192.168.1.10", "port": 54321, "worldName": "My Survival World"}`.
>     - `/api/minecraft/multiplayer/discover` (GET): Allows a client to query active LAN world sessions hosted by their **mutual friends** on the same server partition. Returns IP and port details for direct integration in the Minecraft client or launcher."
>

---

## 🛠️ DEPLOYMENT INSTRUCTIONS FOR TERMUX (ANDROID PHONE-SERVER)

If you wish to use an old Android phone as a 100% free, 24/7 online hosting server in your local network or over the internet, follow these steps:

### 1. Set Up Environment in Termux
Make sure you install Termux from **F-Droid** or **GitHub Releases** (do not use Google Play as it is outdated). Launch Termux and run:

```bash
# Update and upgrade core packages (Press Enter/Yes to all prompts)
pkg update && pkg upgrade -y

# Request physical storage permissions
termux-setup-storage

# Install Python, Git, and utilities
pkg install python git wget -y

# Install Flask for launching the server API
pip install flask
```

### 2. Write and Run the Server
Create a folder for the server and save the Python server file inside:

```bash
mkdir -p ~/netauth-server && cd ~/netauth-server
```

Create `server.py` and run it:
```bash
python server.py
```

### 3. Expose Server Globally via Ngrok
To access your database server from outside your Wi-Fi network (using mobile data or other Wi-Fi networks) completely for free:

1. Sign up on [https://ngrok.com](https://ngrok.com) and retrieve your **Authtoken**.
2. Download Ngrok inside Termux:
   ```bash
   cd ~
   wget https://bin.equinox.io/c/b34edq8Zmqn/ngrok-v3-stable-linux-arm64.tgz
   tar -xvzf ngrok-v3-stable-linux-arm64.tgz
   mv ngrok ~/../usr/bin/ && chmod +x ~/../usr/bin/ngrok
   rm ngrok-v3-stable-linux-arm64.tgz
   ```
3. Connect your token:
   ```bash
   ngrok config add-authtoken <YOUR_AUTHTOKEN>
   ```
4. Start Ngrok in a separate Termux tab (Swipe from the left edge -> **New Session**):
   ```bash
   ngrok http 8080
   ```
5. Copy the HTTPS **Forwarding URL** (e.g., `https://a1b2-34-56-78.ngrok-free.app`) and enter it as your **Server URL** in your OrvexaAuth Beta app configuration!

Your folder-based cloud server is now fully active, secure, and accessible worldwide!
