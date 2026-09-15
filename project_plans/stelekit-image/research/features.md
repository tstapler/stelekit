# Feature Research: Image Rendering Pipeline & Toolbar Icon Visibility

## 1. `extractSingleImageNode` — path handling

**Location:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItemUtils.kt`

`extractSingleImageNode(content: String)` delegates to `InlineParser(content.trim()).parse()`, filters out blank `TextNode`s, and checks if the first meaningful node is an `ImageNode`. If exactly one image node exists (with an optional trailing Logseq `{:height N, :width N}` size-hint tolerated as trailing `TextNode`s), it returns `img.url to img.alt` — the **raw URL string as parsed from the markdown**. It performs zero path manipulation.

For content `![alt](../uploads/filename.png)`:
- The `InlineParser` will emit an `ImageNode` with `url = "../uploads/filename.png"` and `alt = "alt"`.
- `extractSingleImageNode` returns `"../uploads/filename.png" to "alt"` — the raw relative path is passed directly to `ImageBlock`.

There are **no tests** covering `../uploads/` paths in `ImageBlockDetectionTest`. All existing tests only use `../assets/` or `https://` URLs.

## 2. `BlockItem.kt` `else` branch — what happens to `../uploads/` blocks

**Location:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` lines 398–428

The `else` branch (for `BULLET`, `PARAGRAPH`, `RAW_HTML`, and unknown block types) runs `extractSingleImageNode(block.content)`. For a block with content `![alt](../uploads/filename.png)`:
1. `extractSingleImageNode` returns `("../uploads/filename.png", "alt")` — non-null.
2. `ImageBlock(url = "../uploads/filename.png", ...)` is called.
3. `ImageBlock` calls `rememberSteleKitImageLoader()` to build a Coil `ImageLoader`.

## 3. Root cause: `SteleKitAssetMapper` ignores `../uploads/` paths → blank image

**Location:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcher.kt`

`SteleKitAssetMapper.map()` has a hard guard at line 32:
```kotlin
if (!data.startsWith("../assets/")) return null
```

For `../uploads/filename.png`, this returns `null`. Coil then falls through to its default fetchers. None of the default Coil 3 fetchers can resolve a raw relative path string like `../uploads/filename.png` without an absolute base — the `NetworkFetcher` only handles http/https and the `FileUriFetcher` requires a `file://` URI. The image fails silently and Coil shows nothing (blank space, no error UI).

**This is the confirmed root cause for symptom S3.** The fix is to add a second prefix check inside `SteleKitAssetMapper.map()`:
```kotlin
if (data.startsWith("../uploads/")) {
    val filename = data.removePrefix("../uploads/")
    // same path-traversal guards
    return "file://$graphRoot/uploads/$filename".toUri()
}
```

`SteleKitAssetMapperTest` currently has **no test for `../uploads/` paths** — a new test must be added.

## 4. `LocalGraphRootPath` — provision scope

**Location:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/GraphRootPathProvider.kt`

`LocalGraphRootPath` is a `compositionLocalOf<String?>` defaulting to `null`. It is provided **only inside `PageView`** (line 115 of `PageView.kt`):
```kotlin
CompositionLocalProvider(LocalGraphRootPath provides currentGraphPath.ifEmpty { null }) {
    ...
}
```

`ScreenRouter.kt` renders `GalleryScreen` without wrapping it in a `CompositionLocalProvider` for `LocalGraphRootPath`. Therefore:
- In `PageView`: `LocalGraphRootPath.current` is the graph root path → `SteleKitAssetMapper` is registered → `../assets/` paths resolve correctly.
- In `GalleryScreen` (`GalleryCard` composable at line 183): `LocalGraphRootPath.current` is **`null`** → `rememberSteleKitImageLoader()` skips `SteleKitAssetMapper` → `ImageAnnotationBlockItem`'s `imagePath` (which uses the absolute `file://` path stored in the database after import) still works because it's already absolute. However, if any gallery item ever stores a relative path, it would fail.
- In `JournalsView` (scrolling journals list): need to verify whether `LocalGraphRootPath` is provided; if not, inline image blocks in journals would also show blank.

## 5. Toolbar icon visibility — toolbar code path is correct

**Location:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt` lines 153–208

The `AttachFile` icon (onAttachImage) and `CameraAlt` icon (onCaptureImage) are inside the **"Primary actions row — only shown while editing a block"** guard at line 154:
```kotlin
if (editingBlockId != null) { ... }
```

Both icons are `null`-guarded:
- `if (onAttachImage != null)` — wired from `EditorCapabilities.onAttachImage`
- `if (onCaptureImage != null)` — wired from `EditorCapabilities.onCaptureImage`

In `App.kt` lines 1239–1335, `onAttachImage` is set when `attachmentService != null` (always non-null on Android via `rememberAndroidMediaAttachmentService`). `onCaptureImage` is set when `cameraImportEnabled = imageImportService != null && SensorModule.cameraProvider.isAvailable`.

**`AndroidCameraProvider.isAvailable` is hardcoded `true`** (line 51 of `AndroidCameraProvider.kt`). So as long as `imageImportService != null`, the camera button will appear. The `attachmentService` (gallery picker) is always non-null on Android.

**Conclusion:** The toolbar icons are not hidden by code — they only appear when a block is in edit mode (`editingBlockId != null`). This is a UX discoverability issue (user may not realize they need to tap into a block to enter edit mode), not a code bug. RC-3 in requirements is confirmed correct.

The camera button click will result in `AndroidCameraProvider.capturePhoto()` returning `DomainError.SensorError.PermissionDenied` immediately if the CAMERA permission has not been granted at runtime, since line 62–65 checks `ContextCompat.checkSelfPermission` but there is no `ActivityResultContracts.RequestPermission` launcher registered anywhere. The snackbar shown to the user is transient. This confirms RC-1 in requirements.

## 6. Tests covering inline image rendering

`ImageBlockDetectionTest` (jvmTest) covers `extractSingleImageNode` with:
- `../assets/photo.jpg` — passes, returns URL + alt
- `https://example.com/img.png` — passes
- Logseq size hints `{:height N, :width N}` — passes
- Null cases (text, two images, empty)

**No test covers `../uploads/filename.png` input to `extractSingleImageNode`.** No UI test renders an `ImageBlock` with a `../uploads/` path and asserts it loads an image. The `SteleKitAssetMapperTest` has 8 tests, all using `../assets/` paths — no `../uploads/` coverage.

## Summary

| Issue | Root Cause | Fix Location |
|---|---|---|
| S3: uploads images blank | `SteleKitAssetMapper` only handles `../assets/` prefix | Add `../uploads/` branch in `SteleKitAssetMapper.map()` + test |
| S2: camera does nothing | No `RequestPermission` launcher; `capturePhoto()` returns `PermissionDenied` immediately | Add `ActivityResultContracts.RequestPermission` launcher in `MainActivity` or `PageView` |
| S1: icon not visible | Icons only render inside the `editingBlockId != null` guard — UX discoverability | Add comment to `EditorCapabilities`; no layout change required unless confirmed clipping |
| Gallery images | `LocalGraphRootPath` not provided to `GalleryScreen`; `GalleryCard` calls `rememberSteleKitImageLoader()` which skips the mapper | `GalleryScreen` receives absolute paths from DB so currently OK; worth adding `LocalGraphRootPath` propagation for future safety |
