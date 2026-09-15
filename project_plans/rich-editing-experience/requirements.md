# Requirements: rich-editing-experience

**Date**: 2026-07-05
**Type**: feature addition (journey mapping + UX audit + toolbar/shortcut overhaul)
**Complexity**: 4 — cross-cutting change (all editing surfaces × all platforms), Large appetite

## Problem Statement

SteleKit's editing experience (block editor, mobile toolbar, keyboard shortcuts, annotation editor, voice-mode input) has grown incrementally with no single map of the user journeys it supports, and no systematic audit of whether those journeys are as efficient (fewest taps/keystrokes) and as discoverable as they should be. Users may be taking more steps than necessary to do common things (insert a tag, insert a link, format text, insert an image, reorder a block, insert a code block/table, toggle a TODO) and may not know some capabilities exist at all, especially on mobile.

## Baseline

Current state (confirmed by codebase survey, 2026-07-05):
- **Main block editor** (`BlockEditor.kt`): hardware-keyboard shortcuts for Bold/Italic/Strikethrough/Highlight/Code/Link (Ctrl+B/I/S/H/E/K), arrow/Enter/Tab/Escape/Backspace/Home/End navigation, Ctrl+Left/Right word nav. Autocomplete triggers for `[[` (wiki-link) and `#` (hashtag) exist for both hardware and soft keyboards.
- **Mobile toolbar** (`MobileBlockToolbar.kt`): three-row layout — expandable formatting overflow row (Bold/Italic/Strikethrough/Code/Highlight/Quote/Numbered-list/Heading), primary row (Outdent/Indent/wiki-link insert/Suggest-tags/Attach-image/Capture-photo), bottom row (Undo/Redo/Move-up/down/Add-block/Paste). Separate selection-mode toolbar (copy/cut/delete/clear) and left-handed layout mirroring.
- **Tag insertion**: two independent mechanisms — typed `#`/`[[` autocomplete, and an explicit "Suggest tags" button that surfaces LLM/local semantic suggestions via `SuggestionBottomSheet` (shipped in `tag-suggestion-trigger` project, PR #185).
- **Command palette**: no formatting-specific entries (no way to trigger Bold/Italic/Link/etc. from the command palette).
- **Annotation editor** (`AnnotationEditorScreen.kt`, `AnnotationToolbar.kt`, `TagEditorPanel.kt`): a structurally separate editing surface for PDF/asset annotations, not audited alongside the main editor before now.
- **Voice-mode input**: separate input modality (see `mobile-voice-mode` project), not previously considered as part of the same "editing experience" journey map.
- **No `docs/journeys/` directory exists yet** — no prior journey documentation to build on or duplicate.
- No feature-flag mechanism exists in the codebase; the existing `TelemetryDatabase` tracks performance/query stats, not user-action usage counters.

## Users / Consumers

All SteleKit end users across Desktop (JVM), Android, iOS, and Web who create or edit notes — the block editor is the primary daily-use surface of the app. Secondary: users who annotate PDFs/assets, and users who use voice input to create content.

## Success Metrics

- Every editing journey in scope (main block editor, annotation editor, voice-mode input) is documented in `docs/journeys/` with a stable `journey_id`.
- Each journey has a defined **step-count benchmark** (target max taps/keystrokes per platform) and is measured against it; journeys exceeding target are logged as gaps.
- Each journey also passes a **heuristic usability review** (Nielsen-style: visibility of system status, consistency, discoverability, minimal memory load) via the `ux-expert` agent.
- A prioritized, ranked gap backlog is produced covering every platform (Desktop/Android/iOS/Web) with equal weight — no gap list is platform-biased.
- The highest-priority gaps identified are implemented in this same project (not spun off) — this is a full overhaul, not an audit-only deliverable.
- Before/after comparison: for at least the core journeys called out by the user (tag insertion on mobile being the flagship example), the implemented fixes measurably reduce step count or close a discoverability gap identified in the audit.

## Appetite

Large (3–6 weeks)
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

## Constraints

- No new dependencies — build on the existing Compose Multiplatform + Arrow stack already in `commonMain`.
- Must preserve existing architecture rules from `CLAUDE.md`: Arrow `Either` at repository boundaries, `@DirectSqlWrite` write gating, `PlatformDispatcher` dispatcher matrix, no `rememberCoroutineScope()` escaping composition.
- iOS remains Gradle-only (no Bazel KMP support yet); Web/WASM has a partial/single-threaded runtime — audit findings must account for platform-specific constraints rather than assuming full parity is always achievable.
- Solo-maintainer project — no dedicated design/QA team; the `ux-expert` agent stands in for a dedicated UX reviewer.

## Non-functional Requirements

- **Performance SLO**: no toolbar/shortcut change may cause recomposition on every keystroke (existing rule already enforced for `EditorToolbar` — do not regress it).
- **Scalability**: not applicable (per-user local editing surface).
- **Security classification**: internal.
- **Data residency**: no special requirements.

## Scope

### In Scope
- Journey mapping (`docs/journeys/`) for: main block editor (Journals + PageView), annotation editor, voice-mode input — across Desktop, Android, iOS, Web.
- Step-count benchmarking and heuristic usability review of every mapped journey.
- A ranked gap backlog (missing shortcuts, missing toolbar affordances, discoverability issues, inconsistent platform parity).
- Implementation of the highest-priority fixes identified by the audit — new/changed toolbar buttons, new/changed keyboard shortcuts, new/changed command-palette entries, mobile UI clarity improvements.
- Full SDD phases 2–7 (research, plan, validate, implement, verify, ship) covering the above.

### Out of Scope
- Real-time/collaborative multi-user editing (not applicable — SteleKit is local-first, single-user per graph).
- Adding a feature-flag system purely for this project (none exists today; use normal PR review + verify-phase testing as the risk control instead).
- Changing the underlying LLM tag-suggestion algorithm/providers (already covered by prior `llm-provider`/`llm-service` projects).
- New telemetry infrastructure beyond what already exists in `TelemetryDatabase` (see Observability below).

## Rabbit Holes

- Soft-keyboard shortcut detection differs by platform (`detectSoftKeyboardBracketWrap` exists specifically because IME behavior isn't uniform) — any new shortcut must be verified on both hardware and soft keyboards, not assumed to transfer.
- "All platforms equally" prioritization could balloon scope if research finds materially different gaps per platform — Phase 3 planning must explicitly sequence epics (e.g., by platform or by journey) rather than attempting one undifferentiated mega-task.
- The annotation editor and voice-mode input are structurally separate code paths from the main block editor — treating them as "the same editing experience" for journey mapping is intentional per this requirements doc, but Phase 3 planning should not assume shared implementation, only shared UX principles.
- Roborazzi screenshot tests and Compose UI tests require a display (`xvfb-run` in headless/CI) — any UI change here must budget for updating/adding screenshot baselines.
- Command-palette formatting entries (currently absent) may require new command-palette architecture, not just new entries — verify before committing to a step-count target that assumes they exist.

## Alternatives Considered

1. **Journey-mapping only, defer implementation** — rejected per user's explicit choice of "Audit + full overhaul."
2. **Mobile-only overhaul** — rejected; user chose "all platforms equally" to avoid a mobile-biased backlog.
3. **Heuristic review only (no step-count targets)** — rejected; user chose "both" so the audit produces an objective, measurable claim in addition to qualitative findings.

## Feasibility Risks

- No prior journey documentation exists — Phase 2 research must build the journey map from scratch (higher research cost than a project with existing `docs/journeys/` to update).
- No feature-flag system — any risky UI change ships directly; mitigate via strong Phase 6 verify-phase UX/behavioral checks (Playwright → claude-in-chrome → ui-playwright fallback) before shipping.
- Cross-platform parity claims are easy to assert and hard to verify without a physical device pass for Android/iOS — Phase 6 verify should flag any finding that could not be device-verified.

## Observability Requirements

Standard request/error logging is sufficient for the implementation itself. No new instrumentation is required to ship this project. As an optional follow-on (not blocking this project), the existing `TelemetryDatabase` could be extended with lightweight toolbar/shortcut usage counters to validate the audit's step-count assumptions against real usage post-launch — captured here as a future idea, not in scope.

## Risk Control

No feature-flag system exists in this codebase (see Constraints). Risk control for this project is: incremental PRs per epic (not one giant PR), full Phase 6 verify pass (idiom review, architecture review, correctness/security/observability gate, UX/behavioral verification) before each ship, and manual device testing for Android/iOS-specific changes before merge. Rollback is standard `git revert` per PR — no data migration or irreversible state is introduced by this project.

## Open Questions

- Should the ranked gap backlog use a fixed step-count target (e.g., "≤2 taps for any single-block action") applied uniformly, or a per-journey target set during research based on what's actually achievable per platform? (Defer to Phase 2 research to propose a concrete rubric.)
- Should annotation-editor and voice-mode fixes be sequenced as later epics (after main block editor) given they're lower-traffic surfaces, or interleaved? (Defer to Phase 3 planning.)
