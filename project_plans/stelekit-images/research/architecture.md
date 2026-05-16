# Architecture: Image & File Attachment Support

## Overview
This document maps the integration points for image attachment support in SteleKit, based on analysis of the codebase structure, existing asset infrastructure, and platform abstraction patterns.

---

## 1. Image Block Detection & Rendering

### Current State
- `ImageNode` exists in `parsing/ast/InlineNodes.kt` and is successfully parsed by `InlineParser.parseImage()` (lines 166–208)
- `ImageBlock` composable is written but **not wired into `BlockItem.kt`** (see lines 13–14 comment: "TODO: Wire ImageBlock in BlockItem dispatch")
- Currently, blocks containing image-only content render via `BlockViewer` (which uses `WikiLinkText` to render as text)

### Decision: Image-Only Block Detection
**Recommendation: Detect at render time in `BlockItem.kt`, NOT as a new `BlockTypes.IMAGE`**

**Rationale:**
- `BlockTypes` are semantic markers set at block *creation* time based on markdown prefix (e.g., `#` = HEADING, `>` = BLOCKQUOTE)
- An image-only block starts as a PARAGRAPH and is detected by inspecting the parsed AST at render time
- Adding a new `BlockTypes.IMAGE` would require:
  - Changes to block creation logic (outliner pipeline)
  - Changes to markdown re-serialization (must preserve the right prefix)
  - Upstream changes in Logseq compatibility
  
**Integration Point: `BlockItem.kt` lines 348–407 (view mode dispatch)**

When `block.blockType == BlockTypes.PARAGRAPH` (or `else` fallback):
1. Parse the block content to extract the AST via the same parser that reads markdown
2. Check if AST contains **exactly one child** and that child is `ImageNode`
3. If yes → dispatch to `ImageBlock(url, altText, onStartEditing)`
4. If no → dispatch to existing `BlockViewer`

**Helper function location:** `BlockViewerUtils.kt` or inline in `BlockItem.kt`
```kotlin
private fun isSingleImageBlock(content: String): Pair<String, String>? {
    // Return (url, altText) if content parses to single ImageNode, else null
    val ast = OutlinerPipeline.parseInline(content)
    return if (ast.size == 1 && ast[0] is ImageNode) {
        val img = ast[0] as ImageNode
        img.url to img.alt
    } else null
}
```

---

## 2. Slash Command Integration (`/image`)

### Current State
- `SlashCommandHandler` (lines 104–378) implements the command parsing and execution interface
- `mapSlashToCommandId()` (lines 278–312) maps slash command names to editor command IDs
- `mapCommandIdToSlash()` (lines 343–377) does the reverse mapping
- `SlashCommandSuggestions` are emitted for autocomplete

### Integration Point: `SlashCommandHandler.mapSlashToCommandId()`
Add mapping at line ~300 (between existing formatting and system commands):
```kotlin
"image" -> "media.image"  // or "block.image"
```

And the reverse in `mapCommandIdToSlash()`:
```kotlin
"media.image" -> "image"
```

### Implementation of the `/image` Handler
The handler itself can be:
1. **Registered via `SlashCommandHandler.registerSlashCommand()`** if adding a custom, non-standard handler
2. **Mapped to an editor command** (preferred) by adding an `EditorCommand` with ID `"media.image"` to `EssentialCommands` or a new `MediaCommands` object

The handler receives a `CommandContext` and returns `CommandResult`. For image attachment, it should:
- Emit a `FormatAction.IMAGE` event (requires adding this to `FormatAction` enum)
- Or directly dispatch to the file/gallery picker (platform-specific, see section 4)

---

## 3. Toolbar Button Integration (FormatAction)

### Current State
- `FormatAction` enum is defined in `JournalsViewModel.kt` (lines 24–35) with text formatting actions (BOLD, ITALIC, CODE, etc.)
- `FormatAction` has `prefix` and `suffix` fields for text wrapping
- `BlockStateManager._formatEvents` (SharedFlow) broadcasts `FormatAction` events
- `MobileBlockToolbar.kt` emits `onFormat(FormatAction.XXX)` calls
- `BlockItem.kt` lines 214–225 listen to `formatEvents` and call `applyFormatAction()`

### Design Problem with FormatAction
**Text wrapping actions don't fit image insertion**, which is not a text transformation but a *block replacement*.

### Two Integration Options

#### Option A: Extend FormatAction (Not Recommended)
Add `IMAGE_PICKER("")` to the enum. Requires modifying `applyFormatAction()` to handle non-text-wrapping actions:
```kotlin
enum class FormatAction(val prefix: String, val suffix: String) {
    // existing: BOLD, ITALIC, ...
    IMAGE_PICKER("", "")  // special case: not used for wrapping
}
```

**Problem:** `applyFormatAction()` is designed for text wrapping. Mixing image insertion here conflates two different action types.

#### Option B: New Action Event (Recommended)
Create a separate `sealed class BlockAction` or `BlockToolbarAction` that encompasses both `FormatAction` and `ImagePickerAction`:
```kotlin
// ui/screens/BlockActions.kt
sealed class BlockAction {
    data class Format(val action: FormatAction) : BlockAction()
    object ImagePicker : BlockAction()
}
```

Emit from toolbar and listen in `BlockItem`:
```kotlin
// In MobileBlockToolbar
onBlockAction(BlockAction.ImagePicker)

// In BlockItem
LaunchedEffect(isEditing, blockActions) {
    if (isEditing && blockActions != null) {
        blockActions.collect { action ->
            when (action) {
                is BlockAction.Format -> applyFormatAction(action.action, ...)
                is BlockAction.ImagePicker -> /* platform call to file picker */
            }
        }
    }
}
```

**This is the cleaner path**, but requires refactoring the toolbar callback signature.

### Fallback (Minimal Refactor)
If refactoring is deferred, the `/image` slash command alone provides entry point; the toolbar button can be added later by wrapping image picker logic in an action handler tied to the editor command.

---

## 4. Platform-Specific File Picker & Attachment

### Current Platform Abstraction Pattern
The codebase uses Kotlin Multiplatform **expect/actual** pattern:

**Example:** `PlatformDispatcher.kt`
- `commonMain`: `expect object PlatformDispatcher { val IO, val DB, val Main, val Default }`
- `jvmMain`: `actual object PlatformDispatcher { actual val IO = Dispatchers.IO, ... }`
- `androidMain`: `actual object PlatformDispatcher { actual val IO = Dispatchers.IO, ... }`

### Image Attachment Service Pattern
Create a platform-agnostic service interface in `commonMain`:

```kotlin
// commonMain/service/ImageAttachmentService.kt
expect interface IImageAttachmentService {
    suspend fun pickImageFromGallery(): Result<ImageAttachment>
    suspend fun captureFromCamera(): Result<ImageAttachment>
    suspend fun pasteFromClipboard(): Result<ImageAttachment>
}

data class ImageAttachment(
    val fileName: String,
    val mimeType: String,
    val byteArray: ByteArray
)
```

**Implementations by platform:**

#### jvmMain
```kotlin
// jvmMain/.../ImageAttachmentService.jvm.kt
actual class ImageAttachmentService : IImageAttachmentService {
    override suspend fun pickImageFromGallery(): Result<ImageAttachment> {
        // Use JFileChooser (AWT) or Compose multiplatform file picker
        // Return selected file as ImageAttachment
    }
}
```

#### androidMain
```kotlin
// androidMain/.../ImageAttachmentService.android.kt
actual class ImageAttachmentService(private val activity: Activity) : IImageAttachmentService {
    override suspend fun pickImageFromGallery(): Result<ImageAttachment> {
        // Use ActivityResultContracts.GetContent() or PickVisualMedia()
    }
    override suspend fun captureFromCamera(): Result<ImageAttachment> {
        // Use CameraX or MediaStore.ACTION_IMAGE_CAPTURE
    }
}
```

#### iosMain
```kotlin
// iosMain/.../ImageAttachmentService.ios.kt
actual class ImageAttachmentService : IImageAttachmentService {
    override suspend fun pickImageFromGallery(): Result<ImageAttachment> {
        // Use UIImagePickerController via Kotlin/Native interop
    }
}
```

### Attachment Writer Service
After the user picks an image, copy it to `<graph_root>/assets/<filename>` and update block content.

```kotlin
// commonMain/service/AttachmentWriter.kt
class AttachmentWriter(
    private val graphRootPath: String,
    private val blockRepository: BlockRepository,
    private val dispatcher: CoroutineDispatcher = PlatformDispatcher.IO
) {
    suspend fun attachImage(
        blockUuid: String,
        imageAttachment: ImageAttachment
    ): Result<String> = withContext(dispatcher) {
        // 1. Create assets/ folder if it doesn't exist
        // 2. Handle filename collisions (append -1, -2, etc.)
        // 3. Write bytes to disk
        // 4. Update block content to ![filename](../assets/filename)
        // 5. Persist to database
    }
}
```

**Key design:** Use `PlatformDispatcher.IO` for all file I/O (not DB dispatcher).

---

## 5. Asset Loading (SteleKitAssetFetcher)

### Current State
- `SteleKitAssetFetcher.kt` is stubbed in `ui/components/`
- Used by `rememberSteleKitImageLoader()` to create a Coil `ImageLoader`
- Must resolve relative paths like `../assets/image.png` relative to the graph root

### Implementation Pattern
Coil 3 uses `Fetcher<T>` interface. Implement a custom fetcher that:
1. Detects if URL is relative (starts with `../assets/`)
2. Resolves relative path using `LocalGraphRootPath` composition local
3. Returns a local file source instead of network fetch

```kotlin
// commonMain/ui/components/SteleKitAssetFetcher.kt
class AssetFetcher(
    private val graphRootPath: String
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val request = (request as? ImageRequest) ?: return null
        val url = request.data.toString()
        
        if (!url.startsWith("../assets/")) return null  // Let other fetchers handle it
        
        val relativePath = url
        val assetFile = File(graphRootPath).resolve(relativePath)
        
        return if (assetFile.exists() && assetFile.isFile) {
            FetchResult(
                source = { assetFile.inputStream() },
                mimeType = "image/*"
            )
        } else null
    }
}
```

Register this fetcher when creating the `ImageLoader`:
```kotlin
@Composable
fun rememberSteleKitImageLoader(): ImageLoader {
    val graphRootPath = LocalGraphRootPath.current
    return remember(graphRootPath) {
        ImageLoader.Builder(LocalContext.current)
            .components {
                add(AssetFetcher(graphRootPath))  // Custom fetcher first
            }
            .build()
    }
}
```

---

## 6. Block Editor Integration (Slash Command Execution)

### Entry Point: BlockEditor Slash Command Handling
When the user types `/image` in the editor:
1. `BlockEditor` detects the slash command (via keyboard input in the TextField)
2. `SlashCommandHandler.parse()` recognizes it as a valid command
3. `SlashCommandHandler.execute()` maps it to `"media.image"` command ID
4. The registered handler launches the file/gallery picker on that platform

### Coroutine Safety
The picker runs asynchronously:
```kotlin
// In BlockEditor or a handler
LaunchedEffect(slashCommand) {
    if (slashCommand?.command == "image") {
        val result = imageAttachmentService.pickImageFromGallery()
        result.onSuccess { imageAttachment ->
            attachmentWriter.attachImage(blockUuid, imageAttachment)
        }
    }
}
```

---

## 7. Drag-and-Drop Integration (Desktop JVM)

### Entry Point: `BlockList` or Page View
Compose provides `Modifier.pointerInput()` with drag detection. For desktop:

```kotlin
// On JVM, detect drop zones in BlockList
Modifier.pointerInput {
    detectDragGesturesAfterLongPress(
        onDrag = { change, offset ->
            // Visual feedback during drag
        },
        onDragEnd = {
            // Handle drop
        }
    )
}
```

**Alternatively**, use AWT `DropTarget` for lower-level drag-and-drop (JVM-only):
```kotlin
// jvmMain/ui/components/DragDropHandler.jvm.kt
fun setupDropTarget(blockUuid: String) {
    DropTarget(targetComponent) { TransferableObject ->
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val image = transferable.getTransferData(DataFlavor.imageFlavor)
            // Handle drop
        }
    }
}
```

---

## Summary: Integration Checklist

| Component | File | Change |
|-----------|------|--------|
| **Block Type Detection** | `BlockItem.kt` lines 348–407 | Add image-only detection before `BlockViewer` dispatch |
| **ImageBlock Rendering** | `ImageBlock.kt` | Already complete; ensure `rememberSteleKitImageLoader()` is called |
| **Slash Command Registration** | `SlashCommandHandler.kt` lines 278–312 | Add `"image" -> "media.image"` mapping |
| **FormatAction / Toolbar** | `FormatAction.kt` or new `BlockActions.kt` | Either extend enum (Option A) or create new action type (Option B) |
| **Platform File Picker** | `jvmMain/`, `androidMain/`, `iosMain/` | Implement `IImageAttachmentService` for each platform |
| **Attachment Writer** | New `AttachmentWriter.kt` | Copy file to `assets/`, update block, persist |
| **Asset Loader** | `SteleKitAssetFetcher.kt` | Implement Coil fetcher for `../assets/*` paths |
| **Clipboard Paste** | Platform-specific handlers | Optional: detect PASTE key in BlockEditor, ask service for clipboard image |
| **Drag-and-Drop** | `BlockList.kt` (or `jvmMain/`) | Detect drag gestures, route to attachment writer |

---

## Key Architectural Decisions

1. **Image-only block detection happens at render time** → no schema changes, Logseq-compatible
2. **Separate action event type for non-text-wrapping actions** → cleaner than extending FormatAction
3. **expect/actual for file picker** → platform abstractions already established in codebase
4. **Use `PlatformDispatcher.IO` for file I/O** → maintains coroutine structure
5. **Asset resolution via composition local** → consistent with existing patterns

---

## Risk Factors & Mitigation

| Risk | Mitigation |
|------|-----------|
| Rendering performance of large images | Use `ContentScale.FillWidth` + lazy loading in `LazyColumn` |
| Filename collisions in `assets/` folder | Implement suffix generation (photo-1.jpg, photo-2.jpg) with database check |
| Platform picker complexity on iOS/Android | Use existing compose-multiplatform or platform SDK wrappers; test each platform separately |
| Relative path resolution fails on some platforms | Test with both `../assets/` and absolute paths; log debug info on resolution failure |

