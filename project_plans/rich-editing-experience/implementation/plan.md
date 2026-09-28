# Implementation Plan: rich-editing-experience

**Feature**: Journey-map every editing surface (main block editor, annotation editor, voice-mode input) across Desktop/Android/iOS/Web, audit each journey against a JTBD-weighted step-count + Nielsen-heuristic rubric, and implement the highest-priority gaps via `FormatAction` extension, mobile toolbar rows, keyboard shortcuts, and command-palette entries — while fixing a live recomposition-SLO bug and retiring the orphaned `editor/` command framework.
**Date**: 2026-07-05
**Status**: Ready for implementation
**ADRs**: ADR-001-extend-live-format-system-not-orphaned-framework, ADR-002-build-not-buy-editor-toolbar-palette

---

## Step 0.5 — Alternatives Considered (epic/architecture structuring)

1. **Sequential phase-locked** (chosen) — Phases A→H run strictly in order; each phase's tasks are gated on the prior phase's artifacts (backlog, bug fix, enum extension, etc.). *Strength*: matches architecture.md §5's evidence-based rollout sequencing exactly — no feature-flag system exists, so minimizing concurrently-in-flight changes to the same files (`BlockEditor.kt`, `MobileBlockToolbar.kt`, `CommandPalette.kt`) is the only real risk control available. *Weakness*: long critical path — nothing in Phases C–H can start until Phase A's audit lands, which risks stalling wall-clock progress on a Large (3–6wk) appetite if Phase A runs long.
2. **Parallel-track by structural independence** (rejected) — split into Track 1 (Phases B–F, all touching `BlockEditor`/`MobileBlockToolbar`/`CommandPalette`, sequential within the track) running concurrently with Track 2 (Phase G, annotation editor — architecture.md §2 confirms zero shared code) and Track 3 (Phase H, orphaned-framework cleanup — separate files). *Strength*: exploits real code-ownership boundaries to shorten wall-clock time. *Reason rejected*: the task brief explicitly mandates "Structure the plan around this rollout sequencing... as your Phase/Epic backbone — do not re-derive a different sequencing from scratch." Phase G still depends on its own Phase-A-shaped audit landing first, so the parallelism gain is marginal for a solo maintainer who works serially in practice; the plan instead notes Phase G and Phase H tasks as *independently startable* once their own gating stories are done (see Dependency Visualization) rather than restructuring the phase order.
3. **Vertical-slice per JTBD journey** (rejected) — ship each journey (e.g. "insert tag") end-to-end (toolbar + shortcut + palette entry) in one PR, repeated per journey, instead of horizontal phases (all toolbar work, then all shortcut work, then all palette work). *Strength*: every PR is a complete, demoable, user-facing improvement rather than partial infrastructure. *Reason rejected*: directly contradicts architecture.md §5's explicit, evidence-based recommendation — a naive full-stack-per-journey PR touches more files per PR (`BlockEditor.kt` + `MobileBlockToolbar.kt` + `CommandPalette.kt`/`AppState.kt` simultaneously) with no feature-flag safety net, which is the exact risk the research flagged as unacceptable given the demonstrated same-day fix-forward history on this surface (commits `27fdc421c6`, `2b3b13a951`).

**Decision**: Approach 1 (sequential phase-locked), matching the prescribed Phase A→H backbone, with Phase G/H noted as schedulable in parallel with C–F once their own gating tasks complete (an optimization, not a restructuring).

---

## Domain Glossary
| Term | Definition | Notes |
|------|-----------|-------|
| `FormatAction` | Existing enum (`JournalsViewModel.kt:26-37`) of markdown prefix/suffix text-mutation actions (BOLD, ITALIC, STRIKETHROUGH, HIGHLIGHT, CODE, LINK, QUOTE, NUMBERED_LIST, HEADING). | Extension point for Phase C. |
| `applyFormatAction` | Existing function (`BlockEditor.kt:491`) — the single mutator that turns a `FormatAction` + `TextFieldValue` into a new `TextFieldValue`. | Both keyboard and toolbar paths call this; do not duplicate its logic. |
| `BlockEditor` | Existing composable wrapping `BasicTextField` for one block; owns `handleKeyEvent` and `onPreviewKeyEvent` wiring. | `ui/components/BlockEditor.kt`. |
| `handleKeyEvent` | Existing private function (`BlockEditor.kt` ~L219-465) — ordered cascade of `if` blocks dispatching hardware-keyboard shortcuts. | New shortcuts append a guarded `if` block here (Phase E). |
| `BlockStateManager` | Existing per-block editing state holder; owns debounced save, undo/redo stack, and the `requestFormat`/`formatEvents` SharedFlow. | `ui/state/BlockStateManager.kt`. |
| `requestFormat` / `formatEvents` | Existing method + `MutableSharedFlow<FormatAction>` (`BlockStateManager.kt:497-501`) that decouples toolbar-button clicks from the editing `BlockEditor` instance. | Template pattern this plan extends for `StructuralAction`. |
| `MobileBlockToolbar` | Existing 3-row + selection-mode + left-handed-mirrored mobile toolbar composable. | `ui/components/MobileBlockToolbar.kt`. |
| `EditorToolbar` | Existing wiring composable between `MobileBlockToolbar` and `BlockStateManager`; contains the `onSuggestTags` closure bug (Phase B). | `ui/components/EditorToolbar.kt`. |
| `BlockItem` | Existing composable hosting one block row; its `LaunchedEffect(isEditing, formatEvents)` (`BlockItem.kt:220-231`) is the sole collector of `formatEvents`. | Collector scope only active while `isEditing == true`. |
| `CommandPalette` | Existing global fuzzy-search command UI (`ui/components/CommandPalette.kt`), invoked via Cmd/Ctrl+Shift+P. | Extension point for Phase F. |
| `Command` | Existing UI-facing data class (`AppState.kt:196`: `id`, `label`, `shortcut: String?`, `action: () -> Unit`) rendered by `CommandPalette`. | Distinct from `EditorCommand`. |
| `EditorCommand` | Existing richer command type from the `editor/commands/CommandManager` framework, bridged into `Command` by `StelekitViewModel.updateCommands()`. | Only this thin bridge slice is live (see ADR-001). |
| `CommandManager` | Existing class (`editor/commands/CommandManager.kt`) instantiated by `StelekitViewModel` purely to source the palette's command-ID list. | Kept per ADR-001; internals mostly unreachable. |
| `CommandRegistry` / `CommandSystem` / `CommandTypes` | Existing orphaned supporting types (`editor/commands/CommandRegistry.kt`, `CommandSystem.kt`, `CommandTypes.kt`) for the unreachable command framework. | Deleted or trimmed in Phase H per ADR-001. |
| `EssentialCommands` | Existing file (`editor/commands/EssentialCommands.kt`, 776 lines) defining `EditorCommand`s including the silently-non-functional `block.toggle-todo`. | Repointed in Phase H. |
| `SlashCommandHandler` | Existing, fully-built but entirely unreachable `/`-command parser (`editor/commands/SlashCommandHandler.kt`) — no `/` keystroke trigger exists anywhere. | Deleted in Phase H. |
| `RichTextEditor` | Existing orphaned rich-editor composables (`editor/RichTextEditor.kt` and `editor/components/RichTextEditor.kt`) with zero UI call sites. | Deleted in Phase H. |
| `AnnotationToolbar` | Existing toolbar for the structurally-separate annotation editor, operating on `AnnotationTool` or `MeasurementUnit`, not `FormatAction`. | `ui/annotate/AnnotationToolbar.kt`. |
| `AnnotationTool` | Existing enum (SELECT/CALIBRATE/DISTANCE/AREA/ANGLE/LABEL/GRID_REF) for the annotation editor's 2D-canvas measurement tools. | Not touched by Phases A-F. |
| `AnnotationEditorScreen` | Existing screen hosting the annotation editor, with its own independent undo/redo stack. | `ui/annotate/AnnotationEditorScreen.kt`. |
| `TagEditorPanel` | Existing panel for tagging annotations. | `ui/annotate/TagEditorPanel.kt`. |
| `GraphWriter` | Existing file-write/conflict-detection layer; all `FormatAction`/`StructuralAction` text mutations ultimately reach it via `BlockStateManager`'s debounce → `saveBlock`. | No changes required by this plan. |
| `StelekitViewModel` | Existing top-level ViewModel; owns `updateCommands()` (bridges `EditorCommand` → `Command`) and `legacyCommands` (page/graph-level palette entries, L2076-2109). | Extension point for Phase F. |
| `AppState` | Existing global UI-flag state model, home of the `Command` data class. | `ui/AppState.kt`. |
| `MarkdownEngine` | Existing file (`ui/components/MarkdownEngine.kt`) with `parseMarkdownWithStyling` (live, render-mode) and `applyMarkdownStylingForEditor` (built, zero call sites — reuse target if audit finds a "raw markdown visible while editing" gap). | Not required by any placeholder story below; audit-conditional. |
| `StructuralAction` (**new**) | New sealed class this plan introduces, modeled 1:1 on `FormatAction`, for any *new* non-text-mutation editing action (e.g. "open table-insert picker", "open code-block language picker") added by this project. | See Pattern Decisions — existing structural actions (indent/outdent/move/undo/redo/attach-image/suggest-tags) are NOT retrofitted. |
| `TodoState` (**new**) | New small sum type — `enum class TodoState { NONE, TODO, DOING, DONE }` — modeling a block's todo-marker state as its own type instead of overloading `FormatAction`'s `(prefix, suffix)` shape. `TodoState.parse(content: String): TodoState` reads the current state from raw line content (`"TODO "`/`"DOING "`/`"DONE "` prefix, else `NONE`); `TodoState.next(): TodoState` computes the next state per `EssentialCommands.toggleTodo`'s existing discarded-but-specified cycle: `NONE→TODO`, `TODO→DONE`, `DOING→DONE`, `DONE→TODO` (`EssentialCommands.kt:442-445`). | Replaces the originally-planned `FormatAction.TOGGLE_TODO` case, introduced in Phase C.1 per architecture-review.md blockers 1/2 — see Pattern Decisions. |
| `applyTodoToggle` (**new**) | New dedicated function (`BlockEditor.kt`, alongside `applyFormatAction`) that turns a `TextFieldValue` into a new `TextFieldValue` by parsing the current line's `TodoState`, stripping only the narrowly-scoped set `{"TODO ", "DOING ", "DONE "}`, and prepending `TodoState.next()`'s prefix (empty for `NONE`). | Never touches `FormatAction`'s existing mutually-exclusive line-prefix strip-group (`BlockEditor.kt:511-514`) — todo state is an orthogonal axis, not a member of that group. |
| `requestTodoToggle` / `todoToggleEvents` (**new**) | New method + `MutableSharedFlow<Unit>` on `BlockStateManager`, mirroring `requestFormat`/`formatEvents`'s decoupling pattern, dedicated to dispatching todo-toggle requests (keyboard, later mobile-toolbar/palette call sites) to the actively-editing `BlockEditor`. | Introduced in Phase C.1. Collected by the same `BlockItem.kt:220-231` `LaunchedEffect` scope as `formatEvents`. |
| `requestStructuralAction` / `structuralActionEvents` (**new**) | New method + `MutableSharedFlow<StructuralAction>` on `BlockStateManager`, mirroring `requestFormat`/`formatEvents`. | Introduced in Phase C alongside the first `StructuralAction` case that needs it. |
| `ToolbarButtonSpec` (**new, optional polish**) | Optional new presentation-only data shape `(icon, label, shortcut, enabled, onClick)` for rendering toolbar buttons/rows consistently across `MobileBlockToolbar` and `AnnotationToolbar` — UI rendering only, not a shared editing-action abstraction (architecture.md §2 explicitly rejects a shared `EditingAction` type). | Only built if Phase D/G find repeated ad hoc button-row boilerplate; not a hard requirement. |
| `journey_id` (**new convention**) | Stable identifier used as frontmatter in each `docs/journeys/*.md` file (e.g. `insert-tag`), linking a journey doc to `test_ids`, `status`, and `last_verified` fields, per the existing `journeys-extract` documentation convention. | Introduced in Phase A. |
| JTBD tier (**new classification, from ux.md §5**) | Per-journey label of `functional` (optimize step-count), `social` (optimize discoverability/correctness), or `in-between` (block reorder/multi-select — weigh both). | Recorded per journey in Phase A backlog. |
| `detectSoftKeyboardBracketWrap` | Existing function (`BlockEditor.kt:633-646`) handling the soft-keyboard-specific `[[`/`]]` autocomplete heuristic. | Referenced by the IME-composition-guard task (Phase E). |
| `TextFieldValue.composition` | Existing Compose API field — non-null during active IME composition (CJK/Japanese/Korean candidate selection). | New guard: no autocomplete/shortcut trigger should fire while non-null (Phase E). |
| `DebugBuildConfig.isDebugBuild` | Existing compile-time (not runtime) debug/release switch — the only gating mechanism in the codebase today. | Confirmed insufficient as a feature flag; not used as a rollout gate in this plan. |

---

## Pattern Decisions
| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Epic/phase structuring | Sequential phase-locked backbone (A→H), matching architecture.md §5 | architecture.md §5, requirements.md Open Q2 | Parallel-track by structural independence; vertical-slice per journey | See Step 0.5 above — task brief mandates the prescribed backbone; vertical-slice contradicts the evidence-based no-feature-flag risk control. |
| New structural (non-text-mutation) actions | Introduce `StructuralAction` sealed class + `requestStructuralAction`/`structuralActionEvents` `SharedFlow`, modeled 1:1 on `FormatAction`/`requestFormat`/`formatEvents` | architecture.md §1 ("this pattern will not scale... should be the template extended"), type-driven-design skill (illegal states unrepresentable via sealed types) | (a) Continue threading new callback parameters through `MobileBlockToolbar`→`EditorToolbar`→`BlockStateManager` for every new structural action; (b) retrofit ALL existing structural actions (indent/outdent/move/undo/redo/attach-image/suggest-tags) onto the new type now | (a) is the exact anti-pattern architecture.md flags as non-scaling — every new action would require touching 3 composable signatures. (b) violates pitfalls.md §5's "prefer additive changes over destructive" guidance given no feature-flag rollback — retrofitting working code for no functional gain increases blast radius for zero user-facing benefit. Decision: new-only, existing structural actions stay as-is. |
| Table-insert / code-block-language-picker (if confirmed by audit as high priority) | Model as `FormatAction` line-prefix-style cases where a pure text mutation suffices; only escalate to `StructuralAction` if a picker/dialog UI is required beyond a text insert | architecture.md §4 (all formatting is a pure text mutation with no persistence change), requirements Q2 sequencing | A dedicated `TableInsertAction`/`CodeBlockAction` type separate from both `FormatAction` and `StructuralAction` | A third parallel type contradicts "one business-logic function, two plumbing mechanisms" — reuse the two already-decided extension points instead of inventing a third. |
| TODO-toggle state modeling | Introduce a dedicated `TodoState` sum type (`NONE`/`TODO`/`DOING`/`DONE`) + `applyTodoToggle` function, mirroring `applyFormatAction`'s role but kept fully separate from `FormatAction` | architecture-review.md Blockers 1/2 | (a) Add `FormatAction.TOGGLE_TODO` as a bare enum case; (b) special-case `TOGGLE_TODO` inside `applyFormatAction`'s generic branch | (a)/(b) both force the three-state TODO→DONE cycle into a `(prefix, suffix)` shape that has no field for "current state" or "next state," and both would fold todo-state into `applyFormatAction`'s mutually-exclusive line-prefix strip-group (`BlockEditor.kt:511-514`) — a genuinely orthogonal axis to QUOTE/NUMBERED_LIST/HEADING that would silently strip todo markers when a heading (or vice versa) is applied. A dedicated type + function keeps the two strip-groups structurally incapable of colliding. |
| Command palette ↔ block-format wiring | Gate all Phase F work behind an explicit empirical focus/blur spike (Story F.1.1) before adding any palette entry that calls `requestFormat`/`requestStructuralAction` | architecture.md §3 (documented, unverified risk: `_formatEvents` has no replay cache, so a focus-loss-before-collection drop is silent) | Ship palette entries first and fix focus/blur if bug reports arrive | No feature-flag/kill-switch exists; a silently-broken "Format > Bold" palette entry is exactly the "wired-looking but non-functional" anti-pattern this project is explicitly trying to eliminate (see `block.toggle-todo` precedent in features.md §2). Verify first. |
| Orphaned `editor/` command framework | **Repoint** the one live, reachable slice (`CommandManager`'s id/label bridge feeding `StelekitViewModel.updateCommands()`, plus `EssentialCommands.toggleTodo` — repointed to call the real TODO-toggle mutation introduced in Phase C) and **delete** everything structurally unreachable (`SlashCommandHandler.kt`, `editor/RichTextEditor.kt`, `editor/components/RichTextEditor.kt`, `editor/performance/PerformanceOptimizedEditor.kt`, `editor/commands/README.md`, `editor/commands/UNDO_REDO_README.md`, and any `CommandRegistry`/`CommandSystem`/`CommandTypes` code paths with no live caller after the repoint) | stack.md §3, features.md §2 | (a) Resurrect the full framework (wire up `/`-commands, `EnhancedCommandPalette`); (b) delete the entire `editor/` package including the live `CommandManager` bridge | (a) is explicit scope creep — nothing in requirements.md asks for `/`-syntax, and the Large-but-bounded appetite doesn't budget it. (b) would break `StelekitViewModel`'s existing palette command-ID sourcing, a real live dependency — confirmed via `StelekitViewModel.kt:420`. Repoint-the-live-slice + delete-the-rest resolves the "silently non-functional is worse than either extreme" problem raised in features.md §2. |
| Toolbar/shortcut/palette layer (build vs. buy) | Build from scratch, extending `BlockEditor.kt`'s dispatch table and `CommandPalette.kt`'s existing 3-tier fuzzy matcher | build-vs-buy.md verdict | `MohamedRejeb/compose-rich-editor`, `halilozercan/compose-richtext`, `mikepenz/multiplatform-markdown-renderer` | All three either lack an iOS target, are display-only, or require a lossy `RichTextState↔markdown` converter that is architecturally incompatible with SteleKit's markdown-source-of-truth model; project memory already named two of these as previously rejected. See ADR-002. |
| Annotation/main-editor toolbar relationship | No shared `EditingAction` abstraction; optional shared `ToolbarButtonSpec` presentation-only shape if repeated boilerplate is found in Phase D/G | architecture.md §2 | Shared `EditingAction` sealed class spanning `FormatAction` and `AnnotationTool` | Textbook "wrong abstraction is worse than duplication" — the two editors have zero conceptual overlap beyond "buttons in a row." |
| IME/composition-triggered autocomplete | Guard every autocomplete/shortcut trigger with `newValue.composition == null` before firing, applied as one cross-cutting task across all trigger sites (not per-trigger duplication) | pitfalls.md §2, §3 | Per-trigger-site ad hoc guards added independently as each is touched | A single cross-cutting task (Phase E.1) guarantees no trigger site is missed and gives one place to regression-test, rather than relying on every future PR remembering to add it. |

---

## Observability Plan
- **Logs**: No new log infrastructure required (out of scope per requirements.md). Existing `logger.error`/`onLeft` patterns at repository boundaries are unaffected — this project is UI/interaction-layer only, no repository-boundary changes.
- **Metrics**: No new `TelemetryDatabase` instrumentation in this project (explicitly out of scope). The existing ad hoc `SideEffect { println("[Recompose] ...") }` counters in `BlockList.kt`/`JournalsView.kt`/`PageView.kt` are the reuse pattern for the new `EditorToolbar`/`MobileBlockToolbar` recomposition-count regression test in Phase B — these are test-time instrumentation, not production telemetry.
- **Alerts**: Not applicable — local-first, single-user editing surface; no production alerting system exists or is being added.

## Risk Control
- **Phase A time-box (pre-mortem P1 #1)**: Phase A (all of Epics A.1-A.3) is time-boxed to approximately 1 week (~20% of the Large 3-6wk appetite budget). If Phase A is not complete by the time-box, ship `gap-backlog.md` and `docs/journeys/` for whatever journeys ARE complete, mark remaining journey docs `status: audit-deferred`, and proceed to Phase B/C using the partial backlog rather than letting audit completeness gate all downstream implementation indefinitely — a partial-but-real implementation beats a complete-but-unshipped audit, per the user's explicit choice of "full overhaul" over "audit-only." This is a concrete, checkable rule (elapsed time vs. Epic A.1-A.3 completion status), not vague language, consistent with Story A.3.3's existing cut-not-grow triage-rule style.
- **Feature flag**: None exists and none is being added (explicitly out of scope). Risk control is structural: small, independently-revertable PRs per Phase/Epic (architecture.md §5), staged by platform within each phase (Desktop/JVM first — fastest Roborazzi iteration loop — then Android, then iOS/Web per pitfalls.md §5).
- **Rollback procedure**: Standard `git revert` per PR. Because Phase C-F changes are additive (new `FormatAction`/`StructuralAction` cases, new toolbar buttons, new shortcuts, new palette entries) rather than destructive (no existing shortcut remapped/removed), a revert of any single PR cannot regress a previously-working action — this additive-only discipline is itself a risk control (pitfalls.md §5).
- **Staged rollout**: Desktop/JVM → Android → iOS/Web, per platform, within each of Phases C-F. Phase D (mobile toolbar) budgets Roborazzi baseline regeneration and manual left-handed-layout re-verification as part of every row's task list, not as an end-of-phase catch-up.
- **Manual visual-QA fallback (Epic D.0, Task D.0.1b — used in place of Roborazzi baseline regeneration for every D.1-D.4 baseline-update task, per the NO-GO verdict recorded in Unresolved Questions below)**:
  1. Run the affected screen via `bazel run //kmp:desktop_app` (or the relevant platform build) both immediately before and immediately after the change, in the same window size and theme (light and dark).
  2. Capture a screenshot of the changed surface (`MobileBlockToolbar`'s primary/overflow/bottom rows, or the reorder/multi-select affordances) in both the before and after state.
  3. Place the two screenshots side by side; visually confirm: (a) the intended change is present and correctly labeled/functional, (b) no unrelated row/button shifted position or lost its `contentDescription`, (c) both `isLeftHanded = true` and `isLeftHanded = false` layouts still mirror correctly.
  4. Record a human sign-off (reviewer name + date) directly in this project's implementation notes or the relevant task's summary — e.g. "Manual visual-QA: D.2.1 overflow row (TODO/Code block/Table buttons) — reviewed light+dark, both handedness variants, 2026-07-06, Tyler Stapler." No blind accept: every diff must be looked at, not merely regenerated.
  5. Existing non-screenshot Compose UI tests (`MobileScreenshotTest.kt`'s render-without-throwing checks, semantics-tree assertions for `contentDescription`, etc.) still run as an automated smoke-test safety net alongside the manual pass — they just don't function as pixel-diff regression tests given the NO-GO verdict.

## Unresolved Questions
- [ ] Does opening `CommandPalette`'s `Dialog` blur the currently-editing block's focus/composition before a `requestFormat`/`requestStructuralAction` emission is collected by `BlockItem`'s `LaunchedEffect`? — blocks Story F.1.2 — owner: implementer of Story F.1.1 (the empirical spike itself answers this).
- [x] **RESOLVED (Phase D, Task D.0.1a, 2026-07-06): NO-GO.** What is the actual Roborazzi baseline storage/update workflow? Confirmed by direct inspection, not assumption:
  - No baseline `.png`s are committed anywhere in this repo (no tracked file under any `snapshots`/`roborazzi` path).
  - `.github/workflows/ci.yml:83-96` runs the Android screenshot job as `./gradlew :kmp:testDebugUnitTest :kmp:recordRoborazziDebug :androidApp:assembleDebug` — always **record** mode, never `verifyRoborazziDebug` — and its own inline comment says so explicitly: *"Goldens are intentionally not committed — rendering differs between machines. To enable regression detection: download artifacts from a baseline CI run, commit to `kmp/src/androidUnitTest/snapshots/images/`, then switch to `verifyRoborazziDebug`"* — i.e. regression detection for Android screenshots is documented future work, not yet enabled.
  - The JVM/Desktop suite (`MobileScreenshotTest.kt` et al.) calls `composeTestRule.onRoot().captureRoboImage("build/outputs/roborazzi/<name>.png")` — a path inside the git-ignored, every-build-wiped `build/` directory, with **no comparison assertion at all**. These are render-without-throwing smoke tests / image-generation utilities, not regression diffs — there is no persisted baseline to diff against, by construction.
  - `docs/adr/ADR-009-roborazzi-genrule-wrapper.md` corroborates this independently: native Bazel verify-mode (`//kmp:roborazzi_android_verify`, hermetic, baselines as Bazel `data`) is *planned*, not yet landed as a CI gate.
  - **Consequence for D.1-D.4**: every "update Roborazzi baselines" task in this project uses the **manual visual-QA fallback** instead (see Risk Control below) — there is no automated baseline to regenerate or diff against in this repo today.
- [ ] Does `((` block-reference autocomplete have any underlying data-model support today, or is it a deeper feature gap than an editor-affordance gap? — blocks nothing in this plan directly (out of scope unless clarified) but should be filed as a backlog item with a `needs-data-model-answer` tag rather than silently dropped — owner: data-model owner, resolve during Phase A.3 reconciliation.
- [ ] Does the Compose canvas's `true`-returning key handler actually suppress the underlying browser DOM event for Ctrl+S/Ctrl+F/Ctrl+P on Web/WASM, or does the browser's window-level handler still fire? — blocks Story E.2.1's acceptance criteria — owner: implementer, verify empirically in Phase E rather than assuming. **PARTIALLY ADDRESSED (Phase E, Task E.2.1a, 2026-07-06, STATIC ANALYSIS ONLY — not empirical):** no browser session was available in the implementing environment to load a built `bazel build //kmp:web_app` artifact and press the keys directly, so the underlying `preventDefault`-suppression question remains genuinely unresolved and this checkbox stays open. What was confirmed via static analysis instead: (1) `kmp/src/wasmJsMain/` still has zero custom `keydown`/`preventDefault` wiring (`grep -rn "preventDefault\|addEventListener.*key" kmp/src/wasmJsMain` — the only `addEventListener` hits are unrelated file-input/worker-message listeners), and `browser/Main.kt` mounts via plain `ComposeViewport(document.body!!) { ... }` with no additional JS-level key interception layered on top — this matches pitfalls.md §2's original finding, unchanged since. (2) Ctrl+F and bare Ctrl+P are **not consumed anywhere** in this codebase (`handleKeyEvent` in `BlockEditor.kt` and `onGraphKeyEvent` in `App.kt` were both grepped for `Key.F`/`Key.P` — only `Ctrl+Shift+P` for the command palette exists, no bare `Ctrl+P`, and no `Key.F` binding at all), so there is no interception to suppress for those two — the browser's native Find/Print behavior passes through unmodified on Web, which is not a regression. (3) Ctrl+S **is** consumed by `handleKeyEvent` (mapped to `FormatAction.STRIKETHROUGH`) while a block has focus; whether this actually blocks the browser's native Save-page dialog on Web was not empirically confirmed (a web search for JetBrains Compose Multiplatform 1.10.3 docs/issue-tracker turned up no definitive answer either — see Task E.2.1a's note in `gap-backlog.md`). A working non-keyboard fallback already exists regardless: `MobileBlockToolbar.kt` renders a Strikethrough button (`onClick = { onFormat(FormatAction.STRIKETHROUGH) }`) independent of this question, so Web users have a working path to the same action even if Ctrl+S is confirmed leaky in a future empirical pass. Because nothing was *confirmed* unsuppressable, no new P0 backlog row was filed per Task E.2.1b's condition ("confirmed-unsuppressable... lacking a fallback") — this remains a to-verify item, not a shipped regression. Next owner: whoever has access to a real browser session against the built Web target.
- [ ] **Manual-QA checklist item added (Phase E, Task E.1.1d, 2026-07-06):** JVM/desktop automated tests cannot reproduce real IME composition (pitfalls.md §2 — desktop hardware keyboards don't route through the same composition pipeline as CJK/Japanese/Korean soft-keyboard input), so the `composition == null` guards added to `detectAutocompleteMatch` and `detectSoftKeyboardBracketWrap` (`BlockEditor.kt`) are only unit-tested against synthetic `TextFieldValue(composition = TextRange(...))` fixtures — this proves the guard logic is correct in isolation but does **not** prove real Android/iOS CJK candidate-selection input is actually suppressed end-to-end. **Required manual follow-up before considering Phase E.1 fully verified**: on a physical Android device and a physical iOS device (or simulator with a CJK/Japanese/Korean IME enabled), type a `[[`-triggering or `#`-triggering sequence while mid-composition (e.g., typing "こんにちは[" and selecting a kanji candidate before committing) and confirm the autocomplete popup does NOT flash/open until composition commits. Record pass/fail per platform here or in `docs/journeys/insert-tag.md`/`insert-link.md`'s platform notes — not yet performed as part of this pass (no physical device/simulator with IME available in the implementing environment).
- [ ] **Manual verification still pending (Phase E, Task E.3.1c, 2026-07-06):** per stack.md §6, iOS hardware-keyboard dispatch (Bluetooth/Smart Keyboard `onPreviewKeyEvent` delivery) has never been empirically tested in this repo. The four new Phase E.3 shortcuts (Ctrl+L / Link, Ctrl+' / Quote, Ctrl+Shift+7 / Numbered List, Ctrl+Shift+1 / Heading) are verified on JVM/Desktop via `KeyboardShortcutTest.kt`'s Compose UI test harness only. Before considering these cross-platform-complete, an iOS build must be run on a physical device with an attached Bluetooth/Smart keyboard to confirm each combination is actually delivered to `BlockEditor`'s `onPreviewKeyEvent` (compose-multiplatform's iOS key-event pipeline has open upstream issues per pitfalls.md §2). Not performed as part of this pass — no iOS device/keyboard available in the implementing environment. Record results in `docs/journeys/format-text.md`'s platform notes once verified.
- [x] **RESOLVED (Phase A, Task A.1.1a, 2026-07-05)**: Was there prior journey documentation now superseded (git history signal `b3de1ec7dc` "docs(ux): add editor experience journey map")? — **Yes, it exists but is not superseded/merged**: commit `b3de1ec7dc` adds `docs/ux/journey-map.md` (494 lines, 10 journeys + cross-cutting gaps) on branch `stelekit-editing` (local + `origin/stelekit-editing`), confirmed **not an ancestor of this branch's HEAD** (`git merge-base --is-ancestor b3de1ec7dc HEAD` → false) and not present in this worktree. Its content is real, substantive prior art — read via `git show b3de1ec7dc:docs/ux/journey-map.md` and cited/reused inline across the new `docs/journeys/*.md` docs (see `docs/journeys/README.md`'s changelog for the full resolution). It independently corroborates several of this project's own from-scratch findings (TODO/DOING/DONE non-functional, block-reorder drag-handle discoverability, orphaned command system) and, notably, its "Multi-block selection and drag reorder" journey describes a drag-handle/ghost/drop-zone mechanism that this audit confirmed **is in fact already implemented in the current branch's code** (`BlockGutter.kt`, `BlockList.kt`) despite `docs/tasks/drag-and-drop-reorder.md`'s own `Status: Planning` label suggesting otherwise — see `docs/journeys/reorder-block.md`'s "CORRECTION" section.

## Dependency Visualization

```
Phase A (Journey mapping + audit + backlog)
  A.1 Journey docs ──┐
  A.2 Benchmark+review ──┤──> A.3 Ranked backlog (gap-backlog.md) ─────────────┐
                                                                                │
Phase B (independent of A — pre-existing bug, no backlog dependency)           │
  B.1 Fix onSuggestTags closure ─────────────────────────────────────────┐     │
                                                                          │     │
                                                                          v     v
Phase C (FormatAction extensions)  <───────────────────── gated on A.3 backlog + B.1 landed
  C.1 TODO-toggle collision (StructuralAction introduced here)
  C.2 Backlog-driven FormatAction cases (incl. C.2.1 code-block, C.2.2 table-insert, C.2.3 reconciliation)
        │
        v
Phase D (MobileBlockToolbar rows)  <───────────────────── gated on C.1/C.2 (new actions must exist before a button can call them)
  D.0 Roborazzi baseline workflow spike (go/no-go, MUST resolve before D.1) -> D.1 Primary row (incl. D.1.1 tag-insertion, D.1.2 insert-image) -> D.2 Overflow row -> D.3 Bottom row -> D.4 Selection-mode + left-handed re-verify
        │
        v
Phase E (Keyboard shortcuts)  <───────────────────────── gated on C (same actions, new trigger)
  E.1 IME composition guard (cross-cutting, applies to ALL existing+new triggers)
  E.2 Browser-reserved-shortcut audit (Web)
  E.3 Backlog-driven shortcut additions
        │
        v
Phase F (Command palette)  <────────────────────────────── gated on D+E stabilizing (architecture.md §5: "ship after toolbar/shortcut work stabilizes")
  F.1 Empirical focus/blur spike (MUST PASS before F.2)
  F.2 Palette format/structural entries
  F.3 Canonical shortcut table (fixes badge/binding drift, stack.md §2)

Phase G (Annotation editor — independently schedulable once its own audit lands; no file overlap with B-F)
  G.1 Deep annotation journey-map + audit (same format as Phase A) ──> G.2 Implement top annotation fixes

Phase G2 (Voice-mode input — independently schedulable once A.1.4/A.2's voice-mode audit lands; no file overlap with B-F/G)  <── gated on A.3 backlog (target_phase: G2 rows)
  G2.1 Implement single highest-priority voice-mode backlog item (sized small — secondary surface per requirements.md)

Phase H (Orphaned framework resolution — LAST, per architecture.md §5, to avoid conflicting with C/E/F's command-registration surface)
  H.1 Delete unreachable orphaned files ──> H.2 Repoint toggle-todo + trim CommandManager bridge
```

---

## Phase-by-phase Summary

| Phase | Epics | Focus |
|---|---|---|
| A | A.1 Journey Map Authoring, A.2 Step-count Benchmarking + Heuristic Review, A.3 Ranked Gap Backlog | Document all 7 functional-tier journeys (`insert-tag`, `insert-link`, `format-text`, `toggle-todo`, `insert-code-block`, `insert-table`, `insert-image`), 2 in-between-tier journeys, voice-capture, and the annotation stub; benchmark + heuristic-review each; produce ranked `gap-backlog.md` with a cut-not-grow triage rule (Story A.3.3). |
| B | B.1 Fix `onSuggestTags` closure recomposition storm | Pre-existing recomposition-SLO bug fix, independent of the audit. |
| C | C.1 Resolve TODO-toggle Ctrl+Enter collision (via new `TodoState`/`applyTodoToggle`, not a `FormatAction` case), C.2 Process backlog items tagged `target_phase: C` (C.2.1 code-block insertion, C.2.2 table insertion, C.2.3 reconciliation) | `FormatAction` extensions + the dedicated todo-state type. |
| D | D.0 Roborazzi baseline workflow spike (go/no-go + fallback), D.1 Primary row (D.1.1 tag-insertion, D.1.2 insert-image), D.2 Overflow row, D.3 Bottom row, D.4 Selection-mode + left-handed final re-verify | `MobileBlockToolbar` row-by-row backlog implementation, gated on D.0's spike resolving before any row work begins. |
| E | E.1 IME composition guard (cross-cutting), E.2 Browser-reserved-shortcut audit (Web), E.3 Process backlog items tagged `target_phase: E` | Keyboard shortcut additions + IME safety. |
| F | F.1 Empirical focus/blur risk spike (MUST PASS before F.2), F.2 Format/structural palette entries, F.3 Canonical shortcut table | Command-palette entries, gated on an empirical spike. |
| G | G.1 Deep annotation journey-map + audit, G.2 Implement highest-priority annotation fix | Annotation editor track, fully separate from the main editor. |
| G2 | G2.1 Implement highest-priority voice-mode backlog item | Voice-mode input implementation — the third named surface (main editor, annotation editor, voice-mode input) gets its own small, appetite-bounded implementation home, per adversarial-review.md Blocker 4. |
| H | H.1 Delete unreachable orphaned framework files, H.2 Repoint the live slice | Orphaned `editor/` command-framework cleanup + `block.toggle-todo` repoint, sequenced last. |

**Totals**: 9 phases, 22 epics, 39 stories, 93 tasks. (Updated 2026-07-05: Phase A shipped and its Story A.3.3 reconciliation filed one new story, D.4.2, for `gap-backlog.md`'s GAP-011 — see Epic D.4.)

---

## Phase A: Journey Mapping & Audit

### Epic A.1: Journey Map Authoring
**Goal**: Establish `docs/journeys/` and document every in-scope journey (main editor + voice-mode fully; annotation editor as a scope stub only — its deep audit is Phase G's job) with a stable `journey_id`, satisfying the requirements.md success metric "every editing journey in scope is documented in `docs/journeys/` with a stable `journey_id`."

#### Story A.1.1: Establish `docs/journeys/` structure and template
**As a** planner, **I want** a consistent journey-doc template and directory, **so that** all subsequent journey docs (main editor, voice-mode, and the Phase G annotation stub) are structurally consistent and machine-checkable by `journey_id`.
**Acceptance Criteria**:
- `docs/journeys/README.md` exists describing the frontmatter schema (`journey_id`, `platforms`, `jtbd_tier`, `test_ids`, `status`, `last_verified`).
  - *Given* a new contributor opens `docs/journeys/README.md`, *When* they read the frontmatter schema section, *Then* they can identify that `jtbd_tier` must be one of `functional`/`social`/`in-between` and `journey_id` must be a stable kebab-case slug.
- Git history checked for prior journey documentation (commit `b3de1ec7dc`) before writing fresh docs, per Unresolved Questions.
  - *Given* `git log --all --oneline -- docs/journeys` and `git show b3de1ec7dc --stat`, *When* run, *Then* either recoverable prior content is found and merged, or its absence is confirmed and noted in `docs/journeys/README.md`'s changelog section.
**Files**: `docs/journeys/README.md`

##### Task A.1.1a: Check git history for prior journey docs (~3 min)
- Run `git log --all --oneline -- docs/journeys` and `git show b3de1ec7dc --stat` to confirm whether recoverable content exists.
- Files: none (investigation only)

##### Task A.1.1b: Write `docs/journeys/README.md` template (~5 min)
- Document frontmatter schema (`journey_id`, `platforms: [desktop, android, ios, web]`, `jtbd_tier`, `test_ids`, `status`, `last_verified`) and one filled-in example block.
- Files: `docs/journeys/README.md`

#### Story A.1.2: Document main-editor functional-JTBD journeys
**As a** planner, **I want** journey docs for the highest-frequency mid-composition actions, **so that** Phase A.2's step-count benchmarking has a concrete artifact to measure against.
**Acceptance Criteria**:
- Journey docs exist for `insert-tag`, `insert-link`, `format-text`, `toggle-todo`, `insert-code-block`, `insert-table`, `insert-image`, each with `jtbd_tier: functional` and all 4 platforms' current step sequence recorded. (`insert-image` is the 7th functional-tier journey, added per adversarial-review.md Blocker — requirements.md's Problem Statement names "insert an image" as a common action alongside the other six, and `MobileBlockToolbar`'s Attach-image/Capture-photo primary-row buttons are squarely in Phase D's touch surface.)
  - *Given* `docs/journeys/toggle-todo.md` with `journey_id: toggle-todo`, *When* the current-state step sequence for Desktop is recorded, *Then* it documents the confirmed-broken behavior from features.md §1 ("Ctrl+Enter falls through to plain-Enter; user must hand-type `TODO ` prefix — no working keyboard trigger exists today").
  - *Given* `docs/journeys/insert-image.md` with `journey_id: insert-image`, *When* the current-state step sequence per platform is recorded, *Then* it documents both the "Attach-image" and "Capture-photo" `MobileBlockToolbar` button flows (mobile) and whatever current insert mechanism exists on Desktop/Web (or its confirmed absence), citing `MobileBlockToolbar.kt`'s existing button call sites.
**Files**: `docs/journeys/insert-tag.md`, `docs/journeys/insert-link.md`, `docs/journeys/format-text.md`, `docs/journeys/toggle-todo.md`, `docs/journeys/insert-code-block.md`, `docs/journeys/insert-table.md`, `docs/journeys/insert-image.md`

##### Task A.1.2a: Draft insert-tag.md and insert-link.md (~5 min)
- Record current step sequence per platform (typed `#`/`[[` autocomplete path + `MobileBlockToolbar`'s "Suggest tags"/wiki-link-insert button path), citing `BlockEditor.kt` autocomplete trigger locations.
- Files: `docs/journeys/insert-tag.md`, `docs/journeys/insert-link.md`

##### Task A.1.2b: Draft format-text.md (~4 min)
- Record current step sequence for BOLD/ITALIC/STRIKETHROUGH/HIGHLIGHT/CODE/LINK across all 4 platforms, citing `applyFormatAction` and `MobileBlockToolbar`'s formatting overflow row.
- Files: `docs/journeys/format-text.md`

##### Task A.1.2c: Draft toggle-todo.md (~4 min)
- Record the confirmed-broken Ctrl+Enter behavior (features.md §1) and the only-working-path today (hand-typing `TODO ` prefix) per platform.
- Files: `docs/journeys/toggle-todo.md`

##### Task A.1.2d: Draft insert-code-block.md and insert-table.md (~5 min)
- Record that no trigger exists today (must hand-type fence syntax / no table-insert affordance at all), per features.md §1.
- Files: `docs/journeys/insert-code-block.md`, `docs/journeys/insert-table.md`

##### Task A.1.2e: Draft insert-image.md (~4 min)
- Record the current Attach-image/Capture-photo `MobileBlockToolbar` primary-row button flows per platform (mobile), and the current Desktop/Web insertion mechanism or its confirmed absence, per requirements.md's Problem Statement naming "insert an image" as a named common action. For the Web/WASM platform row specifically, cite `docs/bugs/open/BUG-004-wasm-page-drop-target-noop.md` (open, Medium severity — OS-level image drag-and-drop onto the page is a confirmed no-op on Web) as existing, already-diagnosed evidence rather than re-discovering it as a fresh audit finding, per pre-mortem.md's Failure #2 prevention (evidence-based backlog rows over pure agent narrative).
- Files: `docs/journeys/insert-image.md`

#### Story A.1.3: Document the in-between JTBD tier — block reorder & multi-select
**As a** planner, **I want** a journey doc for block reordering and multi-select, **so that** the highest-value gap candidate identified by ux.md §5/features.md §3 is explicitly tracked.
**Acceptance Criteria**:
- `docs/journeys/reorder-block.md` and `docs/journeys/multi-select-block.md` exist with `jtbd_tier: in-between`, documenting the current Move-Up/Move-Down icon-button flow (no drag handle) per platform.
  - *Given* `docs/journeys/reorder-block.md`, *When* the Android step sequence is recorded, *Then* it documents "tap block to select → tap Move-Up/Move-Down toolbar button → repeat per position moved" as the only current path, contrasted with competitive single-continuous-drag-handle patterns from ux.md §1.
**Files**: `docs/journeys/reorder-block.md`, `docs/journeys/multi-select-block.md`

**Prior-art reuse note (engineering triad finding)**: `docs/tasks/drag-and-drop-reorder.md` and `docs/tasks/multi-block-selection.md` are pre-existing, more detailed, `Status: Planning` plans (dated 2026-04-17, no commits since) targeting this exact same file/UI surface (`MobileBlockToolbar.kt`, `BlockStateManager.kt`, `BlockGutter.kt`, `BlockDragGhost.kt`) for block reorder and multi-select respectively. Before drafting either journey doc, the implementer should read both files' **Known Issues** sections (`drag-and-drop-reorder.md`'s Bugs 001-004; `multi-block-selection.md`'s Concurrency/Data-Integrity/UI/UX Risk entries) — these already document known current-state pain points and implementation pitfalls that Phase A.2's benchmarking/heuristic-review can cite and reuse directly, rather than re-discovering them from scratch during the audit. See Epic D.3/D.4 below for how this project's own implementation scope relates to (and deliberately narrows from) these two pre-existing plans.

##### Task A.1.3a: Draft reorder-block.md (~4 min)
- Record current Move-Up/Down button flow per platform; cite ux.md §1's competitive drag-handle comparison as context (not yet a decision). Check `docs/tasks/drag-and-drop-reorder.md`'s Known Issues section (Bugs 001-004) for already-documented current-state pain points before treating any finding as new.
- Files: `docs/journeys/reorder-block.md`

##### Task A.1.3b: Draft multi-select-block.md (~4 min)
- Record current selection-mode entry mechanism; note features.md §3's open question ("confirm during UX flow analysis whether entry is long-press or a separate explicit action") as a benchmarking input, not a pre-answered fact. Check `docs/tasks/multi-block-selection.md`'s Known Issues section (Concurrency/Data-Integrity/UI/UX Risk entries) for already-documented current-state pain points before treating any finding as new.
- Files: `docs/journeys/multi-select-block.md`

#### Story A.1.4: Document voice-mode input journey
**As a** planner, **I want** the voice-mode input journey documented as part of this project's journey map (per requirements.md scope), **so that** it is benchmarked and heuristically reviewed alongside the main editor rather than treated as an unrelated feature.
**Acceptance Criteria**:
- `docs/journeys/voice-capture.md` exists with `jtbd_tier: functional`, current step sequence per platform, citing `VoiceCaptureButton.kt` and the `mobile-voice-mode` project as prior art (not re-litigating that project's own scope).
  - *Given* `docs/journeys/voice-capture.md`, *When* the Desktop platform row is filled in, *Then* it either records a concrete current step sequence or explicitly states "voice input not available on Desktop" if that is the confirmed baseline (verify against `VoiceCaptureButton.kt`'s platform gating before asserting either way).
**Files**: `docs/journeys/voice-capture.md`

##### Task A.1.4a: Confirm voice-mode platform availability (~3 min)
- Check `VoiceCaptureButton.kt` and its call sites for any platform gating (`expect`/`actual` or conditional composition) before asserting per-platform availability.
- Files: none (investigation only)

##### Task A.1.4b: Draft voice-capture.md (~4 min)
- Record current step sequence per confirmed-available platform.
- Files: `docs/journeys/voice-capture.md`

#### Story A.1.5: Document annotation-editor journey stub (deep audit deferred to Phase G)
**As a** planner, **I want** a lightweight stub for the annotation-editor journey now, **so that** the requirements.md success metric ("every editing journey in scope... is documented") is satisfied for annotation without duplicating Phase G's dedicated, structurally-separate audit track.
**Acceptance Criteria**:
- `docs/journeys/annotate-asset.md` exists with `jtbd_tier: social`, `status: stub-pending-phase-g`, and a one-paragraph pointer to Phase G as the owner of its deep audit/backlog/fix.
  - *Given* `docs/journeys/annotate-asset.md`, *When* its `status` frontmatter field is read, *Then* it reads `stub-pending-phase-g` (not `verified`) until Phase G updates it.
**Files**: `docs/journeys/annotate-asset.md`

##### Task A.1.5a: Draft annotate-asset.md stub (~3 min)
- Write `journey_id: annotate-asset`, `status: stub-pending-phase-g`, and a pointer paragraph referencing `AnnotationToolbar.kt`/`AnnotationEditorScreen.kt` and Phase G.
- Files: `docs/journeys/annotate-asset.md`

### Epic A.2: Step-count Benchmarking + Heuristic Review
**Goal**: Apply the JTBD-weighted rubric (ux.md §5, resolving requirements.md Open Question 1) to every journey doc from Epic A.1, and run a `ux-expert`-agent heuristic review per journey.

#### Story A.2.1: Define per-journey step-count targets using the JTBD-weighted rubric
**As a** planner, **I want** a documented step-count target per journey (not one uniform target), **so that** functional-tier journeys are graded on keystroke minimization while social-tier journeys are graded on discoverability instead.
**Acceptance Criteria**:
- Each journey doc's frontmatter gains a `step_count_target` field whose weighting matches its `jtbd_tier` (functional → numeric max-steps target; social → qualitative discoverability target instead of a hard number; in-between → both).
  - *Given* `docs/journeys/insert-tag.md` with `jtbd_tier: functional`, *When* `step_count_target` is filled in, *Then* it is a concrete number (e.g., "≤2 keystrokes to trigger + select" for the typed `#` path) — not a qualitative statement, per ux.md §5.
  - *Given* `docs/journeys/annotate-asset.md` with `jtbd_tier: social`, *When* its target is filled in (during Phase G, not here — this story defines the rubric it will follow), *Then* the rubric documented in `docs/journeys/README.md` explicitly states social-tier journeys use a discoverability checklist, not a step-count number.
**Files**: `docs/journeys/README.md`, all journey docs from Epic A.1

##### Task A.2.1a: Add rubric section to docs/journeys/README.md (~4 min)
- Document the 3-tier weighting rule from ux.md §5 as a reusable rubric section.
- Files: `docs/journeys/README.md`

##### Task A.2.1b: Fill step_count_target per functional/in-between journey doc (~5 min)
- Add concrete numeric targets to `insert-tag.md`, `insert-link.md`, `format-text.md`, `toggle-todo.md`, `insert-code-block.md`, `insert-table.md`, `insert-image.md`, `voice-capture.md`, and a dual (numeric + discoverability) target to `reorder-block.md`/`multi-select-block.md`.
- Files: `docs/journeys/insert-tag.md`, `docs/journeys/insert-link.md`, `docs/journeys/format-text.md`, `docs/journeys/toggle-todo.md`, `docs/journeys/insert-code-block.md`, `docs/journeys/insert-table.md`, `docs/journeys/insert-image.md`, `docs/journeys/voice-capture.md`, `docs/journeys/reorder-block.md`, `docs/journeys/multi-select-block.md`

#### Story A.2.2: Measure actual current step counts against targets
**As a** planner, **I want** the *current* (pre-fix) step count recorded per journey per platform, **so that** the before/after comparison required by requirements.md's success metrics has a baseline number.
**Acceptance Criteria**:
- Every functional/in-between journey doc has a filled-in `current_step_count` per platform, compared against `step_count_target`, with a pass/fail flag.
  - *Given* `docs/journeys/toggle-todo.md`'s Desktop row, *When* `current_step_count` is recorded, *Then* it reflects the confirmed-broken state (no working shortcut path at all → target not met, flagged as a gap) rather than assuming the documented-but-non-functional `Ctrl+Enter` shortcut works.
**Files**: all journey docs from Epic A.1

##### Task A.2.2a: Measure and record current_step_count for functional-tier journeys (~5 min)
- Fill in `current_step_count` per platform for `insert-tag.md`, `insert-link.md`, `format-text.md`, `toggle-todo.md`, `insert-code-block.md`, `insert-table.md`, `insert-image.md`, `voice-capture.md`.
- Files: `docs/journeys/insert-tag.md`, `docs/journeys/insert-link.md`, `docs/journeys/format-text.md`, `docs/journeys/toggle-todo.md`, `docs/journeys/insert-code-block.md`, `docs/journeys/insert-table.md`, `docs/journeys/insert-image.md`, `docs/journeys/voice-capture.md`

##### Task A.2.2b: Measure and record current_step_count for in-between-tier journeys (~4 min)
- Fill in `current_step_count` per platform for `reorder-block.md`, `multi-select-block.md`.
- Files: `docs/journeys/reorder-block.md`, `docs/journeys/multi-select-block.md`

#### Story A.2.3: Run ux-expert heuristic review per journey
**As a** planner, **I want** a Nielsen-heuristic review (visibility of system status, consistency, discoverability, minimal memory load) recorded per journey, **so that** the audit produces both a quantitative and qualitative verdict per requirements.md's success metrics.
**Acceptance Criteria**:
- Each journey doc has a `heuristic_findings` section populated via the `ux-expert` agent, citing at least the 4 named heuristics.
  - *Given* the `ux-expert` agent reviews `docs/journeys/insert-code-block.md`, *When* it evaluates "discoverability," *Then* its finding cites the confirmed absence of any `/`-trigger or code-block affordance (features.md §1) as a concrete discoverability failure, not a generic statement.
**Files**: all journey docs from Epic A.1

##### Task A.2.3a: Invoke ux-expert agent per journey doc, record findings (~5 min per journey; batch as one task covering functional-tier docs)
- Dispatch `ux-expert` review against `insert-tag.md`, `insert-link.md`, `format-text.md`, `toggle-todo.md`, `insert-code-block.md`, `insert-table.md`, `insert-image.md`; append `heuristic_findings` section to each.
- Files: `docs/journeys/insert-tag.md`, `docs/journeys/insert-link.md`, `docs/journeys/format-text.md`, `docs/journeys/toggle-todo.md`, `docs/journeys/insert-code-block.md`, `docs/journeys/insert-table.md`, `docs/journeys/insert-image.md`

##### Task A.2.3b: Invoke ux-expert agent for in-between + voice-mode docs (~4 min)
- Dispatch `ux-expert` review against `reorder-block.md`, `multi-select-block.md`, `voice-capture.md`.
- Files: `docs/journeys/reorder-block.md`, `docs/journeys/multi-select-block.md`, `docs/journeys/voice-capture.md`

### Epic A.3: Ranked Gap Backlog
**Goal**: Produce `project_plans/rich-editing-experience/implementation/gap-backlog.md`, the concrete artifact Phases C-G/G2's "process backlog items tagged X" stories reference.

#### Story A.3.1: Define backlog schema
**As a** planner, **I want** a fixed backlog table schema, **so that** every downstream phase can filter/reference specific line items unambiguously.
**Acceptance Criteria**:
- `gap-backlog.md` contains a markdown table with columns `gap_id, journey_id, platform, jtbd_tier, gap_description, target_phase (C/D/E/F/G/G2), priority (P0-P3), effort_estimate (S/M/L)`. `target_phase: G2` is the implementation home for voice-mode gaps (Phase G2 — see Dependency Visualization and Phase-by-phase Summary), added per adversarial-review.md Blocker 4 so voice-mode findings from Story A.1.4/Epic A.2 have somewhere to land as real implementation work, not just an audit.
  - *Given* the schema header row, *When* a new backlog row like `gap_id: GAP-004, journey_id: toggle-todo, platform: all, jtbd_tier: functional, gap_description: "Ctrl+Enter TODO-toggle collision unresolved", target_phase: C, priority: P0, effort_estimate: M` is added, *Then* Phase C.1's task references `GAP-004` directly by ID rather than re-describing the gap.
**Files**: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task A.3.1a: Write backlog schema + header (~3 min)
- Create the file with the column schema and one worked example row.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

#### Story A.3.2: Populate and rank backlog from A.2 findings
**As a** planner, **I want** every gap found in Epic A.2 entered as a ranked backlog row, **so that** Phases C-G/G2 have a concrete, prioritized worklist.
**Acceptance Criteria**:
- Every journey doc's `heuristic_findings` and step-count-target failures produce at least one backlog row, and rows are sorted P0 (correctness bugs, e.g. broken TODO-toggle) > P1 (missing high-frequency affordance) > P2 (missing overflow/secondary affordance) > P3 (nice-to-have/discoverability polish).
  - *Given* `docs/journeys/toggle-todo.md`'s confirmed-broken Ctrl+Enter finding, *When* it is entered into `gap-backlog.md`, *Then* it is assigned `priority: P0` (a correctness bug, not merely a missing feature) and `target_phase: C`.
**Files**: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task A.3.2a: Transcribe functional-tier gaps into backlog, assign priority (~5 min)
- Pull findings from `insert-tag.md`, `insert-link.md`, `format-text.md`, `toggle-todo.md`, `insert-code-block.md`, `insert-table.md`, `insert-image.md`, `voice-capture.md` into ranked rows.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task A.3.2b: Transcribe in-between-tier gaps into backlog, assign priority (~4 min)
- Pull findings from `reorder-block.md`, `multi-select-block.md` into ranked rows.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

#### Story A.3.3: Reconcile backlog against this plan's placeholder assumptions, with an explicit cut-not-grow triage rule
**As a** planner, **I want** an explicit reconciliation pass with a stated triage rule, **so that** Phases C-G's illustrative placeholder examples (written before real audit data existed) are checked against the real backlog, and any mismatch is resolved by **cutting lower-priority placeholder scope by default**, not growing the plan — per requirements.md's explicit constraint ("Scope must fit the appetite... if it doesn't fit, cut scope — do not move the deadline") and adversarial-review.md Blocker 6.
**Acceptance Criteria**:
- A `reconciliation` section is added to `gap-backlog.md` explicitly comparing each Phase C/D/E/F/G/G2 placeholder assumption in this plan.md against the real backlog rows, noting "confirmed / partially confirmed / not found — plan task superseded."
  - *Given* Phase C.2's placeholder example ("table insert / code-block-language picker are high-priority gaps"), *When* the reconciliation pass runs, *Then* it either confirms these appear as high-priority (P0/P1) backlog rows, or notes the mismatch and records which actual P0/P1 rows should replace them in Phase C.2's task list.
- **Triage rule (cut-not-grow default)**: for each phase (C, D, E, F, G, G2), count that phase's current illustrative-placeholder story count in this plan.md (baseline: C=2, D=4, E=1, F=1, G=1, G2=1 — C=2 reflects C.2.1 code-block + C.2.2 table-insert; D=4 reflects D.1.1 tag-insertion + D.1.2 insert-image + D.2.1 + D.3.1) and compare it against the count of real P0/P1 `gap-backlog.md` rows tagged `target_phase` = that phase.
  - If the P0/P1 row count for a phase is **≤ 2x** that phase's placeholder-story baseline, filing new sibling stories for uncovered P0/P1 rows (the prior "file new tasks" behavior) is allowed, appetite budget permitting.
  - If the P0/P1 row count for a phase **exceeds 2x** its placeholder-story baseline, new stories are **not** added past the 2x threshold — instead, the phase's existing lowest-priority *placeholder* stories (illustrative examples not yet confirmed against a real P0/P1 row) are **cut** to make room, and the cut decision + reasoning (which stories, which rows took priority, why) is recorded in `gap-backlog.md`'s reconciliation section.
  - The "file new tasks" behavior (Task A.3.3b, formerly unconditional) is demoted to apply **only** when a P0/P1 gap is confirmed **and** the phase is still under its 2x threshold with remaining appetite budget — never automatically.
  - *Given* Phase D (baseline 4 placeholder stories: D.1.1/D.1.2/D.2.1/D.3.1) and a hypothetical 10 real P0/P1 rows tagged `target_phase: D` in `gap-backlog.md`, *When* the triage rule is applied (10 > 2×4=8), *Then* the lowest-priority placeholder story among D.1.1/D.1.2/D.2.1/D.3.1 is cut (not expanded around), and `gap-backlog.md`'s reconciliation section records which story was cut and why.
**Files**: `project_plans/rich-editing-experience/implementation/gap-backlog.md`, `project_plans/rich-editing-experience/implementation/plan.md`

##### Task A.3.3a: Write reconciliation section comparing plan placeholders vs. real backlog, apply the triage rule (~6 min)
- For each of Phase C/D/E/F/G/G2's placeholder examples in this plan.md, add one reconciliation row confirming or correcting it, and compute each phase's P0/P1-row-count-vs-2x-baseline check per the triage rule above.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task A.3.3b: Cut or file per the triage rule's verdict (~5 min)
- For any phase under its 2x threshold with remaining appetite budget: for P0/P1 backlog rows with no corresponding placeholder story, add a new sub-story stub under the correct phase's epic (append to this plan.md's relevant Phase section) referencing the `gap_id`. For any phase over its 2x threshold: cut that phase's lowest-priority placeholder story/stories instead of adding, and record the cut + reasoning in `gap-backlog.md`'s reconciliation section.
- Files: `project_plans/rich-editing-experience/implementation/plan.md`, `project_plans/rich-editing-experience/implementation/gap-backlog.md`

---

## Phase B: Fix Pre-existing Recomposition-SLO Bug

### Epic B.1: Fix `onSuggestTags` closure recomposition storm
**Goal**: Eliminate the live, already-flagged (pitfalls.md §1) violation of this project's own recomposition SLO before any new toolbar work builds on top of the same anti-pattern.

#### Story B.1.1: Replace captured `allBlocks` closure with click-time lookup
**As a** developer, **I want** `EditorToolbar.kt`'s `onSuggestTags` lambda to read block state at click-time instead of capturing `allBlocks` in its closure, **so that** `MobileBlockToolbar` stops recomposing on every keystroke.
**Acceptance Criteria**:
- `onSuggestTags`'s lambda body reads `blockStateManager.blocks.value` inside the `onClick`-invoked lambda itself, not a `collectAsState()`-derived `allBlocks` captured at composable-body scope.
  - *Given* `BlockStateManager` with `blocks: StateFlow<Map<String, List<Block>>>` and `editingBlockUuid = "block-42"`, *When* the user types 10 characters into the currently-editing block then taps "Suggest tags", *Then* `EditorToolbar` recomposes 0 additional times due to the 10 keystrokes (only `BlockEditor`'s own text field recomposes), and the suggest-tags click still correctly resolves block `"block-42"` at click time.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt`

##### Task B.1.1a: Rewrite onSuggestTags to defer allBlocks read to click-time (~4 min)
- Change the `run { }` block (`EditorToolbar.kt` lines ~82-92) so `allBlocks.values.flatten().find { ... }` executes inside the returned `{ }` click lambda, not against a variable captured from the composable body; read `blockStateManager.blocks.value` directly at click-time instead of the `collectAsState()` result.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt`

##### Task B.1.1b: Verify no other EditorToolbar callback closes over allBlocks (~3 min)
- Grep `EditorToolbar.kt` for other lambda params referencing `allBlocks`; apply the same click-time-read fix if found.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt`

#### Story B.1.2: Add recomposition-count regression test
**As a** developer, **I want** an automated assertion (not just a log line) that `EditorToolbar`/`MobileBlockToolbar` do not recompose on every keystroke, **so that** this exact regression cannot silently reoccur.
**Acceptance Criteria**:
- A JVM test asserts `EditorToolbar`'s recomposition count stays constant across N simulated keystrokes into the actively-editing block.
  - *Given* a Compose test rule rendering `EditorToolbar` with a `SideEffect` recomposition counter, *When* 10 `onValueChange` calls simulate typing into the editing block, *Then* the asserted recomposition count is ≤1 (initial composition only), failing the test if it is greater.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/EditorToolbarRecompositionTest.kt` (new file — first test in this area, modeled on existing ad hoc `SideEffect` counters in `BlockList.kt`/`JournalsView.kt`/`PageView.kt`)

##### Task B.1.2a: Write EditorToolbarRecompositionTest (~5 min)
- Add a `SideEffect { recompositionCount++ }` probe wrapping `EditorToolbar` in test, simulate 10 keystroke-equivalent state updates, assert count stays ≤1.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/EditorToolbarRecompositionTest.kt`

##### Task B.1.2b: Run test against pre-fix code to confirm it fails, then against post-fix code to confirm it passes (~3 min)
- Temporarily stash Task B.1.1a's change, run test (expect fail), restore, run again (expect pass) — confirms the test actually catches the regression class.
- Files: none (verification only)

---

## Phase C: `FormatAction` Extensions

### Epic C.1: Resolve TODO-toggle Ctrl+Enter collision
**Goal**: Fix the confirmed-broken TODO-toggle path (features.md §1, §4; GAP-004-shaped backlog item) via a dedicated `TodoState` sum type + `applyTodoToggle` function (not a `FormatAction` case — see architecture-review.md Blockers 1/2 and the Pattern Decisions table), and resolve the Ctrl+Enter overload with autocomplete's "create new page" shortcut.

**Shared-shape risk note (pre-mortem P1 #4)**: `requestTodoToggle`/`todoToggleEvents` (Task C.1.1c) reuses `formatEvents`' exact `MutableSharedFlow` shape (`extraBufferCapacity=1`, no replay cache) — the same shape whose "silent drop if a Dialog blurs focus before collection" risk Phase F.1's spike exists to test. Phase C ships this SharedFlow well before Phase F.1 runs. Phase C does **not** need to re-run the spike itself — it ships assuming parity with `formatEvents`' existing (already-shipped, already-relied-upon) behavior; Phase F.1's now-widened spike (see Epic F.1 below) is the actual empirical verification point for this shared risk, once Phase F's palette work exists to trigger it. A dedicated spike per new SharedFlow before then would be premature.

#### Story C.1.1: Model todo state as a `TodoState` sum type with a dedicated `applyTodoToggle` function
**As a** user, **I want** a working keyboard shortcut to toggle a block's TODO state, **so that** I no longer have to hand-type the `TODO ` prefix.
**As an** architect, **I want** todo-state modeling kept structurally separate from `FormatAction`, **so that** the three-state TODO→DONE cycle isn't shoehorned into a binary `(prefix, suffix)` toggle, and applying `HEADING`/`QUOTE`/etc. can never silently strip a todo marker (or vice versa) — see architecture-review.md Blockers 1 and 2.
**Acceptance Criteria**:
- `TodoState` (`NONE`/`TODO`/`DOING`/`DONE`) is added as its own enum (not a `FormatAction` case), with `TodoState.parse(content: String): TodoState` and `TodoState.next(): TodoState` implementing the exact cycle `EssentialCommands.toggleTodo`'s discarded `newContent` computation already specifies (`EssentialCommands.kt:442-445`): `NONE→TODO`, `TODO→DONE`, `DOING→DONE`, `DONE→TODO`.
  - *Given* content `"Buy milk"`, *When* `TodoState.parse("Buy milk")` runs, *Then* it returns `TodoState.NONE`, and `TodoState.NONE.next() == TodoState.TODO`.
  - *Given* content `"TODO Buy milk"`, *When* `TodoState.parse(...)` runs, *Then* it returns `TodoState.TODO`, and `TodoState.TODO.next() == TodoState.DONE`.
  - *Given* content `"DONE Buy milk"`, *When* `TodoState.parse(...)` runs, *Then* it returns `TodoState.DONE`, and `TodoState.DONE.next() == TodoState.TODO` (cycles back to TODO, not NONE, per the existing specified behavior).
- A new `applyTodoToggle(value: TextFieldValue): TextFieldValue` function (`BlockEditor.kt`, alongside `applyFormatAction`) parses the current line's `TodoState`, strips only `{"TODO ", "DOING ", "DONE "}`, and prepends `next()`'s prefix (empty for `NONE`) — it never reads or mutates `FormatAction`'s existing strip-group (`BlockEditor.kt:511-514`).
  - *Given* a block with content `"Buy milk"` and `applyTodoToggle` applied, *When* the function runs, *Then* the resulting content is `"TODO Buy milk"`; *given* content already `"TODO Buy milk"`, applying `applyTodoToggle` again yields `"DONE Buy milk"`.
- Regression test: applying `FormatAction.HEADING` (via `applyFormatAction`) to a block already prefixed `"TODO "` preserves the `TODO ` marker — composition rule: the heading prefix (`"# "`) is inserted immediately after the todo prefix, so `"TODO Buy milk"` → `"TODO # Buy milk"` (todo marker stays outermost/leftmost since it is checked and re-applied by `applyTodoToggle` independently of heading state); conversely, applying `applyTodoToggle` to a `HEADING`-prefixed block (`"# Buy milk"`) preserves the heading marker, yielding `"TODO # Buy milk"`.
  - *Given* content `"TODO Buy milk"`, *When* `applyFormatAction(FormatAction.HEADING, ...)` runs, *Then* the result is `"TODO # Buy milk"` (the `TODO ` marker is preserved, not stripped as a conflicting line-prefix).
  - *Given* content `"# Buy milk"`, *When* `applyTodoToggle(...)` runs, *Then* the result is `"TODO # Buy milk"` (the `# ` heading marker is preserved).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

##### Task C.1.1a: Add TodoState enum with parse()/next() (~4 min)
- Add `enum class TodoState { NONE, TODO, DOING, DONE }` with `parse(content: String): TodoState` and `TodoState.next(): TodoState`, implementing the cycle specified above, next to `applyFormatAction` in `BlockEditor.kt` (or a small sibling file if preferred for organization).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.1.1b: Implement applyTodoToggle as a dedicated function (~5 min)
- Add `applyTodoToggle(value: TextFieldValue): TextFieldValue` implementing the narrowly-scoped strip-and-reapply logic described above; do not add any case to `FormatAction` or touch its strip-group fold (`BlockEditor.kt:511-514`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.1.1c: Add requestTodoToggle/todoToggleEvents to BlockStateManager (~4 min)
- Add `requestTodoToggle()` method + `MutableSharedFlow<Unit>` `todoToggleEvents`, mirroring `requestFormat`/`formatEvents`'s existing decoupling pattern, so keyboard/toolbar/palette call sites can dispatch a toggle without holding a direct `BlockEditor` reference.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/state/BlockStateManager.kt`

##### Task C.1.1d: Add cross-contamination regression test (HEADING vs. TODO orthogonality) (~4 min)
- Add test cases asserting `applyFormatAction(FormatAction.HEADING, ...)` on a `TODO`-prefixed block preserves the `TODO ` marker, and `applyTodoToggle(...)` on a `HEADING`-prefixed block preserves the `# ` marker, per the composition rule specified in Story C.1.1's acceptance criteria.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt`

#### Story C.1.2: Resolve Ctrl+Enter collision between autocomplete and TODO-toggle
**As a** user, **I want** Ctrl+Enter to do the right thing whether or not I'm mid-autocomplete, **so that** the two overloaded meanings (features.md §4) don't silently clobber each other.
**Acceptance Criteria**:
- `handleKeyEvent`'s Ctrl+Enter branch checks `autocompleteState != null` first (existing "create new page" behavior wins while a query is open); otherwise dispatches `requestTodoToggle()` (Story C.1.1's dedicated plumbing — not `FormatAction`).
  - *Given* `handleKeyEvent` with `autocompleteState = null` and a block with content `"Call mom"`, *When* Ctrl+Enter is pressed, *Then* `applyTodoToggle(...)` fires and content becomes `"TODO Call mom"`; *given* `autocompleteState != null` (an open `[[` query), *When* Ctrl+Enter is pressed, *Then* the existing "create new page from query" behavior fires unchanged and the todo toggle does not fire.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.1.2a: Add autocompleteState-gated Ctrl+Enter branch (~4 min)
- Modify the existing Ctrl+Enter handling around `BlockEditor.kt` line 258/407 to check `autocompleteState != null` first, falling through to `requestTodoToggle()`/`applyTodoToggle` dispatch otherwise.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.1.2b: Add regression test for both Ctrl+Enter branches (~5 min)
- Add test cases to the existing keyboard-shortcut test suite covering (a) Ctrl+Enter with autocomplete open, (b) Ctrl+Enter with autocomplete closed.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt`

### Epic C.2: Process backlog items tagged `target_phase: C`
**Goal**: Implement the remaining `FormatAction`-shaped gaps identified by Phase A's real backlog. **The stories below use an illustrative placeholder** (table insert / code-block language picker) because Phase A's actual `gap-backlog.md` does not exist yet at planning time — implementers must substitute the real `gap_id` rows tagged `target_phase: C` from `gap-backlog.md` once Phase A ships, per Story A.3.3's reconciliation.

#### Story C.2.1: Add code-block insertion as a FormatAction case (illustrative — actual item comes from Phase A's `gap-backlog.md`, e.g. `GAP-00X`)
**As a** user, **I want** a fast way to insert a fenced code block, **so that** I don't have to hand-type triple-backtick fence syntax.
**Acceptance Criteria**:
- `FormatAction.CODE_BLOCK` inserts a fenced code block wrapping the current line/selection.
  - *Given* a block with content `"print(x)"` and `FormatAction.CODE_BLOCK` applied, *When* the action runs, *Then* the resulting content is `` "```\nprint(x)\n```" `` (illustrative expected value — implementer must reconcile the exact fence-insertion convention against whatever `gap-backlog.md`'s real backlog row specifies, e.g. language-tag support, before finalizing).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.2.1a: Add CODE_BLOCK case to FormatAction + applyFormatAction (~5 min, illustrative — confirm against real backlog row first)
- Add enum case and mutation branch per the QUOTE/NUMBERED_LIST/HEADING line-prefix template.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.2.1b: Add unit test for CODE_BLOCK mutation (~3 min)
- Add a case to the existing `applyFormatAction` test coverage.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt`

#### Story C.2.2: Add table insertion as a FormatAction case (illustrative — actual item comes from Phase A's `gap-backlog.md`, e.g. `GAP-00X`) (consistency BLOCKER B2 fix)
**As a** user, **I want** a fast way to insert a markdown table skeleton, **so that** I don't have to hand-type table syntax.
**Acceptance Criteria**:
- `FormatAction.TABLE_INSERT` inserts a 2×2 markdown table skeleton with cursor placed in the first cell, matching ux.md surface (a)'s already-specified mechanism (interaction flow item 4) and UX acceptance criterion #9.
  - *Given* an empty block and `FormatAction.TABLE_INSERT` applied, *When* the action runs, *Then* the resulting content is a 2×2 markdown table skeleton (`| | |` header row + separator row + one data row, per ux.md surface (a) interaction-flow item 4) with the cursor positioned in the first cell.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.2.2a: Add TABLE_INSERT case to FormatAction + applyFormatAction (~5 min, illustrative — confirm against real backlog row first)
- Add enum case and mutation branch inserting the 2×2 markdown table skeleton with cursor in the first cell, per ux.md surface (a) item 4's already-specified mechanism.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task C.2.2b: Add unit test for TABLE_INSERT mutation (~3 min)
- Add a case to the existing `applyFormatAction` test coverage.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt`

#### Story C.2.3: Reconcile Phase C task list against real Phase A backlog rows tagged `target_phase: C`
**As a** planner, **I want** an explicit checkpoint before continuing Phase C work past the illustrative placeholders, **so that** implementation effort is spent on real, ranked gaps rather than assumptions made before the audit existed.
**Acceptance Criteria**:
- Before starting any task beyond C.1 (which is a confirmed, real bug independent of the audit), the implementer reads `gap-backlog.md`'s rows where `target_phase == C`, and either confirms Story C.2.1 (code block) and Story C.2.2 (table insert)'s placeholders match real P0/P1 rows, or substitutes the real row(s) as new sibling stories under Epic C.2.
  - *Given* `gap-backlog.md` after Phase A ships, *When* filtered to `target_phase: C`, *Then* every P0/P1 row in that filter has a corresponding story under Epic C.2 (either C.2.1 and/or C.2.2 confirmed, or new C.2.N stories added).
**Files**: `project_plans/rich-editing-experience/implementation/plan.md`, `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task C.2.3a: Filter gap-backlog.md by target_phase: C and cross-check against Story C.2.1 and Story C.2.2 (~4 min)
- Confirm or replace the illustrative placeholders (code block, table insert) with real backlog rows.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task C.2.3b: Add any missing C.2.N stories to this plan for unconfirmed real P0/P1 rows (~5 min)
- Append new stories/tasks under Epic C.2 for each real backlog row not already covered.
- Files: `project_plans/rich-editing-experience/implementation/plan.md`

---

## Phase D: `MobileBlockToolbar` Rows (one at a time)

### Epic D.0: Roborazzi baseline workflow spike (MUST resolve go/no-go before any D.1-D.3 row task)
**Goal**: Resolve the Unresolved Question ("What is the actual Roborazzi baseline storage/update workflow? — baseline `.png`s were not found in this worktree snapshot") with an explicit go/no-go **before** any row-change task begins, per adversarial-review.md Blocker 5 — four of Phase D's five epics are otherwise structurally gated on a workflow that has never been confirmed to exist/work in this repo.

#### Story D.0.1: Confirm Roborazzi baseline storage/update workflow, or establish the manual-QA fallback
**As a** developer, **I want** to know, before touching any toolbar row, whether Roborazzi baselines can actually be regenerated and reviewed in this repo's CI/local setup, **so that** Phase D's four baseline-gated epics (D.1-D.4) have a working path forward either way.
**Acceptance Criteria**:
- The spike produces an explicit go/no-go verdict recorded in `gap-backlog.md` or this plan's Unresolved Questions section: **GO** if baseline `.png`s can be located (or freshly recorded) and regenerated locally/in CI per a confirmed, reproducible procedure; **NO-GO** if no such procedure can be confirmed within the spike's time-box.
  - *Given* a search of CI artifact storage, `.github/workflows/*.yml`, and any Roborazzi Gradle config (`verifyRoborazziDebug`/`recordRoborazziDebug` tasks or equivalent), *When* the spike completes, *Then* it records either (a) GO, with the exact command(s) to record/verify baselines and where the `.png`s live, or (b) NO-GO, with what was tried and why it failed.
- If **NO-GO**, a documented fallback is established: a manual visual-QA checklist — screenshot the affected screen manually via the existing `/run` command or a platform build, side-by-side compare against a pre-change screenshot, and record a human sign-off (reviewer name + date) in the relevant task's PR description — to be used in place of Roborazzi baseline regeneration for every subsequent D.1-D.4 baseline-update task.
  - *Given* a NO-GO verdict, *When* Task D.1.1c (or D.1.2c/D.2.1b/D.3.1c/D.4.1a) is reached, *Then* its acceptance criteria is satisfied by the manual-QA checklist instead of Roborazzi regeneration, with the sign-off recorded in the PR.
**Files**: `project_plans/rich-editing-experience/implementation/plan.md` (Unresolved Questions section, updated with the verdict), `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task D.0.1a: Investigate Roborazzi baseline storage/update workflow, record GO/NO-GO (~5 min)
- Search CI workflow files, Gradle Roborazzi plugin config, and any `.png`/baseline directories (checked-in or CI-artifact-based); attempt one real record/verify cycle if feasible within the time-box.
- Files: `project_plans/rich-editing-experience/implementation/plan.md` (Unresolved Questions), `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task D.0.1b: If NO-GO, document the manual visual-QA fallback checklist (~4 min)
- Write the fallback checklist (manual screenshot via `/run` or platform build → side-by-side compare → human sign-off) into this plan's Risk Control section, referenced by Tasks D.1.1c/D.1.2c/D.2.1b/D.3.1c/D.4.1a.
- Files: `project_plans/rich-editing-experience/implementation/plan.md`

### Epic D.1: Primary row backlog items
**Goal**: Implement highest-priority Phase-A-backlog gaps affecting `MobileBlockToolbar`'s primary row (Outdent/Indent/wiki-link/Suggest-tags/Attach-image/Capture-photo), with Roborazzi baseline updates and left-handed re-verification as part of this epic's own tasks (not deferred).

#### Story D.1.1: Process backlog items tagged `target_phase: D, row: primary` (illustrative placeholder — real items from `gap-backlog.md`)
**As a** mobile user, **I want** the primary toolbar row updated per the audit's highest-priority finding (illustrative: a faster tag-insertion entry point, since tag insertion is the user's flagship example from requirements.md), **so that** the flagship before/after comparison required by requirements.md's success metrics has a concrete implementation.
**Acceptance Criteria**:
- The primary row change reduces `insert-tag.md`'s Android `current_step_count` (measured in Phase A.2.2) by at least 1 step, verified by re-measuring after the change.
  - *Given* `docs/journeys/insert-tag.md`'s pre-change Android `current_step_count` of, illustratively, 4 (open toolbar → tap Suggest-tags → wait for `SuggestionBottomSheet` → tap a suggestion), *When* the primary-row change ships, *Then* the doc's post-change count is re-measured and recorded as ≤3, with the reduction mechanism described in the journey doc (exact mechanism is Phase A's backlog finding to determine, not invented here).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`, `docs/journeys/insert-tag.md`

##### Task D.1.1a: Implement the confirmed primary-row change (~5 min, illustrative — confirm against real backlog row first)
- Modify `MobileBlockToolbar.kt`'s primary row per the real backlog item's description.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task D.1.1b: Re-measure and record post-change step count in insert-tag.md (~3 min)
- Update `current_step_count` and add a `post_fix_step_count` field with the new measured value.
- Files: `docs/journeys/insert-tag.md`

##### Task D.1.1c: Update Roborazzi baselines affected by the primary-row change (~5 min)
- **If Epic D.0's spike returned GO**: regenerate baselines for any screenshot test rendering the primary row; manually diff-review before accepting. **If NO-GO**: follow Epic D.0's manual visual-QA fallback checklist (manual screenshot via `/run`/platform build → side-by-side compare → recorded human sign-off) instead.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt` (and any other screenshot test found to render `MobileBlockToolbar`'s primary row)

##### Task D.1.1d: Re-verify left-handed mirrored layout for the primary row (~3 min)
- Manually/screenshot-verify the left-handed layout variant mirrors the change correctly.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt`

#### Story D.1.2: Process backlog items tagged `target_phase: D, journey: insert-image` (illustrative placeholder pending real Phase A backlog)
**As a** mobile user, **I want** the primary toolbar row's image-insertion flow improved per the audit's findings for `insert-image`, **so that** "insert an image" — named as a common action in requirements.md's Problem Statement, alongside tag/link/format/reorder/code-block/table/TODO — gets a real implementation home, not just a journey doc (Story A.1.2/Task A.1.2e) (consistency BLOCKER B1 fix).
**Acceptance Criteria**:
- The confirmed change reduces `insert-image.md`'s Android `current_step_count` (measured in Phase A.2.2) by at least 1 step, or measurably improves its discoverability finding, verified by re-measuring after the change — the same step-count/discoverability mechanism as Story D.1.1, applied to the Attach-image/Capture-photo primary-row flow instead of Suggest-tags. Note: `docs/bugs/open/BUG-004-wasm-page-drop-target-noop.md` (Web-platform drag-and-drop image insertion is a confirmed no-op) is existing, already-tracked evidence for the Web platform's current-state row — treat it as such rather than re-discovering it during this story's work.
  - *Given* `docs/journeys/insert-image.md`'s pre-change Android `current_step_count`, *When* the primary-row change ships, *Then* the doc's post-change count is re-measured and recorded, with the reduction mechanism described in the journey doc (exact mechanism is Phase A's backlog finding to determine, not invented here).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`, `docs/journeys/insert-image.md`

##### Task D.1.2a: Implement the confirmed image-insertion primary-row change (~5 min, illustrative — confirm against real backlog row first)
- Modify `MobileBlockToolbar.kt`'s Attach-image/Capture-photo primary-row buttons (or their wiring) per the real backlog item tagged `target_phase: D, journey: insert-image`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task D.1.2b: Re-measure and record post-change step count in insert-image.md (~3 min)
- Update `current_step_count` and add a `post_fix_step_count` field with the new measured value.
- Files: `docs/journeys/insert-image.md`

##### Task D.1.2c: Update Roborazzi baselines affected by the image-insertion change (~4 min)
- **If Epic D.0's spike returned GO**: regenerate baselines for any screenshot test rendering the primary row's image-insertion buttons; manually diff-review before accepting. **If NO-GO**: follow Epic D.0's manual visual-QA fallback checklist instead — same discipline as Task D.1.1c.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt`

### Epic D.2: Overflow (formatting) row backlog items

#### Story D.2.1: Process backlog items tagged `target_phase: D, row: overflow` (illustrative placeholder)
**As a** mobile user, **I want** the formatting overflow row updated per the audit's ranked findings (illustrative: adding the "{ } Code block" AND "▦ Table" buttons alongside Quote/Numbered-list/Heading, matching ux.md surface (a)'s overflow-row design exactly), **so that** newly-added `FormatAction` cases from Phase C (`CODE_BLOCK`, `TABLE_INSERT`) are reachable from the mobile toolbar, not just keyboard.
**Acceptance Criteria**:
- Any `FormatAction` case added in Phase C that the real backlog flags as toolbar-worthy gets a corresponding overflow-row button — illustratively, both "{ } Code block" (`FormatAction.CODE_BLOCK`, Phase C.2.1) and "▦ Table" (`FormatAction.TABLE_INSERT`, Phase C.2.2), per ux.md surface (a)'s overflow-row mockup.
  - *Given* `FormatAction.CODE_BLOCK` exists (Phase C.2.1) and `gap-backlog.md` flags it `target_phase: D`, *When* the overflow row is updated, *Then* tapping the new "{ } Code block" button in `MobileBlockToolbar`'s overflow row calls `blockStateManager.requestFormat(FormatAction.CODE_BLOCK)` and the resulting content matches the same fence-insertion behavior verified in Task C.2.1b.
  - *Given* `FormatAction.TABLE_INSERT` exists (Phase C.2.2) and `gap-backlog.md` flags it `target_phase: D`, *When* the overflow row is updated, *Then* tapping the new "▦ Table" button in `MobileBlockToolbar`'s overflow row calls `blockStateManager.requestFormat(FormatAction.TABLE_INSERT)` and the resulting content matches the same 2×2-skeleton-with-cursor-in-first-cell behavior verified in Task C.2.2b.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task D.2.1a: Add overflow-row button(s) for confirmed backlog items (~5 min, illustrative — confirm against real backlog row first)
- Add the "{ } Code block" and "▦ Table" buttons (or whichever the real backlog confirms) calling `requestFormat` with the relevant `FormatAction` case(s).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task D.2.1b: Update Roborazzi baselines + left-handed re-verification for overflow row (~5 min)
- Same discipline as Task D.1.1c/d, scoped to the overflow row — GO path regenerates baselines; NO-GO path follows Epic D.0's manual visual-QA fallback checklist.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt`

### Epic D.3: Bottom row backlog items

**Supersession note (engineering triad blocker fix)**: `docs/tasks/drag-and-drop-reorder.md` (Status: Planning, dated 2026-04-17, never started) is a pre-existing, more detailed plan targeting this exact same file surface (`BlockGutter.kt`, `BlockDragGhost.kt`, `BlockList.kt`) for mouse/long-press drag-and-drop block reordering — the same mechanism ux.md surface (e) specifies for this epic. **This project's reorder implementation supersedes and absorbs the relevant in-scope slice of that plan, rather than running as a parallel, conflicting effort against the identical files.** Implementers should read it as reusable prior-art detail, not re-derive it from scratch — specifically: its ghost-overlay design (`BlockDragGhost`, Story 3), its drop-indicator divider-line design (Story 2), and its <300ms perceived-latency success metric ("see a ghost preview follow the pointer, see a drop indicator line... release to reorder — all in under 300 ms of perceived latency"). **Scope boundary (cut-not-grow, per Story A.3.3's triage rule)**: this epic implements only what Phase A's real audit backlog confirms as a high-priority reorder gap (illustratively, the mobile long-press drag-handle affordance ux.md surface (e) proposes). `drag-and-drop-reorder.md`'s broader specified surface — desktop mouse drag-and-drop as its own confirmed-priority item, center-zone "make child" drag-to-reparent, and multi-level subtree-drag edge cases (its Story 5) — is **not** assumed in-scope here unless Phase A's audit specifically flags it as P0/P1; that remaining scope stays out of scope for this project, and `docs/tasks/drag-and-drop-reorder.md` remains the reference plan for any future pickup of it.

#### Story D.3.1: Process backlog items tagged `target_phase: D, row: bottom` (illustrative placeholder)
**As a** mobile user, **I want** the bottom row (Undo/Redo/Move-up/down/Add-block/Paste) updated per the audit's block-reorder finding (ux.md §1's highest-value gap candidate), **so that** reordering is measurably faster or more discoverable than today's Move-Up/Down-only flow.
**Acceptance Criteria**:
- The bottom row's reorder affordance change reduces `reorder-block.md`'s Android `current_step_count` (from Phase A.2.2) or improves its discoverability finding, whichever the real backlog row targets.
  - *Given* `docs/journeys/reorder-block.md`'s pre-change Android step count, *When* the bottom-row change ships, *Then* the doc is re-measured and the `post_fix_step_count`/discoverability note reflects a concrete, measured improvement (not asserted without measurement).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`, `docs/journeys/reorder-block.md`

##### Task D.3.1a: Implement confirmed bottom-row change (~5 min, illustrative — confirm against real backlog row first)
- Modify `MobileBlockToolbar.kt`'s bottom row per the real backlog item.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task D.3.1b: Re-measure and record post-change result in reorder-block.md (~3 min)
- Update the journey doc with the measured after-state.
- Files: `docs/journeys/reorder-block.md`

##### Task D.3.1c: Update Roborazzi baselines + left-handed re-verification for bottom row (~5 min)
- Same discipline as prior rows — GO path regenerates baselines; NO-GO path follows Epic D.0's manual visual-QA fallback checklist.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt`

### Epic D.4: Selection-mode toolbar + left-handed layout final re-verification
**Goal**: One consolidated re-verification pass across the whole toolbar after all three rows have changed, since left-handed-mirroring risk compounds per architecture.md §5/pitfalls.md §3.

**Supersession note (engineering triad blocker fix)**: `docs/tasks/multi-block-selection.md` (Status: Planning, dated 2026-04-17, never started) is a pre-existing, more detailed plan targeting the identical files this epic's selection-mode re-verification touches (`MobileBlockToolbar.kt`, `BlockStateManager.kt`, `BlockGutter.kt`, `BlockItem.kt`). **This project's selection-mode work (and Story A.1.3's multi-select-block journey/audit feeding it) supersedes and absorbs the relevant in-scope slice of that plan, rather than running as a parallel, conflicting effort.** Implementers should read its Story 1 (Selection State Foundation — `selectedBlockUuids`, visual highlight/checkbox), Story 2 (Keyboard Selection — Shift+click, Shift+Arrow, Ctrl+A), and Story 3 (Mobile Selection UX — long-press entry, toolbar Delete action) breakdowns as reusable prior-art detail, not re-derive this design from scratch. **Scope boundary (cut-not-grow, per Story A.3.3's triage rule)**: this project implements only what Phase A's real audit backlog confirms as a high-priority multi-select gap. `multi-block-selection.md`'s broader specified surface — Story 4 (multi-block drag-to-reparent onto a new parent) and bulk delete (MB-10/MB-11) — is **not** assumed in-scope here unless the audit specifically flags it as P0/P1; that remaining scope stays out of scope for this project, and `docs/tasks/multi-block-selection.md` remains the reference plan for any future pickup of it.

#### Story D.4.1: Full selection-mode + left-handed regression pass
**As a** developer, **I want** one final full-toolbar screenshot pass after D.1-D.3 land, **so that** compounding pixel drift across 3 independently-shipped row changes is caught before Phase E starts.
**Acceptance Criteria**:
- **If Epic D.0 returned GO**: all `MobileScreenshotTest.kt` cases (both handedness variants, both selection-mode and normal-mode) pass against freshly-reviewed (not blindly-regenerated) baselines.
  - *Given* the full `MobileScreenshotTest.kt` suite run via `xvfb-run --auto-servernum ./gradlew jvmTest --tests "dev.stapler.stelekit.ui.screenshots.MobileScreenshotTest"`, *When* it completes, *Then* all cases pass and any diff images were manually reviewed (not auto-accepted) per pitfalls.md §3's explicit warning against silent visual regressions.
- **If Epic D.0 returned NO-GO**: a full manual visual-QA pass (per Epic D.0's fallback checklist) covers both handedness variants and both selection-mode/normal-mode across all three changed rows, with a recorded human sign-off substituting for the automated suite run.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt`

##### Task D.4.1a: Run full MobileScreenshotTest suite (or manual visual-QA fallback pass), review all diffs (~5 min)
- **GO path**: execute the suite, manually inspect each changed baseline image before accepting. **NO-GO path**: execute Epic D.0's manual visual-QA checklist across all three changed rows, both handedness variants, both modes, and record sign-off.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/MobileScreenshotTest.kt`

#### Story D.4.2: Add an explicit, discoverable entry point into block multi-select (new — filed per Phase A's `gap-backlog.md` GAP-011, Story A.3.3 reconciliation)
**As a** user who cannot or does not know to long-press (mouse-only desktop session, or assistive-tech users), **I want** a visible, non-gesture way to enter block multi-select mode, **so that** selection mode isn't gated entirely behind a pointer/touch-dependent gesture with no fallback.
**Provenance**: this story did not exist as a Phase D placeholder at planning time. Phase A's audit (`docs/journeys/multi-select-block.md`) confirmed, by reading `BlockItem.kt:249-258`, that long-press is the **only** entry point into selection mode — no explicit "Select" button and no keyboard-only path exist. This resolves features.md §3's open question ("confirm... whether entry is long-press or a separate explicit action") with a concrete "long-press only" finding. Filed as `GAP-011` (P1, `target_phase: D`) in `gap-backlog.md`; per Story A.3.3's cut-not-grow triage rule, Phase D's real P0/P1 row count (6) stayed at or under 2× its 4-story baseline (8), so this uncovered finding was added as a new sibling story rather than requiring a cut elsewhere.
**Acceptance Criteria**:
- A discoverable, non-long-press entry point into selection mode exists on at least one platform where long-press is awkward or unavailable (illustratively: a "Select" affordance reachable from the bottom row or an existing overflow surface on touch platforms; a keyboard-triggerable entry — e.g. via the Command Palette once Phase F lands, or a dedicated shortcut — on Desktop/Web). Exact mechanism is this story's own implementation decision, not invented here; Move-Up/Move-Down-style "always-visible fallback" precedent (design/ux.md's discoverability guard for `reorder-block`) is the model to follow, not a gesture-only fix.
  - *Given* a mouse-only Desktop session with no touch/long-press input available, *When* the user wants to select multiple blocks, *Then* a non-gesture path into `blockStateManager.enterSelectionMode(...)` exists and is discoverable without prior knowledge (visible button, palette entry, or documented shortcut — not tribal knowledge).
- The existing long-press entry point (`BlockItem.kt:249-258`) is retained unchanged — this is an additive fallback, not a replacement, consistent with this plan's additive-only rollout discipline (Risk Control section).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`, `docs/journeys/multi-select-block.md`

##### Task D.4.2a: Confirm the real backlog row and decide the concrete non-gesture mechanism (~4 min)
- Re-read `gap-backlog.md`'s GAP-011 row and `multi-select-block.md`'s current-state findings; decide the specific entry-point mechanism per platform (button vs. palette vs. shortcut) before implementing.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`, `docs/journeys/multi-select-block.md`

##### Task D.4.2b: Implement the confirmed non-gesture entry point (~5 min)
- Add the decided mechanism, calling the existing `enterSelectionMode(uuid)` — no changes to the selection-mode machinery itself, only to how it's entered.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockItem.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MobileBlockToolbar.kt`

##### Task D.4.2c: Re-measure and record the fix in multi-select-block.md (~3 min)
- Update the journey doc's discoverability checklist and platform notes to reflect the new entry point.
- Files: `docs/journeys/multi-select-block.md`

---

## Phase E: Keyboard Shortcut Additions

### Epic E.1: IME composition guard (cross-cutting)
**Goal**: Apply the `composition == null` guard (pitfalls.md §2/§3) to every existing and new autocomplete/shortcut trigger site in one pass, per the Pattern Decisions table.

#### Story E.1.1: Guard all autocomplete triggers against active IME composition
**As a** CJK/Japanese/Korean-input user, **I want** `[[`/`#` autocomplete (and any new triggers from Phase C/E) to ignore transient IME composition text, **so that** partial candidate text never falsely triggers autocomplete.
**Acceptance Criteria**:
- Every `onValueChange`-driven trigger check in `BlockEditor.kt` is preceded by a `newValue.composition == null` guard.
  - *Given* a `TextFieldValue` with `text = "こんにちは["` and `composition = TextRange(5, 6)` (active IME composition), *When* `onValueChange` runs, *Then* the `[[` autocomplete trigger does NOT fire (guard short-circuits); *given* the same text with `composition = null` (composition committed), *When* `onValueChange` runs again, *Then* the trigger evaluates normally.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task E.1.1a: Add composition==null guard to `[[` and `#` trigger checks (~4 min)
- Wrap the existing wiki-link and hashtag regex-trigger checks in `BlockEditor.kt`'s `onValueChange` handler with a `newValue.composition == null` precondition.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task E.1.1b: Add composition==null guard to detectSoftKeyboardBracketWrap (~3 min)
- Apply the same guard inside `detectSoftKeyboardBracketWrap` (`BlockEditor.kt:633-646`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task E.1.1c: Add composition==null guard to any new Phase-C-added triggers (~3 min)
- Confirm no new Phase C trigger (e.g. any typed-pattern trigger, if the real backlog adds one) bypasses the guard.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task E.1.1d: Add manual-QA checklist entry for CJK IME testing (~3 min)
- Since JVM/desktop tests cannot reproduce IME composition (pitfalls.md §2), add an explicit manual-device-test checklist item to this plan's verify-phase notes.
- Files: `project_plans/rich-editing-experience/implementation/plan.md` (Unresolved Questions / verify-phase notes section)

### Epic E.2: Browser-reserved-shortcut audit (Web)

#### Story E.2.1: Enumerate and resolve browser-reserved shortcut conflicts
**As a** Web user, **I want** any shortcut this project keeps or adds that collides with a browser-reserved binding (Ctrl+S/Ctrl+F/Ctrl+P/Ctrl+W/T/N) to have a working toolbar/menu fallback, **so that** Web users aren't silently missing functionality that Desktop/Android users have.
**Acceptance Criteria**:
- Every shortcut in `handleKeyEvent` is checked against the browser-reserved list; any confirmed-unsuppressable one (Ctrl+W/T/N, and Ctrl+S if verified non-suppressable) has a toolbar-or-palette equivalent already available on Web.
  - *Given* the existing Ctrl+S shortcut in `handleKeyEvent`, *When* tested on the WASM/Web target by pressing Ctrl+S in a focused block, *Then* the empirical result (event suppressed vs. browser save-dialog still fires) is recorded in `gap-backlog.md`, and if unsuppressable, a corresponding command-palette or toolbar save-equivalent is confirmed to already exist (or is filed as a new P0 backlog row if not).
**Files**: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task E.2.1a: Empirically test Ctrl+S/Ctrl+F/Ctrl+P suppression on Web build (~5 min)
- Build and run `bazel build //kmp:web_app`, test each shortcut in-browser, record pass/fail per shortcut.
- Files: none (manual verification), results recorded in `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task E.2.1b: File backlog rows for any confirmed-unsuppressable shortcut lacking a fallback (~4 min)
- Add P0 rows to `gap-backlog.md` for any gap found.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

### Epic E.3: Process backlog items tagged `target_phase: E`

#### Story E.3.1: Add keyboard shortcuts for confirmed backlog items (illustrative placeholder — actual items from `gap-backlog.md`)
**As a** power user, **I want** a hardware-keyboard shortcut for the highest-priority missing action found by the audit (illustrative: Ctrl+Shift+C for `FormatAction.CODE_BLOCK` from Phase C), **so that** desktop users get the same speed benefit mobile-toolbar users get from Phase D.
**Acceptance Criteria**:
- A new guarded `if` block is added to `handleKeyEvent`'s cascade dispatching the confirmed `FormatAction`/`StructuralAction`.
  - *Given* `handleKeyEvent` and a Ctrl+Shift+C keydown event with a block in edit mode, *When* the event is dispatched, *Then* `applyFormatAction(FormatAction.CODE_BLOCK, ...)` fires exactly once (illustrative binding — confirm exact key combination against the real backlog row and check for conflicts with existing bindings before finalizing).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task E.3.1a: Add new shortcut branch to handleKeyEvent cascade (~4 min, illustrative — confirm against real backlog row + check for keybinding conflicts first)
- Add guarded `if` block checking `isCtrlPressed || isMetaPressed` plus the relevant additional modifier/key, following the existing Ctrl+B/I/S/H/E pattern.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task E.3.1b: Add regression test for the new shortcut (~3 min)
- Add a case to `KeyboardShortcutTest.kt`.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/KeyboardShortcutTest.kt`

##### Task E.3.1c: Verify shortcut on iOS hardware keyboard manually (~5 min)
- Per stack.md §6, iOS hardware-keyboard dispatch has never been tested in this repo — manually verify via Gradle iOS build with a Bluetooth/Smart keyboard before considering this shortcut cross-platform-complete.
- Files: none (manual verification, results recorded in `docs/journeys/format-text.md` or the relevant journey doc's platform notes)

---

## Phase F: Command-Palette Entries

### Epic F.1: Empirical focus/blur risk spike (MUST PASS before F.2)

#### Story F.1.1: Verify whether opening CommandPalette blurs the editing block's focus/composition
**As a** developer, **I want** to empirically confirm whether `CommandPalette`'s `Dialog` steals focus from the actively-editing `BlockEditor` before wiring any palette→`requestFormat`/`requestTodoToggle`/`requestStructuralAction` action, **so that** Phase F doesn't ship a silently-non-functional "Format > Bold" (or "Toggle TODO") entry (the exact anti-pattern found in `EssentialCommands.toggleTodo`).
**Acceptance Criteria** (widened per pre-mortem P1 #4 — this spike is the sole empirical verification point for every `formatEvents`-shaped SharedFlow shipped since Phase C, not just `FormatAction`):
- A manual or instrumented test opens `CommandPalette` while a block is `isEditing == true`, and records whether `BlockItem`'s `LaunchedEffect(isEditing, formatEvents)` collector scope survives (pass) or is torn down (fail) during the palette's open state — covering BOTH the `FormatAction`/`formatEvents` case AND the `requestTodoToggle()`/`todoToggleEvents` case (Phase C.1), since both are collected by the exact same `BlockItem.kt:220-231` `LaunchedEffect(isEditing, formatEvents)` scope and share the identical no-replay-cache SharedFlow shape.
  - *Given* `BlockItem` with `isEditing = true` for block `"block-7"` and `CommandPalette`'s `Dialog` opened via Ctrl+Shift+P, *When* the palette is open and a `FormatAction.BOLD` is emitted via `blockStateManager.requestFormat(FormatAction.BOLD)` from a test harness, *Then* the test records PASS if `applyFormatAction` executes against block `"block-7"`'s content, or FAIL if the emission is silently dropped (no replay cache, per architecture.md §3) — either result is written to this story's acceptance record before Story F.2 begins.
  - *Given* the same `BlockItem`/`CommandPalette` setup, *When* the palette is open and `blockStateManager.requestTodoToggle()` is invoked from a test harness, *Then* the test records PASS if `applyTodoToggle` executes against block `"block-7"`'s content, or FAIL if the emission is silently dropped — recorded alongside the `FormatAction.BOLD` result before Story F.2 begins.
  - If a `StructuralAction`/`structuralActionEvents` SharedFlow also exists by the time this story runs (only if Phase C.2 introduced a concrete `StructuralAction` case — not forced if it didn't), cover that case too with the same PASS/FAIL assertion; do not add a `StructuralAction` case to this test artificially if Phase C never introduced one.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/CommandPaletteFocusRetentionTest.kt` (new file)

##### Task F.1.1a: Write CommandPaletteFocusRetentionTest (~5 min, widened per pre-mortem P1 #4)
- Render `BlockItem` in editing state + `CommandPalette` open simultaneously in a Compose test; emit a `FormatAction` via `requestFormat` AND separately emit a todo-toggle via `requestTodoToggle()`, asserting whether each was applied. Add a third case for `requestStructuralAction`/`structuralActionEvents` only if a concrete `StructuralAction` case exists in the codebase by this point.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/CommandPaletteFocusRetentionTest.kt`

##### Task F.1.1b: Record PASS/FAIL result and, if FAIL, design the fix before proceeding (~5 min)
- If the test fails (focus lost / emission dropped), design a fix (e.g. palette action reads `blockStateManager` reference directly, bypassing the `formatEvents` SharedFlow, or the palette defers closing until after dispatch) and add it as a task here before any Story F.2 task is started.
- Files: `project_plans/rich-editing-experience/implementation/plan.md` (this section, appended with the fix design if needed)

**RECORDED RESULT (empirical, both cases): PASS.**

`CommandPaletteFocusRetentionTest` renders the real production `BlockItem` (isEditing = true,
`formatEvents`/`todoToggleEvents` wired) and the real production `CommandPalette` (visible = true,
its own `Dialog` open) simultaneously in a Compose Desktop UI test (`createComposeRule()`), wiring
`onStopEditing = { isEditingState.value = false }` exactly as `BlockEditor.kt:210-216`/
`BlockStateManager.stopEditingBlock` do in production. Emitting `FormatAction.BOLD` via the shared
`formatEvents` flow, and separately `Unit` via `todoToggleEvents`, while the palette's `Dialog` is
open:
- `FormatAction.BOLD` case: **PASS** — content mutated (`"hello"` → `"****hello"`, collapsed-cursor
  insert since the harness's default `TextFieldValue` has no selection) and `isEditing` remained
  `true` throughout — the palette's `Dialog` did not blur the editing block's focus in this test.
- `requestTodoToggle()` case: **PASS** — content mutated (`"Buy milk"` → `"TODO Buy milk"`) and
  `isEditing` remained `true` throughout.
- No `StructuralAction`/`structuralActionEvents` exists in the codebase as of this phase (confirmed
  by repo-wide search), so no third case was added, per Task F.1.1a's conditional instruction.

Both cases collected and applied cleanly — `BlockItem`'s `LaunchedEffect(isEditing, formatEvents)`
/ `LaunchedEffect(isEditing, todoToggleEvents)` collector scopes were never torn down while the
palette was open, so the "focus-loss-before-collection drop is silent" risk documented in
architecture.md §3 did **not** reproduce in this harness. **No fix is required** — Epic F.2 proceeds
using the existing `requestFormat`/`requestTodoToggle()` SharedFlow dispatch path unchanged.

Caveat: this is a single-process JVM Compose UI test; `CommandPalette`'s `Dialog` may render as a
genuinely separate OS-level window on real Desktop, and this harness cannot fully rule out
window-manager-driven focus arbitration that differs from what `ComposeTestRule` reproduces here.
The recorded verdict reflects the empirical, repeatable, CI-enforced result from the actual
production composables under the project's own Compose UI test infrastructure, which is what this
spike's acceptance criteria call for.

### Epic F.2: Add format/structural command-palette entries (gated on F.1 PASS)

#### Story F.2.1: Add "Format: Bold/Italic/.../Code Block" palette entries (illustrative placeholder — confirm against real backlog items tagged `target_phase: F`)
**As a** keyboard-first user, **I want** formatting actions discoverable via the command palette, **so that** users who don't know the Ctrl+B-style shortcuts can still find and trigger formatting.
**Acceptance Criteria** (BLOCKED until Story F.1.1 records PASS):
- New `Command` entries (`id: "format.bold"`, etc.) are added to `StelekitViewModel`'s command assembly, each calling `blockStateManager.requestFormat(FormatAction.X)` where `blockStateManager` refers to the currently-focused block's manager.
  - *Given* `StelekitViewModel`'s command list with a currently-editing block `"block-7"` and a new `Command(id="format.bold", label="Format: Bold", shortcut="Ctrl+B", action = { blockStateManager.requestFormat(FormatAction.BOLD) })`, *When* the user opens the palette and selects "Format: Bold", *Then* block `"block-7"`'s content is wrapped in `**...**` exactly as if Ctrl+B had been pressed directly (verified per Story F.1.1's confirmed-working dispatch path).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task F.2.1a: Add currently-editing-block reference to command assembly context (~4 min)
- Thread a reference to the currently-focused block's `BlockStateManager` into `updateCommands()`/`legacyCommands` assembly (`StelekitViewModel.kt:2076-2109`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task F.2.1b: Add Command entries for each FormatAction case confirmed by real backlog (~5 min, illustrative — confirm against real backlog first)
- Add `Command` entries calling `requestFormat` for each backlog-confirmed action.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task F.2.1c: Add integration test confirming palette-triggered format actually mutates content (~4 min)
- Extend `CommandPaletteFocusRetentionTest.kt` (or a sibling test) to assert the full palette-select→content-mutation path.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/CommandPaletteFocusRetentionTest.kt`

### Epic F.3: Canonical shortcut table (fixes badge/binding drift)

#### Story F.3.1: Establish one canonical shortcut table read by both handleKeyEvent and Command.shortcut badges
**As a** developer, **I want** a single source of truth for "what shortcut string corresponds to what action," **so that** the palette's display-only `shortcut` badge (`CommandItem`, `CommandPalette.kt:208-223`) can never drift from `handleKeyEvent`'s actual live binding (stack.md §2's audit finding).
**Acceptance Criteria**:
- A new `object ShortcutTable` (or equivalent) maps `FormatAction`/`StructuralAction` → canonical shortcut display string, consumed by both `handleKeyEvent`'s cascade (as a lookup, not hardcoded duplicate strings) and `StelekitViewModel`'s `Command.shortcut` values.
  - *Given* `ShortcutTable.forAction(FormatAction.BOLD) == "Ctrl+B"`, *When* `handleKeyEvent` checks for the Bold shortcut, *Then* it reads the same `"Ctrl+B"`-producing key-combination check that `ShortcutTable` documents, and `Command(id="format.bold", ...).shortcut` reads `ShortcutTable.forAction(FormatAction.BOLD)` rather than a separately hardcoded `"Ctrl+B"` string literal.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShortcutTable.kt` (new file), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task F.3.1a: Create ShortcutTable mapping FormatAction/StructuralAction to display strings (~5 min)
- New file defining the canonical map.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ShortcutTable.kt`

##### Task F.3.1b: Repoint handleKeyEvent's shortcut checks to reference ShortcutTable constants (~5 min)
- Replace hardcoded key-combination literals in `handleKeyEvent` with references to `ShortcutTable`'s canonical values where feasible without behavior change.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockEditor.kt`

##### Task F.3.1c: Repoint Command.shortcut values to ShortcutTable (~4 min)
- Update `StelekitViewModel`'s command assembly to read `ShortcutTable.forAction(...)` instead of hardcoded shortcut strings.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

---

## Phase G: Annotation Editor Track (fully separate)

### Epic G.1: Deep annotation journey-map + audit
**Goal**: Apply the same rigor as Phase A, scoped to `AnnotationToolbar.kt`/`AnnotationEditorScreen.kt`/`TagEditorPanel.kt`, updating `docs/journeys/annotate-asset.md` from its Phase-A stub to a fully verified doc.

#### Story G.1.1: Document annotation-editor journey in full (upgrade the Phase A stub)
**As a** planner, **I want** the annotation-editor journey documented at the same depth as main-editor journeys, **so that** its social-JTBD-tier gaps (discoverability/correctness of annotation labeling) are captured per ux.md §5.
**Acceptance Criteria**:
- `docs/journeys/annotate-asset.md`'s `status` field changes from `stub-pending-phase-g` to `verified`, with `jtbd_tier: social` step sequences (per platform) and a discoverability-focused `heuristic_findings` section (not a step-count target, per the rubric established in A.2.1).
  - *Given* `docs/journeys/annotate-asset.md`, *When* the annotation label-insertion flow (`TagEditorPanel.kt`) is documented, *Then* the doc records the current tool-selection → draw-annotation → label-via-`TagEditorPanel` sequence per platform, with a discoverability finding (not a numeric step-count grade, since `jtbd_tier: social`).
**Files**: `docs/journeys/annotate-asset.md`

##### Task G.1.1a: Record per-platform annotation step sequences (~5 min)
- Document current flow citing `AnnotationToolbar.kt`, `AnnotationEditorScreen.kt`, `TagEditorPanel.kt`.
- Files: `docs/journeys/annotate-asset.md`

##### Task G.1.1b: Run ux-expert heuristic review scoped to annotation editor (~4 min)
- Dispatch `ux-expert` agent review against the updated doc; update `status: verified`.
- Files: `docs/journeys/annotate-asset.md`

#### Story G.1.2: Produce annotation-specific backlog rows
**As a** planner, **I want** annotation-editor gaps entered into the same `gap-backlog.md` schema, **so that** Phase G.2 has a concrete worklist consistent with Phases C-F's format.
**Acceptance Criteria**:
- `gap-backlog.md` gains rows with `target_phase: G`, ranked P0-P3, sourced from Story G.1.1's heuristic findings.
  - *Given* a finding that annotation undo/redo (`AnnotationEditorScreen.kt:160-163`) has no visible keyboard shortcut, *When* transcribed to the backlog, *Then* it appears as a row with `target_phase: G`, `jtbd_tier: social`, and an appropriate priority.
**Files**: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task G.1.2a: Transcribe annotation findings into gap-backlog.md (~4 min)
- Add ranked rows tagged `target_phase: G`.
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

### Epic G.2: Implement highest-priority annotation fixes

#### Story G.2.1: Implement top-ranked annotation-editor fix (illustrative placeholder — actual item from `gap-backlog.md` rows tagged `target_phase: G`)
**As an** annotation user, **I want** the highest-priority annotation-editor gap fixed (illustrative: a discoverable keyboard shortcut for Undo/Redo mirroring the main editor's convention), **so that** the annotation editor benefits from the same UX discipline as the main editor without sharing implementation (per architecture.md §2's "no shared abstraction" finding).
**Acceptance Criteria**:
- The fix is implemented entirely within `ui/annotate/` files, with zero changes to `BlockEditor.kt`/`MobileBlockToolbar.kt`/`FormatAction` (confirming the "structurally separate, no shared-code risk" property architecture.md §2 relies on).
  - *Given* `AnnotationEditorScreen`'s own `canUndo`/`canRedo` state (`AnnotationEditorScreen.kt:160-163`), *When* the confirmed fix (illustrative: Ctrl+Z/Ctrl+Shift+Z hardware binding) is added, *Then* it dispatches against `AnnotationEditorScreen`'s own undo/redo stack exclusively — not `BlockStateManager`'s — verified by the PR diff touching only `ui/annotate/*` files.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationToolbar.kt`

##### Task G.2.1a: Implement confirmed annotation fix (~5 min, illustrative — confirm against real backlog row first)
- Add the fix scoped entirely within `ui/annotate/` files.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationEditorScreen.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/annotate/AnnotationToolbar.kt`

##### Task G.2.1b: Update annotation-related screenshot baselines if visual (~4 min)
- Regenerate/manually review any affected Roborazzi baseline.
- Files: (screenshot test file covering annotation screens, to be identified during Phase G — none confirmed in current repo survey; if none exists, note that as a gap in `docs/journeys/annotate-asset.md`)

##### Task G.2.1c: Record before/after in docs/journeys/annotate-asset.md (~3 min)
- Update the journey doc with the implemented fix and its discoverability improvement.
- Files: `docs/journeys/annotate-asset.md`

---

## Phase G2: Voice-Mode Input Implementation (independently schedulable, same footing as Phase G — added per adversarial-review.md Blocker 4)

### Epic G2.1: Implement highest-priority voice-mode backlog item
**Goal**: Give voice-mode gaps mapped/benchmarked in Phase A (Story A.1.4, Epic A.2) a real implementation home — requirements.md's success metric requires "a full overhaul, not an audit-only deliverable" for all three named surfaces (main editor, annotation editor, voice-mode input), and without this phase voice-mode findings had nowhere to land (the backlog's `target_phase` enum only covered C/D/E/F/G). Sized deliberately small: voice-mode is a named **secondary** surface (requirements.md Users/Consumers: "Secondary: ... users who use voice input") and the overall appetite is Large-but-bounded (3–6 weeks) — this phase implements exactly one confirmed high-priority fix, not a full voice-mode overhaul.

#### Story G2.1.1: Implement top-ranked voice-mode backlog item (illustrative placeholder — actual item from `gap-backlog.md` rows tagged `target_phase: G2`)
**As a** voice-input user, **I want** the single highest-priority voice-mode gap fixed, **so that** voice-mode input receives the same "audit + at least one real fix" treatment as the main editor and annotation editor, without over-scoping a secondary surface against the Large appetite.
**Acceptance Criteria**:
- Exactly one voice-mode fix — the highest-priority (P0, else highest P1) backlog row tagged `target_phase: G2` — is implemented, scoped to voice-mode-specific files (illustratively `VoiceCaptureButton.kt` and its platform gating, confirmed during Task A.1.4a) only, with zero changes to `BlockEditor.kt`/`MobileBlockToolbar.kt`/`FormatAction`/`StructuralAction` (voice-mode is a structurally separate input modality per requirements.md's Rabbit Holes section).
  - *Given* `gap-backlog.md`'s highest-priority row tagged `target_phase: G2` (illustrative: "voice-capture button has no discoverable stop/cancel affordance on Android"), *When* the confirmed fix ships, *Then* `docs/journeys/voice-capture.md` is re-measured/re-reviewed and its `heuristic_findings`/`current_step_count` reflects the improvement, and the PR diff touches only voice-mode-specific files.
**Files**: voice-mode source files confirmed during Task A.1.4a's platform-gating investigation (illustratively `VoiceCaptureButton.kt` and its call sites), `docs/journeys/voice-capture.md`

##### Task G2.1.1a: Filter gap-backlog.md by target_phase: G2, confirm the single highest-priority row (~3 min)
- Same reconciliation discipline as Task C.2.3a, scoped to voice-mode rows; if no P0/P1 row exists tagged `target_phase: G2`, record that finding and treat this epic as a no-op (confirmed-no-gap is a valid, appetite-preserving outcome).
- Files: `project_plans/rich-editing-experience/implementation/gap-backlog.md`

##### Task G2.1.1b: Implement the confirmed voice-mode fix (~5 min, illustrative — confirm against real backlog row first)
- Implement the single highest-priority voice-mode fix, scoped to voice-mode-specific files only, per the "no shared abstraction with main editor" property this phase relies on.
- Files: voice-mode source files confirmed during Task G2.1.1a

##### Task G2.1.1c: Re-measure/re-review and record before/after in voice-capture.md (~3 min)
- Update the journey doc with the implemented fix and its measured step-count or discoverability improvement.
- Files: `docs/journeys/voice-capture.md`

---

## Phase H: Orphaned Command-Framework Resolution

### Epic H.1: Delete unreachable orphaned framework files
**Goal**: Remove the confirmed-zero-call-site parts of the `editor/` package per ADR-001, sequenced last to avoid conflicting with Phases C/E/F's command-registration surface (architecture.md §5).

#### Story H.1.1: Confirm zero live call sites before deleting (safety check)
**As a** developer, **I want** to re-confirm (post Phases C-F, which may have touched command-registration code) that `SlashCommandHandler.kt`, `editor/RichTextEditor.kt`, `editor/components/RichTextEditor.kt`, and `editor/performance/PerformanceOptimizedEditor.kt` still have zero UI/platform call sites, **so that** deletion doesn't break something Phase F's palette work incidentally started depending on.
**Acceptance Criteria**:
- A repo-wide grep for references to `SlashCommandHandler`, `RichTextEditor` (both files), and `PerformanceOptimizedEditor` outside their own `businessTest` coverage and their own file returns zero UI/platform-source-set hits.
  - *Given* `grep -r "SlashCommandHandler" kmp/src/ --include=*.kt`, *When* run, *Then* all hits are confined to `editor/commands/SlashCommandHandler.kt` itself and its own `businessTest` file(s) — any hit in `ui/`, `jvmMain/`, `androidMain/`, `iosMain/`, or `jsMain/` blocks deletion and must be investigated first.
**Files**: none (investigation only)

##### Task H.1.1a: Grep for live call sites of each orphaned file (~4 min)
- Run targeted greps for `SlashCommandHandler`, `RichTextEditor`, `PerformanceOptimizedEditor` across `kmp/src/`.
- Files: none (investigation only)

#### Story H.1.2: Delete confirmed-unreachable files
**As a** developer, **I want** the confirmed-dead files removed, **so that** the codebase no longer carries a maintenance burden for infrastructure that cannot be reached from any UI.
**Acceptance Criteria**:
- `SlashCommandHandler.kt`, `editor/RichTextEditor.kt`, `editor/components/RichTextEditor.kt`, `editor/performance/PerformanceOptimizedEditor.kt`, `editor/commands/README.md`, `editor/commands/UNDO_REDO_README.md` are deleted, and `bazel test //...` still passes (confirming no hidden dependency).
  - *Given* the deletion commit, *When* `bazel test //kmp:jvm_tests` and `bazel test //kmp:business_tests` run, *Then* both pass with no unresolved-reference errors, confirming Task H.1.1a's grep was accurate.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/SlashCommandHandler.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/RichTextEditor.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/components/RichTextEditor.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/performance/PerformanceOptimizedEditor.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/README.md`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/UNDO_REDO_README.md`

##### Task H.1.2a: Delete the 6 confirmed-orphaned files (~3 min)
- Remove the files listed above.
- Files: (as listed in Story H.1.2)

##### Task H.1.2b: Delete or trim any businessTest files that solely covered deleted code (~4 min)
- Remove test files whose only subject-under-test was one of the deleted classes.
- Files: (identified during Task H.1.1a's grep — businessTest files for `SlashCommandHandler`/`RichTextEditor`/`PerformanceOptimizedEditor`)

##### Task H.1.2c: Run bazel test //... to confirm nothing broke (~5 min)
- `bazel test //...` full run.
- Files: none (verification only)

### Epic H.2: Repoint the live slice
**Goal**: Fix the `block.toggle-todo` silently-non-functional palette entry (features.md §2) by repointing it to Phase C.1's real `applyTodoToggle`/`requestTodoToggle` mutation, and trim `CommandManager`/`CommandRegistry`/`CommandSystem`/`CommandTypes`/`EssentialCommands` to only what the live palette-ID-sourcing bridge needs.

#### Story H.2.1: Repoint block.toggle-todo to call the real mutation path
**As a** user, **I want** the palette's "Toggle Todo" entry to actually work, **so that** it stops being a wired-looking-but-non-functional trap (the exact anti-pattern this project was created partly to eliminate).
**Acceptance Criteria**:
- `StelekitViewModel.executeCommand()`'s handling of `block.toggle-todo` (or its bridged `Command`) now calls `blockStateManager.requestTodoToggle()` (Phase C.1's real implementation, `TodoState`/`applyTodoToggle` — not a `FormatAction` case) instead of computing-and-discarding a `CommandResult`.
  - *Given* the command palette showing "Toggle Todo" (shortcut badge "Ctrl+Enter", per `I18n.kt`) for a currently-editing block with content `"Buy milk"`, *When* the user selects it from the palette, *Then* the block's content becomes `"TODO Buy milk"` — verified by the same assertion style as Task C.1.1a/b's tests, run through the palette-invocation path instead of direct keyboard.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt`

##### Task H.2.1a: Repoint executeCommand's block.toggle-todo handling to requestTodoToggle (~5 min)
- Modify `StelekitViewModel.executeCommand()` (line ~1973) so the `block.toggle-todo` case calls `blockStateManager.requestTodoToggle()` instead of discarding `commandManager.executeCommand(...)`'s result.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task H.2.1b: Remove now-redundant newContent-computation logic from EssentialCommands.toggleTodo (~3 min)
- Simplify `EssentialCommands.kt`'s `toggleTodo` definition to source only `id`/`label`/`shortcut` metadata for the palette bridge, since actual mutation now happens via `requestTodoToggle`/`applyTodoToggle`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt`

##### Task H.2.1c: Add integration test confirming palette Toggle-Todo actually mutates content (~4 min)
- Add a test exercising the full palette-select→`requestFormat`→content-mutation path.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/CommandPaletteFocusRetentionTest.kt`

#### Story H.2.2: Trim CommandManager/CommandRegistry/CommandSystem/CommandTypes/EssentialCommands to only the live bridge's needs
**As a** developer, **I want** the remaining orphaned-framework surface reduced to exactly what `StelekitViewModel`'s palette-ID-sourcing bridge uses, **so that** the "silently non-functional" trap can't recur for any other `EditorCommand` still defined in `EssentialCommands.kt`.
**Acceptance Criteria**:
- Every other `EditorCommand` in `EssentialCommands.kt` (text.bold, text.italic, text.code, text.strikethrough, text.highlight, text.link, text.heading, media.image) is either (a) confirmed to already route to a real mutation via the existing `media.image` special-case pattern in `updateCommands()`, or (b) removed from the palette-visible set (via `CommandTypes.kt`'s `requiresSelection` filter, already confirmed to exclude these per features.md §2) with an explicit comment noting they are metadata-only/filtered-out, not silently broken.
  - *Given* `EssentialCommands.kt`'s `text.bold` `EditorCommand`, *When* `CommandTypes.kt`'s `getAvailableCommands()` filter runs with the palette's selection-less `CommandContext`, *Then* it is confirmed excluded from `getAvailableCommands()`'s output (per the existing `requiresSelection=true` filter), and a code comment at its definition site notes "excluded from palette by design — see Phase F.2 for the real, working `format.bold` palette entry" to prevent a future reader from assuming it's reachable.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/CommandTypes.kt`

##### Task H.2.2a: Audit each remaining EditorCommand for reachability, annotate accordingly (~5 min)
- For each of text.bold/italic/code/strikethrough/highlight/link/heading/media.image, confirm reachability status and add clarifying comments.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/EssentialCommands.kt`

##### Task H.2.2b: Remove dead branches in CommandRegistry/CommandSystem not needed by the live bridge (~5 min)
- Trim any `CommandRegistry`/`CommandSystem` code path with no caller after H.2.2a's audit.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/CommandRegistry.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/editor/commands/CommandSystem.kt`

##### Task H.2.2c: Run full test suite to confirm the trim didn't break the live palette bridge (~5 min)
- `bazel test //...` and manual palette smoke-check.
- Files: none (verification only)
