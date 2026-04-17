# Requirements: Multi-Word Term Highlighting & Unlinked References

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-14

## Problem Statement

SteleKit highlights single-word page titles as link suggestions while the user reads blocks in view mode. The underlying `AhoCorasickMatcher` already supports multi-word patterns and word-boundary enforcement at the outer edges of a term — but the pipeline that feeds it or applies it may not correctly surface multi-word page names (e.g. "Andrew Underwood", "KMP SDK") in practice. This blocks the related unlinked-references workflow: per-page and global flows that let users review and accept/reject candidate links cannot reliably surface compound-name suggestions if the matcher never fires on them. The primary users are knowledge-graph users who author dense, interconnected notes and want the graph to stay well-connected without manual link hunting.

## Success Criteria

- Multi-word page titles (≥2 tokens) are highlighted in `BlockViewer` exactly like single-word titles — same colour, same click-to-link popup.
- Clicking a highlighted multi-word term produces the `[[Page Title]]` wrap with correct character positions (no off-by-one for terms containing spaces).
- The per-page unlinked-references panel (`ReferencesPanel`) correctly surfaces blocks containing multi-word matches.
- A global unlinked-references flow (command palette entry or dedicated screen) lists every unlinked reference across all pages and allows the user to accept/reject each one.
- Accepting a suggestion in either flow wraps the matched text in `[[…]]` and persists to disk.
- No user-visible latency regression: highlighting on a page with 200+ blocks remains imperceptible.

## Scope

### Must Have (MoSCoW)

- Fix multi-word term highlighting in `BlockViewer` so compound page names are highlighted inline.
- Fix any position mapping issues when the confirmed `[[…]]` wrap is applied to multi-word matches.
- Wire the per-page unlinked-references panel to correctly count/return multi-word matches (`getUnlinkedReferences` in `SqlDelightSearchRepository`).
- Implement a global unlinked-references flow: a command or screen that aggregates all unlinked references across all loaded graphs and pages.
- Accept/reject UX for each suggestion in both per-page and global flows (wraps on confirm, skips on reject).

### Out of Scope

- Automatic silent linking (all linking requires explicit user confirmation).
- Fuzzy / approximate / stemmed matching — only exact case-insensitive page title matches.
- Alias or page-property matching — primary page title only.
- Creating new pages from a highlighted span.

## Constraints

- **Tech stack**: Kotlin Multiplatform + Compose Multiplatform only; no platform-specific native UI, no new Gradle dependencies without justification.
- **Editor integration**: Must integrate with existing `BlockEditor` / `BlockStateManager` / `OutlinerPipeline` / `AhoCorasickMatcher` / `PageNameIndex` stack. Do not replace or fork these.
- **Performance**: Highlighting runs on every recompose of every visible block. The `AhoCorasickMatcher` is immutable and shared — O(text length) per block is acceptable; O(page count) per block is not.
- **Persistence**: Accept-link writes go through the existing `GraphWriter.saveBlock()` path.

## Context

### Existing Work

- `AhoCorasickMatcher` (domain layer) — already supports multi-word patterns; uses word-boundary check only at the outer edges of the full term. Thread-safe and immutable after construction.
- `PageNameIndex` — reacts to `PageRepository.getAllPages()`, rebuilds matcher on background thread when page set changes. Excludes journal pages and names shorter than 3 chars.
- `StelekitViewModel.suggestionMatcher` — exposes `PageNameIndex.matcher` as a `StateFlow<AhoCorasickMatcher?>` to the UI.
- `BlockViewer` / `MarkdownEngine` — receives `suggestionMatcher`, applies it to visible blocks in view mode using `AnnotatedString` spans.
- `LinkSuggestionPopup` — left-click popup for accepting a single suggestion; does positional `[[…]]` wrap.
- `SuggestionContextMenu` — right-click menu with link / skip / navigate-all options.
- `ReferencesPanel` — per-page panel with paginated `getUnlinkedReferences` (block repository call).
- No prior global unlinked-references screen has been built.

### Stakeholders

- Solo developer / primary user (Tyler Stapler).
- Any future SteleKit users who rely on the knowledge-graph linking workflow.

## Research Dimensions Needed

- [ ] Stack — evaluate whether `AhoCorasickMatcher` needs changes for multi-word, or if the bug is upstream (index build, `MarkdownEngine` span application, position mapping).
- [ ] Features — survey how Logseq, Roam, Obsidian, and Notion handle unlinked-reference UX and multi-word term suggestion flows.
- [ ] Architecture — design the global unlinked-references aggregation: where it lives in the ViewModel, how it pages/streams results, how accept/reject dispatches writes.
- [ ] Pitfalls — known failure modes: overlapping matches, multi-word terms whose words also exist as single-word pages, position drift after partial edits, performance with large page sets.
