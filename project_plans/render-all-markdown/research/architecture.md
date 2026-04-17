# Architecture Research: Block Renderer Dispatch

## Option A: Re-detect at Render Time

Re-inspect `block.content` at render time to infer the block type (e.g., `startsWith("#")` → heading, triple-backtick fence → code block, pipe-delimited rows → table). No model changes required.

**Pros**

- Zero schema migration — `Block` stays as `content: String` + `level: Int`.
- Zero DB changes — no new column, no migration script, no SQLDelight schema diff.
- Cheap to prototype; gets visible rendering improvements in one PR.
- Content string is always the source of truth, so re-detection is deterministic.
- Detection heuristics are the same ones the parser already uses, so logic duplication is bounded.

**Cons**

- Detection runs on every recomposition — code fence scanning and table detection are O(n) string operations executed inside a `@Composable` that may recompose frequently.
- Heuristics are fragile. A blockquote whose first line is `> # Heading` is simultaneously multiple types. Ambiguous content (e.g., a single `---` line) requires ordering rules.
- Multiline blocks (code fences, tables) cannot be detected from a single-line content string unless the full raw block text is preserved. The current `content` field on `Block` is a single line (Logseq-style outliner), which means code fences that span children would need to scan the subtree — significant complexity.
- Testing the renderer requires constructing raw string fixtures rather than typed model instances.
- The detection logic becomes a second parser, creating dual-maintenance burden alongside `LogseqParser`.

**Implementation sketch**

```kotlin
@Composable
fun BlockItem(block: Block) {
    val type = remember(block.content) { detectBlockType(block.content) }
    when (type) {
        BlockRenderType.HEADING    -> HeadingBlock(block)
        BlockRenderType.CODE_FENCE -> CodeFenceBlock(block)
        BlockRenderType.TABLE      -> TableBlock(block)
        BlockRenderType.BLOCKQUOTE -> BlockquoteBlock(block)
        else                       -> InlineTextBlock(block)
    }
}

fun detectBlockType(content: String): BlockRenderType = when {
    content.startsWith("#")   -> BlockRenderType.HEADING
    content.startsWith("```") -> BlockRenderType.CODE_FENCE
    content.startsWith(">")   -> BlockRenderType.BLOCKQUOTE
    // table: heuristic — has at least one pipe-delimited row
    content.contains("|")     -> BlockRenderType.TABLE
    else                      -> BlockRenderType.PARAGRAPH
}
```

`remember(block.content)` prevents re-detection on unrelated recompositions.

---

## Option B: Preserve Block Type in Model

Add a `blockType` field to `ParsedBlock` and `Block`, propagate through the parser pipeline, persist in the DB schema, and dispatch at render time on the typed field.

**Pros**

- Single parse pass — type is computed once by `LogseqParser`, never again.
- Render dispatch is O(1) — a `when` on an enum/sealed value, no string scanning.
- Typed model makes the render contract explicit; adding a new block type is a compile-error-guided workflow.
- Unit tests for the renderer can use typed fixtures (`Block(type = HEADING, content = "# Foo")`).
- Enables future features that need type at the repository layer (e.g., search filtering by heading, export logic).
- Aligns with how reference implementations (mikepenz/multiplatform-markdown-renderer) work: the library's `MarkdownElement` dispatch function receives a typed AST node, not a raw string.

**Cons**

- Requires a SQLDelight schema migration — one new column (`block_type TEXT NOT NULL DEFAULT 'PARAGRAPH'`). This is low-risk but involves touching `SteleDatabase.sq`, regenerating queries, and writing a migration.
- `Block` model gains a dependency on a type enum/sealed class shared between parser and DB layers.
- Existing persisted data has no type annotation — on first load after migration, blocks will all be `PARAGRAPH` until re-parsed. A re-parse on graph open is needed (or the migration sets `DEFAULT` sensibly and a background re-parse runs).
- Small increase in DB storage (one TEXT column per block row).

**Implementation sketch**

```kotlin
// model/BlockType.kt
enum class BlockType {
    PARAGRAPH, HEADING, CODE_FENCE, TABLE,
    BLOCKQUOTE, ORDERED_LIST_ITEM, THEMATIC_BREAK, RAW_HTML
}

// model/Block.kt
data class Block(
    val id: String,
    val content: String,
    val level: Int,
    val blockType: BlockType = BlockType.PARAGRAPH,
    // ... other fields
)

// parser: set type during convertBlock()
fun convertBlock(node: BlockNode): ParsedBlock = ParsedBlock(
    content  = node.rawContent,
    level    = node.level,
    blockType = when (node) {
        is HeadingBlockNode       -> BlockType.HEADING
        is CodeFenceBlockNode     -> BlockType.CODE_FENCE
        is TableBlockNode         -> BlockType.TABLE
        is BlockquoteBlockNode    -> BlockType.BLOCKQUOTE
        is OrderedListItemBlockNode -> BlockType.ORDERED_LIST_ITEM
        is ThematicBreakBlockNode -> BlockType.THEMATIC_BREAK
        is RawHtmlBlockNode       -> BlockType.RAW_HTML
        else                      -> BlockType.PARAGRAPH
    }
)

// UI dispatch
@Composable
fun BlockItem(block: Block) {
    when (block.blockType) {
        BlockType.HEADING    -> HeadingBlock(block)
        BlockType.CODE_FENCE -> CodeFenceBlock(block)
        BlockType.TABLE      -> TableBlock(block)
        BlockType.BLOCKQUOTE -> BlockquoteBlock(block)
        else                 -> InlineTextBlock(block)
    }
}
```

SQLDelight migration:

```sql
-- migration 2.sqm
ALTER TABLE Block ADD COLUMN block_type TEXT NOT NULL DEFAULT 'PARAGRAPH';
```

---

## Option C: AST Alongside String

Keep `content: String` for editing fidelity, and store (or lazily compute at render time) a full typed AST alongside it — e.g., a `parsedAst: BlockNode?` field on the in-memory `Block`, re-derived from `content` on demand.

**Pros**

- Editing and rendering concerns are fully separated: the editor always binds to `content`, the renderer always reads the AST.
- No DB schema change required if the AST is kept purely in-memory (lazy-parse on load).
- Can support incremental re-parsing: only re-parse blocks whose `content` hash changed.
- Maximum rendering fidelity — the AST carries richer information (e.g., heading level as `Int`, fence language tag) that a type enum alone does not.

**Cons**

- Memory overhead: each `Block` in the list holds an additional parsed object graph. For large graphs (thousands of blocks) this matters.
- Re-parsing on every graph load duplicates work already done during file import.
- The AST type (`BlockNode`) lives in the parser module; exposing it on the `Block` model couples two layers that should be independent.
- Lazy-parse during rendering (`remember { parse(block.content) }`) introduces parser calls on the main thread, which conflicts with Compose's expectation of cheap `remember` lambdas.
- More complex: requires wiring a parsing strategy into the Block model or ViewModel layer.

**Implementation sketch**

```kotlin
// ViewModel holds parsed representation, not the DB Block directly
data class BlockUiState(
    val block: Block,            // raw DB model (for editing)
    val rendered: BlockNode      // parsed AST (for rendering)
)

// In ViewModel, after loading blocks:
val uiBlocks = blocks.map { block ->
    BlockUiState(block, parser.parseBlock(block.content))
}

// Renderer dispatches on BlockNode subtype
@Composable
fun BlockItem(state: BlockUiState) {
    when (val node = state.rendered) {
        is HeadingBlockNode   -> HeadingBlock(node, state.block)
        is CodeFenceBlockNode -> CodeFenceBlock(node, state.block)
        is TableBlockNode     -> TableBlock(node, state.block)
        else                  -> InlineTextBlock(state.block)
    }
}
```

---

## Recommended Approach

**Option B (Preserve Block Type in Model)** is the best fit for SteleKit.

Rationale:

1. **The parser already produces typed nodes.** `LogseqParser` already distinguishes `HeadingBlockNode`, `CodeFenceBlockNode`, etc. The information exists at parse time — `MarkdownParser.convertBlock()` is actively discarding it. Option B simply stops discarding it.

2. **O(1) render dispatch matters in a LazyColumn.** Block items are rendered in a virtualized list. String-scanning heuristics in `detectBlockType()` (Option A) execute on every item that enters the viewport. For a graph with thousands of blocks, this is unnecessary CPU work on the main thread.

3. **The schema migration is small and low-risk.** One `ALTER TABLE ... ADD COLUMN block_type TEXT NOT NULL DEFAULT 'PARAGRAPH'` is a trivial SQLDelight migration. The default covers all pre-existing rows; a re-parse of each file on first open (which already happens) will backfill correct types.

4. **Option C couples layers unnecessarily.** Exposing `BlockNode` (a parser type) on the in-memory `Block` model (a domain/DB type) creates unwanted coupling. Option B uses a simple enum/sealed class that both layers can share without coupling.

5. **Prior art confirms this pattern.** The `mikepenz/multiplatform-markdown-renderer` library — the most widely used Compose multiplatform markdown renderer — uses exactly this approach: a typed AST node is the input to the rendering function, and a `when` branch dispatches to per-type composables. Re-detecting from strings at render time is explicitly not how it works.

**Migration path**

1. Add `BlockType` enum to the shared model module.
2. Add `blockType` column to `SteleDatabase.sq` with `DEFAULT 'PARAGRAPH'`.
3. Update `MarkdownParser.convertBlock()` to propagate type from `BlockNode` subtypes.
4. Update `BlockItem.kt` to dispatch on `block.blockType`.
5. Implement per-type composables (`HeadingBlock`, `CodeFenceBlock`, `TableBlock`, `BlockquoteBlock`).
6. On graph open, existing blocks will re-parse and get correct types written back through normal save paths (or a dedicated re-index step).

---

## Compose Heterogeneous List Pattern

Jetpack Compose `LazyColumn` natively supports heterogeneous item rendering without adapters or ViewHolders — a major simplification over the RecyclerView era.

**The canonical pattern** (from Android Developers docs and community practice):

```kotlin
LazyColumn {
    items(
        items       = blocks,
        key         = { block -> block.id },          // stable key for recomposition identity
        contentType = { block -> block.blockType }    // hint for slot reuse
    ) { block ->
        BlockItem(block)   // dispatch happens inside BlockItem
    }
}
```

Key points:

- **`key`**: Providing a stable, unique key (e.g., block ID) lets Compose track item identity across dataset changes (add/remove/reorder). Without it, Compose falls back to position-based identity, causing unnecessary full recompositions on list mutations.
- **`contentType`**: When items have different composable structures (heading vs. code fence vs. table), `contentType` tells Compose which slots are compatible for reuse. Compose maintains separate slot pools per content type, preventing layout thrashing when different block types scroll in and out of the viewport. This is the primary performance lever for heterogeneous lists.
- **Avoid heavy work inside `itemContent`**: Pre-compute anything expensive (type detection, formatting) before it reaches the lambda.

For SteleKit specifically, `block.blockType` maps directly to `contentType`, making Option B the natural fit for optimal LazyColumn performance.

---

## Sealed-Class Dispatch Pattern

The standard Compose pattern for sealed-class dispatch is a top-level `@Composable` function acting as a router:

```kotlin
@Composable
fun BlockRenderer(block: Block) {
    when (block.blockType) {
        BlockType.HEADING -> HeadingRenderer(block)
        BlockType.CODE_FENCE -> CodeFenceRenderer(block)
        BlockType.TABLE -> TableRenderer(block)
        BlockType.BLOCKQUOTE -> BlockquoteRenderer(block)
        BlockType.ORDERED_LIST_ITEM -> OrderedListItemRenderer(block)
        BlockType.THEMATIC_BREAK -> ThematicBreakRenderer(block)
        BlockType.RAW_HTML -> RawHtmlRenderer(block)
        BlockType.PARAGRAPH -> ParagraphRenderer(block)
    }
}
```

Design principles:

- **`when` must be exhaustive**: Using a sealed class or enum forces the compiler to flag unhandled cases. This is a compile-time safety net as new block types are added to the parser.
- **Each branch is a focused composable**: `HeadingRenderer`, `CodeFenceRenderer`, etc. each own their own visual logic, preview annotations, and tests.
- **Avoid logic in the `when` itself**: The router should only route. Conditional logic (e.g., "render heading only if level <= 3") belongs inside the per-type composable.
- **Composable stability**: If `Block` is a data class with `val` fields and `BlockType` is an enum, Compose can treat `Block` as stable, enabling smart recomposition (only the composable whose block changed will recompose).

The mikepenz/multiplatform-markdown-renderer library uses this exact pattern — its `MarkdownElement` function is a `when` over an `ASTNode` type, delegating to `MarkdownBlockWithLinks`, `CodeFence`, `MarkdownTable`, etc.

---

## Sources

- [Creating a heterogeneous list with Jetpack Compose — Francesc Vilarino Guell, Medium](https://fvilarino.medium.com/creating-a-heterogeneous-list-with-jetpack-compose-138d3698c4cc)
- [Build a list using multiple item types — Android Developers](https://developer.android.com/develop/ui/compose/quick-guides/content/build-list-multiple-item-types)
- [LazyColumn Performance Optimization — key, contentType & Recomposition Control, DEV Community](https://dev.to/myougatheaxo/lazycolumn-performance-optimization-key-contenttype-recomposition-control-5h3b)
- [A Simple key to a Better LazyList in Jetpack Compose — Shreyas Patil](https://blog.shreyaspatil.dev/a-simple-key-to-a-better-lazylist-in-jetpack-compose)
- [mikepenz/multiplatform-markdown-renderer — GitHub](https://github.com/mikepenz/multiplatform-markdown-renderer)
- [Markdown Element Rendering — mikepenz/multiplatform-markdown-renderer, DeepWiki](https://deepwiki.com/mikepenz/multiplatform-markdown-renderer/3-markdown-element-rendering)
- [Markdown Rendering and Recursive Composition — Halil Ozercan, ITNEXT](https://itnext.io/markdown-rendering-and-recursive-composition-e76cca3631e8)
- [Best Practices for Composition Patterns in Jetpack Compose — getstream.io](https://getstream.io/blog/composition-pattern-compose/)
- [Lists and grids — Jetpack Compose, Android Developers](https://developer.android.com/develop/ui/compose/lists)
