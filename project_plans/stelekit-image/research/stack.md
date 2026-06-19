# FR-1 Research: Android Camera Permission Request

## Context

`AndroidCameraProvider.capturePhoto()` already checks `ContextCompat.checkSelfPermission` and returns
`Either.Left(DomainError.SensorError.PermissionDenied)` when the CAMERA permission is missing. There is
no runtime permission request anywhere in the call chain. This research covers where and how to add one.

---

## 1. Can `rememberLauncherForActivityResult(RequestPermission)` coexist with the existing pattern?

**Yes, cleanly.** `MainActivity.setContent {}` already hosts several
`rememberLauncherForActivityResult` calls (via `rememberAndroidMediaAttachmentService`) and the pattern
is well-established in the project. The camera permission launcher can be added in the same composable
scope — either directly in `setContent {}` or delegated to a new Android-only composable helper.

The key constraint from FR-1: the launcher must be created unconditionally at composition time, not
inside a `LaunchedEffect`, `remember`, or coroutine. All existing launchers in this codebase satisfy
that requirement.

---

## 2. Where does the permission launcher belong?

**Option A — directly in `MainActivity.setContent {}`**

This matches how `micPermissionLauncher` is registered: as a private `ActivityResultLauncher` field via
`registerForActivityResult()` at the Activity class level. The mic launcher uses Activity-level
registration (called before `setContent`); a camera launcher could use either Activity-level or
`rememberLauncherForActivityResult` inside `setContent {}`.

Precedent in this codebase:
- `micPermissionLauncher` — Activity-level (`registerForActivityResult`), wired via a
  `requestMicrophonePermission(): suspend fun Boolean` method using `CompletableDeferred<Boolean>`.
- `rememberAndroidMediaAttachmentService` — composable-level (`rememberLauncherForActivityResult`),
  wired via a `ContinuationHolder` and `suspendCancellableCoroutine`.

**Option B — a new `rememberAndroidCameraPermissionLauncher` composable helper**

Mirrors `rememberAndroidMediaAttachmentService`. Keeps the camera-permission concern encapsulated,
avoids growing `MainActivity.setContent {}` further, and makes the pattern reusable if other screens
ever need to launch camera permission independently.

**Recommendation: Option B (new composable helper)** housed in a new file
`androidMain/.../service/AndroidCameraPermissionHelper.kt`. This composable:
- Calls `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> ... }`
- Exposes a `suspend fun requestCameraPermission(): Boolean` that fast-paths when the permission is
  already granted (same guard as `requestMicrophonePermission`).
- Returns the `suspend fun` reference so `MainActivity.setContent {}` can pass it down to
  `AndroidCameraProvider` or to the `onCaptureImage` lambda factory.

---

## 3. How does the permission request trigger flow from the camera button tap to the permission launcher?

The signal path is:

```
MobileBlockToolbar (commonMain)
  └─ onCaptureImage: () -> Unit        ← button tap fires this
       └─ coroutineScope.launch {       ← caller launches a coroutine
            requestCameraPermission()   ← suspend fun, bridges to launcher via CompletableDeferred
            if (granted) cameraProvider.capturePhoto()
          }
```

Two bridge patterns exist in the codebase:

**Pattern A — CompletableDeferred (Activity-level, mic permission model)**

`MainActivity` holds a `private var pendingCameraPermission: CompletableDeferred<Boolean>?`. The
launcher calls `pendingCameraPermission?.complete(granted)`. The suspend bridge:

```kotlin
private suspend fun requestCameraPermission(): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) return true
    val deferred = CompletableDeferred<Boolean>()
    pendingCameraPermission = deferred
    runOnUiThread { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
    return deferred.await()
}
```

**Pattern B — ContinuationHolder (composable-level, media attachment model)**

A `remember { ContinuationHolder() }` holds the `Continuation<Boolean>?`. The launcher callback
calls `holder.continuation?.resume(granted)`. The suspend bridge uses `suspendCancellableCoroutine`.

Both patterns are safe and tested. **Pattern A (CompletableDeferred at Activity level)** is
recommended for camera permission because:
- Camera capture is initiated from a context that already holds a coroutine scope tied to the
  ViewModel, not a `rememberCoroutineScope()` — matching the mic permission lifecycle.
- Using Activity-level registration via `registerForActivityResult` is mandatory if the launcher must
  survive configuration changes (though for a one-shot permission request this is not critical).
- It avoids introducing a third ownership pattern for what is logically the same mechanism as mic.

---

## 4. What pattern does `rememberAndroidMediaAttachmentService` use, and can we reuse it for permission?

`rememberAndroidMediaAttachmentService` uses a **`ContinuationHolder`** (composable-scope, line 161-162
of `AndroidMediaAttachmentService.kt`):

```kotlin
private class ContinuationHolder {
    var continuation: Continuation<Uri?>? = null
}
```

The `rememberLauncherForActivityResult` callback resumes the continuation, and `launchGalleryPicker`
suspends via `suspendCancellableCoroutine { cont -> holder.continuation = cont; launcher.launch(...) }`.

This pattern **can** be reused for camera permission — substitute `Boolean` for `Uri?`. However, for
camera permission the Activity-level `CompletableDeferred` pattern (matching mic) is simpler and
avoids scoping the launcher inside a composable that may be remembered with a stale context.

---

## 5. Should `onCaptureImage` in `EditorCapabilities` remain `() -> Unit`?

**Yes, keep it as `() -> Unit`.** The permission request is an implementation detail of the Android
platform layer; the commonMain `EditorCapabilities` contract should not leak it. The lambda passed in
from `MainActivity.setContent {}` (or its composable delegate) wraps the full
`requestPermission + capturePhoto + insertBlock` sequence in a coroutine launch, so the button tap
fires a simple `() -> Unit`. This is the same pattern used for `onAttachImage`.

The only change to `EditorCapabilities` specified in FR-5 is adding a comment to `onCaptureImage`
noting the edit-mode visibility condition.

---

## Implementation Blueprint

### New: `MainActivity` additions (Activity-level, matching mic pattern)

```kotlin
// Field
private var pendingCameraPermission: CompletableDeferred<Boolean>? = null

// Launcher (before onCreate)
private val cameraPermLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    pendingCameraPermission?.complete(granted)
    pendingCameraPermission = null
}

// Suspend bridge
private suspend fun requestCameraPermission(): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) return true
    val deferred = CompletableDeferred<Boolean>()
    pendingCameraPermission = deferred
    runOnUiThread { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
    return deferred.await()
}
```

### `setContent {}` wiring

Inside `setContent {}`, after the existing `attachmentService` line, build the `onCaptureImage` lambda:

```kotlin
val onCaptureImage: (() -> Unit)? = if (cameraProvider.isAvailable) {
    {
        lifecycleScope.launch {
            val granted = requestCameraPermission()
            if (!granted) {
                // surface snackbar: "Camera access is needed to take photos for notes."
                return@launch
            }
            val result = cameraProvider.capturePhoto()
            result.fold(
                ifLeft = { err -> /* show snackbar */ },
                ifRight = { file -> /* insert image_annotation block */ }
            )
        }
    }
} else null
```

Pass `onCaptureImage` into `EditorCapabilities` which is passed into `StelekitApp`.

### No changes needed to

- `AndroidCameraProvider` — the permission gate it already has becomes the second line of defense.
- `EditorCapabilities.onCaptureImage` type — stays `() -> Unit`.
- `AndroidMediaAttachmentService` — unaffected.
- `MobileBlockToolbar` / `EditorToolbar` — unaffected (already call `onCaptureImage?.invoke()`).

---

## Key Constraints Summary

| Constraint | Source | Impact |
|---|---|---|
| Launcher must be created unconditionally before composition completes | FR-1, Android docs | Use `registerForActivityResult` or `rememberLauncherForActivityResult` at top of composable |
| Must not block UI thread | FR-1 | Use `CompletableDeferred.await()` in a `lifecycleScope.launch` coroutine |
| Fast-path when permission already granted | FR-1 | `checkSelfPermission` guard before launching |
| `onCaptureImage` stays `() -> Unit` | FR-5, architecture | Lambda wraps coroutine launch internally |
| `rememberCoroutineScope()` must not escape composition | CLAUDE.md arch rule | Use `lifecycleScope` or ViewModel scope, not `rememberCoroutineScope()` |
