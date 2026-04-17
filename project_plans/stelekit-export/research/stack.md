# Research: Stack
**Dimension**: Stack | **Date**: 2026-04-13

## Clipboard API Options

### JVM / Desktop (AWT)

The authoritative API for writing to the system clipboard on JVM Desktop is `java.awt.Toolkit`:

```kotlin
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

fun copyToClipboard(text: String) {
    val selection = StringSelection(text)
    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}
```

This is synchronous, has no external dependencies, and works on all JVM desktop platforms (macOS, Windows, Linux).

For HTML content (so a rich-text editor like Google Docs or Confluence receives styled paste), AWT supports custom `Transferable` implementations with multiple `DataFlavor`s:

```kotlin
import java.awt.datatransfer.*

class HtmlStringSelection(private val html: String, private val plain: String) : Transferable {
    companion object {
        val HTML_FLAVOR = DataFlavor("text/html; charset=UTF-8; class=java.lang.String")
    }
    override fun getTransferDataFlavors() = arrayOf(HTML_FLAVOR, DataFlavor.stringFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in transferDataFlavors
    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        HTML_FLAVOR -> html
        else -> plain
    }
}
```

This lets the receiving application choose between the HTML and plain-text flavors.

### Compose for Desktop — `LocalClipboardManager`

Compose for Desktop also exposes `LocalClipboardManager` (from `androidx.compose.ui.platform`):

```kotlin
val clipboardManager = LocalClipboardManager.current
clipboardManager.setText(AnnotatedString("plain text"))
```

However, this API only supports plain text (`AnnotatedString`). It does **not** support multi-flavor clipboard writes (e.g., HTML + plain text simultaneously). For the HTML export use case, the AWT `Toolkit` approach is the correct choice.

**Important**: `LocalClipboardManager` is a `@Composable` value that must be captured inside a Composable function and passed into the ViewModel or use-case layer. The actual write happens on the main thread via `Toolkit`, so it can safely be dispatched to `Dispatchers.Main` from a coroutine.

### Platform Abstraction Needed

The desktop JVM target can use `java.awt.Toolkit` directly. For a future Android port, `android.content.ClipboardManager` (via `Context`) is the equivalent. To keep the architecture clean, a `ClipboardWriter` `expect`/`actual` interface (or a simple lambda injected from the platform entry point) should wrap these.

---

## Existing Serialization Libraries

From `kmp/build.gradle.kts`, the following libraries are already declared:

| Library | Version | Source Set | Relevant to Export |
|---|---|---|---|
| `org.jetbrains:markdown` | 0.7.3 | `commonMain` | Yes — provides `HtmlGenerator` for Markdown → HTML conversion |
| `kotlinx-serialization-json` | 1.10.0 | `commonMain` + `commonTest` | Yes — first-class JSON serialization via `@Serializable` and `Json.encodeToString` |
| `kotlinx-coroutines-core` | 1.10.2 | `commonMain` | Infrastructure only (coroutines for async export) |
| `kotlinx-datetime` | 0.7.1 | `commonMain` | Useful for including metadata timestamps in JSON export |

**`org.jetbrains:markdown` (jetbrains-markdown)**

This library (artifact group `org.intellij.markdown`) is already on the classpath and is used in tests (`ReproductionTest.kt`, `ASTDebugTest.kt`) to drive `CommonMarkFlavourDescriptor` + `MarkdownParser` + `HtmlGenerator`. Its public API can produce HTML from a Markdown string:

```kotlin
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser as JBMarkdownParser

fun markdownToHtml(md: String): String {
    val flavour = CommonMarkFlavourDescriptor()
    val tree = JBMarkdownParser(flavour).buildMarkdownTreeFromString(md)
    return HtmlGenerator(md, tree, flavour).generateHtml()
}
```

No new dependency is needed for HTML generation — just promote test-only usage to production code.

**No dedicated HTML-to-string templating library is present.** HTML for rich export must either be generated via jetbrains-markdown (if the input is first rendered as clean Markdown) or hand-built using `StringBuilder`/`buildString`.

---

## Existing Codebase Utilities

### Parser AST (fully usable for export)

The codebase contains a production-quality Logseq-flavored Markdown parser with a rich, typed AST:

- **`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/`**
  - `InlineNodes.kt` — complete inline AST: `TextNode`, `BoldNode`, `ItalicNode`, `StrikeNode`, `HighlightNode`, `CodeNode`, `WikiLinkNode`, `BlockRefNode`, `MdLinkNode`, `ImageNode`, `UrlLinkNode`, `TagNode`, `TaskMarkerNode`, `MacroNode`, `LatexInlineNode`, `HardBreakNode`, `SoftBreakNode`, `PriorityNode`, `SubscriptNode`, `SuperscriptNode`
  - `BlockNodes.kt` — complete block AST: `BulletBlockNode`, `ParagraphBlockNode`, `HeadingBlockNode`, `CodeFenceBlockNode`, `BlockquoteBlockNode`, `OrderedListItemBlockNode`, `ThematicBreakBlockNode`, `TableBlockNode`, `RawHtmlBlockNode`

- **`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt`**
  - Contains `reconstructContent(nodes: List<InlineNode>): String` — a fully implemented inline-AST-to-Markdown serializer. This is the most directly reusable piece: it already serializes every inline node type back to clean Markdown text (including bold, italic, strike, code, links, images, wiki-links with `[[` stripped or kept, etc.).

- **`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`**
  - Contains `savePageInternal()` which uses `buildString` to serialize `List<Block>` back to the Logseq `- ` bullet format. This is a working reference for the block-tree flattening logic (group by `parentUuid`, sort by `position`, recurse).

### UI Rendering

- **`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`**
  - `parseMarkdownWithStyling()` — parses inline content via `InlineParser` and renders to a Compose `AnnotatedString`. Not directly useful for text export, but shows the pattern of walking `InlineNode` trees. The rendering logic mirrors what an export serializer would do.

### Domain Models

- **`Block`** (`model/Models.kt`) — flat storage model: `uuid`, `pageUuid`, `parentUuid`, `leftUuid`, `content` (raw Logseq Markdown string), `level`, `position`, `properties`, `contentHash`.
- **`Page`** (`model/Models.kt`) — `uuid`, `name`, `namespace`, `filePath`, `properties`, `isJournal`, `journalDate`.
- **`BlockNode`** (`outliner/BlockNode.kt`) — in-memory tree model with `parent` pointer and `children: MutableList<BlockNode>`. This is the tree representation the export should walk.
- **`ParsedBlock` / `ParsedPage`** (`model/ParsedModels.kt`) — intermediate post-parse models; each `ParsedBlock` carries `content`, `properties`, `level`, `children`, `references`, `scheduled`, `deadline`.

### No Existing Export Utilities

A search for export-specific serializers (`toJson`, `toMarkdown`, `toHtml`, `Formatter`) found zero dedicated export classes. The codebase does not contain any export module today.

---

## Gap Analysis (what's missing)

| Format | What exists | What is missing |
|---|---|---|
| **Clean Markdown** | `reconstructContent()` in `MarkdownParser.kt` handles inline nodes; `GraphWriter.savePageInternal()` handles block-tree flattening | No pipeline that converts `Block`/`BlockNode` tree → clean Markdown (stripping Logseq syntax: `[[page]]` → page name, `#tag` → keep or strip, task markers → keep or strip). A `MarkdownExporter` class is needed. |
| **Plain text** | `reconstructContent()` could be stripped of markers | No `PlainTextExporter`. Would need a second pass over inline nodes that emits only `TextNode` content and resolves wiki-links/tags to their text form. |
| **HTML** | `org.jetbrains:markdown` `HtmlGenerator` can convert clean Markdown → HTML. Already on classpath. | No `HtmlExporter`. Two-step approach: clean-Markdown serializer → jetbrains-markdown HTML generator. Or direct AST → HTML walk. |
| **JSON** | `kotlinx-serialization-json` is on classpath; `Block`, `Page` are plain `data class` | No `@Serializable` annotation on `Block` or `Page`. Adding `@Serializable` to those models (or creating export-specific DTOs) enables `Json.encodeToString`. |
| **Clipboard write** | `ITextOperations` interface defines cut/copy/paste but the `paste()` impl is a stub (`"[pasted text]"`) | No `ClipboardWriter` abstraction for JVM. Need `java.awt.Toolkit` write for plain text + AWT `Transferable` for HTML. No platform abstraction layer (`expect`/`actual`) for clipboard. |
| **UI entry point** | `TopBar.kt` has a "File" menu (only "Switch Graph" item). Context menu component exists (`SuggestionContextMenu`). | No "Export" menu item, no export dialog/sheet, no block-level context menu item for export. These are all new UI work. |

---

## Recommendation

**No new Gradle dependencies are needed for Markdown, HTML, or JSON export.**

The required building blocks are all present:

1. **Markdown export** — Write a `MarkdownExporter` that walks `BlockNode` tree (or `List<Block>`) and emits clean Markdown. Reuse the `reconstructContent()` pattern from `MarkdownParser.kt` for inline rendering. For Logseq-specific syntax, define a transform policy (e.g., `[[Page Name]]` → `Page Name` or kept as-is, `#tag` kept, task markers kept).

2. **Plain text export** — Write a `PlainTextExporter` that walks the same inline AST but emits only the text of `TextNode`, link labels, and tag names (stripping all Markdown markers).

3. **HTML export** — Use the two-step approach: `MarkdownExporter` → clean Markdown string → `HtmlGenerator` (jetbrains-markdown, already a dependency). This is the lowest-risk path since the HTML generator is already proven in tests.

4. **JSON export** — Add `@Serializable` to `Block` and `Page` (or create lightweight export DTOs), then use `Json { prettyPrint = true }.encodeToString(...)`. No new dependency needed.

5. **Clipboard** — Add a `ClipboardWriter` interface in `commonMain` (or `jvmCommonMain`) with a JVM implementation using `java.awt.Toolkit.getDefaultToolkit().systemClipboard`. For HTML clipboard writes, use a custom AWT `Transferable` with both `text/html` and `text/plain` flavors. Inject into ViewModel via constructor (avoids `@Composable` threading constraints).

6. **UI** — Add an "Export page…" item to the existing "File" `DropdownMenu` in `TopBar.kt`. A simple dialog or bottom sheet with format radio buttons (Markdown / Plain text / HTML / JSON) and Copy / Save buttons is sufficient for V1.

**Priority note on `Block.content` field**: The `content` stored in the DB is the raw Logseq Markdown string (e.g., `- TODO **bold** [[PageName]]`). The export pipeline should pass this through `InlineParser` (the same parser used by `MarkdownEngine.kt`) rather than the heavier `LogseqParser`, since single-block inline parsing is what is needed for per-block export. `GraphWriter.savePageInternal()` provides the block-tree reconstruction reference.
