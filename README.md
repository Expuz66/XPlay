# XPlay

XPlay is a branded Android client for Jellyfin. It combines the Jellyfin web interface with native Android playback, downloads, media controls, and device integration.

## Version

- Release: **1.0.1**
- Application ID: `com.expuz.explay`
- Proprietary debug application ID: `com.expuz.explay.debug`
- Android namespace: `org.jellyfin.mobile`

## Features

- XPlay branding and launcher artwork.
- Local-first Jellyfin connection selection with HTTPS fallback.
- Configurable `LOCAL` / `REMOTE` connection status in the navigation drawer.
- Jellyfin authentication and session restoration across configured endpoints.
- Jellyfin web interface hosted in Android WebView.
- Native Media3/ExoPlayer playback with subtitles, transcoding, seeking, and playback controls.
- Android media notification and Quick Settings controls.
- Downloads for offline playback.
- External player support.
- Chromecast support in the proprietary flavor.
- Android Auto/media browser integration.

## Requirements

- Android SDK with the configured compile and target SDK platforms.
- Java 11-compatible Android build environment.
- Android device or emulator meeting the minimum SDK requirement.
- A reachable Jellyfin server.

## Endpoint configuration

Deployment endpoints are supplied at build time. Do not store credentials, API keys, or access tokens in source control.

```powershell
.\gradlew.bat assembleProprietaryDebug `
  "-Pexpuz.local.endpoint=http://192.168.10.111:8096" `
  "-Pexpuz.remote.endpoint=https://stream.expuz-server.my.id"
```

The resolver probes the configured LAN endpoint first and uses the configured HTTPS endpoint only when the local endpoint is unavailable or invalid.

## Build with Android Studio

1. Open the repository in Android Studio.
2. Allow Gradle synchronization to complete.
3. Select the `proprietaryDebug` variant for Chromecast support, or `libreDebug` for the libre build.
4. Configure endpoint Gradle properties as needed.
5. Build or run the selected variant.

Command-line debug build:

```powershell
.\gradlew.bat assembleProprietaryDebug `
  "-Pexpuz.local.endpoint=<LOCAL_ENDPOINT>" `
  "-Pexpuz.remote.endpoint=<HTTPS_ENDPOINT>"
```

## Installation

Install a verified debug APK with Android Debug Bridge:

```powershell
adb install -r app/build/outputs/apk/proprietary/debug/explay-v1.0.1-proprietary-debug.apk
```

Do not uninstall an existing installation when testing unless its data migration impact is understood.

## Project structure

```text
app/                 Android application module
app/src/main/        Shared application code and resources
app/src/libre/       Libre flavor sources
app/src/proprietary/ Proprietary flavor sources
app/src/debug/       Debug-only resources
app/src/test/        JVM unit tests
app/schemas/         Room database schemas
buildSrc/            Gradle helper logic
fastlane/            Store metadata and screenshots
gradle/              Version catalog and Gradle wrapper files
icon/                Source branding artwork
```

## Credits

XPlay is based on the official Jellyfin Android client and retains Jellyfin web-client and SDK integration. The project also uses AndroidX, Jetpack Compose, Kotlin, Koin, Room, Media3/ExoPlayer, Coil, and other third-party libraries declared by Gradle.

## License

This repository includes `LICENSE.md`, which contains the GNU General Public License, version 2. The applicable license and upstream notices remain in that file.
