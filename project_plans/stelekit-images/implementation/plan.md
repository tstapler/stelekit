# Implementation Plan: Image & File Attachment Support

**Generated**: 2026-05-15
**Branch**: `stelekit-images`
**CMP version**: 1.10.3 (≥ 1.7 — `dragAndDropTarget` available; `onExternalDrag` removed)
**Coil version**: 3.2.0

---

## Overview

This plan delivers image display and file attachment in four epics, ordered by dependency. Epic 1 (display) has zero prerequisites and should land first to prove the pipeline end-to-end. Epics 2–4 build on the display infrastructure.

**Total scope**: 4 epics · 14 tasks

---

## Epic 1: Image Display

**Goal**: Existing `![alt](path)` markdown renders as an inline image in view mode for both `https://` URLs and `../assets/<name>` relative paths.

**Prerequisite for all other epics**: none.

---

### Task 1.1 — Implement `SteleKitAssetMapper` in commonMain

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcher.kt`

Replace the existing stub with a working `Mapper<String, coil3.Uri>`. Keep the file at the same path; rename the class internally.

**Implementation**:

```kotlin
// Full replacement of SteleKitAssetFetcher.kt

package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.compose.LocalPlatformContext
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri

/**
 * Coil 3 [Mapper] that rewrites Logseq-style relative asset paths to absolute file:// URIs.
 *
 * Input:  "../assets/photo.jpg"
 * Output: coil3.Uri("file:///absolute/graph/root/assets/photo.jpg")
 *
 * Strings that do not start with "../assets/" are returned as null so Coil falls through
 * to its default StringMapper (handles http/https and absolute file:// paths).
 *
 * @param graphRoot absolute filesystem path of the graph root directory (no trailing slash).
 */
class SteleKitAssetMapper(private val graphRoot: String) : Mapper<String, Uri> {
    override fun map(data: String, options: Options): Uri? {
        if (!data.startsWith("../assets/")) return null
        val filename = data.removePrefix("../assets/")
        return "file://$graphRoot/assets/$filename".toUri()
    }
}

/**
 * Returns a Coil [ImageLoader] scoped to the current graph root path.
 *
 * When [LocalGraphRootPath] is non-null, registers [SteleKitAssetMapper] so that
 * `../assets/<filename>` paths resolve to absolute file:// URIs, which Coil's built-in
 * [coil3.fetch.FileUriFetcher] then loads from disk.
 *
 * No platform-specific fetcher is required: FileUriFetcher uses okio and works on all targets.
 */
@Composable
fun rememberSteleKitImageLoader(): ImageLoader {
    val graphRoot = LocalGraphRootPath.current
    val context: PlatformContext = LocalPlatformContext.current
    return remember(graphRoot, context) {
        ImageLoader.Builder(context)
            .components {
                if (graphRoot != null) {
                    add(SteleKitAssetMapper(graphRoot))
                }
            }
            .build()
    }
}
```

**Key decisions**:
- Uses `Mapper<String, Uri>`, not a full `Fetcher` — Coil's built-in `FileUriFetcher` handles the file:// URI after mapping.
- Does **not** disable the service loader — keeps `ContentUriFetcher` (Android `content://`) and `NetworkFetcher` intact.
- `graphRoot` is captured at `ImageLoader` build time (inside `remember`), not inside the mapper's `map()` call — correct threading (composition thread at build time, fetcher thread at map time, but the value is already captured).
- String cache key (`../assets/foo.jpg`) stays stable; no custom `Keyer` needed.

**Acceptance criteria**:
- [ ] `SteleKitAssetMapper.map("../assets/photo.jpg", ...)` returns `Uri("file:///graph/root/assets/photo.jpg")` when `graphRoot = "/graph/root"`.
- [ ] `SteleKitAssetMapper.map("https://example.com/img.png", ...)` returns `null`.
- [ ] `SteleKitAssetMapper.map("../assets/", ...)` returns `Uri("file:///graph/root/assets/")` (empty filename is a degenerate case; Coil will 404 gracefully).
- [ ] `rememberSteleKitImageLoader()` does **not** throw when `LocalGraphRootPath.current` is `null` (graph not yet open).
- [ ] Unit test class `SteleKitAssetMapperTest` added to `jvmTest`.

---

### Task 1.2 — Wire `ImageBlock` in `BlockItem.kt`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

Add image-only block detection before the `else -> BlockViewer(...)` branch in the view-mode dispatch (`when (block.blockType)` starting at line 349).

**Step 1** — Add private helper at the bottom of `BlockItem.kt` (alongside existing `headingLevelFromContent` etc.):

```kotlin
/**
 * Returns the image URL and alt text if [content] parses to exactly one [ImageNode]
 * with no sibling inline nodes. Returns null for all other content (text, mixed, empty).
 *
 * Called at render time only — does not affect block storage or BlockTypes enum.
 */
private fun extractSingleImageNode(content: String): Pair<String, String>? {
    val nodes = InlineParser(content.trim()).parse()
    // Allow trailing TextNode("") that some formatters emit
    val meaningful = nodes.filterNot { it is TextNode && (it as TextNode).content.isBlank() }
    return if (meaningful.size == 1 && meaningful[0] is ImageNode) {
        val img = meaningful[0] as ImageNode
        img.url to img.alt
    } else null
}
```

Required import: `dev.stapler.stelekit.parsing.InlineParser` and `dev.stapler.stelekit.parsing.ast.ImageNode`, `dev.stapler.stelekit.parsing.ast.TextNode`.

**Step 2** — In the `else ->` branch of the view-mode `when` (line 388), prepend an image check:

```kotlin
else -> {
    // Image-only block: dispatch to ImageBlock before falling through to BlockViewer
    val imageData = extractSingleImageNode(block.content)
    if (imageData != null) {
        val (url, altText) = imageData
        ImageBlock(
            url = url,
            altText = altText,
            onStartEditing = onStartEditing,
            modifier = Modifier.weight(1f),
        )
    } else {
        BlockViewer(
            content = block.content,
            textColor = textColor,
            linkColor = linkColor,
            resolvedRefs = resolvedRefs,
            onLinkClick = onLinkClick,
            onStartEditing = onStartEditing,
            modifier = Modifier.weight(1f),
            isShiftDown = isShiftDown,
            onShiftClick = onShiftClick,
            suggestionMatcher = suggestionMatcher,
            onSuggestionClick = { canonicalName, contentStart, contentEnd ->
                suggestionState = SuggestionState(canonicalName, contentStart, contentEnd, block.content)
            },
            onSuggestionRightClick = { canonicalName, contentStart, contentEnd ->
                contextMenuState = SuggestionState(canonicalName, contentStart, contentEnd, block.content)
            },
        )
    }
}
```

**Note on `ImageBlock` modifier**: `ImageBlock` already applies `Modifier.fillMaxWidth()` internally. The outer `Modifier.weight(1f)` is still required so the `Row` layout computes width correctly.

**Note on `ImageBlock` size**: `ImageBlock.kt` uses `ContentScale.FillWidth` and `Modifier.fillMaxWidth()`. Add `Modifier.heightIn(max = 400.dp)` to `ImageBlock.kt` to prevent OOM on 12MP+ images (pitfalls §6).

**Acceptance criteria**:
- [ ] A page with only `![](https://example.com/img.png)` as a block renders `AsyncImage`, not link text.
- [ ] A page with `![photo](../assets/photo.jpg)` renders the local image when the file exists.
- [ ] A page with `some text ![](url) more text` renders as `BlockViewer` (mixed content).
- [ ] A block with only `text content` renders as `BlockViewer`.
- [ ] Tapping the image calls `onStartEditing`, entering edit mode.
- [ ] `ImageBlock` now has `Modifier.heightIn(max = 400.dp)`.

---

### Task 1.3 — Unit tests for `extractSingleImageNode`

**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ImageBlockDetectionTest.kt` (new file)

```kotlin
package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImageBlockDetectionTest {

    @Test fun `image-only block returns url and alt`() {
        val result = extractSingleImageNode("![My Photo](../assets/photo.jpg)")
        assertNotNull(result)
        assertEquals("../assets/photo.jpg", result.first)
        assertEquals("My Photo", result.second)
    }

    @Test fun `https url image-only block`() {
        val result = extractSingleImageNode("![](https://example.com/img.png)")
        assertNotNull(result)
        assertEquals("https://example.com/img.png", result.first)
    }

    @Test fun `text only block returns null`() {
        assertNull(extractSingleImageNode("just some text"))
    }

    @Test fun `mixed content returns null`() {
        assertNull(extractSingleImageNode("text ![photo](url) more text"))
    }

    @Test fun `empty string returns null`() {
        assertNull(extractSingleImageNode(""))
    }

    @Test fun `blank string returns null`() {
        assertNull(extractSingleImageNode("   "))
    }

    @Test fun `two images returns null`() {
        assertNull(extractSingleImageNode("![a](url1) ![b](url2)"))
    }

    @Test fun `image with trailing whitespace returns url and alt`() {
        // Trailing whitespace is a hard break candidate — should still detect the image
        val result = extractSingleImageNode("![alt](../assets/x.png)  ")
        // Content ends with "  " which is a HardBreak signal; parser may emit HardBreakNode
        // Accept either result (null is safe; no false-positive detection)
        // This test documents the expected behavior: trailing double-space with image = not a simple image block
        assertNull(result) // Trailing hard-break node means it's not single-image
    }
}
```

**Note**: `extractSingleImageNode` is a `private fun` in `BlockItem.kt`. To test it without making it `internal`, either:
1. Move it to a new `BlockItemUtils.kt` file with `internal` visibility (preferred).
2. Or keep it private and test indirectly via integration tests.

**Recommended**: extract to `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItemUtils.kt` as `internal fun extractSingleImageNode(content: String): Pair<String, String>?`.

**Acceptance criteria**:
- [ ] All 8 test cases pass.
- [ ] `extractSingleImageNode` is `internal` and testable from `jvmTest`.
- [ ] CI (`./gradlew jvmTest`) passes green.

---

## Epic 2: File Attachment Service

**Goal**: A platform-agnostic interface for picking files and copying them to `<graphRoot>/assets/`. This is the domain layer; no UX wiring yet.

**Prerequisite**: Epic 1 complete (verifies the display pipeline before attaching files).

---

### Task 2.1 — `MediaAttachmentService` interface in commonMain

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/MediaAttachmentService.kt` (new file)

```kotlin
package dev.stapler.stelekit.service

import arrow.core.Either
import dev.stapler.stelekit.model.DomainError

/**
 * Result of a successful file attachment operation.
 *
 * @param relativePath Logseq-compatible relative path to insert into block markdown,
 *   always of the form `../assets/<filename>`.
 * @param displayName Human-readable filename for use as the image alt text.
 */
data class AttachmentResult(
    val relativePath: String,   // e.g. "../assets/photo-1.jpg"
    val displayName: String     // e.g. "photo-1.jpg"
)

/**
 * Platform-agnostic service for picking a media file and copying it into the graph's
 * `assets/` directory.
 *
 * All implementations must:
 * - Run the file copy on [dev.stapler.stelekit.platform.PlatformDispatcher.IO].
 * - Create `<graphRoot>/assets/` if it does not exist.
 * - Handle duplicate filenames via suffix counter (photo.jpg → photo-1.jpg → photo-2.jpg).
 * - Write to a temp file first, then atomically rename, to prevent Coil reading partial writes.
 * - Return [arrow.core.Either.Left] with [DomainError] on any failure; never throw.
 */
interface MediaAttachmentService {
    /**
     * Opens the platform file picker (gallery on mobile, file chooser on desktop),
     * copies the selected file to `<graphRoot>/assets/<uniqueName>`, and returns
     * the relative markdown path.
     *
     * Returns [Either.Right] with [AttachmentResult] on success.
     * Returns [Either.Left] with [DomainError.AttachmentError] if the user cancels or
     * if the file copy fails.
     * Returns `null` if the user cancels without selecting a file (no error, no result).
     */
    suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>?
}
```

**Also add** to `DomainError` (file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/DomainError.kt`):

```kotlin
// Inside the existing DomainError sealed class:
sealed class AttachmentError : DomainError() {
    data class CopyFailed(override val message: String) : AttachmentError()
    data class PickerFailed(override val message: String) : AttachmentError()
    data class AssetsDirectoryFailed(override val message: String) : AttachmentError()
}
```

**Acceptance criteria**:
- [ ] `MediaAttachmentService.kt` compiles in `commonMain`.
- [ ] `DomainError.AttachmentError` variants exist and are sealed subclasses.
- [ ] No platform imports in either file.

---

### Task 2.2 — Unique filename generation in commonMain

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/service/AttachmentFileNaming.kt` (new file)

```kotlin
package dev.stapler.stelekit.service

import okio.FileSystem
import okio.Path

/**
 * Returns a unique filename under [assetsDir] using a suffix-counter strategy:
 *   photo.jpg → photo-1.jpg → photo-2.jpg → …
 *
 * Checks for file existence using the provided [fileSystem] (injectable for tests).
 * The check-and-name loop must be called within a single-coroutine serialized context
 * to eliminate TOCTOU races (the caller — [AttachmentWriter] — is responsible for this).
 *
 * @param assetsDir    absolute okio [Path] to the assets directory.
 * @param stem         filename stem without extension (e.g. "photo").
 * @param ext          file extension without leading dot (e.g. "jpg").
 * @param fileSystem   okio [FileSystem] for existence checks (default: [FileSystem.SYSTEM]).
 * @return             unique filename string (e.g. "photo-2.jpg").
 */
fun uniqueFileName(
    assetsDir: Path,
    stem: String,
    ext: String,
    fileSystem: FileSystem = FileSystem.SYSTEM
): String {
    val base = if (ext.isBlank()) stem else "$stem.$ext"
    if (!fileSystem.exists(assetsDir / base)) return base
    var counter = 1
    while (true) {
        val candidate = if (ext.isBlank()) "$stem-$counter" else "$stem-$counter.$ext"
        if (!fileSystem.exists(assetsDir / candidate)) return candidate
        counter++
    }
}
```

**Test file**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/service/AttachmentFileNamingTest.kt`

```kotlin
package dev.stapler.stelekit.service

import okio.fakefilesystem.FakeFileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentFileNamingTest {
    private val fs = FakeFileSystem()
    private val assetsDir = "/graph/assets".toPath()

    @Test fun `returns base name when no conflict`() {
        fs.createDirectories(assetsDir)
        assertEquals("photo.jpg", uniqueFileName(assetsDir, "photo", "jpg", fs))
    }

    @Test fun `returns dash-1 when base exists`() {
        fs.createDirectories(assetsDir)
        fs.write(assetsDir / "photo.jpg") {}
        assertEquals("photo-1.jpg", uniqueFileName(assetsDir, "photo", "jpg", fs))
    }

    @Test fun `returns dash-2 when dash-1 also exists`() {
        fs.createDirectories(assetsDir)
        fs.write(assetsDir / "photo.jpg") {}
        fs.write(assetsDir / "photo-1.jpg") {}
        assertEquals("photo-2.jpg", uniqueFileName(assetsDir, "photo", "jpg", fs))
    }

    @Test fun `no extension base case`() {
        fs.createDirectories(assetsDir)
        assertEquals("file", uniqueFileName(assetsDir, "file", "", fs))
    }
}
```

**Acceptance criteria**:
- [ ] All 4 test cases pass.
- [ ] Uses `okio.fakefilesystem.FakeFileSystem` — no real disk I/O in tests.
- [ ] `uniqueFileName` is pure (no side effects, injectable filesystem).

---

### Task 2.3 — JVM actual: `JvmMediaAttachmentService`

**File**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/service/JvmMediaAttachmentService.kt` (new file)

```kotlin
package dev.stapler.stelekit.service

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.DomainError
import dev.stapler.stelekit.platform.PlatformDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop JVM implementation of [MediaAttachmentService].
 * Uses [FileDialog] (AWT native file chooser) to pick an image file.
 */
class JvmMediaAttachmentService : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? = withContext(PlatformDispatcher.IO) {
        // 1. Show native file picker on AWT Event Dispatch Thread
        val selectedFile: File? = showFilePicker()
        if (selectedFile == null) return@withContext null   // user cancelled

        // 2. Prepare assets directory
        val assetsDir = File("$graphRoot/assets")
        try {
            assetsDir.mkdirs()
        } catch (e: Exception) {
            return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(
                "Cannot create assets dir: ${e.message}"
            ).left()
        }

        // 3. Determine unique filename
        val stem = selectedFile.nameWithoutExtension
        val ext = selectedFile.extension
        val uniqueName = uniqueFileName(assetsDir.toPath(), stem, ext, FileSystem.SYSTEM)
        val destFile = File(assetsDir, uniqueName)
        val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")

        // 4. Copy to temp, then atomic rename
        try {
            selectedFile.inputStream().use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmpFile.delete()
            return@withContext DomainError.AttachmentError.CopyFailed(
                "File copy failed: ${e.message}"
            ).left()
        }

        AttachmentResult(
            relativePath = "../assets/$uniqueName",
            displayName = uniqueName
        ).right()
    }

    /**
     * Shows a native AWT [FileDialog] on the Event Dispatch Thread.
     * Returns the selected [File], or null if cancelled.
     *
     * [FileDialog] is preferred over [javax.swing.JFileChooser] on macOS because it
     * renders as a native sheet; on Linux it falls back to GTK.
     */
    private fun showFilePicker(): File? {
        var result: File? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        java.awt.EventQueue.invokeLater {
            try {
                val dialog = FileDialog(null as Frame?, "Select Image", FileDialog.LOAD).apply {
                    // Filter to common image types (best-effort; platform support varies)
                    setFilenameFilter { _, name ->
                        name.lowercase().let { n ->
                            n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                            n.endsWith(".png") || n.endsWith(".gif") ||
                            n.endsWith(".webp") || n.endsWith(".heic") ||
                            n.endsWith(".svg")
                        }
                    }
                    isVisible = true
                }
                val file = dialog.file
                val dir = dialog.directory
                if (file != null && dir != null) {
                    result = File(dir, file)
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return result
    }
}
```

**Acceptance criteria**:
- [ ] `JvmMediaAttachmentService` implements `MediaAttachmentService`.
- [ ] Returns `null` (not `Either.Left`) when user cancels the dialog.
- [ ] Returns `Either.Left(DomainError.AttachmentError.CopyFailed)` on IO failure.
- [ ] Returns `Either.Right(AttachmentResult)` with `relativePath = "../assets/<name>"` on success.
- [ ] Temp file is created inside `assets/` (same filesystem as destination) and cleaned up on failure.
- [ ] No raw exceptions escape the function.

---

### Task 2.4 — Android actual: `AndroidMediaAttachmentService`

**File**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/service/AndroidMediaAttachmentService.kt` (new file)

Android's `ActivityResultContracts` require a Composable `LauncherForActivityResult`. The service itself cannot show UI — instead it provides a Composable builder that wires launchers and returns a ready-to-use service.

**Pattern**: Hoist launcher wiring into a `@Composable` factory; the returned object is stable across recompositions.

```kotlin
package dev.stapler.stelekit.service

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.DomainError
import dev.stapler.stelekit.platform.PlatformDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume

/**
 * Android implementation of [MediaAttachmentService].
 *
 * Use [rememberAndroidMediaAttachmentService] in a Composable to create an instance
 * with properly scoped [ActivityResultLauncher]s.
 */
class AndroidMediaAttachmentService(
    private val context: Context,
    private val launchGalleryPicker: suspend () -> Uri?,
) : MediaAttachmentService {

    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? = withContext(PlatformDispatcher.IO) {
        // 1. Show photo picker
        val uri: Uri? = launchGalleryPicker()
        if (uri == null) return@withContext null  // user cancelled

        // 2. Prepare assets directory
        val assetsDir = File("$graphRoot/assets")
        try {
            assetsDir.mkdirs()
        } catch (e: Exception) {
            return@withContext DomainError.AttachmentError.AssetsDirectoryFailed(
                "Cannot create assets dir: ${e.message}"
            ).left()
        }

        // 3. Determine filename from URI
        val displayName = resolveDisplayName(uri) ?: "attachment"
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (displayName.contains('.')) displayName.substringAfterLast('.') else ""
        val uniqueName = uniqueFileName(assetsDir.toPath(), stem, ext, FileSystem.SYSTEM)
        val destFile = File(assetsDir, uniqueName)
        val tmpFile = File(assetsDir, ".tmp-${java.util.UUID.randomUUID()}")

        // 4. Copy content:// URI → temp → atomic rename
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext DomainError.AttachmentError.CopyFailed(
                    "ContentResolver returned null stream for $uri"
                ).left()
            inputStream.use { input ->
                tmpFile.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(tmpFile.toPath(), destFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmpFile.delete()
            return@withContext DomainError.AttachmentError.CopyFailed(
                "File copy failed: ${e.message}"
            ).left()
        }

        AttachmentResult(
            relativePath = "../assets/$uniqueName",
            displayName = uniqueName
        ).right()
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }
}

/**
 * Composable factory that wires [ActivityResultLauncher]s and returns an
 * [AndroidMediaAttachmentService] stable across recompositions.
 *
 * Call this once per screen (e.g. in PageView or JournalsView), not per-block.
 *
 * Uses [ActivityResultContracts.PickVisualMedia] (Android Photo Picker, no permission
 * required on API 21+ via Google Play Services backport).
 */
@Composable
fun rememberAndroidMediaAttachmentService(context: Context): AndroidMediaAttachmentService {
    // Continuation holder for bridging launcher callback → coroutine
    var pendingContinuation: kotlin.coroutines.Continuation<Uri?>? = null

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        pendingContinuation?.resume(uri)
        pendingContinuation = null
    }

    return remember(context) {
        AndroidMediaAttachmentService(
            context = context,
            launchGalleryPicker = {
                suspendCancellableCoroutine { cont ->
                    pendingContinuation = cont
                    cont.invokeOnCancellation { pendingContinuation = null }
                    launcher.launch(
                        androidx.activity.result.contract.ActivityResultContracts
                            .PickVisualMedia.VisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts
                                    .PickVisualMedia.ImageOnly
                            )
                    )
                }
            }
        )
    }
}
```

**AndroidManifest.xml additions** (file: `kmp/src/androidMain/AndroidManifest.xml`):

```xml
<!-- Only needed for Android < 13 fallback (Photo Picker handles 13+ without permission) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32" />
```

**Acceptance criteria**:
- [ ] `AndroidMediaAttachmentService` implements `MediaAttachmentService`.
- [ ] Uses `PickVisualMedia` (Photo Picker), not `GetContent` — no manifest permission on API 33+.
- [ ] `rememberAndroidMediaAttachmentService` is called at screen level (not per-block).
- [ ] `openInputStream(uri)` null-check returns `Either.Left` with a descriptive error.
- [ ] Manifest permissions have `maxSdkVersion="32"` to avoid unnecessary permission requests on API 33+.
- [ ] File copy uses temp-then-rename (atomic, prevents partial-write visible to Coil).

---

### Task 2.5 — iOS actual: `IosMediaAttachmentService` stub

**File**: `kmp/src/iosMain/kotlin/dev/stapler/stelekit/service/IosMediaAttachmentService.kt` (new file)

```kotlin
package dev.stapler.stelekit.service

import arrow.core.Either
import dev.stapler.stelekit.model.DomainError

/**
 * iOS stub for [MediaAttachmentService].
 *
 * Full implementation requires [PHPickerViewController] via Kotlin/Native ObjC interop —
 * deferred to a follow-up iteration. Consider adopting the `ImagePickerKMP` library
 * (io.github.ismoy:imagepickerkmp) to avoid hand-rolling the ObjC callback bridge.
 *
 * See pitfalls.md §5 for: PHPickerViewController vs UIImagePickerController, thread safety,
 * NSCameraUsageDescription requirement, and security-scoped URL handling.
 */
class IosMediaAttachmentService : MediaAttachmentService {
    override suspend fun pickAndAttach(
        graphRoot: String,
        pageRelativePath: String
    ): Either<DomainError, AttachmentResult>? {
        throw NotImplementedError(
            "iOS image picker not yet implemented. " +
            "Use PHPickerViewController via Kotlin/Native interop or ImagePickerKMP library."
        )
    }
}
```

**Acceptance criteria**:
- [ ] iOS target compiles.
- [ ] Attempting to call `pickAndAttach` throws `NotImplementedError` with a descriptive message.
- [ ] A comment documents the recommended follow-up path (PHPickerViewController / ImagePickerKMP).

---

## Epic 3: Attachment UX — Slash Command & Toolbar Button

**Goal**: Wire the attachment service into the editor UX. After picking, update the block content to `![<displayName>](../assets/<name>)`.

**Prerequisite**: Epic 2 complete (service must exist before wiring UX).

---

### Task 3.1 — Add `/image` slash command

**Files to change**:
1. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/SlashCommandHandler.kt`
2. `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt`

**Step 1** — In `SlashCommandHandler.mapSlashToCommandId()` (line ~278), add the image mapping alongside the existing entries:

```kotlin
"image" -> "media.image"
```

**Step 2** — In `SlashCommandHandler.mapCommandIdToSlash()` (line ~343), add the reverse:

```kotlin
"media.image" -> "image"
```

**Step 3** — Add a `MediaCommands` object to `EssentialCommands.kt`:

```kotlin
object MediaCommands {
    val insertImage = EditorCommand(
        id = "media.image",
        type = CommandType.BLOCK,    // BLOCK type — not a text-wrap operation
        label = "Image",
        description = "Attach an image from gallery or file system",
        shortcut = null,
        icon = "image",
        config = CommandConfig(
            requiresBlock = true,
            priority = CommandPriority.NORMAL,
            async = true
        ),
        execute = { context ->
            // The actual picker launch is platform-specific and happens in the UI layer
            // (BlockItem.kt LaunchedEffect). The command signals intent via CommandResult data.
            CommandResult.Success(
                message = "Image picker requested",
                data = mapOf("action" to "openImagePicker",
                             "blockId" to (context.currentBlockId ?: ""))
            )
        }
    )
}
```

Also add `MediaCommands.insertImage` to the `getAll()` list in `EssentialCommands`.

**Step 4** — In `BlockItem.kt`, add a `onAttachImage: (() -> Unit)? = null` parameter and wire a `LaunchedEffect` to listen for the `media.image` command result. This is the bridge between the command system and the platform picker:

```kotlin
// In BlockItem parameter list (add after formatEvents):
onAttachImage: (() -> Unit)? = null,
```

The actual call to `onAttachImage` is triggered from the slash-command autocomplete selection handler in `BlockEditor.kt` when the selected command ID is `"media.image"`. See Task 3.3 for the complete wiring.

**Acceptance criteria**:
- [ ] Typing `/image` in a block shows "Image" in the slash command autocomplete list.
- [ ] Selecting `/image` from the autocomplete emits the `media.image` command signal.
- [ ] `mapSlashToCommandId("image")` returns `"media.image"`.
- [ ] `mapCommandIdToSlash("media.image")` returns `"image"`.
- [ ] `EssentialCommands.getAll()` includes `MediaCommands.insertImage`.

---

### Task 3.2 — Attachment toolbar button in `MobileBlockToolbar`

**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

Add an `onAttachImage: (() -> Unit)? = null` parameter to `MobileBlockToolbar`. Add an attachment button to the primary actions row (alongside the existing `[[]]` link button):

```kotlin
// In MobileBlockToolbar parameter list (after onLinkPicker):
onAttachImage: (() -> Unit)? = null,
```

In the primary actions row (the `val primaryActions: @Composable RowScope.() -> Unit` lambda, around line 166), add an image button after the link button:

```kotlin
// After the [[]] link TextButton:
if (onAttachImage != null) {
    IconButton(
        onClick = { onAttachImage() },
        modifier = Modifier.semantics { contentDescription = "Attach image" }
    ) {
        Icon(
            Icons.Default.AttachFile,
            contentDescription = null
        )
    }
}
```

Required import: `androidx.compose.material.icons.filled.AttachFile`

**Note**: `onAttachImage` is nullable — on platforms where attachment is not yet implemented (iOS), callers pass `null` and the button is hidden.

**Acceptance criteria**:
- [ ] A paperclip (AttachFile) icon button appears in the block toolbar when editing a block on Desktop and Android.
- [ ] Button is absent on iOS (caller passes `null`).
- [ ] Pressing the button calls `onAttachImage()`.
- [ ] Toolbar still passes `./gradlew ciCheck` (no detekt failures).

---

### Task 3.3 — Wire picker to block content update

**Files**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (or `JournalsView.kt` — wherever `BlockList` and `MobileBlockToolbar` are assembled)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockList.kt` (pass-through of `onAttachImage`)

**Wiring pattern** (in the screen composable, e.g. `PageView.kt`):

```kotlin
// 1. Obtain the platform-specific service
// On Android (inside AndroidActivity composable context):
val attachmentService = rememberAndroidMediaAttachmentService(LocalContext.current)
// On JVM (no Composable factory needed):
val attachmentService = remember { JvmMediaAttachmentService() }

// 2. Obtain the graph root from the composition local
val graphRoot = LocalGraphRootPath.current ?: return  // guard: no graph open

// 3. Obtain the editing block UUID
val editingBlockUuid by viewModel.editingBlockUuid.collectAsState()

// 4. Pass onAttachImage to MobileBlockToolbar and BlockList
val coroutineScope = rememberCoroutineScope()  // ok here: UI-event use only, not stored in class

val onAttachImage: (() -> Unit)? = editingBlockUuid?.let { blockUuid ->
    {
        coroutineScope.launch {
            val result = attachmentService.pickAndAttach(
                graphRoot = graphRoot,
                pageRelativePath = viewModel.currentPagePath  // relative path of the open page
            )
            if (result != null) {
                result.fold(
                    ifLeft = { error ->
                        // TODO: show snackbar with error.message
                    },
                    ifRight = { attachment ->
                        val newContent = "![${attachment.displayName}](${attachment.relativePath})"
                        viewModel.updateBlockContent(blockUuid, newContent)
                    }
                )
            }
            // null result = user cancelled, do nothing
        }
    }
}
```

**For the slash command path**: In `BlockEditor.kt`'s autocomplete selection handler, when the selected `SlashCommandSuggestion.command == "image"`, invoke the same `onAttachImage` callback. This requires adding `onAttachImage: (() -> Unit)? = null` to `BlockEditor`'s parameters and threading it through from `BlockItem → BlockEditor`.

**Acceptance criteria**:
- [ ] Pressing the toolbar button while editing a block opens the platform file picker.
- [ ] After picking a file, the block content is updated to `![<displayName>](../assets/<name>)`.
- [ ] After the content update, the block re-renders as an `ImageBlock` (image display, not text).
- [ ] If the user cancels the picker, block content is unchanged.
- [ ] If the copy fails, an error is shown (snackbar or log; no crash).
- [ ] `coroutineScope.launch` is used only for UI-event callbacks — not stored in a long-lived class (no `ForgottenCoroutineScopeException` risk).

---

## Epic 4: Drag-and-Drop (Desktop JVM only)

**Goal**: Drop an image file from the OS file manager onto the page content area. The file is copied to `assets/` and inserted as a new image block.

**Prerequisite**: Epic 2 (file copy infrastructure), Epic 3 (block content update pattern).

**Platform scope**: Desktop JVM only. CMP version 1.10.3 has `dragAndDropTarget` available and `onExternalDrag` removed.

---

### Task 4.1 — `PageDropTarget` in jvmMain

**File**: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/ui/components/PageDropTarget.kt` (new file)

```kotlin
package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.dragAndDropTarget
import java.awt.datatransfer.DataFlavor

/**
 * Applies a drag-and-drop target to the page content area on Desktop JVM.
 *
 * Filters for image files (by extension) dropped from the OS file manager.
 * On drop, calls [onFilesDropped] with the list of accepted [java.io.File]s.
 * Non-file drags (text, URLs) are ignored (returns false from [onDrop]).
 *
 * Requires [ExperimentalComposeUiApi] opt-in.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.pageDropTarget(
    onFilesDropped: (List<java.io.File>) -> Unit
): Modifier {
    val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "svg", "bmp")
    val target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val transferable = event.awtTransferable
            if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
            @Suppress("UNCHECKED_CAST")
            val files = transferable.getTransferData(DataFlavor.javaFileListFlavor)
                as? List<java.io.File> ?: return false
            val imageFiles = files.filter { file ->
                file.extension.lowercase() in imageExtensions
            }
            if (imageFiles.isEmpty()) return false
            onFilesDropped(imageFiles)
            return true
        }
    }
    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        },
        target = target
    )
}
```

**Note**: `event.awtTransferable` is a JVM-only extension property on `DragAndDropEvent`. This file lives in `jvmMain` for this reason.

**Companion stub in commonMain**: Add `expect fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier` with a no-op actual in `androidMain` and `iosMain` returning `this`.

Alternative: Simply do not add the `pageDropTarget` modifier in the common screen composable — use a `jvmMain`-specific screen extension that wraps `PageContent`. Choose based on whether `PageView.kt` is in `commonMain` or `jvmMain`.

If `PageView.kt` is in `commonMain` (likely), use `expect/actual`:

```kotlin
// commonMain: PageDropTargetModifier.kt
expect fun Modifier.pageDropTarget(onFilesDropped: (List<Any>) -> Unit): Modifier

// jvmMain: actual with the DragAndDrop implementation (see above, adapt List<File> → List<Any>)
// androidMain: actual { return this }
// iosMain: actual { return this }
```

**Wiring in the page content composable** (commonMain, e.g. `PageView.kt`):

```kotlin
// Inside PageView composable, wrap the BlockList:
val coroutineScope = rememberCoroutineScope()
val graphRoot = LocalGraphRootPath.current

LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .pageDropTarget { files ->
            // files is List<Any> in commonMain; cast in jvmMain actual before calling
            @Suppress("UNCHECKED_CAST")
            val javaFiles = files as? List<java.io.File> ?: return@pageDropTarget
            coroutineScope.launch {
                javaFiles.forEach { file ->
                    val service = JvmMediaAttachmentService()
                    // Insert as new block after current editing block, or at end of page
                    val result = service.attachExistingFile(
                        file = file,
                        graphRoot = graphRoot ?: return@forEach
                    )
                    result?.fold(
                        ifLeft = { /* log error */ },
                        ifRight = { attachment ->
                            viewModel.addNewBlockAtEnd(
                                content = "![${attachment.displayName}](${attachment.relativePath})"
                            )
                        }
                    )
                }
            }
        }
) { /* ... existing block items ... */ }
```

**Also add** `suspend fun attachExistingFile(file: java.io.File, graphRoot: String): Either<DomainError, AttachmentResult>?` to `JvmMediaAttachmentService` — same copy logic as `pickAndAttach` but skips the file picker step.

**Acceptance criteria**:
- [ ] Dropping a `.jpg` or `.png` file onto the page content area on Desktop copies the file to `<graphRoot>/assets/` and inserts `![<name>](../assets/<name>)` as a new block.
- [ ] Dropping a `.pdf` or `.txt` file is silently ignored (extension filter).
- [ ] Dropping non-file content (e.g., text from a browser) does not crash.
- [ ] `pageDropTarget` is a no-op on Android and iOS (compiles but does nothing).
- [ ] `@OptIn(ExperimentalComposeUiApi::class)` is applied at the usage site.
- [ ] Linux app-close regression: manually verify on Linux that the app closes normally after dropping a file (see pitfalls §2).

---

## Technology Decisions (Flagged)

| # | Decision | Choice | Rationale | Alternatives considered |
|---|----------|--------|-----------|------------------------|
| TD-1 | Coil asset path resolution | `Mapper<String, Uri>` (`SteleKitAssetMapper`) | Simpler than full `Fetcher`; `FileUriFetcher` already handles `file://` URIs; no service-loader disruption | Custom `Fetcher.Factory<String>` |
| TD-2 | File picker library | **No third-party library** — use AWT `FileDialog` (JVM) and `PickVisualMedia` (Android) directly | FileKit (v0.14.1, pre-1.0) would add pre-1.0 dependency risk; direct APIs are sufficient for the use case | FileKit, Calf |
| TD-3 | Android photo picker | `ActivityResultContracts.PickVisualMedia` | No manifest permission on API 33+; auto-backport via Play Services to API 21+ | `GetContent` (requires permission on API 33) |
| TD-4 | Desktop drag-and-drop API | `Modifier.dragAndDropTarget` | CMP 1.10.3 ships this; `onExternalDrag` was removed in CMP 1.8.0 | AWT `DropTarget` |
| TD-5 | Block action signaling | New `onAttachImage: (() -> Unit)?` callback parameter | Avoids polluting `FormatAction` enum (text-wrap semantics only); simpler than introducing a `sealed class BlockAction` given the single new action type | Extend `FormatAction`, introduce `sealed class BlockAction` |
| TD-6 | `extractSingleImageNode` location | `BlockItemUtils.kt` (internal) | Enables unit testing without making `BlockItem.kt` private API public | Inline `private fun` in `BlockItem.kt` (untestable) |
| TD-7 | File copy atomicity | Write to `.tmp-<uuid>` inside `assets/`, then `Files.move(ATOMIC_MOVE)` | Prevents Coil from decoding partial files; temp inside same dir ensures same filesystem for atomic rename | Direct write to destination |
| TD-8 | iOS implementation | Stub / `NotImplementedError` | PHPickerViewController requires ObjC interop complexity; evaluate `ImagePickerKMP` library for follow-up | Hand-rolled UIKit interop |

---

## File Index (all new/modified files)

### New files

| File | Epic | Purpose |
|------|------|---------|
| `kmp/src/commonMain/.../ui/components/BlockItemUtils.kt` | 1 | `internal fun extractSingleImageNode(content: String)` |
| `kmp/src/commonMain/.../service/MediaAttachmentService.kt` | 2 | Interface + `AttachmentResult` data class |
| `kmp/src/commonMain/.../service/AttachmentFileNaming.kt` | 2 | `fun uniqueFileName(...)` |
| `kmp/src/jvmMain/.../service/JvmMediaAttachmentService.kt` | 2 | Desktop AWT FileDialog implementation |
| `kmp/src/androidMain/.../service/AndroidMediaAttachmentService.kt` | 2 | Android PickVisualMedia implementation |
| `kmp/src/iosMain/.../service/IosMediaAttachmentService.kt` | 2 | iOS stub |
| `kmp/src/jvmMain/.../ui/components/PageDropTarget.kt` | 4 | `fun Modifier.pageDropTarget(...)` (JVM actual) |
| `kmp/src/commonMain/.../ui/components/PageDropTargetModifier.kt` | 4 | `expect fun Modifier.pageDropTarget(...)` |
| `kmp/src/jvmTest/.../ui/components/ImageBlockDetectionTest.kt` | 1 | Tests for `extractSingleImageNode` |
| `kmp/src/jvmTest/.../service/AttachmentFileNamingTest.kt` | 2 | Tests for `uniqueFileName` |

### Modified files

| File | Epic | Change |
|------|------|--------|
| `kmp/src/commonMain/.../ui/components/SteleKitAssetFetcher.kt` | 1 | Replace stub with `SteleKitAssetMapper` + updated `rememberSteleKitImageLoader` |
| `kmp/src/commonMain/.../ui/components/BlockItem.kt` | 1,3 | Add image dispatch in `else` branch; add `onAttachImage` parameter |
| `kmp/src/commonMain/.../ui/components/ImageBlock.kt` | 1 | Add `Modifier.heightIn(max = 400.dp)` |
| `kmp/src/commonMain/.../model/DomainError.kt` | 2 | Add `AttachmentError` sealed subclass |
| `kmp/src/commonMain/.../editor/commands/SlashCommandHandler.kt` | 3 | Add `"image" -> "media.image"` mappings |
| `kmp/src/commonMain/.../editor/commands/EssentialCommands.kt` | 3 | Add `MediaCommands.insertImage`; update `getAll()` |
| `kmp/src/commonMain/.../ui/components/MobileBlockToolbar.kt` | 3 | Add `onAttachImage` parameter + AttachFile button |
| `kmp/src/androidMain/AndroidManifest.xml` | 2 | Add `READ_MEDIA_IMAGES` / `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` |

---

## Dependency Graph

```
Epic 1 (Display)
  └── Task 1.1: SteleKitAssetMapper
  └── Task 1.2: BlockItem image dispatch  (depends on 1.1 for testing with real images)
  └── Task 1.3: Unit tests

Epic 2 (Service)
  └── Task 2.1: MediaAttachmentService interface
  └── Task 2.2: uniqueFileName            (depends on 2.1 for directory path types)
  └── Task 2.3: JvmMediaAttachmentService (depends on 2.1, 2.2)
  └── Task 2.4: AndroidMediaAttachmentService (depends on 2.1, 2.2)
  └── Task 2.5: IosMediaAttachmentService stub (depends on 2.1)

Epic 3 (UX)
  └── Task 3.1: Slash command             (depends on 2.1 — interface must exist)
  └── Task 3.2: Toolbar button            (depends on 3.1 for consistent callback signature)
  └── Task 3.3: Wire picker to content    (depends on 2.3/2.4, 3.1, 3.2, 1.2)

Epic 4 (Drag-and-drop)
  └── Task 4.1: PageDropTarget            (depends on 2.3 — uses JvmMediaAttachmentService)
```

---

## Suggested Implementation Order

1. **Task 1.1** → `SteleKitAssetMapper` (30 min) — unblocks all display verification
2. **Task 1.2** → `BlockItem` image dispatch (45 min) — proves end-to-end with existing assets
3. **Task 1.3** → `extractSingleImageNode` tests (20 min)
4. **Task 2.1** → `MediaAttachmentService` interface (20 min)
5. **Task 2.2** → `uniqueFileName` + tests (30 min)
6. **Task 2.3** → JVM attachment service (60 min, including manual testing with FileDialog)
7. **Task 2.4** → Android attachment service (90 min, including Manifest, testing on device)
8. **Task 2.5** → iOS stub (10 min)
9. **Task 3.1** → Slash command (20 min)
10. **Task 3.2** → Toolbar button (30 min)
11. **Task 3.3** → Wire end-to-end (60 min, test on Desktop and Android)
12. **Task 4.1** → Drag-and-drop (90 min, test on Desktop + Linux close regression)

**Total estimated effort**: ~8 hours

---

## Risk Register

| Risk | Severity | Mitigation |
|------|----------|------------|
| `Modifier.dragAndDropTarget` `shouldStartDragAndDrop` called on every mouse-over event | LOW | `shouldStartDragAndDrop` checks `isDataFlavorSupported` which is fast; no action needed |
| Android `pendingContinuation` race if picker is launched twice | MEDIUM | Guard with `if (pendingContinuation != null) return` before launching; only one picker open at a time |
| `Files.move(ATOMIC_MOVE)` throws `AtomicMoveNotSupportedException` on some Linux filesystems | LOW | Catch and fall back to `StandardCopyOption.REPLACE_EXISTING` without `ATOMIC_MOVE` |
| `SteleKitAssetMapper` registered when `graphRoot` changes, old ImageLoader still in memory | LOW | `remember(graphRoot, context)` creates a new `ImageLoader` on graph-root change; Coil's disk cache is keyed by the absolute URI path |
| iOS target doesn't compile due to missing `actual` | BLOCKING | Task 2.5 must be done before any iOS build; stub with `NotImplementedError` is sufficient |
| `ForgottenCoroutineScopeException` from `rememberCoroutineScope` stored in service | MEDIUM | Services own their own `CoroutineScope`; `rememberCoroutineScope` used only for UI-event callbacks, not passed to services |
