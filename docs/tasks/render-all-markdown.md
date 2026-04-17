# Implementation Plan: Render All Markdown Blocks

## Epic Overview

SteleKit currently discards block-level type information when converting `BlockNode` subtypes into `ParsedBlock` in `MarkdownParser.convertBlock()`. As a result, headings render as plain bullet text, code fences collapse to raw ``` markers, tables appear as pipe-separated strings, and images show as underlined text. This epic restores that information by threading block type through the model and data layers, then builds dedicated Compose composables for each block kind.

**Outcome**: Every Logseq-compatible markdown construct renders with appropriate visual treatment in view mode. Edit mode is unchanged — the outliner model retains raw markdown in `BasicTextField`, which is intentional.

### ADR References

| ADR | Decision | File |
|-----|----------|------|
| ADR-001 | Preserve block type by adding `blockType` discriminator to `ParsedBlock`, `Block`, and SQLDelight schema | `project_plans/render-all-markdown/decisions/ADR-001-block-type-preservation.md` |
| ADR-002 | Dispatch rendering via sealed-class `when` in `BlockItem`; use `contentType` in `LazyColumn` for slot pooling | `project_plans/render-all-markdown/decisions/ADR-002-block-renderer-dispatch.md` |
| ADR-003 | Use Coil 3 for image loading with a custom fetcher for Logseq relative asset paths | `project_plans/render-all-markdown/decisions/ADR-003-image-rendering-coil3.md` |
| ADR-004 | Defer full LaTeX rendering to Phase 2; Phase 1 uses improved monospace fallback with `$...$` delimiters stripped | `project_plans/render-all-markdown/decisions/ADR-004-latex-phase1-fallback.md` |

---

## Story Breakdown

### Story 1 — Model & Data Layer
**Goal**: Stop discarding block-level type at parse time. Add `blockType` to `ParsedBlock` and `Block`, migrate the SQLDelight schema, and update `MarkdownParser.convertBlock()` to populate it.

**Files touched**: `ParsedModels.kt`, `Models.kt`, `SteleDatabase.sq`, `MarkdownParser.kt`, `GraphLoader.kt` (mapping layer)

**Acceptance criteria**:
- `ParsedBlock.blockType` is populated for all nine `BlockNode` subtypes
- `Block.blockType` round-trips through SQLDelight (insert → select → domain object)
- Existing rows in the DB default to `"bullet"` via migration DEFAULT
- All existing tests pass without modification
- `Block.init` validates `blockType` against the known set of discriminator strings

---

#### Task 1.1 — Define `BlockType` sealed class and add to `ParsedBlock`
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt`

Add a `sealed class BlockType` (or `enum class`) covering: `Bullet`, `Paragraph`, `Heading(level: Int)`, `CodeFence(language: String)`, `Blockquote`, `OrderedListItem(number: Int)`, `ThematicBreak`, `Table`, `RawHtml`.

Add `val blockType: BlockType = BlockType.Bullet` to `ParsedBlock`. Default keeps backward compat with callers that construct `ParsedBlock` without the field.

Design note: Use a `sealed class` rather than an `enum` so `Heading` can carry its H1-H6 level and `CodeFence` can carry its language string without a separate property lookup later.

**Effort estimate**: 1-2h

---

#### Task 1.2 — Add `blockType` string column to `Block` domain model
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`

Add `val blockType: String = "bullet"` to `Block`. Update `Block.init` to call a `validateBlockType()` helper that checks the value against the set of known discriminator strings (`"bullet"`, `"paragraph"`, `"heading"`, `"code_fence"`, `"blockquote"`, `"ordered_list_item"`, `"thematic_break"`, `"table"`, `"raw_html"`).

String discriminator (not sealed class) at the domain/DB boundary keeps SQLDelight mapping simple and avoids a custom column adapter for the initial migration.

**Effort estimate**: 1h

---

#### Task 1.3 — SQLDelight schema migration
**Files**:
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`
- New migration file: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/migrations/2.sqm`

Add column to `blocks` table:

```sql
ALTER TABLE blocks ADD COLUMN block_type TEXT NOT NULL DEFAULT 'bullet';
```

Update the `insertBlock` named query to include `block_type` as the 13th positional parameter:

```sql
insertBlock:
INSERT OR REPLACE INTO blocks (uuid, page_uuid, parent_uuid, left_uuid, content, level, position, created_at, updated_at, properties, version, content_hash, block_type)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

Also update `updateBlockContent` if it needs to allow `block_type` update on re-import (it likely does not — block type does not change when the user edits inline content, only when a whole block is re-parsed on disk reload).

Add `block_type` to the FTS5 update trigger column list in `blocks_au` — it is not indexed for FTS but triggers must reflect schema. Verify: `blocks_fts` only indexes the `content` column so no FTS change is needed.

SQLDelight migration file numbering: the schema uses version 1 implicitly (no existing `.sqm` files found). Create `2.sqm` containing only the ALTER TABLE statement above.

**Effort estimate**: 1-2h

---

#### Task 1.4 — Update `MarkdownParser.convertBlock()` to populate `blockType`
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt`

In `convertBlock(node: BlockNode)`, add a `when (node)` branch before the existing `reconstructContent()` call to set `blockType` on the resulting `ParsedBlock`. Map each `BlockNode` subtype:

- `BulletBlockNode` → `BlockType.Bullet`
- `ParagraphBlockNode` → `BlockType.Paragraph`
- `HeadingBlockNode` → `BlockType.Heading(level = node.level)`
- `CodeFenceBlockNode` → `BlockType.CodeFence(language = node.language.orEmpty())`
- `BlockquoteBlockNode` → `BlockType.Blockquote`
- `OrderedListItemBlockNode` → `BlockType.OrderedListItem(number = node.number)`
- `ThematicBreakBlockNode` → `BlockType.ThematicBreak`
- `TableBlockNode` → `BlockType.Table`
- `RawHtmlBlockNode` → `BlockType.RawHtml`

**Effort estimate**: 1-2h

---

#### Task 1.5 — Propagate `blockType` through `GraphLoader` → `Block` mapping
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

Find the site where `ParsedBlock` is converted to `Block` (the constructor call). Add the mapping from `ParsedBlock.blockType` to `Block.blockType` using a `toDiscriminatorString()` extension that converts the sealed class back to its string form. Centralise this in a new file `BlockTypeMapper.kt` under `model/` so both `GraphLoader` and any future importers share it.

**Effort estimate**: 1h

---

#### Task 1.6 — Update SQLDelight repository adapter
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/` (the SQLDelight-backed `BlockRepository`)

SQLDelight will regenerate the `Blocks` data class with `block_type: String`. Update the mapping function that converts the generated `Blocks` row to domain `Block` to pass `blockType = row.block_type`. Update `insertBlock` calls to pass the 13th argument.

**Effort estimate**: 1-2h

---

### Story 2 — Block-Level Renderer Dispatch
**Goal**: Wire `BlockItem` view-mode path to dispatch to type-specific composables. Implement composables for Heading, ThematicBreak, CodeFence, Blockquote, and OrderedListItem.

**Files touched**: `BlockItem.kt`, `BlockViewer.kt`, new `HeadingBlock.kt`, `CodeFenceBlock.kt`, `BlockquoteBlock.kt`, `ThematicBreakBlock.kt` (grouped or individual), `LazyColumn` usage site

**Acceptance criteria**:
- `# Heading` text renders at MaterialTheme heading scale (H1-H6)
- Code fences render in monospace with background, horizontal scroll, and a language label
- `---` renders as a `HorizontalDivider`
- Blockquotes render with a left accent bar and indented text
- Ordered list items render with their numeric marker
- Bullet and paragraph blocks render via the existing `BlockViewer` path (no regression)
- `LazyColumn` call sites pass `contentType = { block.blockType }` where applicable

---

#### Task 2.1 — Add `blockType` to `BlockItem` signature and branch view-mode dispatch
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`

`BlockItem` receives a `Block`. After Story 1, `block.blockType` is available. In the view-mode `else` branch (currently a single `BlockViewer` call at line 332), replace with a `when (block.blockType)` dispatch. For this task, only implement the `"bullet"` and `"paragraph"` branches pointing to the existing `BlockViewer`; all other branches fall through to `BlockViewer` as a safe default. This establishes the dispatch structure without any visual change.

**Effort estimate**: 1h

---

#### Task 2.2 — `HeadingBlock` composable
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/HeadingBlock.kt` (new)

```kotlin
@Composable
internal fun HeadingBlock(content: String, level: Int, onStartEditing: () -> Unit, modifier: Modifier = Modifier)
```

Strip the leading `# ` / `## ` / etc. markers from `content` before rendering. Map `level` to `MaterialTheme.typography`: 1 → `displaySmall`, 2 → `headlineLarge`, 3 → `headlineMedium`, 4 → `headlineSmall`, 5 → `titleLarge`, 6 → `titleMedium`.

Run `content` through the existing inline `parseMarkdownWithStyling` so bold/italic within headings still works. Wrap in `BasicText` with `clickable { onStartEditing() }`.

Wire `"heading"` branch in `BlockItem` dispatch to this composable, passing `level` parsed from `block.blockType` (stored as JSON or a simple `"heading:2"` format — see ADR-001 for the chosen encoding).

**Effort estimate**: 2-3h

---

#### Task 2.3 — `ThematicBreakBlock` composable
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ThematicBreakBlock.kt` (new, or inline in `BlockItem`)

Single `HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))` with `MaterialTheme.colorScheme.outlineVariant` color. Tap starts editing. Wire `"thematic_break"` branch.

**Effort estimate**: 0.5h

---

#### Task 2.4 — `CodeFenceBlock` composable
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/CodeFenceBlock.kt` (new)

```kotlin
@Composable
internal fun CodeFenceBlock(content: String, language: String, onStartEditing: () -> Unit, modifier: Modifier = Modifier)
```

Layout:
- Outer `Box` with `background(MaterialTheme.colorScheme.surfaceVariant)`, `shape = RoundedCornerShape(4.dp)`, padding 12.dp
- Optional language label (`Text` in top-right corner using `labelSmall` typography, shown only when `language.isNotBlank()`)
- `SelectionContainer` wrapping a `BasicText` with `FontFamily.Monospace`, `softWrap = false`, inside `horizontalScroll(rememberScrollState())`
- Strip the opening ` ``` lang ` and closing ` ``` ` markers from `content` before display (these are stored in `block.content` as part of the raw markdown)
- Tap on the outer `Box` (outside the `SelectionContainer`) → `onStartEditing()`

Pitfall: `SelectionContainer` captures tap gestures; the outer `Box.pointerInput` tap target handles editing entry only when user taps outside the text itself.

**Effort estimate**: 2-3h

---

#### Task 2.5 — `BlockquoteBlock` composable
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockquoteBlock.kt` (new)

```kotlin
@Composable
internal fun BlockquoteBlock(content: String, onStartEditing: () -> Unit, onLinkClick: (String) -> Unit, modifier: Modifier = Modifier)
```

Layout: `Row` with a 3dp-wide `Box` on the left using `MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)`, then inline `WikiLinkText` with the `> ` prefix stripped. One-frame lag of `drawBehind` is avoided by using an opaque `Box` instead.

**Effort estimate**: 1-2h

---

#### Task 2.6 — `OrderedListItemBlock` composable
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/OrderedListItemBlock.kt` (new, or as a small function in `BlockItem`)

`Row` with a `Text("${number}.")` fixed-width prefix (`width = 32.dp`) followed by `WikiLinkText` for the content (with leading `1. ` stripped). Derive `number` from `block.blockType` discriminator string.

**Effort estimate**: 1h

---

#### Task 2.7 — Wire `contentType` in `LazyColumn` block list
**File**: The screen(s) that render block lists in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/`

Locate `LazyColumn` calls that render `BlockItem`. Add `contentType = { block.blockType }` to each `items { }` or `item { }` lambda. This hints the Compose slot pool to reuse composables of the same type, reducing recomposition on large pages.

**Effort estimate**: 1h

---

### Story 3 — Table Renderer
**Goal**: Implement `TableBlock` composable with horizontal scroll, column alignment, and a header row.

**Files touched**: `TableBlock.kt` (new), `BlockItem.kt` dispatch

**Acceptance criteria**:
- Header row renders with bold text and a bottom border
- Body rows alternate background tints
- Columns respect `:---`, `:---:`, `---:` alignment markers
- Table scrolls horizontally when wider than screen
- iOS nested-scroll conflict mitigated (see pitfall note)

---

#### Task 3.1 — Parse table structure from `block.content`
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TableBlock.kt` (new)

Write a `parseTableContent(raw: String): TableData` function (pure, no Composable annotation). `TableData` holds `headers: List<String>`, `alignments: List<Alignment>`, `rows: List<List<String>>`.

The parsing is simple line-splitting: split `content` on `\n`, first line is headers (split on `|`), second line is separator (derive alignment from `:` positions), remaining lines are rows. Strip the stored ` ```table ` wrapper if `BlockParser` stores it that way, or assume `TableBlockNode.content` already contains the inner pipe table.

Verify against `BlockNodes.kt` to understand what `TableBlockNode` stores in its `content` field — adjust stripping accordingly.

**Effort estimate**: 2h

---

#### Task 3.2 — `TableBlock` composable
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TableBlock.kt`

```kotlin
@Composable
internal fun TableBlock(content: String, onStartEditing: () -> Unit, modifier: Modifier = Modifier)
```

Layout using `horizontalScroll(rememberScrollState())` wrapping a `Column` with `wrapContentWidth(unbounded = true)`. Each row is a `Row`. Each cell is a `Box(modifier = Modifier.widthIn(min = 80.dp).padding(horizontal = 8.dp, vertical = 4.dp))` containing `WikiLinkText` with the cell string.

Header row: wrap in `Box` with `drawBehind` bottom border using `MaterialTheme.colorScheme.outline`.

Alignment: use `Arrangement.Start` / `Arrangement.Center` / `Arrangement.End` on the cell `Box` via a helper that maps `TableData.Alignment`.

Column widths: use `SubcomposeLayout` only if intrinsic measurement is needed. For Phase 1, equal-width columns with `weight(1f)` inside the unbounded `Row` is acceptable.

iOS pitfall: `horizontalScroll` inside a vertically scrolling `LazyColumn` triggers CMP#4279. Mitigate by wrapping the scroll `Modifier` conditionally — or accept the known limitation and document in code with a TODO linking to the bug.

**Effort estimate**: 3-4h

---

### Story 4 — Image Renderer + Inline Improvements
**Goal**: Integrate Coil 3 for `ImageNode`, fix subscript/superscript styling, and improve the LaTeX monospace fallback.

**Files touched**: `kmp/build.gradle.kts`, `MarkdownEngine.kt`, `BlockViewer.kt`, `BlockItem.kt` dispatch, new `ImageBlock.kt`, new `GraphRootPathProvider.kt`

**Acceptance criteria**:
- Images referenced by absolute URL load asynchronously with a loading placeholder and error state
- Images referenced by relative Logseq `../assets/` paths resolve correctly against the graph root
- Subscript and superscript render with `BaselineShift` and reduced font size
- LaTeX delimiters `$...$` and `$$...$$` are stripped; content renders in monospace with italic style
- No new crashes on missing or malformed image URLs

---

#### Task 4.1 — Add Coil 3 to `build.gradle.kts`
**File**: `kmp/build.gradle.kts`

In `commonMain` dependencies:

```kotlin
implementation("io.coil-kt.coil3:coil-compose:3.2.0")
implementation("io.coil-kt.coil3:coil-network-ktor3:3.2.0")
```

Coil 3 is a full KMP rewrite. The `coil-network-ktor3` artifact pulls in `ktor-client-core`; verify there is no version conflict with existing Ktor usage. If Ktor is not yet in the project, add `implementation("io.ktor:ktor-client-core:3.1.3")` to `commonMain` and platform-specific engine artifacts (`ktor-client-okhttp` for JVM/Android, `ktor-client-darwin` for iOS).

**Effort estimate**: 1-2h (includes dependency resolution verification)

---

#### Task 4.2 — `GraphRootPathProvider` CompositionLocal
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/GraphRootPathProvider.kt` (new)

```kotlin
val LocalGraphRootPath = compositionLocalOf<String?> { null }
```

Provide the graph root directory path at the top of the page screen composable so that any nested composable (including `ImageBlock`) can resolve relative paths without threading a parameter through every composable in the hierarchy.

**Effort estimate**: 0.5h

---

#### Task 4.3 — Custom Coil fetcher for relative Logseq asset paths
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcher.kt` (new)

Implement `Fetcher<Uri>` that intercepts URIs whose path starts with `../assets/` or `./assets/`. Resolve against `LocalGraphRootPath.current` to produce an absolute file path, then delegate to Coil's built-in `FileFetcher`.

Register the fetcher in a custom `ImageLoader` singleton built once per graph load. Expose via a `rememberImageLoader(graphRoot: String?)` composable function that memoises by `graphRoot`.

**Effort estimate**: 2-3h

---

#### Task 4.4 — `ImageBlock` composable and `BlockItem` dispatch
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ImageBlock.kt` (new)

```kotlin
@Composable
internal fun ImageBlock(url: String, altText: String, imageLoader: ImageLoader, onStartEditing: () -> Unit, modifier: Modifier = Modifier)
```

Use `AsyncImage(model = url, contentDescription = altText, imageLoader = imageLoader)` with:
- `contentScale = ContentScale.FillWidth` and `Modifier.fillMaxWidth()`
- `placeholder = painterResource(Res.drawable.placeholder_image)` (or a `CircularProgressIndicator` inside a `Box`)
- `error = painterResource(Res.drawable.error_image)` fallback

Tap outside the image → `onStartEditing()`.

Note: `ImageNode` currently only contributes an annotation in `MarkdownEngine.kt`. For standalone image blocks (blocks whose only content is `![alt](url)`), `BlockItem` should dispatch to `ImageBlock` via the `"paragraph"` branch detecting an `ImageNode` as sole child, or via the block-type system if `BlockParser` produces a dedicated image block type. Check `BlockNodes.kt` to confirm whether standalone images are `ParagraphBlockNode` or a distinct type; adjust accordingly.

**Effort estimate**: 2-3h

---

#### Task 4.5 — Subscript and superscript inline styling
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`

In the `SubscriptNode` and `SuperscriptNode` cases, replace the current no-op/text-only handling with:

```kotlin
is SubscriptNode -> {
    withStyle(SpanStyle(baselineShift = BaselineShift(-0.3f), fontSize = 0.75.em)) {
        append(node.text)
    }
}
is SuperscriptNode -> {
    withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)) {
        append(node.text)
    }
}
```

**Effort estimate**: 0.5h

---

#### Task 4.6 — Improved LaTeX monospace fallback
**File**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`

For `LatexInlineNode`:
1. Strip `$` delimiters from `node.formula`
2. Apply `SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic, background = codeBg)`
3. Add a `// TODO(latex-phase2): replace with full renderer when KMP LaTeX library is available` comment

**Effort estimate**: 0.5h

---

## Known Issues

### Concurrency Risk: `blockType` column missing from FTS triggers [SEVERITY: Low]

**Description**: SQLDelight generates FTS update triggers that reference all columns by name. After the migration adds `block_type`, the existing trigger definitions in `SteleDatabase.sq` (`blocks_ai`, `blocks_ad`, `blocks_au`) do not need modification because they only index `content` — but a developer may mistakenly add `block_type` to the FTS index column list, causing trigger SQL errors.

**Mitigation**: Add a comment in `SteleDatabase.sq` above the FTS table definition noting that `block_type` is intentionally excluded from FTS indexing.

---

### Data Integrity: Existing database rows after migration [SEVERITY: Medium]

**Description**: The migration `ALTER TABLE blocks ADD COLUMN block_type TEXT NOT NULL DEFAULT 'bullet'` sets all existing rows to `"bullet"`. Pages that were already loaded and saved will have incorrect `blockType` for headings, code fences, etc. until the page is reloaded from disk.

**Mitigation**: `GraphLoader.externalFileChanges` already re-parses on disk change. On first open after the migration, `GraphLoader` will re-parse and overwrite with correct `block_type` values. Document this in migration comments. Add a migration validation test that confirms the DEFAULT is `"bullet"`.

---

### Integration Risk: `BlockParser` table content format unknown at planning time [SEVERITY: Medium]

**Description**: Task 3.1 assumes `TableBlockNode.content` contains a raw pipe-table string. If `BlockParser` stores header cells and rows as structured fields on the AST node, the `parseTableContent()` function is unnecessary.

**Mitigation**: Before beginning Story 3, read `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/BlockNodes.kt` `TableBlockNode` definition. Adjust Task 3.1 to either parse from the content string or map directly from AST node fields. The `reconstructContent()` call in `MarkdownParser.convertBlock()` converts back to a string, so the DB always holds the raw pipe-table text — `parseTableContent()` is correct as designed.

---

### Performance Risk: `parseTableContent()` called on every recompose [SEVERITY: Low]

**Description**: Table parsing runs inside the `TableBlock` composable. If the composable recomposes frequently, this string-parsing work runs on each frame.

**Mitigation**: Wrap with `remember(content) { parseTableContent(content) }` so parsing only re-runs when `content` changes.

---

### Platform Risk: Coil 3 Ktor engine version conflict [SEVERITY: Medium]

**Description**: `coil-network-ktor3` depends on `ktor-client-core`. If SteleKit adds Ktor independently (or a future dependency does), version alignment is required.

**Mitigation**: In Task 4.1, run `./gradlew dependencies --configuration commonMainImplementation` after adding Coil to verify no `FAILED` resolution or version conflict. Pin Ktor to `3.1.3` explicitly in `commonMain` to ensure alignment.

---

### UX Risk: Heading blocks lose the `BlockGutter` bullet/collapse toggle [SEVERITY: Low]

**Description**: `BlockItem` renders `BlockGutter` (bullet, collapse, drag handle) unconditionally for all blocks. Headings in Logseq are children of an implicit page-level root and do not have a traditional bullet. Showing the bullet gutter next to `# Heading` may look odd.

**Mitigation**: In `BlockItem`, when `blockType == "heading"`, pass `hasChildren = false` (or a new `showBullet = false` parameter) to `BlockGutter` to suppress the bullet dot while retaining the drag handle and collapse toggle. Defer visual polish to a follow-up ticket if the team is satisfied with the gutter showing.

---

## Dependency Visualization

```
Story 1 (Model & Data Layer)
  ├── Task 1.1  (BlockType sealed class)
  ├── Task 1.2  (Block.blockType field)   ← depends on 1.1
  ├── Task 1.3  (DB migration)            ← depends on 1.2
  ├── Task 1.4  (MarkdownParser mapping)  ← depends on 1.1
  ├── Task 1.5  (GraphLoader mapping)     ← depends on 1.1, 1.4
  └── Task 1.6  (Repository adapter)     ← depends on 1.3

Story 2 (Dispatch + "no-library" blocks)  ← depends on all Story 1
  ├── Task 2.1  (BlockItem dispatch skeleton)
  ├── Task 2.2  (HeadingBlock)            ← depends on 2.1
  ├── Task 2.3  (ThematicBreakBlock)      ← depends on 2.1
  ├── Task 2.4  (CodeFenceBlock)          ← depends on 2.1
  ├── Task 2.5  (BlockquoteBlock)         ← depends on 2.1
  ├── Task 2.6  (OrderedListItemBlock)    ← depends on 2.1
  └── Task 2.7  (LazyColumn contentType) ← depends on 2.1

Story 3 (Table)                           ← depends on Story 2 (dispatch)
  ├── Task 3.1  (parseTableContent)
  └── Task 3.2  (TableBlock composable)  ← depends on 3.1

Story 4 (Images + Inline)                ← depends on Story 2 (dispatch); Stories 3 and 4 can run in parallel
  ├── Task 4.1  (Coil 3 dependency)
  ├── Task 4.2  (LocalGraphRootPath)
  ├── Task 4.3  (SteleKitAssetFetcher)     ← depends on 4.1, 4.2
  ├── Task 4.4  (ImageBlock + dispatch)  ← depends on 4.1, 4.2, 4.3
  ├── Task 4.5  (Subscript/Superscript)  ← independent
  └── Task 4.6  (LaTeX fallback)         ← independent
```

---

## Integration Checkpoints

**After Story 1**: Run `./gradlew jvmTest`. All existing tests must pass. Manually open a page containing headings and verify `block.blockType` is populated (add a temporary `isDebugMode` log or inspect via the DB).

**After Story 2**: Screenshot test (Roborazzi) against a fixture page with H1-H6 headings, a code fence, a blockquote, a thematic break, and an ordered list. Verify no regression in existing inline rendering (bold, italic, wiki links).

**After Story 3**: Screenshot test against a fixture page with a multi-column table including alignment markers. Verify horizontal scroll activates on narrow viewport.

**After Story 4**: Manual test on JVM and Android (iOS if available) with a page containing web-URL images and a page with relative `../assets/` image references. Verify loading placeholder shows, error state shows on 404.

---

## Context Preparation Guides

### Before starting Story 1

Read these files in full:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/BlockNodes.kt` — confirm all nine `BlockNode` subtype names and their properties (especially `HeadingBlockNode.level`, `CodeFenceBlockNode.language`, `OrderedListItemBlockNode.number`, `TableBlockNode` fields)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` — understand `convertBlock()` call site and `reconstructContent()` behavior
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` — find the `ParsedBlock` → `Block` mapping site

### Before starting Story 2

Read:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt` — understand the view-mode branch (lines 287-350) where `BlockViewer` is currently called
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt` — understand `parseMarkdownWithStyling()` signature to call it from `HeadingBlock`

### Before starting Story 3

Read:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/ast/BlockNodes.kt` — inspect `TableBlockNode` fields
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` `reconstructContent()` for table blocks

### Before starting Story 4

Read:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt` — current `ImageNode`, `LatexInlineNode`, `SubscriptNode`, `SuperscriptNode` handling
- `kmp/build.gradle.kts` — current dependency list to verify Ktor absence before adding Coil
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` or the page screen composable — find where `CompositionLocalProvider` should wrap to supply `LocalGraphRootPath`
