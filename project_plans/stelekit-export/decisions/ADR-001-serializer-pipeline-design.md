# ADR-001: Serializer Pipeline Design

**Status**: Accepted
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

---

## Context

The export feature must produce four output formats from the same `Page` + `List<Block>` input: clean Markdown, plain text, HTML, and JSON. The primary design question is whether to use a typed-interface-per-format architecture or a visitor pattern over the inline AST.

Two options were evaluated:

**Option A — `PageExporter` interface with four implementations**

```kotlin
interface PageExporter {
    val formatId: String
    val displayName: String
    fun export(page: Page, blocks: List<Block>): String
}
```

Each format is an independent class (`MarkdownExporter`, `PlainTextExporter`, `HtmlExporter`, `JsonExporter`) that walks the `List<Block>` (pre-sorted by `BlockSorter.sort()`) and calls `InlineParser` per block to get a `List<InlineNode>` it can serialize in its own way.

**Option B — Visitor pattern over the `InlineNode` AST**

A single `ExportVisitor<T>` interface with methods `visitText`, `visitBold`, `visitWikiLink`, etc. Four concrete visitor implementations produce different output types. The block-tree walk is a shared skeleton.

---

## Decision

**Option A: `PageExporter` interface with four independent implementations.**

---

## Rationale

1. **Reuse of existing pattern**: `GraphWriter.savePageInternal()` already uses a local recursive `writeBlocks()` function over `List<Block>`. The export pipeline replicates the same tree-walk structure, making Option A consistent with established conventions in the codebase.

2. **Per-format control over block structure**: The block-level structure differences between formats are significant:
   - Markdown needs YAML frontmatter + paragraph vs. bullet logic based on `block.level` and child count.
   - HTML needs proper open/close `<ul>/<li>` nesting tracked via a depth counter.
   - JSON serializes the tree structure, not a string rendering.
   - Plain text does flat indented lines with no structural markup.
   These structural differences argue against a shared skeleton — each exporter needs full control over how it handles nesting and block boundaries, not just leaf node rendering.

3. **Testability**: Each `PageExporter` implementation is a pure `(Page, List<Block>) -> String` function with no side effects. Unit tests can call `MarkdownExporter().export(page, blocks)` directly with in-memory fixtures, without any framework setup.

4. **Simpler than visitor for this use case**: The visitor pattern excels when the same traversal is shared across many operations. Here, only four operations exist and they diverge significantly at the block level. A visitor would share a traversal that needs to be overridden in almost every method for HTML vs. Markdown.

5. **`reconstructContent()` already exists**: `MarkdownParser.reconstructContent(List<InlineNode>)` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` already implements the inline-AST-to-Markdown serializer. The `MarkdownExporter` can reuse this function directly, with Logseq-specific transforms applied as a preprocessing pass over the `InlineNode` list.

---

## Consequences

- New package: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/`
- Files: `PageExporter.kt` (interface), `MarkdownExporter.kt`, `PlainTextExporter.kt`, `HtmlExporter.kt`, `JsonExporter.kt`, `ExportService.kt`
- `ExportService` takes a `List<PageExporter>` and a `ClipboardProvider` so new formats can be added without modifying existing code (Open-Closed principle).
- `InlineParser` will be called once per block inside each exporter — this is a pure in-memory operation and adds negligible cost.
- The visitor pattern remains available as a future refactoring if a fifth format is added that shares significant inline-node logic with an existing exporter.
