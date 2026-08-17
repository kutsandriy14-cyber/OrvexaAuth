# OrvexaAuth

**OrvexaAuth Beta 0.1.1** is the Android client for the public OrvexaAuth authentication and social API. The project is designed for the Orvexa ecosystem: the Android application acts as a trusted account companion, while the Windows launcher can use the same HTTPS API for account and multiplayer-social features.

## Architecture

The beta uses a single public Cloudflare Worker API endpoint:

`https://orvexaauth-api.bot724524.workers.dev`

The Android client is **Cloudflare-only**. It has no local server mode, no saved server endpoint, no LAN discovery, no Firebase, no Google Sign-In and no Google Drive synchronization. Transport uses HTTPS/TLS, while sensitive local session material is encrypted with Android Keystore and excluded from Android backup rules.

Registration for a PC account is provided by the OrvexaAuth web client. The Android client is intended for sign-in, account security and companion features rather than hosting a local authentication server.

## Android build

Prerequisites are Android Studio or a Java 11/21 environment with the Android SDK installed. From the `client` project directory, run:

```bash
./gradlew :app:assembleRelease --no-daemon
```

The signed release APK is created at:

```text
app/build/outputs/apk/release/app-release.apk
```

Every release must use the same private signing keystore. The keystore and `signing.properties` are intentionally ignored by Git; store their encrypted backup offline before creating another release. Do not commit `local.properties`, signing keys, generated build directories or APK files.

## Credential Manager

The app contains an optional Android Credential Manager adapter. It delegates password saving and retrieval to the provider selected by Android, which may be Google Password Manager. OrvexaAuth never stores the clear-text password in its own preferences or database. Users can decline the provider prompt and continue using the regular Cloudflare API sign-in flow.

## Updates

The client checks the OrvexaAuth GitHub Releases endpoint over HTTPS. When a newer beta release is available, the user can download the APK through Android DownloadManager and approve installation in the system package installer. Each manual GitHub release includes a SHA-256 checksum; users should verify the checksum before installing manually downloaded builds.

## Cloudflare Worker

The Worker source is in `termux-server/cloudflare_worker.js`. Deploy it with Wrangler from a separately configured environment. The KV binding name is `ORVEXAAUTH_KV`. Credentials and deployment tokens must be supplied through Cloudflare or CI secrets and must never be placed in source code.

### Authentication and social API

After a successful login or registration, the Worker returns a short-lived profile response together with a server-issued `sessionToken`. The Android client stores only that token in Android Keystore and automatically sends it as `Authorization: Bearer <token>`. Password hashes are used only during credential exchange and are not persisted as session material. Sessions expire after 30 days, can be validated with `GET /api/sessions/{token}`, revoked with `DELETE /api/sessions/{token}`, listed for the current account through `GET /api/users/{id}/sessions`, or terminated for all devices with `DELETE /api/users/{id}/sessions`.

Authenticated social routes currently include friend requests and acceptance (`/api/friends/request`, `/api/friends/{id}`, `/api/friends/{id}/accept`), block management (`/api/blocks`), profile presence (`GET /api/users/{id}`), group creation and membership (`/api/groups`), group messages (`/api/groups/{id}/messages`), personal messages (`/api/messages`) and Minecraft-session access control (`/api/minecraft/sessions`). These endpoints are beta APIs and require the bearer token; no API key or service secret is embedded in the Android, web or C++ client.

### QR sign-in between phone and desktop

The desktop client creates a five-minute request through `POST /api/qr/sessions` and receives an `approveUrl` in the form `orvexaauth://qr/approve?request=<id>`. Opening that URL on an Android device with OrvexaAuth transfers the request identifier to the app. The already authenticated mobile app confirms the request with its bearer session; the desktop client then polls `GET /api/qr/sessions/{id}` and receives a new server-issued token only after approval. The QR request itself contains no password and does not persist a token in the Launcher settings file.

## Releases

Automatic GitHub Actions publication is intentionally disabled. A maintainer creates a release only after building and verifying `assembleRelease` with the permanent private signing key. This prevents a new certificate from being generated for a later build and lets Android accept future updates that use the same application ID and signing certificate.

## Scope exclusions

This beta intentionally excludes local authentication servers, saved server settings, Google Drive storage synchronization, Google Sign-In, server-role administration and a separate test-server mode. These exclusions keep the public beta smaller, easier to audit and compatible with the Cloudflare KV storage limits.

## License and status

This project is an experimental **beta**. APIs, data formats and social features may change between releases. Do not use the beta as the sole recovery mechanism for important accounts.
