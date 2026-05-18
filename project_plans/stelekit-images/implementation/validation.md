# Validation Plan: stelekit-images

**Date**: 2026-05-15

---

## Requirement → Test Mapping

| Requirement | Test File | Test Name | Type | Scenario |
|-------------|-----------|-----------|------|----------|
| REQ-1: Render `![](url)` as inline image | `ImageBlockDetectionTest` | `image-only block returns url and alt` | Unit | Happy path: pure image markdown returns `(url, alt)` pair |
| REQ-1: Render `![](url)` as inline image | `ImageBlockDetectionTest` | `mixed content returns null` | Unit | Error path: text + image → returns null (no false dispatch) |
| REQ-1: Render `![](url)` as inline image | `ImageBlockRenderingTest` | `imageOnlyBlock_should_renderAsAsyncImage_whenContentIsSingleImageMarkdown` | Integration | Screenshot: block with `![test](https://example.com/img.png)` renders `ImageBlock`, not text |
| REQ-2: Render `../assets/` relative paths | `SteleKitAssetMapperTest` | `map_should_returnFileUri_whenInputIsRelativeAssetPath` | Unit | Happy path: `../assets/photo.jpg` + graphRoot → `file:///root/assets/photo.jpg` |
| REQ-2: Render `../assets/` relative paths | `SteleKitAssetMapperTest` | `map_should_returnNull_whenInputIsHttpUrl` | Unit | Error path: `https://` URL passed through (returns null — falls to default Coil mapper) |
| REQ-2: Render `../assets/` relative paths | `SteleKitAssetMapperTest` | `map_should_returnNull_whenGraphRootIsNull` | Unit | Error path: null graphRoot (loader built without mapper; returns null) |
| REQ-3: Duplicate filename handling | `AttachmentFileNamingTest` | `returns base name when no conflict` | Unit | Happy path: no existing file → original name returned |
| REQ-3: Duplicate filename handling | `AttachmentFileNamingTest` | `returns dash-1 when base exists` | Unit | Error path (collision): one file exists → `-1` suffix appended |
| REQ-3: Duplicate filename handling | `AttachmentFileNamingTest` | `returns dash-2 when dash-1 also exists` | Unit | Error path (multiple collisions): counter increments correctly |
| REQ-3: Duplicate filename handling | `AttachmentFileNamingTest` | `no extension base case` | Unit | Edge case: blank extension → no trailing dot |
| REQ-4: Copy file to assets dir via service | `JvmMediaAttachmentServiceTest` | `pickAndAttach_should_returnRelativePath_whenFileSelectedAndCopied` | Integration | Happy path: file picked → copied to assets/ → returns `../assets/<name>` |
| REQ-4: Copy file to assets dir via service | `JvmMediaAttachmentServiceTest` | `pickAndAttach_should_returnNull_whenUserCancels` | Integration | Cancel path: null file from picker → returns null (not Either.Left) |
| REQ-4: Copy file to assets dir via service | `JvmMediaAttachmentServiceTest` | `pickAndAttach_should_returnLeft_whenCopyFails` | Integration | Error path: unreadable source → `Either.Left(CopyFailed)` |
| REQ-4: Copy file to assets dir via service | `JvmMediaAttachmentServiceTest` | `pickAndAttach_should_handleCollision_andReturnUniquePath` | Integration | Collision: destination already exists → unique suffix used |
| REQ-5: `/image` slash command appears in autocomplete | `SlashCommandHandlerTest` | `mapSlashToCommandId_should_returnMediaImage_whenInputIsImage` | Unit | Happy path: `"image"` maps to `"media.image"` |
| REQ-5: `/image` slash command appears in autocomplete | `SlashCommandHandlerTest` | `mapCommandIdToSlash_should_returnImage_whenInputIsMediaImage` | Unit | Happy path: reverse mapping `"media.image"` → `"image"` |
| REQ-5: `/image` slash command appears in autocomplete | `SlashCommandHandlerTest` | `getAll_should_containInsertImageCommand` | Unit | Happy path: `EssentialCommands.getAll()` contains `media.image` entry |
| REQ-5: `/image` slash command appears in autocomplete | `SlashCommandAutocompleteTest` | `autocomplete_should_showImageEntry_whenSlashImageTyped` | Integration | Typing `/image` in a block editor shows "Image" in the suggestions list |
| REQ-6: Mixed-content block renders as text | `ImageBlockDetectionTest` | `text only block returns null` | Unit | Happy path (null expected): plain text → BlockViewer, not ImageBlock |
| REQ-6: Mixed-content block renders as text | `ImageBlockDetectionTest` | `two images returns null` | Unit | Error path: two image nodes → not a single-image block |
| REQ-6: Mixed-content block renders as text | `ImageBlockRenderingTest` | `mixedBlock_should_renderAsBlockViewer_whenContentHasTextAndImage` | Integration | Screenshot: `![test](url) some text` renders as `BlockViewer` with annotation |
| REQ-7: Empty / blank blocks handled | `ImageBlockDetectionTest` | `empty string returns null` | Unit | Happy path: empty input → null |
| REQ-7: Empty / blank blocks handled | `ImageBlockDetectionTest` | `blank string returns null` | Unit | Happy path: whitespace-only input → null |

---

## Detailed Test Specifications

### 1. `extractSingleImageNode` / `isSingleImageBlock` — `ImageBlockDetectionTest`

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ImageBlockDetectionTest.kt`
**Subject**: `internal fun extractSingleImageNode(content: String): Pair<String, String>?` in `BlockItemUtils.kt`

| # | Test Name | Input | Expected Output |
|---|-----------|-------|-----------------|
| 1 | `image-only block returns url and alt` | `"![My Photo](../assets/photo.jpg)"` | `Pair("../assets/photo.jpg", "My Photo")` |
| 2 | `https url image-only block` | `"![](https://example.com/img.png)"` | `Pair("https://example.com/img.png", "")` |
| 3 | `text only block returns null` | `"just some text"` | `null` |
| 4 | `mixed content returns null` | `"text ![photo](url) more text"` | `null` |
| 5 | `empty string returns null` | `""` | `null` |
| 6 | `blank string returns null` | `"   "` | `null` |
| 7 | `two images returns null` | `"![a](url1) ![b](url2)"` | `null` |
| 8 | `image with trailing whitespace returns null` | `"![alt](../assets/x.png)  "` | `null` (hard-break node present) |
| 9 | `url-only image no alt text` | `"![](https://img.example.com/pic.png)"` | `Pair("https://img.example.com/pic.png", "")` |
| 10 | `relative path image no extension` | `"![diagram](../assets/diagram)"` | `Pair("../assets/diagram", "diagram")` |

**Framework**: `kotlin.test` (KMP standard); no additional library required.

---

### 2. `SteleKitAssetMapper` — `SteleKitAssetMapperTest`

**Source set**: `jvmTest` (or `commonTest` — class has no platform APIs)
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetMapperTest.kt`
**Subject**: `class SteleKitAssetMapper(graphRoot: String) : Mapper<String, Uri>`

| # | Test Name | graphRoot | Input | Expected Output |
|---|-----------|-----------|-------|-----------------|
| 1 | `map_should_returnFileUri_whenInputIsRelativeAssetPath` | `"/graph/root"` | `"../assets/photo.jpg"` | `Uri("file:///graph/root/assets/photo.jpg")` |
| 2 | `map_should_returnNull_whenInputIsHttpUrl` | `"/graph/root"` | `"https://example.com/img.png"` | `null` |
| 3 | `map_should_returnNull_whenInputIsAbsolutePath` | `"/graph/root"` | `"/absolute/path/photo.jpg"` | `null` |
| 4 | `map_should_handleDeeplyNestedPath_whenAssetsSubdirProvided` | `"/my/graph"` | `"../assets/subdir/photo.jpg"` | `Uri("file:///my/graph/assets/subdir/photo.jpg")` |
| 5 | `map_should_returnUri_whenFilenameHasSpaces` | `"/graph"` | `"../assets/my photo.jpg"` | `Uri("file:///graph/assets/my photo.jpg")` |
| 6 | `rememberSteleKitImageLoader_should_notThrow_whenGraphRootIsNull` | N/A (null) | Composable build with null graphRoot | No exception; `ImageLoader` built without `SteleKitAssetMapper` |

**Note on test 6**: Use `runComposeUiTest` or a simple unit test with `ImageLoader.Builder` directly to verify the null-guard branch. The `rememberSteleKitImageLoader` composable only registers the mapper when `graphRoot != null`.

**Framework**: `kotlin.test` + Coil 3's `coil3.toUri()` for URI comparison.

---

### 3. `uniqueFileName` — `AttachmentFileNamingTest`

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/service/AttachmentFileNamingTest.kt`
**Subject**: `fun uniqueFileName(assetsDir: Path, stem: String, ext: String, fileSystem: FileSystem): String`

| # | Test Name | Existing Files | Input (stem, ext) | Expected |
|---|-----------|----------------|--------------------|----------|
| 1 | `returns base name when no conflict` | none | `"photo", "jpg"` | `"photo.jpg"` |
| 2 | `returns dash-1 when base exists` | `photo.jpg` | `"photo", "jpg"` | `"photo-1.jpg"` |
| 3 | `returns dash-2 when dash-1 also exists` | `photo.jpg`, `photo-1.jpg` | `"photo", "jpg"` | `"photo-2.jpg"` |
| 4 | `no extension base case` | none | `"file", ""` | `"file"` |
| 5 | `no extension with collision` | `file` | `"file", ""` | `"file-1"` |
| 6 | `stem with dots is handled` | none | `"my.file.name", "jpg"` | `"my.file.name.jpg"` |

**Framework**: `kotlin.test` + `okio.fakefilesystem.FakeFileSystem` (no real disk I/O).

---

### 4. `JvmMediaAttachmentService` — `JvmMediaAttachmentServiceTest`

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/service/JvmMediaAttachmentServiceTest.kt`
**Subject**: `class JvmMediaAttachmentService : MediaAttachmentService`

These are integration tests. The AWT `FileDialog` cannot be tested headlessly, so `JvmMediaAttachmentService` must be refactored to accept an injectable `filePicker: suspend () -> java.io.File?` lambda (same pattern as Android's `launchGalleryPicker`). This decouples the picker UI from the copy logic.

| # | Test Name | Type | Setup | Expected |
|---|-----------|------|-------|----------|
| 1 | `pickAndAttach_should_returnRelativePath_whenFileSelectedAndCopied` | Integration | Inject lambda returning a real temp file; graphRoot is a temp dir | `Either.Right(AttachmentResult(relativePath = "../assets/<name>", displayName = "<name>"))` |
| 2 | `pickAndAttach_should_returnNull_whenUserCancels` | Integration | Inject lambda returning `null` | Returns `null` (not `Either.Left`) |
| 3 | `pickAndAttach_should_returnLeft_whenCopyFails` | Integration | Inject lambda returning a non-existent `File` | `Either.Left(DomainError.AttachmentError.CopyFailed(...))` |
| 4 | `pickAndAttach_should_handleCollision_andReturnUniquePath` | Integration | Pre-create `photo.jpg` in assets/; picker returns file named `photo.jpg` | `Either.Right` with `relativePath = "../assets/photo-1.jpg"` |
| 5 | `pickAndAttach_should_createAssetsDir_whenNotExists` | Integration | graphRoot points to a temp dir with no `assets/` subdir | `assets/` is created; file is copied successfully |
| 6 | `pickAndAttach_should_useTempThenRename_notWriteDirectly` | Integration | Spy on file system during copy | No partial file visible at destination during copy; final file exists after completion |

**Framework**: `kotlin.test` + `kotlinx.coroutines.test.runTest` + real `java.io.File` (temp dirs) + injectable `filePicker` lambda.

---

### 5. Block Rendering — `ImageBlockRenderingTest`

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/ImageBlockRenderingTest.kt`
**Subject**: `BlockItem` composable — dispatch logic in view mode

These are snapshot/screenshot tests using [Roborazzi](https://github.com/takahirom/roborazzi) (already used in `jvmTest` per `TESTING_README.md`).

| # | Test Name | Type | Block Content | Expected Render |
|---|-----------|------|---------------|-----------------|
| 1 | `imageOnlyBlock_should_renderAsAsyncImage_whenContentIsSingleImageMarkdown` | Snapshot | `"![test](https://example.com/img.png)"` | `ImageBlock` composable present in tree; no `BlockViewer` |
| 2 | `mixedBlock_should_renderAsBlockViewer_whenContentHasTextAndImage` | Snapshot | `"![test](https://url) some text"` | `BlockViewer` composable present; no standalone `ImageBlock` |
| 3 | `plainTextBlock_should_renderAsBlockViewer_whenNoImageMarkdown` | Snapshot | `"just some text"` | `BlockViewer` present; no `ImageBlock` |
| 4 | `imageOnlyBlock_should_enterEditMode_whenTapped` | UI | `"![alt](https://example.com/img.png)"` | `onStartEditing` callback invoked on click |
| 5 | `emptyBlock_should_renderAsBlockViewer_notImageBlock` | Snapshot | `""` | `BlockViewer` present; `ImageBlock` absent |

**Implementation notes**:
- For tests 1–3, 5: Use `composeTestRule.onNodeWithTag("ImageBlock").assertExists()` / `assertDoesNotExist()` — requires adding a `testTag("ImageBlock")` modifier to `ImageBlock.kt` and `testTag("BlockViewer")` to `BlockViewer.kt`.
- For test 4: use `composeTestRule.onNodeWithTag("ImageBlock").performClick()` and assert the `onStartEditing` lambda was invoked.
- Snapshot (Roborazzi) tests compare against golden images; run `./gradlew jvmTest -PrecordRoborazzi` to create initial goldens.

**Framework**: Compose test rules + Roborazzi (existing infrastructure).

---

### 6. Slash Command — `SlashCommandHandlerTest`

**Source set**: `jvmTest` (or `businessTest`)
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/editor/commands/SlashCommandHandlerTest.kt`
**Subject**: `SlashCommandHandler.mapSlashToCommandId`, `mapCommandIdToSlash`, and `EssentialCommands.getAll()`

| # | Test Name | Type | Input | Expected |
|---|-----------|------|-------|----------|
| 1 | `mapSlashToCommandId_should_returnMediaImage_whenInputIsImage` | Unit | `"image"` | `"media.image"` |
| 2 | `mapCommandIdToSlash_should_returnImage_whenInputIsMediaImage` | Unit | `"media.image"` | `"image"` |
| 3 | `getAll_should_containInsertImageCommand` | Unit | `EssentialCommands.getAll()` | List contains command with id `"media.image"` and label `"Image"` |
| 4 | `autocomplete_should_showImageEntry_whenSlashImageTyped` | Integration | Type `"/image"` into `BlockEditor` in `composeTestRule` | Node with text `"Image"` exists in the autocomplete suggestion list |

**For test 4** (`SlashCommandAutocompleteTest.kt`): Use `composeTestRule` to render a `BlockEditor` in editing state, call `performTextInput("/image")`, then assert autocomplete suggestion "Image" appears using `onNodeWithText("Image").assertIsDisplayed()`.

**Framework**: `kotlin.test` + Compose test rules (for test 4).

---

## Test Stack

- **Unit**: `kotlin.test` (`assertEquals`, `assertNull`, `assertNotNull`) — KMP standard; no additional library
- **Unit (filesystem)**: `okio.fakefilesystem.FakeFileSystem` — injectable, no real disk I/O
- **Integration**: `kotlinx.coroutines.test.runTest` + real `java.io.File` temp dirs + injectable lambdas for platform pickers
- **Snapshot/UI**: Compose test rules + Roborazzi (existing `jvmTest` infrastructure)
- **Error handling verification**: Arrow `Either` fold / `isLeft()` / `isRight()` assertions

---

## Coverage Targets

- Unit test coverage: ≥80% line coverage on `BlockItemUtils.kt`, `AttachmentFileNaming.kt`, `SteleKitAssetMapper`, `SlashCommandHandler` image mappings
- All public service methods (`MediaAttachmentService.pickAndAttach`): happy path + cancel path + error paths
- All external integrations (file picker): unit mocked via injectable lambda + at least one end-to-end integration test per platform service (JVM)
- Block rendering dispatch: all branches covered (image-only, mixed, text-only, empty)
- Slash command: forward mapping, reverse mapping, and `getAll()` inclusion verified

---

## Test Count Summary

| Category | Count |
|----------|-------|
| `extractSingleImageNode` unit tests | 10 |
| `SteleKitAssetMapper` unit tests | 6 |
| `uniqueFileName` unit tests | 6 |
| `JvmMediaAttachmentService` integration tests | 6 |
| Block rendering snapshot/UI tests | 5 |
| Slash command unit + integration tests | 4 |
| **Total** | **37** |

**Unit tests**: 26
**Integration / Snapshot tests**: 11

---

## Requirement Coverage

| Requirement | Tests | Covered |
|-------------|-------|---------|
| REQ-1: Render `![](url)` inline | ImageBlockDetectionTest ×2, ImageBlockRenderingTest ×1 | ✅ |
| REQ-2: Render `../assets/` relative paths | SteleKitAssetMapperTest ×6 | ✅ |
| REQ-3: Duplicate filename handling | AttachmentFileNamingTest ×6 | ✅ |
| REQ-4: Copy file to assets via service (JVM) | JvmMediaAttachmentServiceTest ×6 | ✅ |
| REQ-5: `/image` slash command in autocomplete | SlashCommandHandlerTest ×4 | ✅ |
| REQ-6: Mixed-content block renders as text | ImageBlockDetectionTest ×2, ImageBlockRenderingTest ×2 | ✅ |
| REQ-7: Empty/blank block safety | ImageBlockDetectionTest ×2, ImageBlockRenderingTest ×1 | ✅ |

**Requirements covered**: 7/7 (100%)
