# Requirements: SteleKit Export

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-13

## Problem Statement

SteleKit users want to share page content with others (docs, emails, chats, etc.) but the raw Markdown stored on disk is block-indented and Logseq-flavored — it's awkward to paste directly into external tools. The feature mirrors Logseq's export capability to make copying and exporting page content convenient.

**Primary user**: SteleKit desktop user sharing content externally.

## Success Criteria

- A user can export the currently open page in multiple human-readable formats with one action
- A user can select a block subtree and export just that subset
- Output is clean enough to paste into Notion, GitHub, email, or a rich-text editor without manual cleanup
- Feature ships on JVM/Desktop; mobile is a follow-on

## Scope

### Must Have (MoSCoW)
- Single-page export (currently open page → clipboard or file)
- Block-level selection export (export selected block subtree, not just the whole page)
- Formatted Markdown output (clean headings, bullet nesting, links — no Logseq-specific syntax)
- Plain text output (strips all markup)
- HTML output (for rich-text editors: email, Confluence, Google Docs)
- JSON / structured data output (block tree + properties + metadata, machine-readable)

### Out of Scope
- Sync / live export to external services (Notion, Roam, etc.)
- Import from other tools
- Custom user-defined export templates
- PDF export
- Graph-wide bulk export (may be a later phase)

## Constraints

- **Tech stack**: KMP / Compose UI only — no new native dependencies; must work in existing Kotlin Multiplatform Compose architecture
- **Target platform**: Desktop (JVM) first; mobile follow-on
- **Dependencies**: Prefer existing Gradle dependencies; avoid adding new ones if possible

## Context

### Existing Work
No prior research or decisions have been made. This is a greenfield feature.

### Stakeholders
- SteleKit desktop users who copy content to share externally
- Tyler Stapler (owner/developer)

## Research Dimensions Needed

- [ ] Stack — evaluate clipboard API options in KMP, existing Markdown/HTML serialization libs already in the project
- [ ] Features — survey Logseq's export formats and output structure; look at comparable tools (Obsidian, Notion)
- [ ] Architecture — design patterns for a serializer pipeline (block tree → format); UI entry points (context menu, toolbar, keyboard shortcut)
- [ ] Pitfalls — known failure modes: block reference expansion, nested property handling, unicode/emoji, large page performance
