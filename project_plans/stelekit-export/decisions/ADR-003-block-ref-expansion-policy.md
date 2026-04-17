# ADR-003: Block Reference Expansion Policy

**Status**: Accepted
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

---

## Context

Logseq stores block references as `((block-uuid))` in block content. The AST parser produces `BlockRefNode(blockUuid: String)`. When exporting, there are three strategies for how to render these references:

**Option A — Expand inline**: Resolve the UUID to the target block's text content and emit it inline in the output, replacing the `((uuid))` syntax.

**Option B — Leave as plain text**: Emit `((uuid))` verbatim, or convert to a placeholder like `[block ref]`.

**Option C — Convert to link**: Emit a Markdown/HTML link that preserves the UUID as an anchor or footnote reference, so the relationship is traceable.

---

## Decision

**A two-tier policy based on export format:**

| Format | Policy | Output |
|--------|--------|--------|
| Markdown | Expand to resolved text with `[^1]` footnote annotation, or `[block ref]` if unresolvable | `resolved block text[^1]` |
| Plain Text | Expand to resolved text with no annotation, or `[block ref]` if unresolvable | `resolved block text` |
| HTML | Expand to resolved text inside `<blockquote data-block-ref="uuid">`, or `<span class="unresolved-ref">[block ref]</span>` | structured, traceable |
| JSON | Emit a structured object: `{"type":"block_ref","uuid":"...","resolvedText":"..."}` | lossless, round-trippable |

**Circular reference guard**: Track a `Set<String>` of visited UUIDs during expansion. If a UUID is already in the set, emit `[circular ref]` and stop recursion. Maximum depth: 3 levels.

**Dangling reference handling**: If `BlockRepository.getBlockByUuid(uuid)` returns null, emit `[block ref]` for human-readable formats, `{"type":"block_ref","uuid":"...","resolvedText":null}` for JSON. Never crash or emit raw UUIDs in human-readable output.

**Resolution strategy**: Pre-fetch all `BlockRefNode` UUIDs found in the page's block content before starting serialization. Pass the resulting `Map<String, String>` (uuid → first-line content) into the exporter so resolution is O(1) per reference during serialization, not a DB call per reference.

---

## Rationale

1. **Expand > preserve UUID**: Raw UUIDs (`6b5a2f3d-...`) are meaningless to external readers. Any human-readable format must resolve or replace them.

2. **Pre-fetch prevents DB call amplification**: A page with 50 block references that each reference blocks on other pages would trigger 50 sequential `suspend` DB calls during a synchronous string-building loop. Pre-fetching all UUIDs in a single bulk query (or multiple parallel queries grouped by page) keeps export latency bounded.

3. **Depth limit prevents stack overflow**: Logseq allows circular embeds; the codebase does not currently guard against them during rendering (`MarkdownEngine` relies on the repository cache). A depth-3 cap matches Logseq's own rendering behavior.

4. **JSON preserves structure**: JSON consumers (scripts, migration tools) may want to re-resolve references themselves. Emitting `resolvedText` alongside the `uuid` gives them both options.

5. **Format-specific output**: HTML's `<blockquote>` conveys the "quoted from another block" semantic that Logseq users understand. Plain text keeps it minimal. Markdown footnotes avoid breaking prose flow while still attributing the source.

---

## Consequences

- `ExportService` gains a `resolveBlockRefs(blocks: List<Block>): Map<String, String>` step before calling the exporter, which calls `BlockRepository.getBlockByUuid()` for each unique `BlockRefNode` UUID found in the page.
- `PageExporter.export()` signature gains an optional `resolvedRefs: Map<String, String> = emptyMap()` parameter.
- `HtmlExporter` and `MarkdownExporter` receive the pre-fetched map; they do not make repository calls themselves.
- `ExportService` runs on `Dispatchers.Default` (suspend function) to allow the async ref pre-fetch without blocking the UI thread.
- A new `ExportService.collectBlockRefUuids(blocks: List<Block>): Set<String>` helper extracts all `BlockRefNode` UUIDs via `InlineParser` over each block's `content`.
