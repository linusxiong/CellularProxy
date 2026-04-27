# CellularProxy Compose And E2E MVP Design Spec

Date: 2026-04-27

Supersedes: `docs/superpowers/specs/2026-04-25-cellularproxy-design.md`

## Summary

This spec updates the CellularProxy product direction from the original Android proxy design into an end-to-end MVP closeout plan. The current repository already contains a multi-module Android/Kotlin foundation for proxy serving, management APIs, configuration, root controls, Cloudflare tunnel state, rotation orchestration, diagnostics-adjacent models, foreground service wiring, and tests. The next MVP should turn that foundation into a usable app console and a repeatable verification path.

The MVP will use Jetpack Compose, Material3, Navigation Compose, Lifecycle ViewModel, and StateFlow to replace the current native View-based Activity with a complete operator console. The console must cover daily operation, configuration, runtime status, root rotation, Cloudflare management tunnel control, logs, audit records, and diagnostics.

Cloudflare Tunnel remains management-only. The app must not route ordinary HTTP proxy traffic or HTTPS `CONNECT` traffic through Cloudflare. The Cloudflare tunnel client must remain Kotlin/Android native and must not use `cloudflared`, Go sidecars, `gomobile`, JNI, or native binaries.

Testing remains layered. JVM unit tests and pure Kotlin module tests are the default. Android emulator or virtual-device testing is used for Android UI, configuration, storage, foreground service, and fake lifecycle smoke tests. USB rooted real-device testing is reserved for paths that cannot be validated correctly without physical device behavior: root commands, real cellular data network switching, public IP probing over cellular, real Cloudflare edge connectivity, and end-to-end management API round trips through the tunnel.

The real Cloudflare tunnel token provided by the user may be used for explicit local/e2e validation, but it must not be written into this spec, source code, committed configuration, logs, snapshots, commit messages, PR descriptions, or exported diagnostic artifacts.

## Goals

- Build a complete Compose operator console for CellularProxy.
- Preserve the existing module boundaries and tested core behavior.
- Allow reliable external Android/Kotlin libraries when they reduce UI, state, protocol, serialization, network, or testing complexity.
- Keep Cloudflare Tunnel limited to management API access.
- Keep the Cloudflare tunnel client Kotlin/Android native.
- Support real tunnel token validation through UI entry and local, uncommitted developer/e2e injection.
- Prefer unit tests and pure Kotlin verification over device tests.
- Use emulator or virtual-device tests for Android-specific smoke coverage.
- Use USB rooted real devices only for necessary root, cellular, Cloudflare, and management-chain e2e validation.
- First merge the current branch's completed work plus this spec into `main` and push `main` to GitHub. Implement the Compose/e2e MVP as a follow-up phase from updated `main`.

## Non-Goals

- Do not send ordinary HTTP proxy traffic through Cloudflare Tunnel.
- Do not create Cloudflare tunnels from the app.
- Do not configure Cloudflare DNS or public hostnames from the app.
- Do not store Cloudflare API tokens.
- Do not use `cloudflared`, Go sidecars, `gomobile`, JNI, or native binaries for the tunnel client.
- Do not require root for normal proxy serving, UI, configuration, Cloudflare management tunnel, or non-root diagnostics.
- Do not use rooted real-device e2e as the default test path for ordinary UI or pure Kotlin changes.
- Do not commit real tunnel tokens, management API tokens, proxy credentials, or unredacted sensitive diagnostic output.
- Do not implement this spec in the design step. Implementation starts only after this written spec is reviewed and an implementation plan is created.

## Architecture And Module Boundaries

The MVP keeps the current multi-module architecture. Compose is an app-layer presentation and operation surface; it must not absorb socket handling, root command implementation, Cloudflare tunnel protocol internals, management routing, or rotation state machine logic.

### `app`

The `app` module owns Android entry points and user-facing composition:

- Compose UI, Material3 theme, Navigation Compose graph, and screen components.
- ViewModels, immutable UI state, UI events, one-shot effects, and StateFlow collection.
- Foreground service command dispatch and runtime status projection.
- Android storage wiring for DataStore and encrypted sensitive storage.
- Notification/status projection.
- Diagnostics orchestration.
- Mapping user actions into proxy, root, Cloudflare, management, and rotation actions through existing boundaries.

Recommended package boundaries inside `app`:

- `ui/compose`: screens, reusable components, navigation, theme.
- `ui/state`: immutable view state, events, effects, and state mappers.
- `viewmodel`: dashboard, settings, Cloudflare, rotation, diagnostics, and logs ViewModels.
- `diagnostics`: root check, route probe, public IP probe, management API check, Cloudflare connectivity test orchestration.
- `runtime`: foreground service command dispatch, runtime status projection, and lifecycle adapters.
- `config`: existing DataStore and sensitive-storage wiring.

### `shared-model`

`shared-model` continues to own cross-module models, configuration, policies, state machines, redaction rules, and stable public contracts used by UI and runtime code. Compose screens should consume stable view state mapped from these models, not reach into implementation-specific internals.

### `proxy-server`

`proxy-server` continues to own:

- Plain HTTP proxy support.
- HTTPS `CONNECT` tunneling.
- Proxy authentication.
- Management API routing and dispatch.
- Connection limits, timeout handling, pause/drain controls, metrics, and redacted proxy errors.

The UI must not directly handle sockets or streams.

### `core-network`

`core-network` continues to own:

- Android network discovery.
- Route selection.
- Route-bound DNS and socket creation.
- Public IP probing.

Diagnostics should call this behavior through app-layer use cases or stable interfaces instead of duplicating route logic.

### `root-control`

`root-control` continues to own:

- Root availability checks.
- Root command execution.
- Mobile data and airplane mode command controllers.
- Root-triggered restart behavior.
- Root audit records.

Root failure must not break non-root proxy features.

### `cloudflare-tunnel`

`cloudflare-tunnel` continues to own:

- Kotlin/Android native Cloudflare tunnel behavior.
- Tunnel token parsing and validation.
- Tunnel lifecycle states and transitions.
- Edge/session state.
- Reconnect/degrade handling.
- Management-only ingress filtering.

This module must not depend on root and must not expose ordinary proxy ingress.

## External Library Policy

Reliable external libraries are allowed when they clearly reduce complexity or improve correctness. Candidate categories:

- Jetpack Compose BOM, `activity-compose`, Material3, Lifecycle ViewModel Compose, Navigation Compose.
- Kotlin coroutines and StateFlow.
- AndroidX lifecycle/runtime/testing libraries.
- Serialization libraries for structured protocol and diagnostic payloads.
- Mature HTTP/WebSocket/TLS/CBOR/JSON support where useful for protocol implementation or diagnostics.
- Testing libraries that improve fake runtime wiring, coroutine testing, or Android semantics tests.

External libraries must not replace the product requirement that Cloudflare Tunnel is implemented natively in Kotlin/Android. A library may support protocol primitives, encoding, TLS, HTTP, WebSocket, backoff, or tests, but the tunnel lifecycle, token handling, ingress filtering, and management-only product boundary remain owned by the app's Kotlin modules.

## Compose Console Design

The Compose app is an operator console, not one long settings form. It should organize the product around common operating tasks: daily status, configuration, remote management, rotation, diagnostics, and audit review.

The console uses Material3 `Scaffold`, top app bar, and adaptive navigation. On phones it may use a bottom navigation bar; on wider screens it may use a navigation rail or two-pane layout. Navigation Compose owns screen routing. Each major screen has its own ViewModel or a clearly scoped child state to avoid rebuilding the current oversized Activity pattern.

### Dashboard

Dashboard shows:

- Proxy service running/stopped state.
- Current proxy endpoint.
- Selected outbound route.
- Proxy authentication and broad-listener risk state.
- Management API status.
- Cloudflare tunnel state.
- Root availability.
- Current public IP when known.
- Active connection count and recent traffic summary when available.
- Recent high-severity errors.

Primary actions:

- Start proxy.
- Stop proxy.
- Refresh status.
- Copy proxy endpoint.
- Open relevant detail screens for risk, Cloudflare, rotation, logs, or diagnostics.

### Settings

Settings edits:

- Listen host.
- Listen port.
- Proxy authentication enabled.
- Proxy username/password update.
- Maximum concurrent connections.
- Default route policy.
- Management API token update.
- Rotation timing and strict IP change mode.
- Root operations enabled.
- Cloudflare enabled.
- Cloudflare tunnel token update.
- Cloudflare management hostname label.

Sensitive fields use the existing "leave blank to keep current value" behavior. Full secrets are not displayed after save.

### Cloudflare

Cloudflare screen shows:

- Tunnel enabled/disabled.
- Token present/missing/invalid status.
- Tunnel lifecycle state: disabled, starting, connected, degraded, stopped, failed.
- Management hostname label.
- Last connection error category.
- Edge/session summary when available.
- Last management API round-trip result through the tunnel when available.

Actions:

- Start tunnel.
- Stop tunnel.
- Reconnect tunnel.
- Test management tunnel.
- Copy redacted diagnostic summary.

### Rotation

Rotation screen shows:

- Root availability.
- Root operations enabled/disabled.
- Cooldown status.
- Last rotation result.
- Old and new public IPs when available.
- Current rotation phase.
- Pause/drain status.
- Strict IP change mode.

Actions:

- Check root.
- Probe current public IP.
- Rotate mobile data.
- Rotate airplane mode.
- Copy redacted rotation diagnostic summary.

High-impact actions require clear confirmation and must show in-progress, cooldown, duplicate-operation, and failure states.

### Diagnostics

Diagnostics screen provides one-shot tests:

- Root availability check.
- Selected-route probe.
- Public IP probe.
- Proxy bind check.
- Local management API check.
- Cloudflare tunnel connectivity check.
- End-to-end management API check through Cloudflare when configured and explicitly triggered.

Each diagnostic result includes:

- Status: not run, running, passed, warning, failed.
- Duration.
- Error category when failed.
- Redacted details.
- Copyable redacted summary.

Diagnostics must not reveal full tunnel tokens, management API tokens, proxy credentials, cookies, authorization headers, or URL query secrets.

### Logs And Audit

Logs screen shows recent records from:

- App runtime.
- Proxy server.
- Management API.
- Cloudflare tunnel.
- Root commands.
- Rotation.
- Audit records.

Filtering:

- Category.
- Severity.
- Time window.
- Search over already-redacted text.

Actions:

- Copy selected record.
- Copy filtered redacted summary.
- Export redacted logs/audit bundle when supported.

The export path must reuse redaction logic and must never include raw secrets.

## UI State And Error Handling

Compose screens render immutable UI state. Composables do not perform IO directly. User actions are sent to ViewModel event handlers, which call repositories, runtime command dispatchers, diagnostics use cases, or service/management bridges.

Long-running operations expose loading/progress state and disable duplicate unsafe actions. High-impact actions such as service stop, tunnel restart, and rotation require confirmation. Failed actions should report what failed, whether the system is still usable, and what action can be tried next.

Risk states must be visible and actionable:

- Broad listener with proxy authentication disabled.
- Cloudflare enabled but token missing.
- Cloudflare connected but management API check failing.
- Root operations enabled but root unavailable.
- Rotation blocked by cooldown or in-progress state.
- Selected route unavailable.
- Sensitive configuration invalid or missing.

## Cloudflare Tunnel And Token Handling

Cloudflare is only a remote management API ingress. Ordinary proxy clients connect through localhost, LAN, VPN/Tailscale, or any other directly reachable Android interface allowed by the configured listen host and network environment.

Tunnel ingress rules:

- Allow `GET /health` with minimal non-sensitive output.
- Allow authenticated `/api/*` management paths.
- Reject all explicit proxy request forms.
- Reject ordinary proxy traffic.
- Reject paths outside health and management API scope.
- Require management API token authentication for all `/api/*` requests, including requests arriving through Cloudflare.

Token handling:

- Product path: user enters the tunnel token in Compose Settings or Cloudflare screen.
- Developer/e2e path: token may be supplied through local uncommitted configuration, Gradle property, environment variable, adb/instrumentation argument, or another non-committed local mechanism.
- UI shows token present/missing/invalid, not the full token.
- Save clears the token input field.
- Logs, diagnostics, exports, commits, specs, and PR descriptions redact or omit token content.
- Unit tests use fake tokens or structure-valid non-secret fixtures, never the real token.

Runtime behavior:

- Tunnel states include disabled, starting, connected, degraded, stopped, and failed.
- Tunnel failure does not stop the local proxy.
- Start, stop, reconnect, and test operations apply duplicate-operation protection.
- Lifecycle operations and high-impact management actions are audit logged.
- Connectivity diagnostics are explicitly triggered, not silently run whenever a token is configured.

## Testing And Verification Strategy

Testing is ordered by stability and cost.

### JVM Unit Tests

JVM tests are the default. They cover:

- Shared state machines and policies.
- Proxy parsing, rendering, routing, and error mapping.
- Management API routing and authentication policy.
- Root command result parsing and root orchestration boundaries.
- Cloudflare token parsing, state machine, ingress filtering, retry/degrade, and redaction.
- App config mappers and save controllers.
- UI state reducers and mappers.
- ViewModel action routing with fake repositories and fake dispatchers.
- Diagnostics result mapping and redaction.

### Android Local Or Instrumented Tests

Android-specific tests cover:

- DataStore repository behavior.
- Encrypted sensitive storage behavior.
- Foreground service command wiring.
- Notification/status projection.
- Compose navigation and screen semantics smoke tests.
- Android route binding adapters when they cannot be reasonably tested as plain JVM units.

### Emulator Or Virtual-Device Smoke Tests

Emulator tests cover:

- Debug APK installation.
- Compose navigation.
- Settings save/load behavior.
- Start/stop foreground service smoke.
- Fake Cloudflare lifecycle display.
- Local management API path where feasible.

Emulator tests are not used for root command correctness or real cellular network switching.

### USB Rooted Real-Device E2E

Rooted real-device e2e is reserved for:

- Root availability.
- Root command dispatch.
- Mobile data disable/enable.
- Airplane mode command behavior where supported.
- Real cellular network return.
- Public IP probe before and after rotation.
- Real Cloudflare tunnel edge connection with the user-provided token.
- Management API round trip through Cloudflare.

Real-device e2e output must be redacted and summarized. It may record status, duration, edge/session category, HTTP status, and error class. It must not record full token, management API token, proxy credentials, authorization headers, cookies, or sensitive query strings.

### Default Verification Order

1. Run JVM tests for all modules.
2. Run configured lint/ktlint checks where practical.
3. Build the debug APK.
4. Run emulator smoke only when Android UI, storage, service, or lifecycle behavior changed.
5. Run rooted real-device e2e only when root, real network, Cloudflare connectivity, or end-to-end management chain changed, or before a release-quality merge.
6. Redact and summarize all e2e evidence.

## Acceptance Criteria

- Relevant JVM unit tests pass.
- Debug APK builds.
- Compose console supports configuration, start/stop, status refresh, Cloudflare controls, rotation controls, diagnostics, and logs/audit review.
- Management-only Cloudflare boundary is covered by unit tests.
- At least one explicit real-token Cloudflare connection validation is performed before claiming real tunnel support.
- Root rotation critical path is validated on a rooted real device before claiming root rotation support.
- Logs, exports, diagnostics, and audit records pass redaction tests.
- No real token or credential appears in source, committed config, spec, logs intended for commit, commit message, PR text, or exported diagnostic samples.
- Before merge/push, feasible automated verification is rerun on the merged `main` result.

## Merge, Push, And Delivery Flow

Delivery is split into two phases.

### Phase 1: Current Work Closeout

1. Commit this spec as a standalone design commit.
2. Self-review the spec for placeholders, ambiguity, scope drift, contradictions, and secret leakage.
3. Ensure visual companion temporary files are not committed.
4. Run feasible automated verification for the current project state.
5. Switch to `main`.
6. Merge the current feature branch into `main` without squashing, preserving existing commits.
7. Rerun feasible verification on merged `main`.
8. Push `main` to `origin`.

If remote `main` has advanced, fetch and integrate it without force pushing. Force push is out of scope unless explicitly requested later.

### Phase 2: Compose And E2E MVP Implementation

1. Create a new implementation branch from updated `main`.
2. Write an implementation plan from this spec.
3. Implement in focused batches:
   - Compose dependencies, theme, and navigation.
   - UI state and ViewModel foundations.
   - Dashboard and Settings.
   - Cloudflare controls.
   - Rotation controls.
   - Diagnostics.
   - Logs/audit.
   - E2E token injection and device validation hooks.
4. Prefer unit tests for every batch.
5. Use emulator or real-device tests only where the changed behavior requires them.
6. Keep real token use local, explicit, and uncommitted.

## Security Notes

Sensitive values include:

- Cloudflare tunnel token.
- Management API token.
- Proxy username and password.
- Authorization and Proxy-Authorization headers.
- Cookies and Set-Cookie headers.
- URL query strings that may contain credentials.
- Root command output that may include network or account details.

All logs, diagnostics, export flows, and test evidence intended for sharing must use redaction before leaving the local machine or entering git.
