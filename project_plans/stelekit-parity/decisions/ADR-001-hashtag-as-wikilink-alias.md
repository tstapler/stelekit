# ADR-001: Hashtag Parsing Strategy — TagNode as First-Class Page Reference

**Status**: Accepted
**Date**: 2026-04-16
**Feature**: Hashtag Links (`#tag` / `#[[multi word tag]]`)

---

## Context

Logseq treats `#tag` as syntactic sugar for `[[tag]]`. The two forms are semantically identical: both create a page reference that appears in the target page's backlinks. The `#[[multi word tag]]` form extends this to multi-word page names.

SteleKit already has:
- `TagNode` in `InlineNodes.kt` — the AST node is present but treated as a styled annotation only
- `InlineParser.parseTag()` — parses `#word` into `TagNode`; handles terminator characters (`,`, `.`, etc.)
- `MarkdownEngine.kt` — renders `TagNode` with link color and a `TAG_TAG` annotation
- `BlockViewer.WikiLinkText` — taps on `TAG_TAG` already call `onLinkClick(tag.item)` (line 147)
- `BacklinkRenamer` — only rewrites `[[OldName]]`; does not rewrite `#OldName` or `#[[OldName]]`
- `extractReferences()` in `MarkdownParser.kt` — already adds `TagNode.tag` to the `references` list (line 229)

Three strategies were evaluated:

**Option A — Expand hashtags to `[[wikilink]]` at parse time**: During `InlineParser.parseTag`, immediately emit a `WikiLinkNode` instead of a `TagNode`. The `#` prefix is lost; round-trip fidelity requires tracking whether the source used `#` or `[[]]`.

**Option B — Keep `TagNode` distinct; treat it as a page reference at the repository layer**: `TagNode` remains a separate AST node; the reference extraction pipeline, backlink renamer, and autocomplete are taught to treat it identically to `WikiLinkNode` in terms of page-link semantics. The `#` syntax round-trips cleanly.

**Option C — Normalize `#tag` → `[[tag]]` in the serializer layer only**: `reconstructContent()` in `MarkdownParser.kt` emits `[[tag]]` whenever a `TagNode` is encountered, discarding the original `#` syntax at storage time.

## Decision

**Option B — Keep `TagNode` as a first-class page-reference node; extend all page-link pipelines to treat it identically to `WikiLinkNode`.**

Specifically:
1. `InlineParser.parseTag` gains `#[[multi word tag]]` support (bracket form).
2. `BacklinkRenamer` gains a `replaceHashtag(content, oldName, newName)` function parallel to `replaceWikilink`.
3. `BlockEditor` autocomplete trigger regex gains a `#` trigger path mirroring the `[[` path.
4. The `TAG_TAG` annotation already navigates to the tag's page via `onLinkClick` — no UI changes needed for click navigation.
5. `extractReferences()` already includes `TagNode.tag` — reference tracking is already correct.

## Rationale

| Criterion | Option A | Option B | Option C |
|---|---|---|---|
| Round-trip fidelity | Breaks — `#tag` becomes `[[tag]]` | Preserved — `#tag` stays `#tag` | Breaks — same as A |
| AST expressiveness | Loses distinction between syntax forms | Full — `TagNode` vs `WikiLinkNode` is meaningful | Loses distinction |
| Backlink tracking | Automatic (same node type) | Requires explicit parallel handling | Automatic (same node type) |
| Autocomplete | Same as wikilink | New trigger needed | Same as wikilink |
| Rename scope | `replaceWikilink` already handles | New `replaceHashtag` needed | `replaceWikilink` already handles |
| Diff/export tools | `[[tag]]` in exported content | `#tag` in exported content — matches Logseq | `[[tag]]` in exported content |

Option B preserves syntactic fidelity with Logseq's file format. Users who write `#idea` in their Logseq graph will see `#idea` in SteleKit — not `[[idea]]`. This is critical for file-format compatibility: SteleKit writes files that Logseq can read, and vice versa.

Option A and C would silently mutate existing Logseq graphs on first load, which is unacceptable.

## Consequences

**Positive:**
- Hashtags render correctly as clickable links today (the `TAG_TAG` annotation path already calls `onLinkClick`).
- File format compatibility with Logseq is preserved — `#tag` in source is `#tag` in written files.
- `extractReferences()` is already correct — no change needed for backlink counting.

**Negative / Watch-outs:**
- `BacklinkRenamer` needs a parallel `replaceHashtag` path and must apply both rewrites when renaming a page. Missing this causes broken hashtag links after rename.
- `BlockEditor` autocomplete for `#` needs to be added — typing `#` should trigger the same page-search autocomplete as `[[`.
- The `#[[multi word tag]]` form requires lexer-level lookahead after `HASH`: if the next token is `L_BRACKET`, delegate to a `parseWikiLinkAsHashtag` path.

## Patterns Applied

- **Visitor pattern**: All pipelines that walk `InlineNode` trees (reference extraction, backlink renamer, serializer) gain a `TagNode` arm — consistent with the existing dispatch pattern.
- **Open/Closed Principle**: `BacklinkRenamer` is extended with `replaceHashtag` without modifying the existing `replaceWikilink` function.
- **Parallel Grammar Forms**: `TagNode(tag)` and `WikiLinkNode(target)` are distinct Value Objects that share semantic meaning at the domain layer (both are page references) but differ at the syntax layer.
