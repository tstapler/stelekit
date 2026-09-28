# Pitfalls & Edge Cases — Image Insertion (stelekit-image)

## 1. Camera Permission Request Lifecycle (FR-1 / RC-1)

**Problem:** `ActivityResultLauncher` (including `RequestPermission`) must be registered during `Activity.onCreate` or during initial composition — before any recomposition triggered by state changes. `AndroidCameraProvider.capturePhoto()` is called from a coroutine (`scope.launch` in `App.kt`), which means the permission request cannot be launched directly from inside `capturePhoto`.

**Current state:** `MainActivity` already demonstrates the correct pattern for microphone permission: a `private val micPermissionLauncher = registerForActivityResult(...)` + `private var pendingMicPermission: CompletableDeferred<Boolean>?` + a `private suspend fun requestMicrophonePermission()` that creates and awaits the deferred. There is **no equivalent camera permission launcher** in `MainActivity` today.

**Correct bridge pattern (mirrors existing mic pattern):**

```kotlin
// MainActivity.kt
private var pendingCameraPermission: CompletableDeferred<Boolean>? = null

private val cameraPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    pendingCameraPermission?.complete(granted)
    pendingCameraPermission = null
}

private suspend fun requestCameraPermission(): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) return true
    val deferred = CompletableDeferred<Boolean>()
    pendingCameraPermission = deferred
    runOnUiThread { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
    return deferred.await()
}
```

`AndroidCameraProvider` must accept an optional `suspend () -> Boolean` callback (wired from `MainActivity`) and call it before its own `checkSelfPermission` fast-path. The callback reference must flow from `MainActivity` → `SteleKitApplication` → `SensorModule.cameraProvider` at application startup.

**Pitfall to avoid — ContinuationHolder is wrong here.** `AndroidMediaAttachmentService` uses `ContinuationHolder` + `rememberLauncherForActivityResult` because the gallery launcher is owned by a composable. A composable-level permission launcher would work too, but it must be registered in a composable that persists across navigation (e.g. the root `StelekitApp` or `MainActivity`'s `setContent` block) — not inside a leaf composable that may leave composition while the camera is open. The Activity-level `registerForActivityResult` approach (Pattern A from `stack.md`) is safer because `MainActivity` survives configuration changes and process death.

**shouldShowRequestPermissionRationale edge case:** On second denial, Android permanently suppresses the dialog and `shouldShowRequestPermissionRationale` returns `false`. The implementation should detect this and show an in-app message directing the user to Settings, rather than just showing "Camera permission denied".

---

## 2. ProcessLifecycleOwner for CameraX — Rotation and Pause Safety

**Current code (`AndroidCameraProvider.kt`, line 93-99):**

```kotlin
val lifecycleOwner = androidx.lifecycle.ProcessLifecycleOwner.get()
cameraProvider.unbindAll()
cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, imageCapture)
```

**Issue:** `ProcessLifecycleOwner` tracks the entire app process lifecycle (ON_START when any activity starts, ON_STOP only when all activities stop). This means:

- **Rotation is safe:** Activity rotation triggers `onStop`/`onStart` on the Activity but NOT on `ProcessLifecycleOwner` (the process stays alive). CameraX will not unbind on rotation.
- **Activity pause mid-capture is safe:** The process doesn't enter STOP while a foreground Activity is paused. The camera capture coroutine will complete normally.
- **Risk: `cameraProvider.unbindAll()` is called unconditionally.** If another part of the app (e.g. voice capture or a future preview use case) has bound a use case to `ProcessCameraProvider`, `unbindAll()` will kill it silently. This is only a risk if multiple concurrent CameraX users exist, which is not the case today.
- **Risk: CameraX initialization on background thread.** `ProcessCameraProvider.getInstance(context)` listener fires on `ContextCompat.getMainExecutor(context)` (main thread). The `suspendCancellableCoroutine` bridge correctly handles this, but if the process is under memory pressure and the main executor's queue is large, the Future may not resolve quickly. This is unlikely to cause a bug but can manifest as a delayed capture start.
- **Known Android restriction:** On some OEM devices (MIUI, ColorOS), `ProcessLifecycleOwner`-bound cameras are killed aggressively when the screen turns off, returning a `CameraAccessException.CAMERA_ERROR`. This will surface as `DomainError.SensorError.CaptureFailed` and the snackbar "Capture failed" will appear. Nothing to fix here, but worth noting in QA.

**No code change needed** for the `ProcessLifecycleOwner` itself — it is the right choice when no Activity reference is available.

---

## 3. Snackbar Wiring

**Finding: Fully wired.** `StelekitViewModel` (line 1670–1675) declares:

```kotlin
private val _snackbarEvents = Channel<String>(Channel.BUFFERED)
val snackbarEvents: Flow<String> = _snackbarEvents.receiveAsFlow()

fun sendSnackbar(message: String) {
    val result = _snackbarEvents.trySend(message)
    if (!result.isSuccess) logger.warn("sendSnackbar: channel full, message dropped: $message")
}
```

`App.kt` (line 1082–1090) collects the flow in a `LaunchedEffect(Unit)` and calls `snackbarHostState.showSnackbar(msg)`. `SnackbarHost(hostState = snackbarHostState)` is placed in the `statusBar` slot (line 1390) which renders on every screen. The `onCaptureImage` lambda in `App.kt` (line 1333) already calls `viewModel.sendSnackbar(it)`.

**No wiring gap** — both success and error messages from `executeCaptureAndImport` will reach the UI correctly.

**Minor risk:** `Channel.BUFFERED` has a capacity of 64. If a burst of errors fires (e.g. gallery pick fails while a concurrent camera import error fires), messages can be dropped. In practice only one capture is in flight at a time, so this is not a real concern.

---

## 4. CAMERA Permission Declaration — `android:required="false"`

**Finding: Correct.** The manifest declares:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

- `<uses-permission android:name="android.permission.CAMERA" />` — no `android:required` attribute on permissions (it only applies to `uses-feature`). This declaration is correct.
- `<uses-feature android:required="false" />` — prevents Play Store from filtering the app off camera-less devices (tablets without cameras). The toolbar button is already hidden when `SensorModule.cameraProvider.isAvailable` is false (which is always `true` for `AndroidCameraProvider`).

**CameraX-specific note:** CameraX does not require any additional `<uses-feature>` flags beyond the manifest permission declaration. However, on camera-less devices `ProcessCameraProvider.getInstance()` will succeed but `cameraProvider.bindToLifecycle()` will throw `IllegalArgumentException: Unable to open camera`. This is caught by the outer `catch (e: Exception)` in `capturePhoto()` and returned as `CaptureFailed`.

**To be safe:** `AndroidCameraProvider.isAvailable` is hardcoded to `true`. It should check `PackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)` at construction time (not just the permission). This prevents the camera icon from appearing on tablet emulators without a camera, which would show an error to users who tap it.

---

## 5. Write-Behind Queue and SAF Directory Creation for `assets/images/`

**Problem:** When the user's graph is on SAF (the common Android case), `ImageImportService.copyImageBytes` calls `fileSystem.readFileBytes(srcPath)` and `fileSystem.writeFileBytes(destPath, bytes)`. **Android `PlatformFileSystem` does NOT override `readFileBytes` or `writeFileBytes`** (confirmed: no override exists in `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`). The base class default for both methods throws `UnsupportedOperationException`.

**What actually happens:** `ImageImportService.copyImageBytes` (line 242-244) catches `UnsupportedOperationException` and falls back to `copyBytesViaText` — which calls `fileSystem.readFile` (reads as `String`) and `fileSystem.writeFile` (writes as `String`). For JPEG files this will **silently corrupt the image**: JPEG bytes contain arbitrary non-UTF-8 byte sequences that are mangled by String encode/decode round-trips. The corrupt file will be saved to SAF, the DB row will be written, but Coil will fail to decode the image.

**Root cause:** `copyImageBytes` was written assuming a JVM target (where `readFileBytes`/`writeFileBytes` are implemented via `JvmFileSystemBase`). The `UnsupportedOperationException` fallback was intended only for platforms without byte IO (WASM/JS), not for Android.

**Fix required:** Implement `readFileBytes` and `writeFileBytes` on `PlatformFileSystem` (Android) using `contentResolver.openInputStream` / `openOutputStream` for SAF paths, and `java.io.File.readBytes()` / `java.io.File.writeBytes()` for legacy paths. The `srcPath` in `copyImageBytes` for camera capture is always a `cacheDir` path (returned by `AndroidCameraProvider` — `context.cacheDir/captures/<uuid>.jpg`), so `readFileBytes` just needs `File(path).readBytes()`. The `destPath` is a SAF-rooted path (`saf://...`), so `writeFileBytes` needs to open a `contentResolver.openOutputStream` in binary mode.

**Write-behind queue — does NOT apply:** The write-behind queue (`WriteBehindQueue` / `ShadowFlushActor`) only handles Markdown text files via `markDirty(path, content: String)`. Binary image writes bypass the write-behind queue entirely and go directly to SAF via `writeFile`/`writeFileBytes`. This means `assets/images/<uuid>.jpg` is written synchronously to SAF during the import call — no deferred flush needed for the image bytes. However, the sidecar JSON file (`ImageSidecarManager.writeSidecar`) does go through `fileSystem.writeFile` on a SAF path, which IS immediate (not write-behind) for these non-page paths.

**Directory creation timing:** `ImageImportService.reservePath` calls `ensureDirectory("$graphPath/assets")` then `ensureDirectory(dir)` synchronously before the copy. Both call `fileSystem.createDirectory`, which has full SAF support in `PlatformFileSystem` (lines 427-457). The directory is guaranteed to exist before `writeFileBytes` is called. No race condition here.

**Coil read timing:** Once the JPEG is written to SAF and the block is saved to the DB, the `ImageAnnotationBlockItem` composable calls Coil to load the image. Coil needs `assets/images/<uuid>.jpg` to exist. Since `writeFileBytes` is synchronous (no deferred queue), the file will be present when Coil attempts to load it — assuming `writeFileBytes` is correctly implemented. With the current `copyBytesViaText` fallback the file will exist but will be corrupt.

---

## Summary of Action Items

| # | Risk | Severity | Fix |
|---|------|----------|-----|
| P1 | `writeFileBytes` not implemented on Android — JPEG corrupted via text fallback | CRITICAL | Implement `readFileBytes` / `writeFileBytes` in `PlatformFileSystem.android.kt` using `contentResolver.openInputStream/openOutputStream` for SAF, `File.readBytes/writeBytes` for legacy |
| P2 | No camera permission launcher in `MainActivity` — permission never requested | HIGH | Add `cameraPermissionLauncher` + `pendingCameraPermission` + `requestCameraPermission()` in `MainActivity`, wire callback into `AndroidCameraProvider` |
| P3 | `AndroidCameraProvider.isAvailable = true` hardcoded — shows camera icon on camera-less devices | LOW | Check `PackageManager.FEATURE_CAMERA_ANY` at construction time |
| P4 | `shouldShowRequestPermissionRationale` not handled — permanent denial gives no recovery path | LOW | After `requestCameraPermission()` returns false, check rationale flag; if false, show "Open Settings" snackbar |
| P5 | `System.currentTimeMillis()` used for `capturedAtMs` in `AndroidCameraProvider` (line 107) | LOW | Already flagged by recent commit message — replace with `Clock.System.now().toEpochMilliseconds()` for consistency with commonMain |
