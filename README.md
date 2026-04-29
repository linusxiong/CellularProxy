# CellularProxy

[简体中文](README.zh-CN.md)

CellularProxy is an Android app project that keeps a user-visible HTTP service/proxy running on the phone. External devices can connect to it, and the user can choose which network route proxy traffic should use, such as cellular data, Wi-Fi, or VPN.

Project principles: network selection, HTTP serving, foreground service lifecycle, configuration storage, UI, builds, and releases use standard Android capabilities. Root is optional and is used only for persistent root assistance plus mobile-data and airplane-mode controls.

## Features

- Material Design 3 UI with content laid out to avoid system bars and navigation bars.
- HTTP proxy support for regular HTTP requests and HTTPS `CONNECT` tunnels.
- Default route shows only currently available egress types, preventing unavailable Wi-Fi/VPN/cellular settings from being saved.
- The dashboard automatically probes the current public IP when the app starts.
- Rotation public-IP probing always uses the cellular route because mobile-data rotation success must be judged through cellular egress.
- In-app language switching supports system language, English, and Simplified Chinese.
- The Management API token is used only by the app's internal local control API. It is not the password for external proxy clients.

## Development

The Gradle wrapper requires JDK 17 or newer to launch. On this machine, use an explicit modern JDK if the shell has an older `JAVA_HOME`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test --no-daemon
```

Common verification commands:

```sh
./gradlew ktlintCheck test :app:assembleDebug --no-daemon
ANDROID_SERIAL=<device-serial> ./gradlew :app:connectedDebugAndroidTest --no-daemon
```

GitHub Actions runs ktlint as part of CI. If CI reports a ktlint line-wrap failure, run:

```sh
./gradlew :app:ktlintFormat --no-daemon
./gradlew :app:ktlintMainSourceSetCheck --no-daemon
```
