# Requirements: Render All Markdown Blocks

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-13

## Problem Statement

SteleKit's improved markdown parser now produces AST nodes for many block types, but the Compose UI layer lacks renderers for most of them. End users who rely on SteleKit for Logseq-style note-taking encounter raw/unparsed block content where formatted output is expected. The exact set of block types the parser currently covers is not fully known — a coverage audit is needed as part of research.

## Success Criteria

- Every block type that the parser produces has a corresponding Compose Multiplatform renderer
- No parser output falls through to a fallback/raw-text display (100% parser↔renderer parity)
- All priority block types (code, tables, math, callouts, images) render correctly for end users on all KMP targets (Desktop JVM, Android, iOS, Web)

## Scope

### Must Have (MoSCoW)
- Fenced code blocks (``` with optional language tag)
- Markdown pipe tables
- Math / LaTeX blocks (`$$` display math and inline `$...$`)
- Callouts / Admonitions (`> [!NOTE]`, `> [!WARNING]`, etc.)
- Images (`![alt](url)` and asset references)

### Should Have
- Task list items (`- [ ]` / `- [x]`) if not already rendering
- All other block types the parser currently emits

### Out of Scope
- Block embeds / transclusion (`{{embed [[page]]}}`) — separate feature
- Real-time collaborative editing
- New markdown syntax not currently supported by the parser
- Platform-specific native renderers (must use Compose Multiplatform only)

## Constraints

- **Tech stack**: Kotlin Multiplatform + Compose Multiplatform — renderers must work on all targets (Desktop JVM, Android, iOS, Web); no platform-specific branches
- **Dependencies**: Prefer no new third-party libraries; if a library is needed (e.g., math rendering), it must support all KMP targets
- **Timeline**: Not specified
- **Team**: Solo / small team

## Context

### Existing Work
- An improved markdown parser has been implemented (branch: `stelekit-render-all-markdown`)
- Parser produces block-level AST nodes; exact coverage of block types is unknown and needs to be audited
- Some block types may already have renderers; a gap analysis (parser output vs. renderer coverage) is the first technical step

### Stakeholders
- End users (note-takers) who use SteleKit to view Logseq-compatible markdown notes and expect fully rendered content

## Research Dimensions Needed

- [ ] Stack — evaluate KMP-compatible rendering libraries (math, syntax highlighting, image loading) and their multiplatform support
- [ ] Features — survey what block types Logseq supports and how comparable KMP apps (e.g., Multiplatform Markdown renderers) handle them
- [ ] Architecture — how to structure the renderer dispatch (sealed class dispatch, visitor pattern, composable lookup map), and where rendering lives in the layered architecture
- [ ] Pitfalls — known failure modes: math rendering on iOS/Web, image async loading in Compose, table overflow/scrolling, fenced code with no language tag
