---
journey_id: annotate-asset
platforms: [desktop, android, ios, web]
jtbd_tier: social
step_count_target: "discoverability checklist only, no hard step-count number (social-tier journey, per Rubric in docs/journeys/README.md and design/ux.md criterion 12)"
current_step_count: "N/A — social-tier, graded on discoverability checklist below, not step count"
heuristic_findings: |
  Full audit (Epic G.1), applying Nielsen's 4 named heuristics (visibility of system status,
  consistency and standards, discoverability, minimal memory load), reviewed independently by the
  ux-expert agent per Task G.1.1b:

  1. Visibility of system status (P2): Disabled measurement tools (DISTANCE/AREA/ANGLE/GRID_REF,
     pre-calibration) render grayed out (`Color(0xFF555555)`, `AnnotationToolbar.kt`'s `ToolButton`)
     but the tooltip text only ever shows label+shortcut, never a disabled-reason. The user learns
     *that* a tool is off but not *why* — they must already know calibration is a prerequisite.
  2. Consistency and standards (P2, now partially fixed — see Before/After): every one of the 7
     tool-selection/calibrate buttons in `AnnotationToolbar.kt` wraps a `TooltipBox`/`PlainTooltip`
     with a shortcut hint (`toolShortcut(tool)`, lines 216-219, 253-260) — an exemplary,
     copy-worthy pattern per design/ux.md §(g). Undo/Redo (lines 161-174, pre-fix) were the only
     two buttons in the same toolbar without this treatment — an internal inconsistency with no
     discernible rule for which buttons got it.
  3. Discoverability (P0, now fixed — see Before/After): `TagEditorPanel.kt` is a fully-built,
     backend-wired image-tagging UI (chip row + autocomplete, backed by
     `AnnotationEditorViewModel.addTag()`/`removeTag()`, lines 755-774, persisted via
     `persistTagsUpdate`, and consumed downstream by `GalleryScreen.kt`/`GalleryViewModel.kt` for
     tag-filter chips and per-image tag display) but had **zero call sites** anywhere in the
     codebase (confirmed by a full-repo grep for `TagEditorPanel(`, matching only its own function
     definition). This is not a placement problem — the feature was unreachable by construction.
     Gallery's tag-filter UI would present as permanently empty to any user who never had another
     way to add a tag, since the one entry point that would populate it never existed.
  4. Minimal memory load (P1, not yet fixed): all 7 tool buttons advertise a distinct one-letter
     keyboard shortcut (S/D/A/G/L/R/C) intended to reduce recall burden for repeat users, but a
     full-codebase grep for `onPreviewKeyEvent`/`onKeyEvent` scoped to `ui/annotate/` (plus
     jvmMain/androidMain/iosMain) found **zero matches** before this fix — none of those 7
     shortcuts do anything. A user who memorizes "D for Distance" and tries it gets silent
     nothing. Only Undo/Redo's Ctrl+Z/Ctrl+Shift+Z binding was resolved in this pass (see
     Before/After); the 7 tool-selection shortcuts remain unwired — filed as GAP-022 (P1) for a
     future pass, since wiring all 7 is materially larger scope than the single confirmed fix this
     phase budgets for.

  Most severe finding, per this project's own priority taxonomy (P0 = confirmed correctness bug /
  silently non-functional feature; P1 = missing high-frequency affordance): **finding 3
  (TagEditorPanel's total unreachability) is P0** — independently confirmed by both direct code
  audit and the ux-expert agent's review. Finding 4 (dead tool-selection shortcuts) is also
  P0-caliber by "actively promises broken functionality," but it degrades an already-reachable
  mouse/touch workflow (a lost accelerator, not a lost capability), whereas finding 3 is the
  *entire* tagging journey missing outright — categorically worse. Both were reconciled during
  Story G.1.2's backlog transcription (see gap-backlog.md GAP-020/GAP-021/GAP-022/GAP-023); this
  phase implements GAP-020 (P0) and GAP-021 (P1, already-designed per ux.md §(g)/validation.md
  REQ-12) in full, and files GAP-022/GAP-023 for future work rather than expanding this pass's
  scope past a single confirmed fix.
test_ids:
  - dev.stapler.stelekit.ui.annotate.AnnotationToolbarShortcutTest#annotationUndoRedo_should_showTooltipWithShortcutHint_When_toolButtonRendered
  - dev.stapler.stelekit.ui.annotate.AnnotationEditorScreenTagWiringTest#annotationEditorScreen_should_renderTagEditorPanel_When_imageAnnotationLoaded
status: audited
last_verified: 2026-07-06
---

# Annotate asset

## Trigger

User taps an `image_annotation` block's thumbnail to open the full-screen annotation editor
(`AnnotationEditorScreen`, routed from `ScreenRouter.kt` — a shared `commonMain` route with no
platform gating, so this journey applies identically to Desktop/Android/iOS/Web) to label/measure
the image.

## Current flow (all platforms — tool-selection → draw-annotation → tag/label)

1. **Tool selection** — `AnnotationToolbar.kt` renders a tool-selection row (SELECT / DISTANCE /
   AREA / ANGLE / LABEL / GRID_REF) plus a CALIBRATE button. Measurement tools are disabled
   (grayed, `enabled = isCalibrated`) until the image has been calibrated via the CALIBRATE flow
   (`CalibrationSheet.kt`, `CalibrationNudgeBanner.kt`). Every one of these 7 buttons shows a
   `TooltipBox` naming its keyboard shortcut (`toolShortcut(tool)` — S/D/A/G/L/R/C).
2. **Draw annotation** — gesture-driven point placement on a zoomable/pannable Coil `AsyncImage`
   canvas (`AnnotationEditorScreen.kt`'s layered `Canvas`es), committed via
   `AnnotationEditorViewModel.commitAnnotation`/tool-specific commit methods once the required
   number of points is placed.
3. **Per-point labeling** — for the LABEL tool specifically, placing an anchor opens
   `LabelInputOverlay.kt` (`state.isLabelInputVisible` gate, `AnnotationEditorScreen.kt` lines
   ~565-574), a text input dispatching to `viewModel.updateLabelText`/`confirmLabel`/
   `dismissLabelInput`. This is a per-measurement-point label, distinct from image-level tags
   below — the two are easy to conflate and are called out separately here precisely because they
   are easy to conflate.
4. **Image-level tagging (Logseq-style tags)** — **as of this fix**, `TagEditorPanel.kt` is now
   composed inside `AnnotationEditorScreen.kt` as a persistent info strip (alongside
   `CalibrationConfidenceBadge`/`GpsMetadataRow`'s existing always-visible placement), wired to
   `viewModel.addTag()`/`removeTag()`. **Before this fix, no such call site existed anywhere in the
   codebase** — see Before/After below. Tags added here are what `GalleryScreen.kt`'s tag-filter
   chips read from `ImageAnnotation.tags`.
5. **Undo/Redo** — `AnnotationToolbar.kt`'s Undo/Redo `IconButton`s dispatch to
   `AnnotationEditorViewModel.undo()`/`redo()`, gated by `canUndo`/`canRedo` (independent of
   `BlockStateManager`'s undo/redo stack — zero shared code with the main editor, per
   architecture.md §2). **As of this fix**, these buttons now also show a `TooltipBox` ("Undo
   (Ctrl+Z)" / "Redo (Ctrl+Shift+Z)") matching every other tool button's convention, and the
   screen now has a working Ctrl+Z/Ctrl+Shift+Z hardware binding — see Before/After.

## Discoverability checklist (social-JTBD tier, per design/ux.md criterion 12)

- [x] Every tool in the tool-selection row has a visible label (`showLabels`) and a tooltip.
- [ ] Disabled (uncalibrated) tools communicate *why* they're disabled, not just that they are —
      **not fixed** (grayed with no reason tooltip; filed as GAP-023, P2).
- [x] Undo/Redo now match the rest of the toolbar's shortcut-hint convention — **fixed this pass**.
- [ ] Every advertised keyboard shortcut actually does something — **partially fixed**: Undo/Redo's
      Ctrl+Z/Ctrl+Shift+Z now work; the 7 tool-selection shortcuts (S/D/A/G/L/R/C) remain
      unwired — **not fixed** (filed as GAP-022, P1).
- [x] Image-level tagging (a core, backend-supported capability) is reachable from the running
      app — **fixed this pass** (previously false: zero call sites for `TagEditorPanel`).
- [ ] The calibrate → measure → label sequence has no step that requires guessing which tool to
      use next — not independently re-verified in this pass; no new finding either way.

## Before / after (Story G.2.1 — implemented fixes)

**GAP-020 (P0) — TagEditorPanel wiring.** `AnnotationEditorScreen.kt` now composes
`TagEditorPanel(tags = annotation.tags, onAddTag = { viewModel.addTag(it) }, onRemoveTag = {
viewModel.removeTag(it) })` as a persistent row above the canvas, right after the GPS metadata row
and before the zoom/pan `Box`. Before: the panel existed as dead code with a fully working
ViewModel/repository layer behind it but no reachable UI. After: users can add/remove tags
directly in the annotation editor, and those tags now actually populate Gallery's tag filter as
intended. Verified by `AnnotationEditorScreenTagWiringTest` (new file,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/annotate/`), which renders the real screen,
confirms the "Tags" section is displayed, types a tag, and asserts it reaches
`AnnotationEditorViewModel`'s real state.

**GAP-021 (P1) — Undo/Redo tooltip + keyboard shortcut**, per design/ux.md §(g)'s already-designed
fix and validation.md REQ-12. `AnnotationToolbar.kt`'s Undo/Redo `IconButton`s are now each wrapped
in a `TooltipBox`/`PlainTooltip` reading "Undo (Ctrl+Z)"/"Redo (Ctrl+Shift+Z)", matching every
other tool button's convention exactly. `AnnotationEditorScreen.kt` gained a
`Modifier.onKeyEvent` handler (mirroring `App.kt`'s `onGraphKeyEvent` Ctrl+Z/Ctrl+Shift+Z
convention in spirit, but independent code — zero shared abstraction with the main editor) that
dispatches Ctrl+Z/Ctrl+Shift+Z to `viewModel.undo()`/`redo()`, gated by the same `canUndo`/
`canRedo` the buttons already use. Before: the new tooltip text would have been a lie (advertising
a shortcut that did nothing) had the keyboard binding not been added in the same pass. After: the
tooltip and the actual keyboard behavior are consistent. Verified by
`AnnotationToolbarShortcutTest#annotationUndoRedo_should_showTooltipWithShortcutHint_When_toolButtonRendered`
(new file), matching validation.md REQ-12 exactly.

**Not fixed in this pass (filed to gap-backlog.md for future phases):**
- GAP-022 (P1): the 7 tool-selection/calibrate keyboard shortcuts (S/D/A/G/L/R/C) remain unwired —
  larger scope than a single confirmed fix, and not the item validation.md/ux.md had already
  pre-designed.
- GAP-023 (P2): disabled tools don't explain *why* they're disabled — ux.md §(g) itself flagged
  this as "a finding, not fixed here."

## Test coverage gap

No Compose UI/Roborazzi screenshot test existed for any `ui/annotate/` screen prior to this
pass (confirmed by searching `kmp/src/jvmTest` for `annotate`/`Annotation` — only
`AnnotationExporterTest.kt`, a bitmap-baking unit test with no `ComposeContentTestRule`, existed).
This pass adds the first two Compose UI tests for this surface
(`AnnotationToolbarShortcutTest.kt`, `AnnotationEditorScreenTagWiringTest.kt`), but neither is a
Roborazzi screenshot baseline — the fixes here are functional/discoverability changes (tooltip
text, a new persistent row, a keyboard binding) rather than layout changes, so no visual baseline
was judged necessary. A Roborazzi baseline for `AnnotationEditorScreen`/`AnnotationToolbar` remains
an open gap for whoever next touches this surface's visual layout.

## Prior-art pointer

`docs/ux/journey-map.md` (commit `b3de1ec7dc`, branch `stelekit-editing`, not merged — see
`docs/journeys/README.md` changelog) has a full "Image annotation sub-editor" journey with a
Mermaid state diagram, covering entry nudges (calibration/tilt/ARCore banners), coach marks,
point-placement flow, `GRID_REF`'s 2-point calibration pause, recalibration warning gate, and the
`UnsavedChangesDialog` back-navigation guard. It flags as a cross-cutting gap that this "showcase"
sub-feature has *more* onboarding/unsaved-changes-guard investment than the main block editor does,
despite being used far less often — read via `git show b3de1ec7dc:docs/ux/journey-map.md`. Neither
this audit nor that prior art found the TagEditorPanel-unreachability gap (GAP-020) — it appears to
be a genuinely new finding from this pass's from-scratch code audit.
