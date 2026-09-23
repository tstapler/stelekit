# ADR-002: New parallel `CameraFrameSource` interface, not a method on `CameraProvider`

**Status**: Accepted — **supersedes requirements.md constraint "reuse CameraProvider"**
**Date**: 2026-07-11
**Feature**: camera-qr-export

## Context

requirements.md Constraints states: *"Must reuse the existing cross-platform
`CameraProvider` abstraction ... rather than introducing a second camera API
surface."* Three independent research agents (Stack, Architecture, Pitfalls, UX)
found this constraint only partially achievable:

- `CameraProvider`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/sensor/CameraProvider.kt`)
  exposes exactly one method: `suspend fun capturePhoto(): Either<..., PlatformImageFile>`.
  It is **single-shot photo only**.
- Only Android has a working implementation. JVM and WASM use `NoOpCameraProvider`
  (`isAvailable=false`); iOS is a stub.
- QR decode needs a **continuous luminance-frame stream**, a fundamentally
  different camera lifecycle (bind-once-keep-running `ImageAnalysis` vs.
  bind-capture-unbind `ImageCapture`). `capturePhoto()` in a loop pays camera
  rebind + `delay(400L)` autofocus-settle + JPEG encode per frame — orders of
  magnitude too slow, and it thrashes the camera lifecycle.

## Decision

Introduce a **new, separate** `commonMain` interface rather than adding
`frameStream()` to `CameraProvider`:

```kotlin
// kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/sensor/CameraFrameSource.kt
interface CameraFrameSource {
    val isAvailable: Boolean
    /** Cold Flow; collection starts capture, cancellation stops it. */
    fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>>
}

data class CameraFrame(
    val luminanceBytes: ByteArray,   // Y-plane (grayscale), ready for ZXing PlanarYUVLuminanceSource
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,        // MUST be applied before decode (pitfalls: rotation drift = silent decode failure)
)
```

Provided via `SensorModule` alongside the existing `cameraProvider`, defaulting to
a `NoOpCameraFrameSource` (`isAvailable=false`) on platforms without a receive
implementation.

## Consequences

**Positive**
- **Interface Segregation (SOLID)**: existing `capturePhoto()` callers
  (`ImageImportService`) are not forced to depend on a streaming method they never
  use; `NoOpCameraProvider` and iOS/JVM photo stubs need no change.
- The single-shot and streaming lifecycles evolve independently.
- Cold-`Flow` cancellation is structural — matches this repo's aversion to
  caller-managed scopes (`MotionSensorProvider.sensorDataFlow`,
  `GraphLoader.externalFileChanges`), so no `rememberCoroutineScope` can leak into
  a camera lifecycle (CLAUDE.md scope-ownership rule).
- Air-gap isolation (ADR side-benefit): the QR module depends only on
  `CameraFrameSource`, a type whose entire surface is `Flow<CameraFrame>` — no
  network-capable type is reachable through it.

**Negative / trade-offs**
- Contradicts the literal requirements.md constraint — hence this ADR. The
  constraint's *intent* ("don't build two competing camera stacks") is honoured:
  permission/availability patterns from `CameraProvider` are reused, and both live
  in `platform/sensor/` under `SensorModule`.
- Two camera abstractions coexist. Accepted; they serve genuinely different jobs.

**Rejected alternatives**
- *Add `frameStream()` to `CameraProvider`* (Architecture research's first
  suggestion) — rejected: forces every existing implementor to add a method, and
  couples photo-capture callers to streaming. ISP violation.
- *Extend `CameraProvider` and give photo-only platforms a throwing default* —
  rejected: hides an unsupported operation behind a runtime failure instead of the
  compile-time `isAvailable` guard the rest of the module uses.
