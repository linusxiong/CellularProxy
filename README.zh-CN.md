# CellularProxy

[English](README.md)

CellularProxy 是一个 Android 应用项目：在手机上常驻运行一个用户可见的 HTTP 服务/代理服务，允许外部设备连接，并由用户选择代理流量的出口网络，例如移动数据、Wi-Fi 或 VPN。

项目原则：网络选择、HTTP 服务、前台服务、配置存储、UI、构建发布全部使用 Android 标准能力；root 只作为可选能力，用于 root 常驻辅助，以及控制移动数据和飞行模式开关。

## 功能概览

- Material Design 3 界面，内容区会避让系统栏和导航栏。
- HTTP proxy 支持普通 HTTP 请求和 HTTPS `CONNECT` 隧道。
- Default route 只展示当前可用的出口类型，避免保存不可用 Wi-Fi/VPN/移动数据配置。
- App 首页启动后会自动探测当前公网 IP。
- Rotation 的公网 IP 探测固定走移动数据出口，因为移动数据轮换只能通过 cellular route 判断是否成功。
- 设置页支持应用内语言切换，当前提供系统语言、English 和简体中文。
- Management API token 仅用于应用内部本地控制接口，不是外部代理客户端的认证密码。

## Development

Gradle wrapper 需要 JDK 17 或更高版本才能启动。如果当前 shell 的 `JAVA_HOME` 较旧，可以在这台机器上显式使用较新的 JDK：

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test --no-daemon
```

常用验证命令：

```sh
./gradlew ktlintCheck test :app:assembleDebug --no-daemon
ANDROID_SERIAL=<device-serial> ./gradlew :app:connectedDebugAndroidTest --no-daemon
```

GitHub Actions 会在 CI 中运行 ktlint。如果 CI 报告 ktlint 换行格式问题，运行：

```sh
./gradlew :app:ktlintFormat --no-daemon
./gradlew :app:ktlintMainSourceSetCheck --no-daemon
```
