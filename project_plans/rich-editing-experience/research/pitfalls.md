# Research: Pitfalls — rich-editing-experience

Agent 4 (Pitfalls), SDD Phase 2

## 1. Compose recomposition storms in the toolbar

**This exact failure mode already exists, live, in the current codebase — not hypothetical.**

`EditorToolbar.kt` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/EditorToolbar.kt`) collects `blockStateManager.blocks` as `allBlocks` (line 29), a `Map<String, List<Block>>` from a `StateFlow` that `BlockStateManager` updates on **every local edit** (every keystroke). The `onSuggestTags` lambda (lines 82–92) is built inside a `run { }` block that closes over `allBlocks`:

```kotlin
onSuggestTags = run {
    val suggestFn = onSuggestTags
    val targetUuid = editingBlockUuid
    if (suggestFn != null && targetUuid != null) {
        {
            val block = allBlocks.values.flatten().find { it.uuid == targetUuid }
            ...
        }
    } else null
},
```

Because `allBlocks` is an unstable type (Compose can't skip on `Map`) and changes every keystroke, this closure is a **new lambda instance on every keystroke**, and `MobileBlockToolbar` recomposes every time `EditorToolbar` does — a direct violation of this project's own stated SLO ("toolbar must not re-compose on every keystroke," requirements.md line 50, inherited from `tag-suggestion-trigger`'s requirements.md line 40).

This was already flagged by that project's own pre-mortem (`project_plans/tag-suggestion-trigger/implementation/pre-mortem.md`, FM-3, P2) and adversarial review (`adversarial-review.md`), which recommended reading `allBlocks` at click-time instead of capturing it in the closure. **The recommendation was not applied** — the flagged pattern is still in the code today. This is a pre-existing regression this project will inherit and must either fix or explicitly not make worse.

General causes of toolbar recomposition storms in Compose text editors (confirmed via code + web research):
- **Unstable captured state in lambdas** — any lambda built with `run { }`/inline closures over `StateFlow`-collected `Map`/`List`/data-class-with-var fields defeats Compose's skipping, because Compose can't prove the lambda is stable when its captures are unstable.
- **`TextFieldValue` identity churn** — `TextFieldValue` is a data class combining text + selection + composition; any read of it in a composable scope that also renders the toolbar ties toolbar recomposition to every keystroke unless the read is isolated behind `remember`/`derivedStateOf` or hoisted so only the text field itself observes it.
- **`derivedStateOf`** is the standard fix for "state changes more often than the UI needs to react" (e.g., toolbar enabled/disabled state should derive from selection presence, not raw cursor position) — use it to throttle, not the raw collected state directly.
- **`@Stable`/`@Immutable` on toolbar param types** lets Compose skip recomposition when semantically-equal instances are passed, even if referentially different.

**Design-against**: any new toolbar/shortcut wiring must read block content/selection *at click time* (inside the `onClick` lambda body, via `blockStateManager.blocks.value` or equivalent snapshot), never capture a `collectAsState()`-derived collection in a closure built in the composable body. Add a recomposition-count regression test (the repo already has ad hoc `SideEffect { println("[Recompose] ...") }` counters in `BlockList.kt`/`JournalsView.kt`/`PageView.kt` — the same pattern should be added for `EditorToolbar`/`MobileBlockToolbar` and asserted, not just logged) before this project touches the toolbar again.

## 2. Keyboard shortcut conflicts across platforms

**Current state**: All hardware-keyboard shortcut handling lives in one shared function, `handleKeyEvent` in `BlockEditor.kt` (`ui/components/BlockEditor.kt:219+`), wired via `.onPreviewKeyEvent` (line 166). It checks `event.isCtrlPressed || event.isMetaPressed` (line 304) for Ctrl+B/I/S/H/E/K style formatting shortcuts, plus Ctrl+Enter (line 258), Ctrl+Left/Right/Home/End word navigation (line 349), and a soft-keyboard bracket-wrap heuristic (`detectSoftKeyboardBracketWrap`, lines 633–646) for `[[` wiki-link insertion on mobile.

**No WASM/JS-specific keydown or `preventDefault` wiring exists anywhere in `kmp/src/wasmJsMain/`.** Confirmed by search — no file under `wasmJsMain` handles `KeyEvent`/keydown at all; the shared `onPreviewKeyEvent` in `BlockEditor.kt` is the only layer. This means:
- **Browser-reserved shortcuts cannot be overridden at all**, regardless of `preventDefault`/event consumption: Ctrl+W (close tab), Ctrl+T (new tab), Ctrl+N (new window), Cmd+Q are hard browser/OS reserved in Chrome/Firefox/Safari and will never reach the Compose canvas. Any shortcut design that assumes these are available on Web will silently fail there only, and only be caught on WASM manual/E2E testing, not JVM tests.
- Compose Multiplatform for Web renders to a canvas, not `contenteditable` DOM, so classic `execCommand`/browser-native bold-on-Ctrl+B does *not* directly double-fire the way it would in a naive `contenteditable` implementation — but the browser's native "Save Page" (Ctrl+S), "Find" (Ctrl+F), and "Print" (Ctrl+P) handlers still listen at the `window`/document level *above* the canvas and can still fire unless the canvas's key handler calls `preventDefault()` on the underlying DOM event — which Compose Multiplatform's `onPreviewKeyEvent` does not automatically guarantee propagates a JS-level `preventDefault`. This project must verify (not assume) that consuming an event in `onPreviewKeyEvent` (returning `true`) actually suppresses the native browser action on WASM/JS for Ctrl+S/Ctrl+H in particular, since Ctrl+S is explicitly listed as an existing shortcut (per requirements baseline).
- **Design-against**: enumerate every planned/kept shortcut against the browser-reserved list before implementation; for anything in the non-overridable set (Ctrl+W/T/N), do not rely on the shortcut on Web — provide a toolbar/menu affordance as the primary path there.

**IME composition interference** (Android/iOS soft keyboards, and CJK input generally): no `isComposing`/composition-range guard was found anywhere in `BlockEditor.kt` or `MobileBlockToolbar.kt`. `TextFieldValue.composition` (the IME's in-progress, not-yet-committed range) is not referenced at all in the shortcut-handling code. Concretely:
- The `[[` bracket-wrap heuristic (`detectSoftKeyboardBracketWrap`) pattern-matches on `oldText`/`newValue` diffs — during active IME composition (e.g., typing an accented character, or CJK candidate selection), the `TextFieldValue.text` can transiently contain partial/candidate text that doesn't match final input, so text-diffing heuristics like this are a known source of "shortcut fires on partial input" bugs industry-wide (confirmed via Compose Multiplatform issue tracker: BasicTextField/BasicTextField2 IME-event support gaps are a live, currently-tracked class of bug for CJK input on Compose Multiplatform, and iOS `onPreviewKeyEvent`/soft-keyboard interaction has multiple open upstream issues, e.g. events not consumed, virtual-keyboard key events not delivered at all).
- **Design-against**: any new autocomplete/shortcut trigger added to the block editor must check `TextFieldValue.composition == null` (i.e., not mid-IME-composition) before firing, and this project should add an explicit test/manual-QA step for CJK/emoji IME input on Android and iOS, since JVM/desktop tests cannot reproduce IME composition behavior (desktop hardware keyboards don't go through the same composition pipeline) — this is a real platform-coverage gap: **screenshot/unit tests on JVM will not catch IME regressions at all**; only manual device testing or instrumented Android tests will.

**External hardware keyboard on tablet (iPad/Android tablet with attached keyboard)**: the codebase has one shared `handleKeyEvent` path but the mobile toolbar (`MobileBlockToolbar.kt`) is a touch-first, 3-row layout designed for soft-keyboard use. If a hardware keyboard shortcut and a toolbar tap-target both trigger the same action, ensure they're idempotent/mutually exclusive (e.g., toolbar's format button and Ctrl+B both calling the same `requestFormat` path — verify no double-toggle when both are used together, e.g., a Bluetooth keyboard shortcut fired while the soft-keyboard-oriented toolbar is still visible/focused). No explicit handling of "hardware keyboard attached" detection was found — the toolbar likely always renders regardless of hardware-keyboard presence on Android/iOS, which is itself a discoverability/redundancy issue worth deciding on (hide soft-keyboard-oriented toolbar when a hardware keyboard is attached, or leave both available deliberately).

## 3. Roborazzi screenshot regression pitfalls

Roborazzi (`io.github.takahirom.roborazzi:1.59.0`) screenshot tests exist only under `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/` (`DemoGraphScreenshotTest.kt`, `DesktopScreenshotTest.kt`, `JournalsViewScreenshotTest.kt`, `MobileScreenshotTest.kt`) plus component-scoped ones (`BottomNavScreenshotTest.kt`, `SectionBadgeScreenshotTest.kt`, `VoiceCaptureButtonScreenshotTest.kt`, `TableBlockScreenshotTest.kt`, settings screens) and `androidUnitTest/.../UiStateScreenshotTest.kt`. Baseline `.png` files are not checked into this worktree snapshot (0 found under any `roborazzi` path) — they are almost certainly generated/compared against a `build/` output directory and either gitignored-but-present in CI cache, or regenerated per run; **confirm baseline storage location and update workflow before assuming "record once, compare forever."**

Known Roborazzi/screenshot pitfalls relevant to a full toolbar overhaul:
- **Baseline churn is proportional to visual surface touched, not files touched.** `MobileScreenshotTest.kt` and `DesktopScreenshotTest.kt` each contain 2 `@Test` methods but likely each renders a full screen composition (not isolated components), meaning *any* pixel-level toolbar layout change (row count, icon spacing, left-handed mirroring) will fail every screenshot test that renders a screen containing the toolbar — this is a "full overhaul" scope, so expect close to 100% of toolbar-adjacent baselines to need regeneration, not incremental diffs.
- **Font rendering non-determinism** is Roborazzi's most common source of flaky (not real) diffs — font hinting/antialiasing differs between local machine and CI runner (different OS font packages), so any local `recordRoborazziImage` regeneration must be done in the same environment CI uses (or accepted with a documented diff tolerance) or the "fix" will fail again in CI.
- **Left-handed mirrored layout doubles the baseline count** for any toolbar-rendering test that parametrizes over `isLeftHanded` — if this project changes toolbar row/button layout, both orientations need re-recording, not just the default.
- Given Large-appetite, cross-cutting scope (all editing surfaces × Desktop/Android/iOS/Web weighted equally, though Roborazzi only covers JVM/Desktop rendering) — **plan an explicit "record new baselines" task as part of implementation, reviewed manually (diff images), not just accepted blindly**, since a mass baseline regeneration can silently bake in a real visual regression if reviewed only by "tests pass."

## 4. UX pitfall: over-optimizing for fewer taps creates hidden, undiscoverable functionality

This directly tensions with the project's stated goal ("fewest taps/keystrokes") and needs an explicit design-against, not just an aspiration.

Failure pattern (well-documented UX anti-pattern, e.g. in Nielsen Norman Group usability heuristics — "visibility of system status" / "recognition rather than recall"):
- Moving functionality behind swipe gestures, long-press, or shortcut-only access reduces taps *for users who already know the gesture exists*, but increases total interaction cost for everyone else, because there is no visual affordance signaling the capability exists at all. This is the classic "gestures are efficient but not discoverable" trap — efficient for power users, invisible for everyone else.
- This repo's own baseline already shows early instances of this pattern: `MobileBlockToolbar.kt`'s left-handed layout mirroring and 3-row structure implies real screen-space/attention constraints are already present — adding more gesture-only shortcuts without an on-screen entry point compounds the discoverability problem rather than trading it off.
- **Design-against, concretely**:
  1. Every action removed from a visible toolbar button in favor of a gesture/shortcut must retain a **visible fallback path** (e.g., overflow menu, long-press reveals a labeled menu rather than *being* the only trigger) — "shortcut in addition to," not "shortcut instead of."
  2. Any new gesture must have a first-use or periodic **discovery affordance** (coach mark, tooltip, or at minimum a settings/help page listing all shortcuts) — this repo has no chrome for this today (no `docs/journeys/` yet, per baseline), so this project should not assume users will find undocumented gestures organically.
  3. Track "reduced taps" and "discoverability" as two separate, sometimes-competing metrics in the plan/validation phase — a design that wins on tap-count but adds an undiscoverable gesture is not unambiguously better and should be flagged in review, not treated as a pure win.

## 5. Rollout pitfalls — no feature-flag system, Large-appetite cross-cutting UI change

**No feature-flag system exists in this repo** (confirmed in requirements baseline). Searched `git log` and `CHANGELOG.md` for prior toolbar/editor-related revert commits as a signal of past regression risk:

- No commit titled `revert` was found that reverts a toolbar- or editor-*UI*-specific change. The two `revert`-titled commits found (`5eff78310c Revert "feat(ci): serve fdroid APKs via GitHub Releases redirect..."` and `df07e5e856 revert: restore 2s large-page timing threshold`) are CI/perf-tuning reverts, unrelated to editing UI — so there is no direct git-history precedent of a shipped-then-reverted toolbar overhaul in this repo to learn from directly.
- However, related signals *do* exist and are informative:
  - `27fdc421c6 fix(tags): reorder EditorToolbar params to satisfy Detekt composable param ordering` — a *follow-up fix commit* needed immediately after `16ef8a91a4 feat(tags): wire Suggest Tags button to MobileBlockToolbar`, i.e. the last toolbar-wiring feature required a same-day fix-forward commit. Small toolbar changes already have a nonzero same-day-fix rate.
  - `2b3b13a951 fix(editor): use in-memory block state before DB fallback in insert/link ops` — a correctness bug in editor insert/link operations shipped and needed a dedicated fix commit after the fact, in an area (`BlockEditor`/link insertion) directly touched by this project.
  - `9137fabbda refactor(ui): unify editor toolbar wiring via EditorCapabilities + EditorToolbar` and `e3607e013f refactor(ui): extract BlockStateManager role interfaces` show this exact surface (editor/toolbar wiring) has already been refactored at least twice recently — indicating it's a known "hot" area with real churn, consistent with the `code-hotspot-analysis` framing of complexity × churn risk.
  - `b3de1ec7dc docs(ux): add editor experience journey map` — some journey documentation may already partially exist for the editor; check `docs/journeys/` (baseline states none exist yet, so confirm whether this commit's content should be treated as a starting point or has since been removed/superseded).
- **Design-against, given no flag system and a Large-appetite full-surface rewrite**:
  1. Stage the rollout by **platform**, not by feature-flag — e.g., land and dogfood Desktop/JVM first (fastest test/iterate loop, Roborazzi coverage exists there), then Android, then iOS/Web, rather than shipping all four platforms' toolbar changes in one release.
  2. Since toolbar/shortcut wiring has a demonstrated same-day-fix-forward pattern (`27fdc421c6`) even for small changes, budget explicit post-merge stabilization time before calling this shipped, and prioritize the correctness-critical paths (link/tag insertion, undo/redo, block reorder) for extra manual QA given `2b3b13a951` shows this exact area has shipped bugs before.
  3. Because there's no flag system to instantly disable a bad shortcut/toolbar change, prefer additive changes (new shortcut alongside old) over destructive ones (removing/remapping an existing shortcut) wherever the requirements allow, so a regression doesn't remove a previously-working capability with no kill switch.
  4. Given this is UI-only work with no DB schema changes (per baseline), a bad rollout is at least cheaply revertible via a follow-up commit/PR — but only if screenshot baselines and journey docs are updated *in the same PR*, or the revert will also revert documentation/test truth, compounding confusion in the next attempt.
