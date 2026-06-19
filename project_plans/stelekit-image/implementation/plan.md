# Implementation Plan: Image Insertion End-to-End

## Problem

Two bugs prevent image insertion from working on Android:

1. **Camera permission is never requested** — `AndroidCameraProvider.capturePhoto()` checks `checkSelfPermission` and returns `PermissionDenied` when the CAMERA permission has not been granted, but nothing in the app requests that permission at runtime. The user sees nothing happen (or a brief snackbar they miss).

2. **Binary file copy crashes/corrupts on SAF graphs** — `ImageImportService.copyImageBytes` calls `fileSystem.readFileBytes` + `fileSystem.writeFileBytes`. The Android `PlatformFileSystem` does not override these methods; they throw `UnsupportedOperationException`, which the caller catches and falls back to a text-round-trip copy that mangles JPEG binary data. Every camera import on a SAF-backed graph produces a corrupt file Coil cannot decode.

The gallery picker (`AndroidMediaAttachmentService.pickAndAttach`) works correctly and does not need fixing — it uses `ActivityResultContracts.PickVisualMedia` which needs no permission.

## Architecture

```
MainActivity (Android)
  ├── pendingCameraPermission: CompletableDeferred<Boolean>?   [new]
  ├── cameraPermLauncher: ActivityResultLauncher               [new]
  ├── suspend requestCameraPermission(): Boolean               [new]
  └── StelekitApp(requestCameraPermission = ::requestCameraPermission)   [new param]
        └── GraphContent(requestCameraPermission = ...)        [new param]
              └── onCaptureImage lambda:
                    1. call requestCameraPermission()          [new gate]
                    2. if denied → snackbar, return
                    3. executeCaptureAndImport(...)            [unchanged]

PlatformFileSystem (androidMain)
  ├── override fun readFileBytes(path): ByteArray?             [new]
  └── override fun writeFileBytes(path, data): Boolean        [new]
```

---

## Epic 1 — Camera Permission Request

### Story 1.1 — Permission fields + launcher in `MainActivity`

**File:** `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`

Add after the existing `pendingMicPermission` field (line ~50):
```kotlin
private var pendingCameraPermission: CompletableDeferred<Boolean>? = null

private val cameraPermLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    pendingCameraPermission?.complete(granted)
    pendingCameraPermission = null
}
```

Add after `requestMicrophonePermission()` (line ~326):
```kotlin
private suspend fun requestCameraPermission(): Boolean {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED
    ) return true
    val deferred = CompletableDeferred<Boolean>()
    pendingCameraPermission = deferred
    runOnUiThread { cameraPermLauncher.launch(Manifest.permission.CAMERA) }
    return deferred.await()
}
```

Wire into `StelekitApp(...)` call (line ~256):
```kotlin
StelekitApp(
    ...
    requestCameraPermission = ::requestCameraPermission,
)
```

### Story 1.2 — Thread `requestCameraPermission` parameter through `StelekitApp` + `GraphContent`

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

Add optional parameter to `StelekitApp`:
```kotlin
fun StelekitApp(
    ...
    requestCameraPermission: (suspend () -> Boolean)? = null,
```

Thread it into `GraphContent(requestCameraPermission = requestCameraPermission)`.

Add same parameter to `GraphContent`:
```kotlin
private fun GraphContent(
    ...
    requestCameraPermission: (suspend () -> Boolean)? = null,
```

### Story 1.3 — Gate camera capture on permission

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

Inside `GraphContent`, the `onCaptureImage` lambda in `EditorCapabilities`:

```kotlin
onCaptureImage = if (cameraImportEnabled) {
    {
        scope.launch {
            // Gate: request camera permission before capturing
            if (requestCameraPermission != null) {
                val granted = requestCameraPermission.invoke()
                if (!granted) {
                    viewModel.sendSnackbar("Camera access is needed to take photos")
                    return@launch
                }
            }
            val pageUuid: String? = ...
            ...
            executeCaptureAndImport(...)
        }
    }
} else null,
```

Also apply the same gate to the `onImportImage` lambda (the GalleryScreen FAB path) which also calls `executeCaptureAndImport` with `navigateAfterImport = true`.

### Story 1.4 — Tests

**File:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/CaptureAndImportTest.kt`

No new tests needed — permission gating is Android-specific platform code and can't be unit-tested in jvmTest. The existing `CaptureAndImportTest` tests cover the logic after permission is granted.

---

## Epic 2 — Android Binary File Copy

### Story 2.1 — `readFileBytes` in `PlatformFileSystem`

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

Add after the `readFile` override:
```kotlin
override fun readFileBytes(path: String): ByteArray? {
    if (!path.startsWith("saf://")) return legacyReadFileBytes(path)
    if (isDirectAccess()) {
        val realPath = resolveToRealPath(path)
        if (realPath != null) return legacyReadFileBytes(realPath)
    }
    return try {
        val docUri = parseDocumentUri(path)
        context?.contentResolver?.openInputStream(docUri)?.use { it.readBytes() }
    } catch (e: Exception) { null }
}

private fun legacyReadFileBytes(path: String): ByteArray? {
    return try {
        val file = File(validateLegacyPath(expandTilde(path)))
        if (!file.exists() || !file.isFile) return null
        if (file.length() > maxFileSize) return null
        file.readBytes()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { null }
}
```

### Story 2.2 — `writeFileBytes` in `PlatformFileSystem`

**File:** `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

Add after the `writeFile` override:
```kotlin
override fun writeFileBytes(path: String, data: ByteArray): Boolean {
    if (!path.startsWith("saf://")) return legacyWriteFileBytes(path, data)
    if (isDirectAccess()) {
        val realPath = resolveToRealPath(path)
        if (realPath != null) return legacyWriteFileBytes(realPath, data)
    }
    return try {
        var docUri = parseDocumentUri(path)
        val ctx = context ?: return false
        if (path !in knownExistingFiles) {
            val docFile = DocumentFile.fromSingleUri(ctx, docUri)
            if (docFile == null || !docFile.exists()) {
                val fileName = path.substringAfterLast('/')
                val parentPath = path.substring(0, path.lastIndexOf('/'))
                if (!directoryExists(parentPath)) {
                    if (!createDirectory(parentPath)) return false
                }
                val parentDocUri = parseDocumentUri(parentPath)
                val mimeType = when (fileName.substringAfterLast('.').lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> "application/octet-stream"
                }
                docUri = DocumentsContract.createDocument(
                    ctx.contentResolver, parentDocUri, mimeType, fileName
                ) ?: return false
            }
        }
        ctx.contentResolver.openOutputStream(docUri, "wt")?.use { stream ->
            stream.write(data)
        }
        knownExistingFiles.add(path)
        true
    } catch (e: Exception) { false }
}

private fun legacyWriteFileBytes(path: String, data: ByteArray): Boolean {
    return try {
        val file = File(validateLegacyPath(expandTilde(path)))
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs()
        file.writeBytes(data)
        true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { false }
}
```

### Story 2.3 — Tests

`PlatformFileSystem` requires Android context, so it can't be tested in jvmTest. The integration is covered by the existing `ImageImportServiceIntegrationTest` which uses `FakeFileSystem`. No new tests required.

---

## Acceptance Criteria Mapping

| AC | Story |
|----|-------|
| AC-1 (system permission dialog appears) | 1.1, 1.3 |
| AC-2 (capture proceeds after grant) | 1.3 |
| AC-3 (image_annotation block rendered) | existing code — no change |
| AC-4 (gallery picker inserts image) | existing code — no change |
| AC-5 (uploads paths) | user confirms not needed |
| AC-6 (existing tests pass) | all stories |

---

## Files Changed

| File | Change |
|------|--------|
| `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt` | +`pendingCameraPermission`, +`cameraPermLauncher`, +`requestCameraPermission()`, pass to `StelekitApp` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | +`requestCameraPermission` param to `StelekitApp` + `GraphContent`, gate in `onCaptureImage` lambda |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` | +`readFileBytes`, +`writeFileBytes`, +`legacyReadFileBytes`, +`legacyWriteFileBytes` |
