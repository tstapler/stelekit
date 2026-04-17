# Research: Architecture
**Dimension**: Architecture | **Date**: 2026-04-13

---

## Block Tree Representation

When a page is open, the block tree lives in two parallel representations:

### 1. Flat `List<Block>` in `BlockStateManager._blocks` (primary live state)
`Block` (in `model/Models.kt`) is a flat data class stored in a `Map<pageUuid, List<Block>>` StateFlow inside `BlockStateManager`. Each `Block` carries:
- `uuid`, `pageUuid`, `parentUuid`, `leftUuid` — linked-list + parent pointer tree topology
- `content: String` — raw Logseq-flavored Markdown for one block
- `level: Int`, `position: Int` — display ordering hints (repaired at render time by `BlockSorter`)
- `properties: Map<String, String>` — inline `key:: value` pairs already stripped from `content` by `OutlinerPipeline.processBlock()`

The flat list is converted to a depth-first visual order by `BlockSorter.sort(List<Block>)`. The sorted list is the canonical "display tree" — children immediately follow their parent.

### 2. `BlockNode` tree (in-memory outliner, transient)
`outliner/BlockNode.kt` wraps a `Block` with mutable `parent` and `children` pointers. This in-memory tree is only used during parse/insert operations inside `OutlinerPipeline` and `GraphLoader`, not as long-lived UI state.

### What the exporter will work with
An exporter must consume the flat `List<Block>` from `BlockStateManager.blocksForPage(pageUuid)` and reconstruct the tree by calling `BlockSorter.sort()` to get the depth-first ordered list. The resulting list contains each block's `level` field which encodes nesting depth — making recursive or iterative tree traversal trivial without needing to build `BlockNode` objects.

---

## Serializer Pipeline Design

### Interface

```kotlin
// kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/PageExporter.kt
interface PageExporter {
    val formatId: String       // e.g. "markdown", "plain-text", "html", "json"
    val displayName: String    // shown in UI, e.g. "Markdown"
    fun export(page: Page, blocks: List<Block>): String
}
```

Using a `List<Block>` (already sorted by `BlockSorter`) as input means exporters are pure functions with no repository dependencies — easy to unit-test and compose.

### Implementations

| File | Format | Key responsibility |
|------|--------|--------------------|
| `MarkdownExporter.kt` | Clean Markdown | Strip `[[page refs]]` → plain links or page names; convert `((block-ref))` to block content; `key:: value` → YAML front-matter or drop; nest bullets by `block.level` |
| `PlainTextExporter.kt` | Plain text | Strip all markup tokens; flatten indent to spaces or nothing |
| `HtmlExporter.kt` | HTML | Build `<ul>/<li>` tree; use existing `org.jetbrains:markdown:0.7.3` (already in Gradle) to render inline Markdown inside each block content |
| `JsonExporter.kt` | JSON | Serialize tree using `kotlinx-serialization-json:1.10.0` (already in Gradle); include page metadata + nested block objects |

### Package placement

```
kmp/src/commonMain/kotlin/dev/stapler/stelekit/
  export/
    PageExporter.kt         ← interface
    MarkdownExporter.kt
    PlainTextExporter.kt
    HtmlExporter.kt
    JsonExporter.kt
    ExportService.kt        ← orchestrator: resolves exporter, calls clipboard/file
```

`export/` is a sibling of `db/`, `parser/`, and `repository/` — pure domain logic with no UI imports. This matches the existing architecture's separation of concerns.

### `ExportService`

```kotlin
class ExportService(
    private val exporters: List<PageExporter> = defaultExporters(),
    private val clipboard: ClipboardProvider,
) {
    fun exportToClipboard(page: Page, blocks: List<Block>, formatId: String): Result<Unit>
    fun exportToString(page: Page, blocks: List<Block>, formatId: String): Result<String>
}
```

`defaultExporters()` returns the four implementations. `ExportService` is constructed in `StelekitApp` alongside other services and injected into `StelekitViewModel`.

---

## UI Entry Points

### Existing patterns observed
- **Command palette** (`CommandPalette.kt`): takes a `List<Command>` from `AppState.commands`, built in `StelekitViewModel.updateCommands()`. Commands are `Command(id, label, shortcut?, action: () -> Unit)`. This is the lowest-friction, already-working hook point.
- **TopBar** (`TopBar.kt`): has a "File" `DropdownMenu` that currently only contains "Switch graph". This is the correct place for page-level file actions on desktop.
- **PageView** header (`PageView.kt` lines 122–150): shows Edit (rename) and Favorite icon buttons. A third icon button for Export fits naturally here.
- **Keyboard shortcut** handling: `PageView.kt` already handles `Ctrl+A`, Delete, Escape via `onKeyEvent`. New shortcuts can be added in the same `when` block.

### Recommended UI entry points (three-pronged approach)

1. **Command palette** (primary, zero friction): Register four commands in `EssentialCommands` or directly in `StelekitViewModel.updateCommands()`:
   - `"export.page.markdown"` → label "Export page as Markdown" → shortcut `Ctrl+Shift+E`
   - `"export.page.plain-text"` → label "Export page as Plain Text"
   - `"export.page.html"` → label "Export page as HTML"
   - `"export.page.json"` → label "Export page as JSON"
   All four copy to clipboard and show a notification ("Copied as Markdown").

2. **TopBar File menu** (discoverable): Add an "Export page" submenu under the existing File `DropdownMenu` in `TopBar.kt`. The submenu lists the four formats. On desktop only (already guarded by `if (!isMobile)` branch in `TopBar`).

3. **PageView header icon** (contextual): Add a single "Share/Export" `IconButton` to the page header row alongside the existing Rename and Favorite buttons. Clicking it opens a small `DropdownMenu` with the four format options. This is the most discoverable entry point for a user currently reading the page.

### Block-level selection export
When `blockStateManager.isInSelectionMode` is true, the "Export" command/icon should offer to export only the selected subtree. The selected UUIDs are in `blockStateManager.selectedBlockUuids`. The exporter receives the filtered + sorted subset of blocks (the selected blocks and their descendants, resolved from `BlockSorter.sort()` output).

---

## Block Selection State

`BlockStateManager` already has a complete, production-ready multi-block selection system:

- `_selectedBlockUuids: MutableStateFlow<Set<String>>` — set of selected block UUIDs
- `_isInSelectionMode: MutableStateFlow<Boolean>` — true when at least one block is selected
- `selectedBlockUuids: StateFlow<Set<String>>` — public read-only flow
- `isInSelectionMode: StateFlow<Boolean>` — public read-only flow
- `enterSelectionMode(uuid)`, `toggleBlockSelection(uuid)`, `extendSelectionTo(uuid)`, `extendSelectionByOne(up)`, `selectAll(pageUuid)`, `clearSelection()` — full keyboard/mouse selection API

`PageView.kt` already subscribes to both flows and passes `isInSelectionMode` and `selectedBlockUuids` down to `BlockList` and `BlockItem`.

For subtree export, the export logic must: take the selected UUIDs, find their descendants from the flat block list (same walk used by `subtreeDedup()`), sort the union with `BlockSorter.sort()`, and pass the result to the exporter. No new selection infrastructure is needed — only a helper in `ExportService` to expand selection to descendants.

---

## Cross-Platform Clipboard Strategy

### Current state
No `ClipboardProvider` abstraction exists yet. `OptimizedTextOperations.getClipboardText()` is a placeholder returning `"[pasted text]"`. `ITextOperations.kt` documents cut/copy/paste methods but they are not fully implemented.

### Recommended approach: `expect`/`actual` pattern

Define in `commonMain`:

```kotlin
// kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/ClipboardProvider.kt
interface ClipboardProvider {
    fun setText(text: String)
    fun getText(): String?
}

expect fun createClipboardProvider(): ClipboardProvider
```

Implement in `jvmMain` (Desktop — the MVP target):

```kotlin
// kmp/src/jvmMain/.../platform/ClipboardProvider.jvm.kt
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual fun createClipboardProvider() = object : ClipboardProvider {
    override fun setText(text: String) {
        val selection = StringSelection(text)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }
    override fun getText(): String? = /* read from systemClipboard */ null
}
```

`java.awt.Toolkit` is available on all desktop JVM targets at zero dependency cost. No new Gradle dependency is needed.

For `androidMain`, use `ClipboardManager` from `android.content.ClipboardManager`. For `iosMain`, use `UIPasteboard.general`. Both are stubs for the mobile follow-on.

### Compose `LocalClipboardManager` consideration
Compose exposes `LocalClipboardManager` which works cross-platform within Compose UI. However, it is only accessible inside a `@Composable` context. Since `ExportService` is a domain service (no Compose dependency), the `expect`/`actual` approach is cleaner. The Compose clipboard manager can be used as a thin adapter: pass it from the composable call-site into `ExportService` as the `ClipboardProvider` implementation.

The lightest path: in `PageView.kt` or `StelekitViewModel`, capture `LocalClipboardManager.current` once and wrap it:

```kotlin
val clipboardManager = LocalClipboardManager.current
val exportService = remember { ExportService(clipboard = { text ->
    clipboardManager.setText(AnnotatedString(text))
}) }
```

This avoids any `expect`/`actual` plumbing for Phase 1 (desktop-only), deferring platform abstraction to when mobile is targeted.

---

## Recommended Architecture

### Summary decision

**Tier 1 — Domain layer** (`export/` package, `commonMain`):
- `PageExporter` interface taking `(Page, List<Block>) -> String`
- Four implementations: `MarkdownExporter`, `PlainTextExporter`, `HtmlExporter` (reuse `org.jetbrains:markdown`), `JsonExporter` (reuse `kotlinx-serialization-json`)
- `ExportService` orchestrates format selection and clipboard write

**Tier 2 — ViewModel** (`StelekitViewModel`):
- New `fun exportPage(formatId: String)` that reads `currentPage` and calls `blockStateManager.blocksForPage(pageUuid)`, calls `ExportService.exportToClipboard()`, then shows a success notification via `notificationManager`
- New `fun exportSelectedBlocks(formatId: String)` for selection export
- Commands registered in `updateCommands()` (or a new `ExportCommands` object in `editor/commands/`)

**Tier 3 — UI** (`ui/` package):
- Command palette entries (free, via existing `Command` list)
- TopBar File menu items (desktop only, `TopBar.kt`)
- PageView header `IconButton` with `DropdownMenu` (three format options + "More…")
- `PageView.kt` keyboard shortcut: `Ctrl+Shift+E` → `viewModel.exportPage("markdown")`

### Dependency graph

```
UI (PageView, TopBar, CommandPalette)
    ↓ calls
StelekitViewModel.exportPage()
    ↓ reads blocks from
BlockStateManager.blocksForPage() + BlockSorter.sort()
    ↓ passes (Page, List<Block>) to
ExportService
    ↓ dispatches to
PageExporter implementation
    ↓ writes result via
ClipboardProvider (Compose LocalClipboardManager adapter)
```

### No new Gradle dependencies required
- HTML rendering: `org.jetbrains:markdown:0.7.3` already present
- JSON serialization: `kotlinx-serialization-json:1.10.0` already present
- Clipboard (desktop): `java.awt.Toolkit` (JDK stdlib) or `LocalClipboardManager` (Compose, already present)
