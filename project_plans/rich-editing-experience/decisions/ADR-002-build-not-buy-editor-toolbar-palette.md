# ADR-002: Build the toolbar/shortcut/command-palette overhaul from scratch; do not adopt a third-party rich-editor library

**Status**: Accepted
**Date**: 2026-07-05
**Project**: rich-editing-experience

## Context

This project extends `BlockEditor.kt`'s hand-rolled `BasicTextField` wrapper, plain `when`-based `handleKeyEvent()` dispatch table, and `CommandPalette.kt`'s hand-rolled ordered-subsequence fuzzy matcher (a legitimate fzf/VS-Code-style 3-tier bucket-sort algorithm, not naive substring search) across a Large-appetite, cross-platform (Desktop/Android/iOS/Web) scope. Requirements.md's Constraints section mandates "no new dependencies — build on the existing Compose Multiplatform + Arrow stack already in `commonMain`." Three third-party candidates were evaluated (build-vs-buy.md):

1. **`mikepenz/multiplatform-markdown-renderer`** — display-only (does not address editing). Already named in project memory (`project_render_all_markdown.md`) as a library the team previously decided against.
2. **`halilozercan/compose-richtext`** — Android+Desktop only, **no iOS target** — fails the hard cross-platform requirement outright. Also already named in project memory as avoid.
3. **`MohamedRejeb/compose-rich-editor`** (`richeditor-compose`) — the only candidate with genuine cross-target (Android/iOS/Desktop/Web) editing support and trigger-popup infrastructure. Its core data model is `RichTextState`, an annotated-string/span-based WYSIWYG document — architecturally incompatible with SteleKit's markdown-source-of-truth model (files → `OutlinerPipeline` parser → `TextFieldValue` → `GraphWriter` writes raw markdown back). Adopting it would require maintaining a lossy bidirectional `RichTextState↔markdown` converter at every block boundary, plausibly more code than extending the current dispatch table, plus it provides no command-palette component and is still pre-1.0 (`1.0.0-rc14`).

A second, pre-existing hand-rolled rich-editor stack already exists in this repo (`editor/RichTextEditor.kt` + `editor/text/`, `editor/format/` packages, addressed separately in ADR-001), confirming "hand-rolled Compose editor" is an established, repeated pattern here, not a one-off.

## Decision

**Build from scratch, extending the existing hand-rolled patterns** in `BlockEditor.kt` (`handleKeyEvent` dispatch cascade, `FormatAction`/`applyFormatAction`) and `CommandPalette.kt` (existing fuzzy matcher, `Command` data class). No new dependency is introduced by this project. If command-palette entry count later grows to hundreds+, borrow *scoring conventions* (contiguous-run/word-boundary bonuses) from fzf/fuzzaldrin-plus literature as local code, not as a new dependency — noted here as a forward-looking escape hatch, not a current action item.

## Consequences

- **Positive**: Zero new dependency risk (licensing, transitive-dependency bloat, WASM/JS compatibility unknowns, maintenance-abandonment risk of a pre-1.0 library). Consistent with requirements.md's explicit constraint and the repo's twice-demonstrated convention.
- **Positive**: No architectural mismatch risk — the markdown-source-of-truth model (`GraphWriter` writes raw markdown) is preserved exactly as-is; no lossy converter layer is introduced.
- **Negative / accepted cost**: Every new formatting/structural affordance (Phase C/E/F work) must be hand-implemented as a new `FormatAction`/`StructuralAction` case rather than gained "for free" from a library's built-in feature set (e.g. `compose-rich-editor`'s existing autocomplete-popup infra). Accepted because the volume of genuinely new primitives needed (per Phase A's audit) is expected to be small (single-digit new `FormatAction`/`StructuralAction` cases), per architecture.md's confirmation that this is "pure UI/interaction layer... no exception found" to the existing text-mutation-into-`onContentChange` data flow.
- **Reversibility**: If a future project finds the hand-rolled approach unsustainable (e.g. dozens of new affordances needed at once), re-evaluating `compose-rich-editor` once it reaches 1.0 is a reasonable future ADR — not precluded by this decision, just not adopted now.

## Alternatives Considered

- **`MohamedRejeb/compose-rich-editor`** — rejected: `RichTextState` WYSIWYG model architecturally incompatible with markdown-source-of-truth; no command-palette component; pre-1.0.
- **`halilozercan/compose-richtext`** — rejected: no iOS target, fails hard cross-platform requirement; previously rejected per project memory.
- **`mikepenz/multiplatform-markdown-renderer`** — rejected: display-only, doesn't address editing at all; previously rejected per project memory.
