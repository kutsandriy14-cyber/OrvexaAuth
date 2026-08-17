# OrvexaAuth

**OrvexaAuth Beta 0.1.1** is the Android client for the public OrvexaAuth authentication and social API. The project is designed for the Orvexa ecosystem: the Android application acts as a trusted account companion, while the Windows launcher can use the same HTTPS API for account and multiplayer-social features.

## Architecture

The beta uses a single public Cloudflare Worker API endpoint:

`https://orvexaauth-api.bot724524.workers.dev`

The Android client is **Cloudflare-only**. It has no local server mode, no saved server endpoint, no LAN discovery, no Firebase, no Google Sign-In and no Google Drive synchronization. Transport uses HTTPS/TLS, while sensitive local session material is encrypted with Android Keystore and excluded from Android backup rules.

Registration for a PC account is provided by the existing Orvexa web registration flow. The Android client is intended for sign-in, account security and companion features rather than hosting a local authentication server.

## Android build

Prerequisites are Android Studio or a Java 11/21 environment with the Android SDK installed. From the `client` project directory, run:

```bash
./gradlew :app:assembleDebug --no-daemon
```

The debug APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Do not commit `local.properties`, signing keys, generated build directories or APK files. The repository workflow builds the APK and publishes a prerelease asset automatically on pushes to `main`.

## Credential Manager

The app contains an optional Android Credential Manager adapter. It delegates password saving and retrieval to the provider selected by Android, which may be Google Password Manager. OrvexaAuth never stores the clear-text password in its own preferences or database. Users can decline the provider prompt and continue using the regular Cloudflare API sign-in flow.

## Updates

The client checks the OrvexaAuth GitHub Releases endpoint over HTTPS. When a newer beta release is available, the user can download the APK through Android DownloadManager and approve installation in the system package installer. The release workflow publishes a SHA-256 checksum alongside the APK; users should verify the checksum before installing manually downloaded builds.

## Cloudflare Worker

The Worker source is in `termux-server/cloudflare_worker.js`. Deploy it with Wrangler from a separately configured environment. The KV binding name is `ORVEXAAUTH_KV`. Credentials and deployment tokens must be supplied through Cloudflare or CI secrets and must never be placed in source code.

## GitHub workflow

`.github/workflows/android.yml` performs the following actions on `main`:

1. Sets up JDK 21 and Gradle.
2. Builds the Beta 0.1.1 debug APK.
3. Generates a SHA-256 checksum.
4. Uploads the APK artifact.
5. Publishes or updates the `v0.1.1-beta01` prerelease with the APK and checksum.

## Scope exclusions

This beta intentionally excludes local authentication servers, saved server settings, Google Drive storage synchronization, Google Sign-In, server-role administration and a separate test-server mode. These exclusions keep the public beta smaller, easier to audit and compatible with the Cloudflare KV storage limits.

## License and status

This project is an experimental **beta**. APIs, data formats and social features may change between releases. Do not use the beta as the sole recovery mechanism for important accounts.
