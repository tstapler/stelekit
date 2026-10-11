# ADR-002: `DownloadableDepthModel` capability interface instead of extending `MonocularDepthEstimator`

## Status
Accepted

## Context

`ScreenRouter.kt` (commonMain) is the sole call site of `AnnotationEditorScreen` and needs to
reach `DepthModelDownloader`'s download surface (`modelState`, `downloadModel()`,
`cancelDownload()`) to wire `onDownloadDepthModel`/`onEstimateDepth`/`onCancelDownload` and the
missing `updateDepthModelUiState` subscription. But `DepthModelDownloader` and
`OnnxMonocularDepthEstimator` are androidMain-only classes — commonMain code cannot reference
them directly. The only commonMain-visible handle is `SensorModule.monocularDepthEstimator:
MonocularDepthEstimator`, whose interface (`platform/ml/MonocularDepthEstimator.kt`) currently
exposes only `isAvailable`, `initialize()`, `estimateDepth()` — no download surface at all.

requirements.md's Non-Goals explicitly scope this fix to Android: *"iOS/desktop/wasm support for
this downloader — it is Android-only today; out of scope to port to other platforms as part of
this fix."* `NoOpMonocularDepthEstimator` (used on JVM/WASM, and as the iOS stub's likely base)
has no model to download at all — it's always unavailable.

## Decision

Add a separate capability interface, `DownloadableDepthModel`, in the same
`platform/ml/MonocularDepthEstimator.kt` file, exposing `modelState`, `downloadModel()`,
`cancelDownload()`. Only `OnnxMonocularDepthEstimator` implements it (`class
OnnxMonocularDepthEstimator : MonocularDepthEstimator, DownloadableDepthModel`).
`NoOpMonocularDepthEstimator` is untouched.

`ScreenRouter.kt` reaches the capability via a safe cast:
`SensorModule.monocularDepthEstimator as? DownloadableDepthModel` — non-null only on Android
where the real estimator is assigned; `null` everywhere else, which naturally reproduces today's
behavior on other platforms (`onDownloadDepthModel = null` → panel doesn't render, exactly per
`AnnotationEditorScreen.kt:581`'s existing gate).

**Rejected alternative**: extend the base `MonocularDepthEstimator` interface directly with
`modelState`/`downloadModel()`/`cancelDownload()` members (with trivial default/no-op
implementations for platforms without a downloadable model). This was rejected because:
- It requires touching `NoOpMonocularDepthEstimator` and any future iOS Core ML implementation
  for a fix explicitly scoped to Android — contradicts the Non-Goal even though the changes
  would be small.
- It leaks an Android-`DownloadManager`-shaped concept (progress-as-percentage-int,
  download/cancel semantics) into a platform-agnostic interface that other platforms may not
  need to think about (e.g. a bundled Core ML model needs no download step at all).
- Interface Segregation Principle: clients (here, `ScreenRouter`) that need the download surface
  can depend on `DownloadableDepthModel` specifically; clients that only need
  `estimateDepth()`/`initialize()` are unaffected by download-related interface changes.

## Consequences

- `ScreenRouter.kt` gains one `as?` cast and a null-safe `?.let { }` per callback — a small,
  explicit, and locally-scoped acknowledgment that this capability is platform-conditional,
  rather than an interface-wide assumption.
- If a future platform (e.g. iOS Core ML with a downloadable weights file) needs the same
  surface, it implements `DownloadableDepthModel` too — no interface changes required, and
  `ScreenRouter`'s existing cast-based wiring picks it up automatically.
- `MonocularDepthEstimator`'s core contract (`isAvailable`, `initialize()`, `estimateDepth()`)
  stays untouched — no ripple into iOS/JVM/WASM source sets for this Android-only bug fix.
