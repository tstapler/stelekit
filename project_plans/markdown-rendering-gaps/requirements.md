# Requirements: Markdown Rendering Gaps Audit

**Date**: 2026-07-27
**Type**: Bug fix / audit of existing feature (block-level Markdown rendering)

## Problem Statement

A user reported (via screenshot) that a page in SteleKit rendered several lines with
literal `#` characters instead of styled headings. This session already root-caused
and fixed one instance of the bug: ATX headings (`# Foo`) written as the content of an
outline bullet (`- # Foo`) rendered with a literal `#` because
`BlockParser.parseBlock()` only checked for the ATX heading marker *before*
bullet-token consumption, never after. That fix (adding `tryConsumeAtxHeadingMarker()`,
checking it post-bullet-consumption, threading `indentLevel` through
`HeadingBlockNode`) is implemented and test-covered in the working tree, but not yet
committed/PR'd.

The user's original ask was to fix "all of these markdown rendering gaps" (plural).
It is not yet confirmed whether the same structural bug (a block-level construct's
detection logic running only before bullet-token consumption, or a correctly-detected
`BlockType` never getting a Compose UI renderer) affects other constructs: fenced code
blocks, blockquotes, ordered lists, thematic breaks, tables when they decorate a
bullet's content, and/or whether `BlockItem.kt`'s dispatch has a live gap between what
`BlockType` variants the parser emits and what has a dedicated Composable.

## Users / Consumers

End users (human note-takers) viewing Markdown-formatted outline pages in the SteleKit
desktop/Android/iOS/Web app. No downstream systems are affected — this is purely a
rendering-correctness bug in the read-mode (non-editing) block view.

## Success Metrics

- Every block-level construct that Logseq/CommonMark allows as bullet-decorated
  content is detected correctly by `BlockParser` regardless of whether it decorates a
  bullet or stands alone at top level.
- Every `BlockType` variant the parser can emit has a corresponding dedicated
  Composable in the `BlockItem.kt` dispatch (no unexpected fallback to generic
  bullet/paragraph text rendering for a correctly-classified block).
- Regression tests exist for each gap found and fixed, and `./gradlew jvmTest` /
  targeted `--tests` runs pass with visible green output.
- If no further gaps exist beyond the already-fixed heading bug, that is reported
  clearly with the verification evidence (audit trail of checks performed), not
  papered over with speculative changes.

## Constraints

- Do not redo or duplicate the already-completed ATX heading fix (already in the
  working tree, uncommitted).
- Follow existing code conventions in `parsing/BlockParser.kt`,
  `parsing/ast/BlockNodes.kt`, `parser/MarkdownParser.kt`,
  `model/ParsedModels.kt` (`BlockType`), and `ui/components/BlockItem.kt` and its
  sibling block Composables.
- Kotlin Multiplatform: any UI fix must work across Desktop/Android/iOS/Web targets
  (no platform-specific branches).
- No completion claims without running `./gradlew jvmTest` (or narrower
  `--tests` filters) and `./gradlew ciCheck` before shipping, per repo CLAUDE.md
  engineering-discipline rules.
- Git hygiene: never `git add -A` / `git add .`; stage only touched files; PR opened
  as a draft by default.

## Scope

### In Scope
- Audit `BlockParser.kt` for the same "marker check only fires before bullet-token
  consumption" structural bug across: fenced code blocks (```` ``` ````), blockquotes
  (`>`), ordered list items (`1.`), thematic breaks (`---`/`***`/`___`), and tables
  (`| a | b |`) when used as bullet-decorated content.
- Audit `model/ParsedModels.kt` (`BlockType`) and `ui/components/BlockItem.kt` dispatch
  for parity — does every `BlockType` variant the parser can emit route to a real
  Composable, or does any correctly-classified type fall through to a generic
  bullet/paragraph renderer?
- Fix any gaps found, following the same pattern as the heading fix (parser-level
  structural fix + threading any needed metadata through the AST/model + regression
  tests).
- Ship a PR (draft) covering only the newly-found-and-fixed gaps, on top of the
  current working tree state (including the already-fixed heading bug, since it is
  uncommitted).

### Out of Scope
- Re-implementing or re-verifying the already-fixed ATX heading bug.
- New Markdown syntax not already supported by the parser (e.g. block embeds,
  transclusion — see prior `project_plans/render-all-markdown/` project for that
  separate, larger effort).
- Editor/edit-mode rendering (`BlockEditor.kt`) — this audit is scoped to read-mode
  (view) rendering only, matching the shape of the original bug report.
- Inline-level markdown (bold/italic/code spans/wikilinks) — the reported bug and the
  known fix are both block-level; inline rendering is a separate, already-mature code
  path (`InlineParser.kt` / `MarkdownEngine.kt`) unless investigation surfaces a
  directly analogous inline bug.

## Open Questions

- Does `project_plans/render-all-markdown/` (an earlier, broader planning effort for
  block-renderer coverage, with ADRs already drafted) fully describe the current state
  of `BlockItem.kt`'s dispatch, or has the code diverged since those ADRs were
  written? Needs verification against current source, not assumed from the ADRs.
- Are there other reported/observed instances of literal Markdown syntax leaking
  through in the original screenshot beyond headings that haven't been described in
  text (the screenshot itself is not available in this text-only session)?
