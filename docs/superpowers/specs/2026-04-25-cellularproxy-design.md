# CellularProxy Design Spec

Date: 2026-04-25

## Summary

CellularProxy is an Android 10+ application that runs a user-visible foreground HTTP proxy service on the phone. External clients can connect through the configured listen address and port, then CellularProxy forwards requests through the user-selected Android network: Wi-Fi, cellular, VPN, or an automatic route policy.

The first version prioritizes a normal non-root Android app path. Root is optional and is only used for advanced controls: mobile data toggling, airplane mode toggling, and root-triggered service restart. Root is not required for the proxy, network discovery, socket binding, UI state, configuration storage, or Cloudflare management API tunnel.

Cloudflare support is limited to exposing the management API. HTTP proxy traffic does not go through Cloudflare Tunnel. Proxy clients should connect through local host, LAN, or Tailscale VPN. The Cloudflare Tunnel client is implemented natively in Kotlin for Android and supports token-based remotely-managed named tunnels only.

## Decisions

- `minSdk` is API 29, Android 10.
- The proxy listen address and port are user-configurable.
- The default proxy listen address is `0.0.0.0`.
- The default proxy listen port is `8080`.
- Proxy authentication is enabled by default and can be explicitly disabled by the user.
- Management API authentication is always required.
- Management API access is not limited by client network range.
- Cloudflare Tunnel is included in the first version, but only for the management API.
- Cloudflare Tunnel uses a user-provided remotely-managed tunnel token.
- The app does not create Cloudflare tunnels, configure DNS, or store Cloudflare API tokens.
- The Cloudflare Tunnel client is implemented in Kotlin/Android native code.
- The app does not use `cloudflared`, a Go sidecar, `gomobile`, or JNI.
- The first version does not output ABI-specific APKs because it does not include native libraries.
- `strictIpChangeRequired` defaults to `false`.
- Root persistent assistance uses root-triggered restart only, not a long-running watchdog shell loop.

## Architecture

The first version uses a standard Android proxy service with optional management API exposure through Cloudflare Tunnel.

Modules:

- `app`: Compose UI, ViewModels, settings screens, foreground service entry points, notifications, DataStore access, and log display.
- `core-network`: Android network discovery, route selection, bound DNS, bound outbound sockets, and public IP probing.
- `proxy-server`: HTTP proxy, HTTPS `CONNECT`, proxy authentication, management API, connection limits, timeout handling, statistics, and log redaction.
- `root-control`: root availability detection, root command execution, mobile data control, airplane mode control, root-triggered service restart, and root audit logging.
- `cloudflare-tunnel`: Kotlin-native minimal Cloudflare Tunnel client for management API exposure.
- `shared-model`: cross-module state models, configuration models, events, metrics, and redacted log records.

The proxy service and Cloudflare Tunnel client both run under the normal app foreground service lifecycle. The Cloudflare module is not allowed to depend on root. The root module is not allowed to implement normal proxying, network discovery, socket binding, Cloudflare tunneling, or UI state management.

## Proxy Service

The proxy server listens on the configured address and port. The default is `0.0.0.0:8080`, so the service is reachable from any network interface that Android allows: local host, LAN, VPN interfaces such as Tailscale, and other reachable interfaces.

The user can change the listen address and port before starting the service. Supported listen hosts include `0.0.0.0`, `127.0.0.1`, and specific local interface IP addresses such as a LAN address or Tailscale address. Service startup validates that the listen host is legal, the port is within range, and the address/port pair can be bound.

The proxy supports:

- Plain HTTP proxy requests.
- HTTPS `CONNECT host:port` tunnels.
- Configurable proxy authentication.
- Connection idle timeout.
- Maximum concurrent connection limits.
- Abnormal disconnect cleanup.
- Redacted request and connection logs.

Proxy authentication is enabled by default through `Proxy-Authorization`. The user can explicitly disable it. When authentication is disabled and the service is listening on a broad interface such as `0.0.0.0`, the UI, notification, and status API show a high-risk state.

## Management API

The management API is served by the proxy server process but has a stricter security boundary than ordinary proxy traffic.

`GET /health` may return a minimal health response without sensitive configuration. All `/api/*` endpoints require the management API token, whether accessed locally, through LAN, through Tailscale, or through Cloudflare Tunnel.

When requests arrive through the Cloudflare tunnel path, only `GET /health` and `/api/*` are forwarded to the local management handler. All other paths and all explicit proxy request forms are rejected by the tunnel layer so Cloudflare cannot accidentally become a proxy ingress.

Initial endpoints:

- `GET /api/status`
- `GET /api/networks`
- `GET /api/ip`
- `GET /api/cloudflare/status`
- `POST /api/cloudflare/start`
- `POST /api/cloudflare/stop`
- `POST /api/rotate/mobile-data`
- `POST /api/rotate/airplane-mode`
- `POST /api/service/stop`

High-impact endpoints, including service stop, rotation, and Cloudflare start/stop, require authentication and audit logging. Rotation and tunnel lifecycle operations also apply cooldown or duplicate-operation protection.

## Network Routing

`core-network` uses Android standard APIs to discover available networks and maintain route state. It listens through `ConnectivityManager.NetworkCallback`, reads `NetworkCapabilities`, and maps Android `Network` instances into internal descriptors.

Supported route targets:

- Wi-Fi.
- Cellular.
- VPN.
- Automatic policy.

For outbound proxy connections, `core-network` must use per-connection binding rather than process-wide binding. DNS and socket creation are performed against the selected Android `Network` where possible.

The core interface is:

```kotlin
interface BoundSocketProvider {
    suspend fun connect(
        route: RouteTarget,
        host: String,
        port: Int,
        timeoutMillis: Long
    ): Socket
}
```

If the selected route disappears, new proxy requests fail quickly by default with a clear proxy error and a redacted log record. A later version may add a wait-for-route policy.

## Cloudflare Management API Tunnel

The first version implements a minimal Cloudflare Tunnel client in Kotlin/Android native code. It exists only to expose the management API through a Cloudflare public hostname.

Scope:

- Supports token-based remotely-managed named tunnels.
- Uses a user-pasted tunnel token.
- Does not use `cloudflared`.
- Does not use Go sidecars, `gomobile`, JNI, or native libraries.
- Does not create tunnels.
- Does not configure public hostnames or DNS.
- Does not store a Cloudflare API token.
- Does not tunnel HTTP proxy traffic.

The user is responsible for creating the Cloudflare tunnel and public hostname in Cloudflare. CellularProxy stores the tunnel token as sensitive configuration and uses it to connect to Cloudflare edge. The app may store an optional hostname label for display only.

Cloudflare tunnel states:

- `disabled`
- `starting`
- `connected`
- `degraded`
- `stopped`
- `failed`

Tunnel failure does not stop the local proxy. It only affects remote management API access and is shown in the UI, notification, status API, and logs.

Because this is a native reimplementation of Cloudflare tunnel behavior, it is a high-risk module. The implementation must be verified against a real Cloudflare account and tunnel. If the Kotlin implementation proves more complex than expected, the first-version requirement remains Kotlin-native unless the design is explicitly revised.

## Tailscale And LAN Proxy Access

HTTP proxy clients should connect directly to the configured proxy listen address through local host, LAN, or Tailscale. Tailscale is treated as a normal Android VPN interface exposed by the operating system. CellularProxy does not integrate with the Tailscale API in the first version.

Typical client configuration:

```text
proxy host: phone LAN IP or phone Tailscale IP
proxy port: configured proxy port, default 8080
```

This avoids relying on Cloudflare public hostname semantics for explicit HTTP proxy traffic and preserves normal HTTP proxy and HTTPS `CONNECT` behavior.

## Root Features

Root features are optional and disabled by default.

Root is used only for:

- Root availability detection.
- Mobile data off/on attempts.
- Airplane mode on/off attempts.
- Root-triggered service restart.
- Root audit logging.

Root is not used for:

- Ordinary HTTP proxying.
- Android network discovery.
- Outbound socket binding.
- Cloudflare Tunnel.
- UI state management.
- Configuration storage.
- CI or release builds.

All root commands must have timeouts, structured results, redacted stdout/stderr logs, and audit records. Root failure must not break non-root proxy features.

Mobile data rotation flow:

1. Check cooldown.
2. Check root availability.
3. Probe old public IP through the target route.
4. Pause new proxy requests.
5. Wait for existing requests to finish or reach the maximum drain time.
6. Run `svc data disable`.
7. Wait for the configured delay.
8. Run `svc data enable`.
9. Wait for the cellular network to return.
10. Probe new public IP.
11. Resume proxy requests.
12. Record the result.

Airplane mode rotation follows the same structure but uses airplane mode commands and a longer network return timeout.

`strictIpChangeRequired` defaults to `false`. If the public IP does not change after rotation, the event is recorded but does not count as a failure unless the user explicitly enables strict mode.

Root-triggered restart is implemented as an explicit best-effort action. The first version does not run a persistent watchdog shell loop.

## Configuration

Non-sensitive configuration is stored in Jetpack DataStore. Sensitive credentials are stored separately using Android Keystore-backed encrypted storage. Sensitive values must not be stored in plain DataStore records.

Initial configuration keys:

- `proxy.listenHost`: default `0.0.0.0`
- `proxy.listenPort`: default `8080`
- `proxy.authEnabled`: default `true`
- `proxy.maxConcurrentConnections`: default `64`
- `proxy.authCredential`: generated or user-provided, encrypted
- `management.apiToken`: generated on first run, always required, encrypted
- `network.defaultRoutePolicy`: Wi-Fi, cellular, VPN, or automatic
- `rotation.strictIpChangeRequired`: default `false`
- `rotation.mobileDataOffDelay`: default `3s`
- `rotation.networkReturnTimeout`: default `60s`
- `rotation.cooldown`: default `180s`
- `cloudflare.enabled`: default `false`
- `cloudflare.tunnelToken`: user-pasted sensitive token, encrypted
- `cloudflare.managementHostnameLabel`: optional display-only hostname label

Sensitive values are not shown permanently in clear text and are excluded or redacted from log export.

## Security

Security requirements:

- Management API token authentication is mandatory for all `/api/*` endpoints.
- Proxy authentication is enabled by default.
- Disabling proxy authentication is an explicit user action.
- Broad listen addresses such as `0.0.0.0` are clearly shown in UI and notifications.
- Cloudflare Tunnel exposes only the management API.
- Cloudflare management access still requires the management API token.
- Cloudflare tunnel tokens are treated as sensitive credentials.
- Root operations are disabled by default and require explicit user action.
- Root operations are cooldown-limited and audit-logged.
- Logs and exports redact sensitive fields.

Sensitive fields:

- `Authorization`
- `Proxy-Authorization`
- `Cookie`
- `Set-Cookie`
- URL query strings
- Management API token
- Proxy credentials
- Cloudflare tunnel token

## Error Handling

Service startup fails before binding if configuration is invalid. Startup errors include invalid listen address, invalid port, invalid maximum concurrent connection limit, port already in use, missing management token, unavailable selected route, and missing Cloudflare tunnel token when Cloudflare is enabled.

Proxy errors are returned without leaking sensitive request data. DNS failures, socket connection failures, selected-route loss, authentication failures, idle timeouts, and client disconnects are logged with redaction.

Cloudflare errors are isolated to remote management API access. A tunnel failure changes the tunnel state and emits a log event, but does not stop the local proxy.

Root command failures are recorded with command category, timeout status, exit status, and redacted output. Root failures do not disable non-root functionality.

## Observability

The app records:

- Service start and stop.
- Listen host and port.
- Active and total connections.
- Rejected connections.
- Bytes in and bytes out.
- Current route target.
- Network availability changes.
- Bound DNS and socket route category.
- Public IP probe results.
- Proxy authentication failures.
- Management API authentication failures.
- Cloudflare tunnel state changes.
- Root command start, end, timeout, and failure.
- Rotation results.

The Dashboard and notification show service state, listen address, current route, public IP, active connections, Cloudflare tunnel status, root status, and high-risk security states.

## Testing

Unit tests cover:

- Configuration validation.
- Listen address parsing.
- Port range validation.
- Proxy authentication.
- Management API token authentication.
- Log redaction.
- Route policy.
- Network descriptor conversion.
- Root command result parsing.
- Rotation state machine.
- Cloudflare tunnel state transitions.

Proxy protocol tests cover:

- Plain HTTP proxy requests.
- HTTPS `CONNECT`.
- DNS failure.
- Outbound connect timeout.
- Idle timeout.
- Maximum concurrent connections.
- Client disconnect cleanup.

Android tests cover:

- Foreground service start and stop.
- Notification stop action.
- DataStore read and write.
- Compose status rendering.
- No-root state display.
- Root-available state display.
- Cloudflare tunnel state display.

Cloudflare tests cover:

- Missing tunnel token.
- Invalid tunnel token.
- Edge connection failure.
- Reconnection.
- Remote `/api/status` access through the public hostname.
- Token redaction.
- Tunnel stop without stopping the local proxy.

LAN and Tailscale real-device tests cover:

- Client connects through phone LAN IP.
- Client connects through phone Tailscale IP.
- HTTP proxy request succeeds.
- HTTPS `CONNECT` succeeds.
- Proxy authentication enabled.
- Proxy authentication disabled.

Root real-device tests cover:

- Root unavailable.
- Root authorization rejected.
- Root authorization granted.
- Mobile data off/on command success.
- Airplane mode command success.
- Command success but network state unchanged.
- Network return timeout.
- Rotation with unchanged public IP.
- Cooldown rejection.
- Root-triggered restart.

CI covers:

- `ktlintCheck`
- Unit tests
- Debug build
- Release build on tags
- Universal APK
- AAB
- `SHA256SUMS`
- `mapping.txt` when available

## Acceptance Criteria

- Android 10+ devices can start and stop the foreground proxy service.
- Users can modify proxy listen address and port.
- The default proxy listener is `0.0.0.0:8080`.
- External clients can use the proxy through LAN or Tailscale.
- HTTP proxy requests work through the selected outbound route.
- HTTPS `CONNECT` works through the selected outbound route.
- Users can select Wi-Fi, cellular, VPN, or automatic route policy.
- Public IP probing uses the selected route.
- Management API always requires token authentication.
- Proxy authentication is enabled by default and can be disabled explicitly.
- Cloudflare Tunnel client is Kotlin-native and exposes the management API.
- Cloudflare Tunnel supports token-based remotely-managed named tunnels.
- Cloudflare Tunnel does not carry HTTP proxy traffic.
- No-root devices can use proxying, route selection, management API, and Cloudflare management API tunnel.
- Root devices can additionally use mobile data rotation, airplane mode rotation, and root-triggered restart.
- `strictIpChangeRequired` defaults to `false`.
- Logs and exports do not leak sensitive tokens, proxy credentials, Cloudflare tunnel tokens, or sensitive headers.
- GitHub Actions builds debug and release artifacts.

## Milestones

1. Android foundation: Kotlin, Compose, DataStore, foreground service, notification, settings shell.
2. Network routing: `NetworkMonitor`, route policy, bound DNS/socket, public IP probe.
3. HTTP proxy service: configurable listen address and port, HTTP proxy, HTTPS `CONNECT`, proxy authentication, management API, redaction, metrics.
4. Cloudflare management API tunnel: Kotlin-native token-based tunnel client, lifecycle state machine, reconnection, management API forwarding, redaction.
5. Root control and rotation: root detection, command runner, mobile data control, airplane mode control, rotation state machine, audit logs, root-triggered restart.
6. CI and release: ktlint, tests, debug CI, release signing, universal APK, AAB, checksums, GitHub Release.
7. Real-device acceptance: Android 10+, no-root, root, Wi-Fi, cellular, VPN, LAN, Tailscale, and Cloudflare management API tunnel.
