# Findings: Image/File Attachment Technology Stack

Researched: 2026-05-15

## Summary

This document covers the five technical subtopics for image/file attachment in a KMP + Compose
Multiplatform project: (1) the Coil 3 custom Fetcher API for loading relative asset paths,
(2) KMP file-picker library comparison, (3) Android camera/gallery via ActivityResultContracts,
(4) Desktop drag-and-drop via Compose Multiplatform modifiers, and (5) clipboard image paste.
The dominant finding is that Coil 3 already ships with all the pieces needed to load local files
via `file://` URIs and `okio.Path` — no third-party fetcher library is required. For file
picking, FileKit (vinceglb) is the recommended library. Clipboard image paste on Desktop is
achievable via raw AWT but is not yet part of the Compose `Clipboard` abstraction.

---

## 1. Coil 3 Custom Fetcher API

### What exists

**Source of truth**: `coil-core/src/commonMain/kotlin/coil3/fetch/Fetcher.kt` (verified from GitHub).

```kotlin
fun interface Fetcher {
    suspend fun fetch(): FetchResult?

    fun interface Factory<T : Any> {
        fun create(
            data: T,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher?
    }
}
```

`FetchResult` is a sealed interface with two concrete classes (from `FetchResult.kt`):

```kotlin
class SourceFetchResult(
    val source: ImageSource,   // okio-backed source or file path
    val mimeType: String?,
    val dataSource: DataSource,
) : FetchResult

class ImageFetchResult(
    val image: Image,
    val isSampled: Boolean,
    val dataSource: DataSource,
) : FetchResult
```

`DataSource` enum values: `NETWORK`, `DISK`, `MEMORY`, `MEMORY_CACHE`.

### Built-in local-file support in Coil 3

Coil 3 ships these built-in mappers/fetchers in `commonMain`:

| Component | Source type → Target | What it does |
|-----------|---------------------|--------------|
| `StringMapper` | `String` → `coil3.Uri` | Parses any string (URL, `file://` path) into a Coil URI |
| `PathMapper` | `okio.Path` → `coil3.Uri` | Wraps an okio Path into a `file://` URI |
| `FileUriFetcher` | `coil3.Uri` (file://) → `SourceFetchResult` | Reads bytes from a local file path using `ImageSource(path, options.fileSystem)` |

In `androidMain`:

| Component | Purpose |
|-----------|---------|
| `AndroidUriMapper` | Maps `android.net.Uri` → `coil3.Uri` |
| `ContentUriFetcher` | Reads `content://` URIs via `ContentResolver` (gallery photos, contacts) |

**Conclusion**: Passing a `file:///absolute/path/to/assets/image.png` string or an `okio.Path`
directly as the `model` to `AsyncImage` already works in Coil 3 on JVM and Android without a
custom fetcher. The chain is: `String` → `StringMapper` → `coil3.Uri` → `FileUriFetcher` →
`ImageSource(path, options.fileSystem)` → decode.

### What SteleKitAssetFetcher must do

The only missing piece is **URL rewriting**: converting a Logseq-style relative path
(`../assets/image.png`) into an absolute `file://` URI anchored at the graph root. This requires
a custom `Mapper<String, coil3.Uri>` (not a `Fetcher`), or a `Fetcher.Factory<String>` that
intercepts strings matching the `../assets/` prefix.

**Recommended pattern** — a `Mapper`:

```kotlin
// commonMain: interface / expect
class SteleKitAssetMapper(private val graphRoot: String) : Mapper<String, Uri> {
    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        return "$graphRoot/assets/$filename".toUri()
    }
}

// Registration in rememberSteleKitImageLoader:
ImageLoader.Builder(context)
    .components {
        add(SteleKitAssetMapper(graphRoot))
    }
    .build()
```

Once mapped to a `file://` URI, `FileUriFetcher` (already registered) handles the rest. No
platform-specific fetcher is needed — `FileUriFetcher` uses okio and works on all platforms.

### ImageLoader registration

```kotlin
ImageLoader.Builder(context)
    .components {
        add(MyFetcher.Factory())         // add a Fetcher.Factory
        add(MyMapper())                  // add a Mapper
        add(MyKeyer())                   // add a Keyer (needed for memory cache)
    }
    .build()
```

**Cache key note**: If `SteleKitAssetMapper` maps strings, the string itself becomes the cache
key (via `StringKeyer`). No custom `Keyer` is needed.

---

## 2. KMP File Picker Library Comparison

### Options surveyed

1. **Wavesonics/compose-multiplatform-file-picker** — archived June 2024, read-only.
2. **MohamedRejeb/Calf** (`calf-file-picker:0.9.0`) — active, Android/iOS/Desktop/Web.
3. **vinceglb/FileKit** (`filekit-dialogs-compose:0.14.1`) — active, Android/iOS/macOS/JVM/JS/WASM.

### Trade-off matrix

| Library | Android | iOS | Desktop JVM | Web | Coil integration | Activity status | License |
|---------|---------|-----|-------------|-----|-----------------|-----------------|---------|
| Wavesonics | Yes | Yes | Yes | No | None | **Archived** | Apache-2.0 |
| Calf | Yes | Yes | Yes | Yes | `calf-file-picker-coil` | Active | Apache-2.0 |
| FileKit | Yes | Yes | Yes (JVM) | JS+WASM | `filekit-coil` (PlatformFileFetcher + keyer) | Active | Apache-2.0 |

### Calf API summary

```kotlin
val launcher = rememberFilePickerLauncher(
    type = FilePickerFileType.Image,
    selectionMode = FilePickerSelectionMode.Single,
    onResult = { files ->
        val bytes = files.firstOrNull()?.readByteArray()  // suspend
    }
)
```

Supports: `Image`, `Video`, `Audio`, `Document`, `Pdf`, `Text`, `All`, `Folder`, custom MIME
types, custom extensions. `readByteArray()` loads the whole file into memory — not suitable for
large files.

Known limitations:
- iOS HEIC/RAW auto-transcoded to JPEG by default.
- iOS security-scoped URLs expire; `url` property copies to temp directory.
- `readByteArray()` in-memory only — for large files, use platform-specific APIs.

### FileKit API summary

```kotlin
// Compose integration
val launcher = rememberFilePickerLauncher(
    mode = PickerMode.Single,
    type = PickerType.Image,
) { file: PlatformFile? ->
    // file.readBytes() — suspend
    // file.path — absolute path (JVM/Android)
}
launcher.launch()

// Coil integration
ImageLoader.Builder(context)
    .components { addPlatformFileSupport() }
    .build()

AsyncImage(model = platformFile, contentDescription = "preview")
```

FileKit dependency coordinates:
- `io.github.vinceglb:filekit-dialogs-compose:<version>` (Compose picker UI)
- `io.github.vinceglb:filekit-coil:<version>` (Coil Fetcher/Keyer/Mapper for PlatformFile)

### Recommendation: FileKit

FileKit is preferred over Calf for this project because:
1. It has a native `filekit-coil` integration module that registers a `PlatformFileFetcher` and
   `PlatformFileKeyer` — exactly what is needed for showing picked-file previews.
2. It provides `file.path` (absolute string) on JVM/Android, enabling direct copy to
   `assets/` without full in-memory buffering.
3. It targets JVM, Android, iOS, macOS, JS, and WASM — complete coverage of the project's
   platform matrix.
4. Wavesonics is archived and should not be adopted.
5. Calf has similar coverage but its Coil integration module is less complete; FileKit's
   `addPlatformFileSupport()` is a single-line setup.

**Risk**: FileKit is at v0.14.1 (pre-1.0). API may shift. Mitigate by pinning the version
in the version catalog.

---

## 3. Android Camera and Gallery

### Gallery — Photo Picker (recommended)

Android 13+ ships a system photo picker (no permission needed):

```kotlin
val galleryLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia()
) { uri: Uri? ->
    uri?.let { /* copy to assets/ via contentResolver.openInputStream(uri) */ }
}

// Launch:
galleryLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
```

For Android < 13 fallback, use `GetContent` with `image/*` MIME type, which requires
`READ_MEDIA_IMAGES` permission (Android 13+) or `READ_EXTERNAL_STORAGE` (Android < 13):

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

### Camera capture

Camera capture requires a `FileProvider` URI (cannot pass a bare File path to `TakePicture`
on Android 7+):

```kotlin
// 1. Create temp file + URI
val photoUri: Uri = FileProvider.getUriForFile(
    context,
    "${context.packageName}.provider",
    File.createTempFile("photo_", ".jpg", context.cacheDir)
)

// 2. Launcher
val cameraLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.TakePicture()
) { success: Boolean ->
    if (success) { /* photoUri is now populated */ }
}

// 3. Launch
cameraLauncher.launch(photoUri)
```

Required manifest entries:

```xml
<uses-permission android:name="android.permission.CAMERA" />

<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

`res/xml/file_paths.xml`:
```xml
<paths>
    <cache-path name="cache" path="." />
</paths>
```

**Summary of permissions**:
- Camera only: `CAMERA`
- Gallery (Photo Picker, Android 13+): no permission needed
- Gallery (GetContent, Android < 13): `READ_EXTERNAL_STORAGE`
- Gallery (GetContent, Android 13): `READ_MEDIA_IMAGES`

### expect/actual pattern for KMP

Define in `commonMain`:
```kotlin
interface ImagePicker {
    suspend fun pickFromGallery(): String?   // returns absolute path copied to assets/
    suspend fun captureFromCamera(): String? // returns absolute path copied to assets/
}

expect fun createImagePicker(context: Any?): ImagePicker
```

Platform implementations live in `androidMain` (using launchers above), `jvmMain` (AWT file
chooser), `iosMain` (UIImagePickerController via interop). The `FileKit` library can replace
the `commonMain` interface + `jvmMain`/`androidMain` actuals entirely.

---

## 4. Desktop Drag-and-Drop

### API evolution

| Version | API | Status |
|---------|-----|--------|
| < 1.4 | AWT `DropTarget` | Works but not Compose-native |
| 1.4–1.6 | `Modifier.onExternalDrag` | Deprecated |
| 1.7+ | `Modifier.dragAndDropTarget` + `Modifier.dragAndDropSource` | **Current** |
| 1.8+ | `Modifier.onExternalDrag` removed | N/A |

The current API (`dragAndDropTarget`) is `@ExperimentalComposeUiApi` and requires opt-in.

### Receiving dropped files on Desktop JVM

On JVM, `DragAndDropEvent` wraps an AWT `DropTargetEvent`. Files are accessed via
`awtTransferable` (the underlying `java.awt.datatransfer.Transferable`):

```kotlin
// jvmMain only
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.*
import java.awt.datatransfer.DataFlavor

@OptIn(ExperimentalComposeUiApi::class)
val dropTarget = remember {
    object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val transferable = event.awtTransferable
            if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
            @Suppress("UNCHECKED_CAST")
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
            // copy files to assets/ here
            return true
        }
    }
}

Box(
    modifier = Modifier
        .fillMaxSize()
        .dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = dropTarget
        )
)
```

**Note**: `event.awtTransferable` is a JVM-only extension property on `DragAndDropEvent`.
This code must live in `jvmMain`, with a no-op `expect` in `commonMain`.

### Web

Compose Multiplatform for Web does **not** support `dragAndDropTarget` as of 1.8. Web drag-and-
drop requires interop with HTML drag events via Kotlin/Wasm JS interop — out of scope for the
initial feature.

### Android

`Modifier.dragAndDropTarget` works on Android but Android does not support dropping files from
external apps into Compose in the same way. The requirement matrix marks drag-and-drop as N/A
for Android.

---

## 5. Clipboard Image Paste

### Current state (Compose Multiplatform 1.8)

The Compose Multiplatform `Clipboard` interface (successor to `ClipboardManager`) is designed
for text and structured data. As of 1.8:

- **Image clipboard paste is not supported** in the common Compose `Clipboard` abstraction.
- The JetBrains issue tracker (CMP-3763, opened 2023-10) tracks this as an enhancement with
  no scheduled release.
- The new `Clipboard` interface uses `suspend` functions for cross-platform compatibility
  (needed for Web's async Clipboard API), but image data types are not yet included.

### Desktop JVM workaround

Direct AWT access works reliably for pasting images on JVM (jvmMain only):

```kotlin
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage

fun getClipboardImage(): BufferedImage? {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val transferable = clipboard.getContents(null) ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
    return transferable.getTransferData(DataFlavor.imageFlavor) as? BufferedImage
}
```

This should be triggered on a keyboard shortcut intercept (`Ctrl+V`) or a `KeyEvent.KEY_TYPED`
handler in the composable.

### Android workaround

Android clipboard only holds content URIs for images (not raw bytes). The paste flow is:

```kotlin
val clipboardManager = context.getSystemService(ClipboardManager::class.java)
val item = clipboardManager.primaryClip?.getItemAt(0)
val uri: Uri? = item?.uri
// Then openInputStream(uri) and copy to assets/
```

Permission `READ_MEDIA_IMAGES` is NOT required for clipboard URIs — the URI carries its own
grant.

### iOS

`UIPasteboard.general` can read `UIPasteboard.general.image` (a `UIImage`) from platform code
in `iosMain`. No Compose Multiplatform abstraction for this yet; requires Swift/ObjC interop.

### Recommendation

Implement clipboard paste **platform-specifically** via expect/actual:

```kotlin
// commonMain
expect suspend fun getClipboardImagePath(graphRoot: String): String?

// jvmMain: use AWT Toolkit + BufferedImage → write JPEG → return path
// androidMain: use ClipboardManager URI → copy via ContentResolver
// iosMain: use UIPasteboard.general.image → write → return path
```

Clipboard paste is the most complex attachment flow; it can be deferred to a follow-up
iteration if needed.

---

## Trade-off Matrix — Overall Stack Choice

| Subtopic | Recommended approach | Alternative | Why chosen |
|----------|---------------------|-------------|------------|
| Coil local asset fetcher | `SteleKitAssetMapper` (Mapper, not Fetcher) | Custom Fetcher | Mapper is simpler; FileUriFetcher already handles file:// |
| File picker | FileKit `filekit-dialogs-compose` | Calf | Coil integration module; absolute path access; active |
| Android gallery | `PickVisualMedia` (Photo Picker) | `GetContent` | No permission needed on API 33+ |
| Android camera | `TakePicture` + `FileProvider` | CameraX | Sufficient for still images; CameraX is overkill |
| Desktop D&D | `Modifier.dragAndDropTarget` + `awtTransferable` | AWT `DropTarget` | Native Compose API; `javaFileListFlavor` works |
| Clipboard paste | Platform-specific expect/actual | Compose Clipboard | Common API doesn't support images yet |

---

## Risk and Failure Modes

| Risk | Condition | Mitigation |
|------|-----------|------------|
| FileKit pre-1.0 API churn | Library update breaks picker API | Pin version in catalog; wrap behind `ImagePickerService` interface |
| `javaFileListFlavor` not in drop | User drags non-file content | Check `isDataFlavorSupported` and return `false` |
| FileProvider authority conflict | Two libraries register same authority | Use `${applicationId}.stelekit.provider` |
| iOS security-scoped URL expiry | Long delay between pick and copy | Copy to assets/ immediately in `onResult` callback |
| `READ_EXTERNAL_STORAGE` on older Android | API < 13 devices need legacy permission | Use `maxSdkVersion="32"` on the permission; provide `PickVisualMedia` fallback |
| `dragAndDropTarget` experimental | API may change in future CMP release | Single isolation layer in `jvmMain`; easy to update |

---

## Migration and Adoption Cost

- **FileKit**: Add 2 Gradle dependencies (`filekit-dialogs-compose`, `filekit-coil`). No
  breaking changes to existing code. Single-line Coil setup (`addPlatformFileSupport()`).
- **SteleKitAssetMapper**: ~30 lines of new code in `commonMain`. Replaces the TODO in
  `SteleKitAssetFetcher.kt`. No change to `ImageBlock.kt`.
- **`dragAndDropTarget`**: `jvmMain` only, ~50 lines. Requires `@OptIn(ExperimentalComposeUiApi::class)`.
- **ActivityResultContracts**: `androidMain` only, requires Manifest additions.
- **Clipboard paste**: Can be deferred; highest implementation cost of the five subtopics.

---

## Open Questions

- [ ] Does FileKit's `PlatformFile.path` return a null-safe absolute path on Android (content://
  URI) or does it require explicit copy? — blocks: deciding whether to use FileKit's path
  directly or always stream via `ContentResolver`.
- [ ] Is `Modifier.dragAndDropTarget` stable enough in the project's current Compose
  Multiplatform version to use without `@OptIn`? — check version in `kmp/build.gradle.kts`.

---

## Recommendation

**For the asset fetcher**: implement `SteleKitAssetMapper` in `commonMain` (a `Mapper<String,
coil3.Uri>` that rewrites `../assets/foo` paths to `file:///graphRoot/assets/foo`). Register it
in `rememberSteleKitImageLoader`. No platform-specific `Fetcher` is needed.

**For file picking**: adopt FileKit (`filekit-dialogs-compose` + `filekit-coil`). It handles
all four platforms, has a Coil integration module, and exposes a file path for the copy-to-
assets step.

**For Android camera/gallery**: use `PickVisualMedia` (gallery) and `TakePicture` +
`FileProvider` (camera) in `androidMain`. Required permissions: `CAMERA` and conditionally
`READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE`.

**For Desktop drag-and-drop**: use `Modifier.dragAndDropTarget` with `awtTransferable` and
`DataFlavor.javaFileListFlavor` in `jvmMain`.

**For clipboard paste**: implement via expect/actual with platform-specific AWT / Android
ClipboardManager / UIPasteboard. This can be deferred to iteration 2.

---

## Sources

- Coil 3 Fetcher.kt (GitHub source): https://github.com/coil-kt/coil/blob/main/coil-core/src/commonMain/kotlin/coil3/fetch/Fetcher.kt
- Coil 3 FetchResult.kt (GitHub source): https://github.com/coil-kt/coil/blob/main/coil-core/src/commonMain/kotlin/coil3/fetch/FetchResult.kt
- Coil 3 FileUriFetcher.kt (GitHub source): https://github.com/coil-kt/coil/blob/main/coil-core/src/commonMain/kotlin/coil3/fetch/FileUriFetcher.kt
- Coil 3 PathMapper.kt (GitHub source): https://github.com/coil-kt/coil/blob/main/coil-core/src/commonMain/kotlin/coil3/map/PathMapper.kt
- Coil Extending the Image Pipeline: https://coil-kt.github.io/coil/image_pipeline/
- Coil SourceFetchResult API: https://coil-kt.github.io/coil/api/coil-core/coil3.fetch/-source-fetch-result/index.html
- FileKit introduction: https://filekit.mintlify.app/introduction
- FileKit Coil integration: https://filekit.mintlify.app/integrations/coil
- FileKit GitHub (CLAUDE.md): https://github.com/vinceglb/FileKit/blob/main/CLAUDE.md
- Calf file picker docs: https://github.com/MohamedRejeb/Calf/blob/main/docs/filepicker.md
- Calf releases: https://github.com/MohamedRejeb/Calf/releases
- Wavesonics compose-multiplatform-file-picker (archived): https://github.com/Wavesonics/compose-multiplatform-file-picker
- Compose Multiplatform drag-and-drop docs: https://kotlinlang.org/docs/multiplatform/compose-drag-drop.html
- Compose Multiplatform 1.7 release (drag-and-drop stable): https://blog.jetbrains.com/kotlin/2024/10/compose-multiplatform-1-7-0-released/
- Compose Multiplatform 1.8.2 what's new (clipboard Clipboard API, iOS D&D): https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html
- JetBrains issue: image clipboard paste CMP-3763: https://github.com/JetBrains/compose-multiplatform/issues/3763
- ActivityResultContracts.TakePicture: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture
- Android READ_MEDIA_IMAGES permissions: https://www.droidcon.com/2025/02/10/dealing-with-read_media_images-permissions-in-android/
- KMP camera/gallery picker with expect/actual: https://dev.to/ismoy/kotlin-multiplatform-compose-unified-camera-gallery-picker-with-expectactual-and-permission-4573
- Kadirkid: Coil 3 custom Fetchers and Mappers: https://www.kadirkid.dev/unleash-the-power-of-coil-3-crafting-custom-fetchers-and-mappers-for-image-loading-mastery/
