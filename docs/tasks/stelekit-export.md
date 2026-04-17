# Implementation Plan: SteleKit Export

## Epic Overview

Enable SteleKit desktop users to export the currently open page (or a selected block subtree) to four formats — clean Markdown, plain text, HTML, and JSON — via clipboard copy or file save. Output is clean enough to paste into GitHub, Notion, Confluence, email, or a rich-text editor without manual cleanup.

**Target platform**: JVM Desktop (Phase 1). Mobile is a follow-on.
**No new Gradle dependencies** — `org.jetbrains:markdown:0.7.3` and `kotlinx-serialization-json:1.10.0` are already declared.

---

## Architecture Decisions

| ADR | Decision | File |
|-----|----------|------|
| ADR-001 | `PageExporter` interface + 4 independent implementations (not visitor pattern) | `project_plans/stelekit-export/decisions/ADR-001-serializer-pipeline-design.md` |
| ADR-002 | `ClipboardProvider` interface injected from UI; AWT `Toolkit` for HTML multi-flavor, `LocalClipboardManager` for plain text | `project_plans/stelekit-export/decisions/ADR-002-clipboard-strategy.md` |
| ADR-003 | Block refs pre-fetched in bulk before serialization; expanded to resolved text with depth-3 circular guard; `[block ref]` fallback for dangling refs | `project_plans/stelekit-export/decisions/ADR-003-block-ref-expansion-policy.md` |
| ADR-004 | Phase 1: command palette (4 entries) + TopBar File menu (desktop branch); Phase 2: PageView icon + block selection context menu | `project_plans/stelekit-export/decisions/ADR-004-ui-entry-points.md` |

---

## Story Breakdown

---

### Story 1: Export Pipeline Core

**Goal**: The domain layer can export a page to Markdown, plain text, and JSON. No UI, no clipboard. Pure functions, fully tested.

---

#### Task 1.1 — `PageExporter` interface + `ExportService` skeleton

**Objective**: Create the `export/` package with the interface and orchestrator. The `ExportService` pre-fetches block references, dispatches to the correct exporter, and writes to clipboard via the injected `ClipboardProvider`.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/PageExporter.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` (new)

**Implementation steps**:

1. Create `PageExporter.kt`:
   ```kotlin
   package dev.stapler.stelekit.export

   import dev.stapler.stelekit.model.Block
   import dev.stapler.stelekit.model.Page

   interface PageExporter {
       val formatId: String
       val displayName: String
       fun export(
           page: Page,
           blocks: List<Block>,
           resolvedRefs: Map<String, String> = emptyMap()
       ): String
   }
   ```

2. Create `ClipboardProvider.kt`:
   ```kotlin
   package dev.stapler.stelekit.export

   interface ClipboardProvider {
       fun writeText(text: String)
       fun writeHtml(html: String, plainFallback: String)
   }
   ```

3. Create `ExportService.kt` with:
   - Constructor: `(exporters: List<PageExporter>, clipboard: ClipboardProvider, blockRepository: BlockRepository)`
   - `suspend fun exportToClipboard(page: Page, blocks: List<Block>, formatId: String): Result<Unit>` — pre-fetches block refs, calls exporter, writes to clipboard
   - `suspend fun exportToString(page: Page, blocks: List<Block>, formatId: String): Result<String>` — same but returns the string (for file save)
   - `fun collectBlockRefUuids(blocks: List<Block>): Set<String>` — calls `InlineParser` on each block's `content` to extract `BlockRefNode.blockUuid` values
   - `suspend fun resolveBlockRefs(uuids: Set<String>): Map<String, String>` — calls `blockRepository.getBlockByUuid()` for each UUID; runs on `Dispatchers.Default`
   - `fun subtreeBlocks(allBlocks: List<Block>, rootUuids: Set<String>): List<Block>` — returns the selected blocks and all their descendants from `BlockSorter.sort(allBlocks)`

4. Add `defaultExporters()` companion returning the four implementations once they exist.

**Validation criteria**:
- `ExportService` compiles with a stub `NoOpExporter` registered
- `collectBlockRefUuids()` correctly identifies `BlockRefNode` UUIDs from a block with `content = "See ((abc-123)) for details"`
- `subtreeBlocks()` with a root UUID returns the root block and all descendant blocks, sorted depth-first

---

#### Task 1.2 — `MarkdownExporter`

**Objective**: Serialize `Page` + `List<Block>` to clean Markdown — YAML frontmatter for page properties, `# Page Name` heading, paragraphs for top-level blocks, indented bullets for nested blocks. Inline transformations: `[[Page]]` kept, `((uuid))` expanded, `==highlight==` → `**highlight**`, `id::` property filtered.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/MarkdownExporter.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` (read-only reference — reuse `reconstructContent`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt` (read-only reference)

**Implementation steps**:

1. Create `MarkdownExporter : PageExporter` with `formatId = "markdown"`, `displayName = "Markdown"`.

2. `export()` builds the output in three sections using `buildString`:
   - **YAML frontmatter**: if `page.properties` is non-empty (after filtering `id` key), emit `---\ntitle: ${page.name}\n${key}: ${yamlEscape(value)}\n---\n\n`
   - **H1 heading**: `# ${page.name}\n\n` (or journal date if `page.isJournal`)
   - **Block tree**: call `writeBlocks(sortedBlocks, sb)`

3. `writeBlocks(blocks: List<Block>, sb: StringBuilder)`:
   - `blocks` is already depth-first sorted by `BlockSorter.sort()`
   - For each block, determine nesting:
     - `level == 0`: emit content as paragraph followed by blank line
     - `level > 0`: emit `${"  ".repeat(level - 1)}- ${inlineContent}\n`
   - Skip property-only blocks (where `block.content.isBlank()` and `block.properties.isNotEmpty()`)

4. Inline content rendering via `renderInlineMarkdown(content: String, resolvedRefs: Map<String, String>): String`:
   - Parse with `InlineParser` (same parser used by `MarkdownEngine.kt`)
   - Walk the `List<InlineNode>` and serialize each node:
     - `TextNode` → escape Markdown special characters in plain text segments (`*`, `_`, `[`, `\`, `` ` ``)
     - `WikiLinkNode` → keep as `[[target]]` (default) or `[alias](target)` based on `WikiLinkNode.alias`
     - `BlockRefNode` → `resolvedRefs[uuid] ?: "[block ref]"`
     - `HighlightNode` → `**${children}**`
     - `TaskMarkerNode("TODO")` → `[ ] ` prefix (prepend before rest of block)
     - `TaskMarkerNode("DONE")` → `[x] ` prefix
     - Other `TaskMarkerNode` (NOW, LATER, WAITING) → `**${marker}** ` bold prefix
     - `LatexInlineNode` → `$${formula}$` preserved as-is
     - All other nodes: reuse `MarkdownParser.reconstructContent()` logic directly
   - Reuse `reconstructContent()` from `MarkdownParser` for remaining node types (bold, italic, strike, code, links, images, macros)

5. `yamlEscape(value: String): String` — wrap in double quotes if value contains `:`, `[`, `{`, `#`, or `&`; escape internal `"` as `\"`

**Validation criteria**:
- `MarkdownExporter().export(page, emptyList(), emptyMap())` returns `# ${page.name}\n\n`
- A top-level block with `level=0` and content `"Hello world"` renders as `Hello world\n\n` (paragraph, not bullet)
- A nested block with `level=1` renders as `- child content\n`
- `HighlightNode` children render as `**highlighted**`
- `id:: uuid-value` property is not emitted in the frontmatter
- YAML values containing `:` are double-quoted

---

#### Task 1.3 — `PlainTextExporter`

**Objective**: Serialize `Page` + `List<Block>` to markup-free plain text. Page name as title, blocks indented by two spaces per level, all Markdown syntax stripped, wiki-link text extracted, block refs resolved.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/PlainTextExporter.kt` (new)

**Implementation steps**:

1. Create `PlainTextExporter : PageExporter` with `formatId = "plain-text"`, `displayName = "Plain Text"`.

2. `export()` structure:
   - Emit `${page.name}\n${"=".repeat(page.name.length)}\n\n` (page title with underline)
   - Walk `BlockSorter.sort(blocks)`, skip property-only blocks
   - For each block: `${"  ".repeat(block.level)}${renderPlainText(block.content, resolvedRefs)}\n`
   - Separate top-level blocks with an additional `\n`

3. `renderPlainText(content: String, resolvedRefs: Map<String, String>): String`:
   - Parse with `InlineParser`
   - Walk `List<InlineNode>`:
     - `TextNode` → `node.content` (verbatim, no escaping needed)
     - `BoldNode`, `ItalicNode`, `StrikeNode`, `HighlightNode` → recurse into children (strip markers)
     - `CodeNode` → `` `${node.content}` `` (keep backticks for readability) or `node.content`
     - `WikiLinkNode` → `node.alias ?: node.target` (strip `[[` / `]]`)
     - `BlockRefNode` → `resolvedRefs[uuid] ?: "[block ref]"`
     - `TagNode` → `node.tag` (strip leading `#`)
     - `UrlLinkNode` → text children rendered recursively
     - `MdLinkNode` → `node.label`
     - `ImageNode` → `[Image: ${node.alt}]`
     - `MacroNode` → `[${node.name}: ${node.arguments.joinToString()}]`
     - `TaskMarkerNode` → `${node.marker} ` (keep as text prefix)
     - `LatexInlineNode` → `${node.formula}` (raw formula)
     - `HardBreakNode`, `SoftBreakNode` → `\n`

4. Handle `CodeFenceBlockNode` in content: if a block's raw content spans multiple lines (code fence), emit the `rawContent` indented but otherwise unchanged.

**Validation criteria**:
- `PlainTextExporter().export(page, emptyList())` returns the page name as a plain title line
- `[[Page Name]]` renders as `Page Name`
- `**bold text**` renders as `bold text`
- `#tagName` renders as `tagName`
- Top-level blocks are separated by blank lines; nested blocks are not

---

#### Task 1.4 — `JsonExporter`

**Objective**: Serialize `Page` + `List<Block>` to a structured JSON tree using `kotlinx-serialization-json`. The output is machine-readable and round-trippable — `content` preserves raw Logseq syntax.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/JsonExporter.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportDtos.kt` (new)

**Implementation steps**:

1. Create `ExportDtos.kt` with `@Serializable` data transfer objects (do NOT add `@Serializable` to `Block` or `Page` in `Models.kt` — avoid polluting domain models):
   ```kotlin
   @Serializable
   data class ExportRoot(
       val version: Int = 1,
       val exportedAt: String, // ISO-8601
       val page: PageDto,
       val blocks: List<BlockDto>
   )

   @Serializable
   data class PageDto(
       val uuid: String,
       val name: String,
       val isJournal: Boolean,
       val journalDate: String? = null,
       val createdAt: String,
       val updatedAt: String,
       val properties: Map<String, String>
   )

   @Serializable
   data class BlockDto(
       val uuid: String,
       val parentUuid: String?,
       val position: Int,
       val level: Int,
       val content: String,
       val properties: Map<String, String>,
       val children: List<BlockDto>
   )
   ```

2. Create `JsonExporter : PageExporter` with `formatId = "json"`, `displayName = "JSON"`.

3. `export()` implementation:
   - Map `Page` → `PageDto` (filter `id` from `properties`)
   - Build nested `List<BlockDto>` from `BlockSorter.sort(blocks)`: group by `parentUuid`, recurse to build `children` lists
   - Construct `ExportRoot` with `Clock.System.now().toString()` as `exportedAt`
   - Return `Json { prettyPrint = true }.encodeToString(ExportRoot.serializer(), root)`

4. `buildBlockTree(blocks: List<Block>): List<BlockDto>`:
   - `blocksByParent = blocks.groupBy { it.parentUuid }`
   - Recursive `fun buildChildren(parentUuid: String?): List<BlockDto>` — returns sorted `BlockDto` list with `children` populated

5. For `resolvedRefs`: JSON format includes raw `content` string (Logseq syntax preserved). Block ref expansion is NOT applied to JSON output — the consumer can resolve UUIDs themselves using the block tree in the same JSON.

**Validation criteria**:
- Output is valid JSON parseable by `kotlinx.serialization`
- Nested blocks appear as `children` arrays, not flat
- `version: 1` appears at the top level
- `exportedAt` is a valid ISO-8601 string
- `id::` property key is absent from `properties` in output
- `Json { prettyPrint = true }` produces indented, human-readable JSON

---

### Story 2: HTML Export + Clipboard Integration

**Goal**: `HtmlExporter` is implemented and produces valid HTML. `ClipboardProvider` JVM implementation is wired. The full pipeline `export → serialize → clipboard` works end-to-end.

---

#### Task 2.1 — HTML escaping utility

**Objective**: Implement a `htmlEscape(String): String` utility function used by `HtmlExporter` for all user-generated content. This is a security-critical component — code fence content without HTML escaping is an XSS vector.

**Files** (max 3):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/HtmlUtils.kt` (new)

**Implementation steps**:

1. Create `HtmlUtils.kt` with:
   ```kotlin
   object HtmlUtils {
       fun escape(text: String): String = buildString(text.length + 16) {
           for (ch in text) when (ch) {
               '&' -> append("&amp;")
               '<' -> append("&lt;")
               '>' -> append("&gt;")
               '"' -> append("&quot;")
               '\'' -> append("&#39;")
               else -> append(ch)
           }
       }

       fun escapeAttr(text: String): String = escape(text)
   }
   ```

2. Add unit test in `kmp/src/businessTest/` (or `jvmTest/`) verifying:
   - `HtmlUtils.escape("<script>alert('xss')</script>")` → `"&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"`
   - `HtmlUtils.escape("a & b")` → `"a &amp; b"`
   - `HtmlUtils.escape("")` → `""`

**Validation criteria**:
- All five special characters (`<`, `>`, `&`, `"`, `'`) are escaped
- Empty string input produces empty string output
- Non-ASCII Unicode (emoji, Arabic) passes through unescaped (UTF-8 safe)

---

#### Task 2.2 — `HtmlExporter`

**Objective**: Serialize `Page` + `List<Block>` to an HTML fragment (no `<html>/<head>/<body>`) suitable for clipboard paste into rich-text editors. Correct `<ul>/<li>` nesting, inline formatting via AST walk, code blocks via `<pre><code>`, block refs via `<blockquote>`.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/HtmlExporter.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/HtmlUtils.kt` (reference)

**Implementation steps**:

1. Create `HtmlExporter : PageExporter` with `formatId = "html"`, `displayName = "HTML"`.

2. `export()` structure (depth-tracking approach, not recursive to avoid stack overflow on deeply nested pages):
   ```
   <article>
     <h1>Page Name</h1>
     [optional <dl> for page properties]
     <p>top-level block 1</p>
     <p>top-level block 2 with children</p>
     <ul>
       <li>child block</li>
       <li>child block with children
         <ul><li>grandchild</li></ul>
       </li>
     </ul>
   </article>
   ```

3. Walk `BlockSorter.sort(blocks)` iteratively, tracking previous block's `level`:
   - When `level` increases: open `<ul>` (or `<ol>` for `OrderedListItemBlockNode`)
   - When `level` decreases: close `</li></ul>` for each level dropped
   - `level == 0` with no children following: emit `<p>${inlineHtml}</p>`
   - `level == 0` with children following: emit `<p>${inlineHtml}</p><ul>` and track open `<ul>` count

4. `renderInlineHtml(content: String, resolvedRefs: Map<String, String>): String`:
   - Parse with `InlineParser`
   - Walk `List<InlineNode>` and emit HTML:
     - `TextNode` → `HtmlUtils.escape(node.content)`
     - `BoldNode` → `<strong>${children}</strong>`
     - `ItalicNode` → `<em>${children}</em>`
     - `StrikeNode` → `<s>${children}</s>`
     - `CodeNode` → `<code>${HtmlUtils.escape(node.content)}</code>`
     - `HighlightNode` → `<mark>${children}</mark>`
     - `WikiLinkNode` → `<a href="#${HtmlUtils.escapeAttr(node.target)}">${HtmlUtils.escape(node.alias ?: node.target)}</a>`
     - `BlockRefNode` → resolved text in `<blockquote data-block-ref="${uuid}">${HtmlUtils.escape(resolved)}</blockquote>` or `<span class="unresolved-ref">[block ref]</span>`
     - `TagNode` → `<span class="tag">#${HtmlUtils.escape(node.tag)}</span>`
     - `UrlLinkNode` → `<a href="${HtmlUtils.escapeAttr(node.url)}">${children}</a>`
     - `MdLinkNode` → `<a href="${HtmlUtils.escapeAttr(node.url)}">${HtmlUtils.escape(node.label)}</a>`
     - `ImageNode` → `<img alt="${HtmlUtils.escapeAttr(node.alt)}" src="${HtmlUtils.escapeAttr(node.url)}">`
     - `MacroNode` → `<em>[${HtmlUtils.escape(node.name)}: ${HtmlUtils.escape(node.arguments.joinToString())}]</em>`
     - `TaskMarkerNode("TODO")` → `<input type="checkbox" disabled> `
     - `TaskMarkerNode("DONE")` → `<input type="checkbox" checked disabled> `
     - Other `TaskMarkerNode` → `<span class="task-marker ${node.marker.lowercase()}">${node.marker}</span> `
     - `LatexInlineNode` → `<code class="math">${HtmlUtils.escape(node.formula)}</code>`

5. Handle `CodeFenceBlockNode`: emit `<pre><code class="language-${lang}">${HtmlUtils.escape(rawContent)}</code></pre>` — the ONLY place where `rawContent` is used; always escape before emitting.

6. Task marker affects the `<li>` class: blocks starting with `TaskMarkerNode("TODO")` emit `<li class="todo">`, DONE blocks emit `<li class="done">`.

**Validation criteria**:
- `<script>` content in a code block is HTML-escaped, not executed
- `[[Page Name]]` becomes `<a href="#Page Name">Page Name</a>`
- Nested blocks produce properly balanced `<ul><li>` structure (no unclosed tags)
- TODO blocks have `class="todo"` on their `<li>`
- Output does not contain `<html>`, `<head>`, or `<body>` tags (fragment only)
- `==highlight==` becomes `<mark>highlight</mark>`

---

#### Task 2.3 — JVM `ClipboardProvider` + `HtmlStringSelection`

**Objective**: Implement the JVM-specific clipboard write using AWT `Toolkit`. Plain text uses `StringSelection`; HTML uses a custom `Transferable` that offers both `text/html` and `text/plain` flavors so rich-text editors (Google Docs, Confluence, Apple Mail) receive styled content.

**Files** (max 3):
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/export/HtmlStringSelection.kt` (new)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` (already created in Task 1.1)

**Implementation steps**:

1. Create `HtmlStringSelection.kt` in `jvmMain`:
   ```kotlin
   package dev.stapler.stelekit.export

   import java.awt.datatransfer.DataFlavor
   import java.awt.datatransfer.Transferable
   import java.awt.datatransfer.UnsupportedFlavorException

   class HtmlStringSelection(
       private val html: String,
       private val plain: String
   ) : Transferable {
       companion object {
           val HTML_FLAVOR = DataFlavor("text/html; charset=UTF-8; class=java.lang.String")
       }

       private val flavors = arrayOf(HTML_FLAVOR, DataFlavor.stringFlavor)

       override fun getTransferDataFlavors(): Array<DataFlavor> = flavors

       override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
           flavor in flavors

       override fun getTransferData(flavor: DataFlavor): Any = when {
           flavor.match(HTML_FLAVOR) -> html
           flavor == DataFlavor.stringFlavor -> plain
           else -> throw UnsupportedFlavorException(flavor)
       }
   }
   ```

2. Document in the call site that `setContents()` must be called on the EDT. In Compose Desktop, `Button.onClick` and `LaunchedEffect` on the main dispatcher satisfy this requirement.

3. Write a JVM integration test that writes to the AWT clipboard and reads it back to verify both flavors are present (skip in CI if headless mode).

**Validation criteria**:
- `HtmlStringSelection` implements `Transferable` correctly
- `getTransferDataFlavors()` returns both HTML and plain text flavors
- `getTransferData(DataFlavor.stringFlavor)` returns `plain` (not `html`)
- `getTransferData(HTML_FLAVOR)` returns `html`
- Calling `getTransferData` with an unsupported flavor throws `UnsupportedFlavorException`

---

### Story 3: UI Integration

**Goal**: Export is accessible from the command palette and TopBar File menu. `StelekitViewModel` has `exportPage()` and `exportSelectedBlocks()` methods. A success notification appears after each export.

---

#### Task 3.1 — `StelekitViewModel` export methods

**Objective**: Add `suspend fun exportPage(formatId: String, clipboard: ClipboardProvider)` and `fun exportSelectedBlocks(formatId: String, clipboard: ClipboardProvider)` to `StelekitViewModel`. Wire `ExportService` construction. Add four export `Command` entries to `updateCommands()`.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt` (already created)

**Implementation steps**:

1. Add `exportService: ExportService? = null` to `StelekitViewModel` constructor (nullable to keep tests that don't need export unaffected).

2. Add `fun exportPage(formatId: String, clipboard: ClipboardProvider)`:
   ```kotlin
   fun exportPage(formatId: String, clipboard: ClipboardProvider) {
       val page = _uiState.value.currentPage ?: return
       val blocks = blockStateManager?.blocksForPage(page.uuid) ?: return
       val sortedBlocks = BlockSorter.sort(blocks)
       scope.launch(Dispatchers.Default) {
           val service = exportService ?: return@launch
           service.clipboard = clipboard
           val result = service.exportToClipboard(page, sortedBlocks, formatId)
           withContext(Dispatchers.Main) {
               result.onSuccess {
                   notificationManager?.show("Copied as ${formatDisplayName(formatId)}", NotificationType.SUCCESS)
               }.onFailure { e ->
                   notificationManager?.show("Export failed: ${e.message}", NotificationType.ERROR)
               }
           }
       }
   }
   ```

3. Add `fun exportSelectedBlocks(formatId: String, clipboard: ClipboardProvider)`:
   - Get `selectedUuids = blockStateManager?.selectedBlockUuids?.value ?: return`
   - If empty, fall back to `exportPage(formatId, clipboard)`
   - Call `exportService.subtreeBlocks(allBlocks, selectedUuids)` to get the filtered+sorted subtree
   - Call `exportToClipboard(page, subtreeBlocks, formatId)`

4. Add export commands to `updateCommands()` (append to `legacyCommands` list):
   - Only add commands when `_uiState.value.currentPage != null`
   - Four `Command` entries: `export.page.markdown`, `export.page.plain-text`, `export.page.html`, `export.page.json`
   - Shortcut `Ctrl+Shift+E` on the Markdown entry only
   - Command actions call `exportPage(formatId, /* clipboard injected at call site */)` — NOTE: the command palette actions do not have access to `LocalClipboardManager`; use a stored `ClipboardProvider` that is set when the composable initializes (see Task 3.2)

5. Add `fun setClipboardProvider(provider: ClipboardProvider)` to allow the composable root to inject the platform-specific clipboard once at startup.

6. Private helper `formatDisplayName(formatId: String): String` → maps `"markdown"` → `"Markdown"`, etc.

**Validation criteria**:
- `exportPage("markdown", fakeClipboard)` with a valid current page calls `ExportService.exportToClipboard()`
- `exportPage(...)` with no current page is a no-op (no crash)
- `exportSelectedBlocks(...)` with empty selection delegates to `exportPage()`
- After export, `notificationManager.show()` is called with `NotificationType.SUCCESS`
- Export commands appear in `AppState.commands` only when `currentPage != null`

---

#### Task 3.2 — TopBar File menu export items + clipboard wiring

**Objective**: Add "Export page" submenu items to the desktop `DropdownMenu` in `TopBar.kt`. Wire `LocalClipboardManager` + AWT clipboard into a `ClipboardProvider` at the `App.kt` / `PageView.kt` level and pass to `viewModel.setClipboardProvider()`.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (already modified in Task 3.1)

**Implementation steps**:

1. Add `onExportPage: ((formatId: String) -> Unit)? = null` parameter to `TopBar()` composable.

2. In the desktop branch (`!isMobile`) of `TopBar.kt`, add to the existing "File" `DropdownMenu` after the "Switch Graph" item:
   ```kotlin
   HorizontalDivider()
   val exportFormats = listOf(
       "markdown" to "Export as Markdown",
       "plain-text" to "Export as Plain Text",
       "html" to "Export as HTML",
       "json" to "Export as JSON"
   )
   exportFormats.forEach { (formatId, label) ->
       DropdownMenuItem(
           text = { Text(label) },
           enabled = appState.currentPage != null,
           onClick = {
               fileMenuExpanded = false
               onExportPage?.invoke(formatId)
           }
       )
   }
   ```

3. In `App.kt`, where `TopBar()` is called:
   - Capture `LocalClipboardManager.current` as `val composeClipboard = LocalClipboardManager.current`
   - Create a `ClipboardProvider` using `remember { createClipboardProvider(composeClipboard) }` where `createClipboardProvider` is a `@Composable` factory (or a plain function if the AWT path does not need Compose context)
   - Call `viewModel.setClipboardProvider(clipboardProvider)` via `LaunchedEffect(Unit)`
   - Pass `onExportPage = { formatId -> viewModel.exportPage(formatId, clipboardProvider) }` to `TopBar()`

4. For JVM, the `createClipboardProvider` function returns:
   ```kotlin
   object : ClipboardProvider {
       override fun writeText(text: String) =
           composeClipboard.setText(AnnotatedString(text))
       override fun writeHtml(html: String, plainFallback: String) {
           java.awt.Toolkit.getDefaultToolkit().systemClipboard
               .setContents(HtmlStringSelection(html, plainFallback), null)
       }
   }
   ```
   This lives in `jvmMain` or in a `@Composable` helper that is platform-guarded.

**Validation criteria**:
- TopBar renders "Export as Markdown" etc. in the File menu when `currentPage != null`
- These items are disabled (grayed) when `currentPage == null`
- Clicking "Export as Markdown" calls `viewModel.exportPage("markdown", ...)`
- TopBar test (`TopBarTest.kt`) still passes after adding the parameter (it is nullable with a default)
- No export items appear in the mobile `DropdownMenu`

---

### Story 4: Block-Level Export

**Goal**: Users can select a block subtree and export just that subset. `Ctrl+Shift+E` on a selection triggers export; a format picker appears or the last-used format is used.

---

#### Task 4.1 — Subtree filter in `ExportService` + keyboard shortcut

**Objective**: Wire the already-complete `BlockStateManager.selectedBlockUuids` + `BlockStateManager.isInSelectionMode` into the export pipeline. Add keyboard shortcut in `PageView.kt`. The `ExportService.subtreeBlocks()` helper (sketched in Task 1.1) is finalized and tested here.

**Files** (max 5):
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

**Implementation steps**:

1. Finalize `ExportService.subtreeBlocks(allBlocks: List<Block>, rootUuids: Set<String>): List<Block>`:
   - Take the depth-first sorted list from `BlockSorter.sort(allBlocks)`
   - Find the index positions of all blocks whose UUID is in `rootUuids`
   - For each root, include it and all following blocks with a higher `level` (its descendants), stopping when `level` drops to or below the root's level
   - Return `BlockSorter.sort()` of the combined set to ensure consistent ordering

2. In `PageView.kt`, add to the existing `Modifier.onKeyEvent { }` block (where `Ctrl+A`, Delete, Escape are handled):
   ```kotlin
   Key.E -> if (event.isCtrlPressed && event.isShiftPressed) {
       if (isInSelectionMode) {
           viewModel.exportSelectedBlocks("markdown", clipboardProvider)
       } else {
           viewModel.exportPage("markdown", clipboardProvider)
       }
       true
   } else false
   ```

3. When `isInSelectionMode == true`, the export command palette entries (from Task 3.1) are replaced by selection-aware variants: "Export selected blocks as Markdown" etc.

4. Write unit tests for `subtreeBlocks()`:
   - Selecting a root block returns that block and all its descendants
   - Selecting a leaf block returns only that block
   - Selecting two non-adjacent blocks returns both subtrees merged and sorted
   - Empty selection returns empty list

**Validation criteria**:
- `subtreeBlocks(allBlocks, setOf(rootUuid))` returns the root and all nested descendants
- `subtreeBlocks(allBlocks, emptySet())` returns empty list
- `Ctrl+Shift+E` with no selection exports the whole page
- `Ctrl+Shift+E` with one block selected exports only that block's subtree

---

#### Task 4.2 — Export tests: integration coverage

**Objective**: Cover the full export pipeline with integration tests using the demo graph fixture. Verify that realistic Logseq content (block refs, wiki links, properties, code blocks, task markers) produces correct output in each format.

**Files** (max 5):
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/ExportIntegrationTest.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/HtmlExporterTest.kt` (new)

**Implementation steps**:

1. Create `ExportIntegrationTest.kt` with test fixtures that reuse `TestFixtures` from `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/TestFixtures.kt`:
   - `testMarkdownExportProducesCleanOutput()` — page with wiki links, block refs, properties, nested blocks → verify no `((uuid))`, no `key::` lines in body, correct heading
   - `testPlainTextExportStripsAllMarkup()` — bold, italic, highlights, links → verify no `*`, `**`, `[[`, `]]` in output
   - `testHtmlExportEscapesCodeBlocks()` — code block with `<script>` content → verify `&lt;script&gt;` in output
   - `testJsonExportIsValidJson()` — parse output with `Json.decodeFromString<ExportRoot>()` and assert structure
   - `testSubtreeExportFiltersCorrectly()` — select middle-level block, verify ancestors excluded

2. Create `HtmlExporterTest.kt` focusing on HTML edge cases:
   - Deeply nested blocks (6+ levels) — verify `<ul>` open/close balance
   - Table block — verify `<table>` output
   - Mixed TODO/DONE/NOW task markers
   - Page with no blocks — verify `<article><h1>...</h1></article>` with no crash
   - Code fence with backticks in content — verify fence is not broken

**Validation criteria**:
- All tests pass with `./gradlew jvmTest`
- HTML escaping test specifically verifies `<script>` is neutralized
- JSON output round-trips through `kotlinx.serialization` decoder
- No test depends on UI threading or Compose runtime

---

## Known Issues / Proactive Bug Identification

---

### Bug 1: Circular Block References — Stack Overflow Risk [SEVERITY: High]

**Description**: `BlockRefNode` can reference a block that itself contains a `BlockRefNode` pointing back to the original. A naive recursive resolver will overflow the call stack.

**Mitigation**:
- Track a `visitedUuids: Set<String>` passed into the ref-resolution helper. If `uuid` is already in the set, return `"[circular ref]"` immediately.
- Cap expansion depth at 3 (matches Logseq's own rendering cap).
- Unit test with a manually constructed cycle: block A refs B, block B refs A.

**Files likely affected**: `ExportService.kt`, `MarkdownExporter.kt`, `HtmlExporter.kt`

---

### Bug 2: Code Fence Content Not HTML-Escaped [SEVERITY: High]

**Description**: `CodeFenceBlockNode.rawContent` may contain `<`, `>`, `&`, `"` characters (e.g., HTML code examples, XML snippets, `<script>` tags). Emitting `rawContent` directly inside `<pre><code>` produces broken HTML and a potential XSS vector if the output is later rendered in a browser.

**Mitigation**:
- `HtmlExporter` MUST call `HtmlUtils.escape(rawContent)` before emitting inside `<pre><code>`.
- Dedicated test in `HtmlExporterTest`: input `content = "```html\n<script>alert(1)</script>\n```"`, assert output contains `&lt;script&gt;`.
- Code review checklist: any `rawContent` reference in `HtmlExporter.kt` must be wrapped in `HtmlUtils.escape()`.

**Files likely affected**: `HtmlExporter.kt`, `HtmlUtils.kt`

---

### Bug 3: `id::` Property Leaking into Human-Readable Exports [SEVERITY: Medium]

**Description**: Every Logseq block accumulates an `id:: <uuid>` property in `Block.properties`. Emitting these in Markdown YAML frontmatter or HTML output produces noise (`id: 6b5a2f3d-...` in every export).

**Mitigation**:
- Define a constant `FILTERED_PROPERTY_KEYS = setOf("id")` in `ExportService` (or a shared `ExportConstants`).
- All exporters filter this key when iterating `page.properties` or `block.properties`.
- Test: a page with `properties = mapOf("id" to "some-uuid", "title" to "My Page")` → YAML frontmatter contains `title:` but not `id:`.

**Files likely affected**: `MarkdownExporter.kt`, `HtmlExporter.kt`, `JsonExporter.kt`

---

### Bug 4: Performance Freeze on Large Pages [SEVERITY: Medium]

**Description**: `ExportService.exportToClipboard()` runs the full block serialization including `InlineParser` calls for each block. On a page with 500+ blocks, this is CPU-intensive. If called on the main thread, the UI freezes.

**Mitigation**:
- `exportToClipboard()` is a `suspend fun` that runs on `Dispatchers.Default`.
- In `StelekitViewModel.exportPage()`, use `scope.launch(Dispatchers.Default)` for the export, then `withContext(Dispatchers.Main)` for the `notificationManager.show()` call.
- Test: mock a page with 1000 synthetic blocks, measure `export()` runtime is under 500ms.
- Note: AWT `setContents()` must be called on the EDT. The switch from `Dispatchers.Default` back to `Dispatchers.Main` before clipboard write satisfies this.

**Files likely affected**: `ExportService.kt`, `StelekitViewModel.kt`

---

### Bug 5: Unbalanced `<ul>/<li>` Tags in HTML Output [SEVERITY: Medium]

**Description**: The iterative `level`-tracking walk in `HtmlExporter` must open a `<ul>` when level increases and close `</li></ul>` for each level dropped. An off-by-one in the level transition logic produces unclosed or prematurely closed tags, corrupting the DOM when pasted into a rich-text editor.

**Mitigation**:
- Maintain a `depth: Int` variable and an `openListCount: Int` counter. At each block transition, emit the exact number of `</li></ul>` closes needed before opening new `<li>`.
- Test with a 3-level nested structure: root → child → grandchild → back to root. Assert the output contains exactly two `</ul>` close tags and both `<ul>` open tags.
- After building the full output string, run a quick sanity check: count `<ul>` vs. `</ul>` occurrences in the output and assert they match (development-time assertion, removable later).

**Files likely affected**: `HtmlExporter.kt`

---

### Bug 6: YAML Frontmatter Injection via Property Values [SEVERITY: Medium]

**Description**: Page property values like `tags:: [[A & B]]` or `title:: My: page` contain characters that are special in YAML (`:`, `[`, `&`). Emitting them without escaping produces invalid YAML that downstream parsers (Obsidian, Hugo, Jekyll) will fail to parse.

**Mitigation**:
- `MarkdownExporter.yamlEscape(value: String)` wraps the value in double quotes if it contains `:`, `[`, `{`, `#`, `&`, or starts with `-`. Internal `"` characters are escaped as `\"`.
- Test: property value `"tags: [[A & B]]"` → `"\"tags: [[A & B]]\""`
- Test: property value `"simple title"` → `"simple title"` (no quotes added unnecessarily)

**Files likely affected**: `MarkdownExporter.kt`

---

### Bug 7: Namespace Page Names Produce Broken Links [SEVERITY: Low]

**Description**: `WikiLinkNode.target` may contain `/` (e.g., `Namespace/SubPage`). When `HtmlExporter` emits `<a href="#Namespace/SubPage">`, the `/` in the fragment identifier is technically valid in URLs but some applications interpret it as a path separator.

**Mitigation**:
- In `HtmlExporter.renderInlineHtml()`, apply `HtmlUtils.escapeAttr()` to `node.target` for `href` values (already planned); `escapeAttr` calls `HtmlUtils.escape()` which does not encode `/` — this is acceptable for fragment identifiers.
- Document the limitation: links in the exported HTML are app-internal anchors that will not resolve outside SteleKit. Add a comment in `HtmlExporter.kt`.
- `MarkdownExporter`: when converting `WikiLinkNode` to `[label](url)` format, URL-encode `/` as `%2F` in the href. Default behavior (`[[Namespace/SubPage]]`) avoids this issue entirely since the brackets are kept.

**Files likely affected**: `HtmlExporter.kt`, `MarkdownExporter.kt`

---

## Dependency Visualization

```
Story 1 (Domain Pipeline)
├── Task 1.1: PageExporter interface + ExportService skeleton
│     ← no upstream dependencies
├── Task 1.2: MarkdownExporter
│     ← requires Task 1.1 (interface)
│     ← reads MarkdownParser.reconstructContent() (existing)
│     ← reads InlineParser (existing)
├── Task 1.3: PlainTextExporter
│     ← requires Task 1.1 (interface)
│     ← reads InlineParser (existing)
└── Task 1.4: JsonExporter + ExportDtos
      ← requires Task 1.1 (interface)
      ← uses kotlinx-serialization-json (existing dep)

Story 2 (HTML + Clipboard)
├── Task 2.1: HtmlUtils (htmlEscape)
│     ← no upstream dependencies
├── Task 2.2: HtmlExporter
│     ← requires Task 1.1 (interface)
│     ← requires Task 2.1 (HtmlUtils)
│     ← reads InlineParser (existing)
└── Task 2.3: JVM ClipboardProvider + HtmlStringSelection
      ← requires Task 1.1 (ClipboardProvider interface)
      ← requires Task 2.2 (for HTML multi-flavor path)

Story 3 (UI Integration)
├── Task 3.1: StelekitViewModel export methods
│     ← requires Story 1 complete (all 4 exporters)
│     ← requires Task 2.3 (ClipboardProvider interface)
└── Task 3.2: TopBar File menu + clipboard wiring
      ← requires Task 3.1 (viewModel methods)
      ← requires Task 2.3 (JVM ClipboardProvider)

Story 4 (Block-Level)
├── Task 4.1: Subtree filter + keyboard shortcut
│     ← requires Task 1.1 (ExportService.subtreeBlocks)
│     ← requires Task 3.1 (viewModel.exportSelectedBlocks)
└── Task 4.2: Integration tests
      ← requires all of Stories 1–3
      ← requires Task 4.1
```

---

## Integration Checkpoints

**After Story 1** (Tasks 1.1–1.4):
- Run `./gradlew jvmTest` — all existing tests pass; new unit tests for the four exporters pass
- Manually verify: `MarkdownExporter().export(page, sampleBlocks)` produces correct Markdown in a REPL or test

**After Story 2** (Tasks 2.1–2.3):
- Manually test: paste HTML export into Google Docs and a plain email client — verify rich formatting is preserved in Google Docs and falls back to plain text in email
- Run `./gradlew jvmTest` — `HtmlExporterTest` all green, including XSS escape test

**After Story 3** (Tasks 3.1–3.2):
- Manual end-to-end: open a page in the desktop app → File menu → "Export as Markdown" → paste into a GitHub comment → verify clean rendering
- Verify TopBar "Export" items are disabled on the Journals screen (no `currentPage`)
- Verify `Ctrl+Shift+E` triggers export notification

**After Story 4** (Tasks 4.1–4.2):
- Manual: select three blocks with `Ctrl+Click`, press `Ctrl+Shift+E`, paste → verify only selected subtree appears
- Run full test suite: `./gradlew allTests`

---

## Context Preparation Guide

Before starting each story, load the following files into context:

**Story 1 (domain layer)**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/InlineNodes.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/BlockNodes.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/outliner/BlockSorter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` (tree traversal reference)
- `project_plans/stelekit-export/decisions/ADR-001-serializer-pipeline-design.md`
- `project_plans/stelekit-export/decisions/ADR-003-block-ref-expansion-policy.md`

**Story 2 (HTML + clipboard)**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/PageExporter.kt` (just created)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt` (just created)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` (just created)
- `project_plans/stelekit-export/decisions/ADR-002-clipboard-strategy.md`

**Story 3 (UI)**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TopBar.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/NotificationManager.kt`
- `project_plans/stelekit-export/decisions/ADR-004-ui-entry-points.md`

**Story 4 (block-level)**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ExportService.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/fixtures/TestFixtures.kt`
