# ADR-001: Block Clipboard Serialization Format

**Status**: Accepted  
**Date**: 2026-04-16  
**Feature**: Copy / Cut / Paste Blocks

## Context

When a user copies or cuts one or more blocks (with their children), we need a clipboard representation that:
1. Carries the full block tree structure (hierarchy, content, properties, blockType)
2. Can be pasted within the same SteleKit instance with new UUIDs (standard paste)
3. Can be pasted as block references preserving original UUIDs (embed paste, Ctrl+Shift+V)
4. Degrades gracefully — a plain-Markdown fallback lets users paste into other apps

Two candidate formats were evaluated:
- **Option A**: Custom JSON envelope (`application/x-stelekit-blocks`) holding a `List<Block>` serialized tree, with a plain-text Markdown fallback on the same clipboard transfer.
- **Option B**: Plain Markdown only — the existing `MarkdownExporter` output, re-parsed on paste via `MarkdownParser`.

## Decision

**Option A — JSON envelope with Markdown fallback.**

The clipboard will carry two flavors simultaneously:
- `text/plain` — Logseq-compatible Markdown (indented bullet list) via `MarkdownExporter`, so pasting into external apps works.
- A private in-process `BlockClipboard` object held in `BlockStateManager` (not the system clipboard) — a `List<ClipboardBlock>` value class wrapping `Block` + depth, enabling lossless round-trip for internal paste operations.

The system clipboard receives only the Markdown text flavor. The in-process clipboard is a `MutableStateFlow<BlockClipboard?>` inside `BlockStateManager`, which is the single source of truth for block state already in scope at every paste site.

## Rationale

- **Option B (Markdown-only)** would lose block UUIDs, properties, blockType discriminators, and nesting precision for deeply-nested trees after a parse round-trip. The existing `MarkdownParser` / `MarkdownExporter` pipeline is lossy for internal block metadata.
- An in-process store avoids serialization complexity for the 99% case (copy-paste within the same session) while still writing Markdown to the system clipboard for external interop.
- Holding the clipboard in `BlockStateManager` rather than a new singleton avoids new global state; `BlockStateManager` already owns selection state which drives copy/cut.
- Paste as reference (`Ctrl+Shift+V`) reuses the same `BlockClipboard` but skips UUID regeneration — a single boolean flag on the paste call site.

## Consequences

- In-process clipboard is **session-scoped**: closing and reopening the app clears it. Pasting after restart falls back to the system Markdown clipboard, which will be re-parsed (lossy).
- The `ClipboardProvider` interface currently only writes (`writeText`, `writeHtml`). Reading from the system clipboard (for external Markdown paste) requires extending `ClipboardProvider` with `readText(): String?`. This extension is scoped to Task 1.1.
- Paste of external Markdown text from the system clipboard is a separate code path (parse → insert as new blocks with new UUIDs).

## Patterns Applied

- **Value Object** — `ClipboardBlock(block: Block, relativeDepth: Int)` is a pure data container with no identity.
- **Null Object / Optional** — `BlockClipboard?` as nullable state avoids a separate "clipboard empty" sentinel.
- **Facade** — `BlockClipboardService` wraps the serialize/deserialize/paste logic, shielding `BlockStateManager` from format details.
