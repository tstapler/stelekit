# ADR-005: BLE Connection Reliability Strategy

**Date**: 2026-05-16
**Status**: Accepted
**Deciders**: Tyler Stapler

## Context

The BLE laser rangefinder integration (US-2.4, US-3.6) requires a persistent, reliable Bluetooth LE connection to Leica DISTO and Bosch GLM devices on Android. Android's BLE GATT stack has a well-documented set of persistent bugs and behavioral constraints that make naive BLE implementations fragile in production:

**GATT Status 133** (`GATT_ERROR`) is a catch-all error code returned for an enormous range of distinct underlying failures: connection timeout, device out of range, BLE stack state corruption after rapid reconnects, and an Android 14 tablet-specific regression where the entire BLE stack enters a broken state until reboot. It is not a single recoverable error.

Specific causes relevant to this feature:
- **GATT object leaks**: Android's native BLE layer has a 30-connection object limit. If `gatt.close()` is not called on every disconnect path (including error paths), leaked GATT objects accumulate and cause all subsequent connections to fail with status 133, requiring an app restart or device restart to clear.
- **MTU mismatch**: Android 12+ automatically sends max MTU (517 bytes) during connection setup. Older Leica DISTO firmware (D2, pre-X series, BLE 4.0) may not handle the max MTU, causing connection drops. The Bosch GLM 50C has also exhibited MTU-related instability.
- **Android 14 regression**: Repeated GATT 133 errors on tablets regardless of the peripheral state; resolved only by device reboot. Observed on multiple OEMs.
- **Leica DISTO acknowledgment timeout**: The DISTO protocol expects a GATT characteristic write acknowledgment within 2 seconds; missing this ACK causes "Error 240" on the device and a dropped connection.
- **Background scanning restrictions (API 26+)**: BLE scans are throttled in background. From API 31+, a `ForegroundService` is required to initiate or maintain BLE connections while the app is not in the foreground.
- **Silent permission failures (API 31+)**: Missing `BLUETOOTH_SCAN` or `BLUETOOTH_CONNECT` runtime permissions cause scans to return zero results with no exception, no logcat error, and no user feedback.

## Decision

The BLE connection layer is implemented with all of the following mitigations active:

**Library**: Kable (JuulLabs, Apache 2.0) wraps the native Android GATT stack with a coroutine-native API, eliminating callback pyramid patterns and providing structured concurrency for all BLE operations. Kable serializes GATT operations internally, satisfying the "never issue a second operation before the first callback returns" requirement.

**ForegroundService** (mandatory, API 31+): All BLE scan initiation, connection maintenance, and measurement reading runs inside a dedicated `ForegroundService` with a persistent user-visible notification. The foreground service is started before any BLE operation and remains running for the duration of a laser measurement session. This prevents Android from killing the BLE coroutine scope during active measurement.

**GATT 133 exponential backoff**: On receiving GATT status 133, the implementation calls `gatt.disconnect()` then `gatt.close()` unconditionally, waits for the callback, then applies exponential backoff before retrying: initial delay 2 s, multiplier 2×, cap 60 s, maximum 5 retry attempts before surfacing a terminal error to the user.

**`gatt.close()` on every disconnect path**: Every code path that exits a GATT connection — normal disconnect, error callback, coroutine cancellation, `ForegroundService` destruction — calls `gatt.close()` to release the native GATT object and prevent the 30-object leak.

**MTU negotiation**: After successful connection, explicitly negotiate MTU to 100 bytes (a middle ground between throughput and compatibility with older DISTO firmware). If the device rejects 100, fall back to the BLE 4.0 minimum of 23 bytes. Do not rely on Android 12's automatic max-MTU negotiation.

**`autoConnect` flag strategy**: Use `autoConnect = false` for the initial connection attempt (faster first connect, fails immediately if out of range rather than hanging). After the first successful connection, switch to `autoConnect = true` for background reconnection so that the DISTO reconnects automatically when brought back into range.

**User-visible reconnect recovery flow**: When the connection enters `DeviceConnectionState.ERROR` after exhausting retries, the UI surface a "Reconnect" button with a brief explanation ("Laser disconnected — tap to reconnect"). The user is not left with a silently broken connection. The ForegroundService notification also reflects the connection state.

**Permission checking before every scan**: Before initiating any BLE scan, check `ContextCompat.checkSelfPermission(BLUETOOTH_SCAN)` and `ContextCompat.checkSelfPermission(BLUETOOTH_CONNECT)`. If either is missing, surface a permission rationale dialog rather than proceeding (which would silently return zero scan results).

**Serial GATT operation queue**: All GATT write and notification-subscribe operations are issued sequentially through Kable's coroutine-structured API. No concurrent GATT operations are issued.

**Operation timeout**: Every `peripheral.write()` and `peripheral.observe()` call is wrapped with `withTimeout(5_000)` to prevent indefinite suspension if the DISTO stops responding mid-operation.

## Alternatives Considered

**Nordic Semiconductor KMM-BLE-Library**
- KMP-compatible (Android + iOS). Coroutine-native.
- Less production-proven than Kable; smaller community and fewer documented production deployments as of early 2026.
- Kable is maintained by JuulLabs, an IoT company with a commercial dependency on its correctness. Preferred for production use.

**Blue Falcon (Reedyuk/blue-falcon)**
- Wider platform support than Kable: includes JVM (via BLESSED library), useful if Desktop BLE were a requirement.
- Less idiomatic coroutine API than Kable; callback-style in some paths.
- Desktop BLE is not a realistic construction-site scenario (see architecture research); the JVM platform advantage is not needed. Kable's cleaner API is preferred.

**Direct Android BluetoothGatt API without a library**
- Maximum control over every GATT detail.
- The callback-to-coroutine bridge is non-trivial to implement correctly (race conditions between `onConnectionStateChange` and `onServicesDiscovered` callbacks are a documented source of GATT 133 errors in naive implementations).
- Every mitigation described above would need to be hand-implemented. Kable already implements the serial operation queue and coroutine bridge correctly; building it from scratch introduces unnecessary risk.

**Ignoring GATT 133 and retrying immediately**
- Immediate retry after 133 is one of the documented causes of the 30-object GATT leak. Without `gatt.close()` between attempts, each retry leaks a native GATT object. Immediate retry was explicitly tested and documented as harmful in public Android BLE post-mortems.

## Consequences

**Positive**
- The mitigation set addresses every documented root cause of Android BLE instability for this device category. Production BLE apps (Kable's own JuulLabs use cases, CaveSurvey, d7knight/Disto-App implementations) that apply these patterns achieve reliable DISTO connectivity on the same device range targeted by SteleKit.
- `ForegroundService` with a persistent notification gives Android OS guarantees that the BLE connection scope will not be killed during an active measurement session, eliminating silent mid-measurement disconnects.
- The user-visible reconnect flow turns connection failures from silent bugs into actionable UX events.
- `gatt.close()` on every path prevents the GATT object accumulation that causes permanent connection failure requiring app restart.

**Negative / Risks**
- `ForegroundService` requires `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` permissions (API 34+), declared in `AndroidManifest.xml`. The persistent notification may be perceived as intrusive by users; it must be dismissible (but the service continues running until the session explicitly ends).
- The Leica DISTO BLE protocol (custom GATT service, proprietary characteristic UUIDs, 2-second ACK requirement) is undocumented by Leica. Implementation relies on community reverse engineering (`seichter/d2relay`, `d7knight/Disto-App`). The characteristic UUID layout must be validated against physical hardware (D2, D510, X3 series at minimum) before release.
- Bosch GLM 50C and 100C also use a proprietary BLE profile. Community documentation is less complete than for Leica DISTO. Firmware version differences across GLM hardware variants may require runtime UUID discovery rather than hardcoded UUIDs.
- The Android 14 tablet GATT regression (repeated 133 errors requiring device reboot) cannot be fully mitigated in app code. The exponential backoff and user reconnect flow handle this gracefully, but the root cause is an Android OS bug. Affected users must be directed to reboot if persistent 133 errors occur.
- BLE is Android and iOS only (Kable has no JVM/Linux support). Desktop users have no laser rangefinder integration path and fall back to reference object or EXIF calibration. This is a known and accepted limitation.
- Pairing (bonding) is unreliable across some Android OEMs (documented: Nexus, older Motorola). A "forget and re-pair" recovery flow must be implemented as a user-accessible option in the device management UI.
