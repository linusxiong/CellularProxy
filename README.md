# CellularProxy

CellularProxy 是一个 Android 应用项目：在手机上常驻运行一个用户可见的 HTTP 服务/代理服务，允许外部设备连接，并由用户选择代理流量的出口网络，例如移动数据、Wi-Fi 或 VPN。

项目原则：网络选择、HTTP 服务、前台服务、配置存储、UI、构建发布全部使用 Android 标准能力；root 只作为可选能力，用于 root 常驻辅助，以及控制移动数据和飞行模式开关。

技术方案见：[docs/TECHNICAL_PLAN.md](docs/TECHNICAL_PLAN.md)。

## Development

The Gradle wrapper requires JDK 17 or newer to launch. On this machine, use an explicit modern JDK if the shell has an older `JAVA_HOME`:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test --no-daemon
```
