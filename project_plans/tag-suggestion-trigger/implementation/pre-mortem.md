# Pre-Mortem: tag-suggestion-trigger

**Date**: 2026-06-22
**Feature**: Add "Suggest tags" button to MobileBlockToolbar
**Status**: Pre-implementation analysis — imagining the project has already shipped and failed

---

## Failure Mode Catalogue

### FM-1 (P1): `onRequestTagSuggestions` parameter removed from `BlockItem` prematurely — context-menu right-click path silently broken

**Failure**: Task 1.1.5a instructs the implementer to "verify `onRequestTagSuggestions` is still used by legitimate call sites (e.g. the context menu path around lines 480–496) before removing the parameter itself." The context-menu block (lines 469–497) uses `onNavigateAllSuggestions`, not `onRequestTagSuggestions` — so a quick grep for usages of the parameter inside `BlockItem.kt` finds only the dead DropdownMenu block (lines 499–516). The implementer concludes the parameter is orphaned and deletes it along with the dead block. This removes the parameter from `BlockItem`, `BlockList`, `BlockRenderer`, and the `JournalsView` + `PageView` call sites — silently eliminating the existing per-block suggestion path from `JournalsView.BlockList` (line 190) and `PageView` (line 361).

**First symptom**: The app compiles (all callers passed `null` or are also updated), but `tagSuggestionViewModel.requestSuggestions` is never called when a block is right-clicked on desktop or long-pressed on mobile. The `SuggestionBottomSheet` never appears. Because the toolbar button is new, QA focuses on it — the right-click regression goes undetected until a desktop-heavy user files a bug.

**Prevention**:
- The plan's own verification step is correct but under-specified. Before deleting the parameter, run: `grep -rn "onRequestTagSuggestions" kmp/src/` — this surfaces the usages in `BlockRenderer.kt:78`, `BlockRenderer.kt:125`, `BlockList.kt:93`, `BlockList.kt:249`, `JournalsView.kt:190`, and `PageView.kt:361`. All of these are legitimate callers passing real lambdas from `tagSuggestionViewModel`, not dead code.
- Add an acceptance-criteria check to Story 1.1.5: "After deletion, `onRequestTagSuggestions` still appears in `BlockRenderer`, `BlockList`, `JournalsView`, and `PageView` — only the dead `DropdownMenu` body is gone."
- Add a regression test: right-click (or simulate long-press) a block and assert `tagSuggestionViewModel.requestSuggestions` is invoked.

**Severity**: P1 — silently removes an existing shipped feature path on desktop and mobile.

---

### FM-2 (P1): `Icons.AutoMirrored.Filled.Label` does not exist — compilation failure

**Failure**: The plan specifies `import androidx.compose.material.icons.automirrored.filled.Label` and `Icons.AutoMirrored.Filled.Label` as the icon for the toolbar button. The research note in the plan claims `Icons.Default.Label` does not exist. This is wrong: `Icons.Default.Label` is already used in `AnnotationToolbar.kt` (line 125) and `SettingsDialog.kt` (line 253), both compiling successfully today against `compose.materialIconsExtended`. `Label` is in the `Icons.Default` / `Icons.Filled` namespace, not `Icons.AutoMirrored.Filled` — there is no automirrored variant of the Label icon in Material Icons. The import will fail to resolve and the build will not compile.

**First symptom**: `./gradlew jvmTest` or `./gradlew ciCheck` fails at Kotlin compilation with `Unresolved reference: Label` (or an unresolved import) immediately after Task 1.1.1a is applied. No tests run.

**Prevention**:
- Use `Icons.Default.Label` (import `androidx.compose.material.icons.filled.Label`) — the same import already proven to compile in `AnnotationToolbar.kt`.
- Correct the plan's Pattern Decisions table: the "Alternative Rejected" column incorrectly states `Icons.Default.Label` does not exist.
- A simple `grep -rn "Icons.Default.Label"` against the existing codebase before writing the plan would have caught this.

**Severity**: P1 — build-breaking; nothing ships.

---

### FM-3 (P2): Lambda instability causes excessive recomposition — toolbar flickers or stutters on every keystroke

**Failure**: The plan derives the `onSuggestTags` no-arg lambda inside the `EditorToolbar` composable body using a `run { }` block that captures `onSuggestTags` (the outer parameter) and `editingBlockUuid` (a `State<BlockUuid?>` read). Every time either of these values changes identity — including `editingBlockUuid` changing on focus events — the `run { }` block produces a new lambda instance. Compose cannot prove the lambda is stable because it closes over `allBlocks` (a `Map<String, List<Block>>` collected from a `StateFlow`), which is an unstable type by Compose's inference rules. `MobileBlockToolbar` will recompose on every `allBlocks` emission — i.e., on every keystroke that triggers a block state update — even when the toolbar's visible output does not change. On a large page this can cause dropped frames.

The `onAttachImage` pattern already in `EditorToolbar` (lines 73–79) has the same shape and the same instability, so this is a pre-existing pattern carried forward rather than a new regression — but the new lambda is called on every character typed (allBlocks updates on every edit), making the issue more visible.

**First symptom**: The `MobileBlockToolbar` recomposition counter (visible in layout inspector or added `SideEffect`) increments on every keystroke. The toolbar visually flickers on lower-end Android devices. The performance SLO stated in requirements ("toolbar must not re-compose on every keystroke — the callback is stable") is violated.

**Prevention**:
- Wrap the derived lambda in `remember(onSuggestTags, editingBlockUuid)` rather than recomputing it in a bare `run { }` block. The content read (`allBlocks.values.flatten().find { ... }`) moves inside the remembered lambda and runs only at click time, not at composition time.
- Alternatively, read `blockStateManager.blocks` inside the lambda via a direct `blockStateManager.blocks.value` read at click time (not via `collectAsState()`), eliminating the `allBlocks` state subscription from the composition entirely for this purpose.
- Add a recomposition assertion to the `EditorToolbarTest` or `JournalsViewUITest`: set content, type 5 characters, assert the toolbar composable recomposed 0 times (using a test-visible counter).

**Severity**: P2 — violates the explicit performance SLO in requirements; noticeable on lower-end hardware.

---

### FM-4 (P2): Roborazzi screenshot baselines not updated — CI fails on `mobile_journals_light` and `mobile_journals_dark`

**Failure**: `MobileScreenshotTest` renders `JournalsView` — which includes `EditorToolbar` — and compares against committed baseline images. Adding the "Suggest tags" icon button changes the rendered output of `MobileBlockToolbar` whenever a block is in editing state. If the test fixture places any block in editing state at screenshot time, the pixel diff will exceed the Roborazzi threshold and the `jvmTest` CI job will fail. Even if the fixture does not show the toolbar with the button visible, the changed composable tree structure (new parameter, new conditional branch) may affect layout measurement.

Additionally, `JournalsViewScreenshotTest.journals_view_markdown_rendering` renders `JournalsView` and captures a screenshot; if it triggers toolbar rendering, it will also fail.

**First symptom**: CI `jvmTest` fails with a Roborazzi pixel-diff or unexpected-image error on `mobile_journals_light`, `mobile_journals_dark`, or `journals_reproduction` after the implementation PR is pushed. The failure is in test comparison, not in the feature itself.

**Prevention**:
- After completing Task 1.1.1b, run `./gradlew jvmTest -Proborazzi.test.verify=false` (or the equivalent record mode) to regenerate baseline PNGs and commit them alongside the feature change.
- Add a checklist item to Story 1.1.1: "Update Roborazzi baseline images if any screenshot test fails after adding the new icon button."
- Alternatively, confirm the screenshot fixtures do not place a block in editing state (so `onSuggestTags` resolves to `null` and the button is invisible), in which case no pixel diff occurs.

**Severity**: P2 — blocks CI merge even though the feature itself works correctly.

---

### FM-5 (P3): `editingBlockUuid` is `null` at click time — `onSuggestTags` passed as `null` to toolbar, button invisibly disappears mid-edit

**Failure**: The `EditorToolbar` derivation computes `onSuggestTags = null` when `editingBlockUuid` is `null`. `editingBlockUuid` is read from `blockStateManager.editingBlockUuid.collectAsState()`. On Android, focus changes can cause `editingBlockUuid` to briefly emit `null` between a blur and re-focus event (e.g., when the user taps the "Suggest tags" button itself — the `TextField` loses focus before the `onClick` fires). If the recomposition triggered by `editingBlockUuid = null` completes before the `onClick` lambda executes, the lambda passed to `IconButton` is the previously-captured closure (closing over the old non-null uuid) and the tap succeeds — but if Compose re-renders the button as invisible (`onSuggestTags = null`) and removes it from the composition before the tap event is dispatched, the tap is swallowed.

**First symptom**: Intermittent reports of tapping "Suggest tags" doing nothing. The `SuggestionBottomSheet` does not appear. The bug does not reproduce reliably on desktop (focus semantics differ) and is hard to reproduce in instrumented tests. It surfaces as sporadic user complaints on Android.

**Prevention**:
- Mirror the existing `onAttachImage` pattern exactly: capture `editingBlockUuid` in the `run { }` block as a local `val targetUuid = editingBlockUuid` — the captured value is the uuid at composition time, not at click time. If the button is visible (non-null uuid at composition), the closure holds a non-null uuid even if the state transitions to null before the click is dispatched.
- The plan's Task 1.1.2b already captures `val targetUuid = editingBlockUuid` inside `run { }` — this is correct. The prevention is to ensure the implementer does not accidentally move the null-check inside the lambda body (reading `editingBlockUuid` again at click time).
- Add an instrumented test: programmatically focus a block, tap "Suggest tags", assert `requestSuggestions` is called with the correct uuid.

**Severity**: P3 — intermittent, Android-only, hard to reproduce but real data loss for the user (no suggestions appear).

---

## Summary Table

| ID | Failure | First Symptom | Prevention | Severity |
|----|---------|---------------|------------|----------|
| FM-1 | `onRequestTagSuggestions` parameter deleted along with dead DropdownMenu — right-click path broken | Right-click "Suggest tags" stops working silently | Grep all callers before removing parameter; add regression test | P1 |
| FM-2 | `Icons.AutoMirrored.Filled.Label` does not exist — compilation failure | Build fails immediately with unresolved reference | Use `Icons.Default.Label` (already proven in AnnotationToolbar.kt) | P1 |
| FM-3 | Lambda instability causes toolbar recomposition on every keystroke | Toolbar flickers; performance SLO violated | `remember(onSuggestTags, editingBlockUuid)` wrapping; read blocks at click time | P2 |
| FM-4 | Roborazzi baseline images not updated | CI fails with pixel-diff on mobile_journals_light/dark | Regenerate and commit baselines after adding icon button | P2 |
| FM-5 | `editingBlockUuid` emits `null` on focus-blur between render and tap dispatch | Intermittent "tap does nothing" on Android | Capture uuid in `run { }` at composition time (plan already does this — do not regress it) | P3 |

---

## Severity Counts

- **P1**: 2 (FM-1, FM-2)
- **P2**: 2 (FM-3, FM-4)
- **P3**: 1 (FM-5)

**Top failure mode**: Deleting `onRequestTagSuggestions` from `BlockItem` entirely while removing the dead DropdownMenu silently kills the existing right-click suggestion path on desktop and mobile (FM-1).
