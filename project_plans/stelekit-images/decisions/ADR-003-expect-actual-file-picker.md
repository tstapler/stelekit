# ADR-003: Platform File Picker via expect/actual (No Third-Party KMP Picker Library)

**Status**: Accepted
**Date**: 2026-05-15

---

## Context

The attachment feature requires a file/image picker on each supported platform. Three implementation strategies were evaluated:

**Option A: FileKit (`io.github.vinceglb:filekit-dialogs-compose`)**
- Active KMP library (v0.14.1 as of research date) covering JVM, Android, iOS, macOS, JS, WASM.
- Ships a companion `filekit-coil` module that registers a `PlatformFileFetcher` and `PlatformFileKeyer` for direct `AsyncImage(model = platformFile)` usage.
- `PlatformFile.path` provides an absolute string on JVM/Android; copy-to-assets can skip a full in-memory buffer.
- Pre-1.0 — API may change across minor versions.
- Adds two Gradle dependencies.

**Option B: Calf (`com.mohamedrejeb.calf:calf-file-picker`)**
- Active, Android/iOS/Desktop/Web coverage.
- `calf-file-picker-coil` companion module, but less complete than FileKit's integration.
- `readByteArray()` in-memory only — unsuitable for large files without platform workarounds.
- Adds two Gradle dependencies.

**Option C: expect/actual with native platform APIs**
- `jvmMain`: `javax.swing.JFileChooser` (AWT file chooser, no external dependency).
- `androidMain`: `ActivityResultContracts.PickVisualMedia` (Android Photo Picker, no permission on API 33+; backported via Google Play services to API 19).
- `iosMain`: `PHPickerViewController` (iOS 14+, no photo library permission required).
- Follows the established project pattern: `PlatformDispatcher`, `DriverFactory`, and other platform abstractions all use expect/actual.
- Each platform implementation is approximately 50 lines. Total: ~150 lines of new code.
- Zero new Gradle dependencies.

**Project philosophy**: SteleKit has a stated preference for minimal external dependencies. All existing platform abstractions use expect/actual. The two KMP picker libraries are both pre-1.0 or have known limitations (`calf` in-memory-only reads). Both would require ongoing version pinning and monitoring for API churn.

**Research finding on pitfalls**: The iOS `PHPickerViewController` callback (`NSItemProvider.loadDataRepresentation()`) runs on a background OS queue and must be bridged with `suspendCancellableCoroutine` (not `suspendCoroutine`). This is a known pitfall but is well-understood and documented in the research. If the ObjC interop complexity proves prohibitive during implementation, adopting FileKit as an escape hatch for iOS only remains an option without architectural lock-in, because the `MediaAttachmentService` interface in `commonMain` insulates callers from the implementation detail.

---

## Decision

Implement `MediaAttachmentService` as an `expect`/`actual` interface across `jvmMain`, `androidMain`, and `iosMain` using native platform APIs. Do not add FileKit, Calf, or any other third-party KMP file-picker library.

Define in `commonMain`:

```kotlin
interface MediaAttachmentService {
    suspend fun pickImageFromGallery(): Either<DomainError, String?>  // absolute path
    suspend fun captureFromCamera(): Either<DomainError, String?>     // Android/iOS only
}

expect fun createMediaAttachmentService(context: Any?): MediaAttachmentService
```

Platform actuals:
- `jvmMain`: `JFileChooser` in `withContext(Dispatchers.Main)` (AWT requires EDT).
- `androidMain`: `PickVisualMedia` launcher wrapped in `suspendCancellableCoroutine`; result path obtained via `ContentResolver.openInputStream` → copy to assets.
- `iosMain`: `PHPickerViewController` presented on `Dispatchers.Main`; completion bridged via `suspendCancellableCoroutine`; `url.startAccessingSecurityScopedResource()` called before read.

The common interface returns the absolute local path (after copy-to-assets) using `Either<DomainError, String?>`, consistent with the project's Arrow error pattern.

---

## Consequences

**Positive**
- No new Gradle dependencies. No version-catalog entries to maintain. No pre-1.0 API churn risk.
- Consistent with existing project patterns (`PlatformDispatcher`, `DriverFactory`, etc.) — contributors know exactly where to look.
- Each platform implementation is independently testable and replaceable.
- The `MediaAttachmentService` interface in `commonMain` means the implementation strategy can be changed per-platform later (e.g., swap iOS actual to FileKit) without touching any call sites.
- `PickVisualMedia` on Android requires no manifest permissions on API 33+ and is backported to API 19 via Google Play services — simpler manifest than any library that uses `READ_MEDIA_IMAGES` internally.

**Negative / Trade-offs**
- More code to write and own: ~150 lines total across three platform actuals vs. ~30 lines with FileKit.
- iOS ObjC interop requires care: `suspendCancellableCoroutine`, main-thread presentation, and security-scoped URL access. If this proves too error-prone, the iOS actual can be swapped to a library implementation without changing the interface.
- Camera capture on iOS requires `NSCameraUsageDescription` in `Info.plist` — a manifest change that must not be forgotten. Tracked in the pitfalls research.

**Escape hatch**
If the iOS `PHPickerViewController` interop implementation fails code review or causes production crashes, the `iosMain` actual can be replaced with FileKit's `rememberFilePickerLauncher` without any changes to `commonMain` or other platforms. The interface boundary makes this a localized swap.

**Risks mitigated**
- No dependency on a pre-1.0 library for a core feature.
- `PickVisualMedia` avoids the `file://` URI pitfall on Android 10+ (content URIs are used instead).
- Security-scoped URL expiry on iOS is handled by copying to `assets/` immediately in the picker callback.
