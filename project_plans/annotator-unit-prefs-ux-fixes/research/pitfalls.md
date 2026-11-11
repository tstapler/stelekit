# Pitfalls Research: Annotator Unit Preferences & UX Fixes

## Pitfall 1: Requirements.md Incorrect Dead-Space Root Cause

**Claim in requirements.md:** An "inner `Scaffold`" double-applies `WindowInsets.safeDrawing`.

**Reality (confirmed by code read):** There is **no inner Scaffold** in `AnnotationEditorScreen.kt`. The screen uses a single outer `Scaffold` with no explicit `contentWindowInsets` override (defaults to `ScaffoldDefaults.contentWindowInsets`). The dead space is most likely from the outer app scaffold/NavHost already consuming top insets while the annotator's scaffold also applies them. The correct fix is likely `contentWindowInsets = WindowInsets(0)` on the annotator's `Scaffold`, or passing `Modifier.consumeWindowInsets(paddingValues)` to the content. Must verify empirically before choosing the exact fix.

## Pitfall 2: Measurement Label Rendering — Must Audit All Read Sites

Fixing unit selection requires that every place reading `state.imageAnnotation?.unit` for display be updated to read `state.selectedUnit` instead. If any label renderer still reads from `imageAnnotation.unit` directly, it won't update when the user changes units. Need to audit `AnnotationEditorScreen.kt` and the canvas drawing code for all unit reads.

## Pitfall 3: FEET_INCHES Excluded from GRID_REF Calibration Dialog

The calibration dialog (`GridRefDialog` or similar) only accepts decimal input and explicitly excludes `FEET_INCHES` from the unit options. This restriction must not be broken when `FEET_INCHES` becomes the global default. The `gridRefUnit` field in `AnnotationEditorState` (which defaults to `METERS`) is a separate concern from `selectedUnit` and must not inherit the global default automatically.

## Pitfall 4: `setDisplayUnit()` Must Not Block the UI Thread

`imageAnnotationRepository.saveImageAnnotation()` is a `suspend` function. The ViewModel method must launch the save in `viewModelScope` (or the ViewModel's scope), not call it synchronously or in a coroutine launched on `Dispatchers.Main`. Pattern: update state optimistically, then save in background.

## Pitfall 5: `DepthModelDownloader` Retry Must Be Bounded

The SUCCEEDED→Failed race fix must use a bounded retry loop (e.g. max 20 × 100ms = 2 seconds). An unbounded `while (!isModelReady())` loop would hang if the file rename never completes (disk full, permission error). Include a `delay()` per iteration (cooperative cancellation) so the coroutine can be cancelled cleanly.

## Pitfall 6: Existing Saved Annotations Retain Their Unit

AC-5 says existing saved annotations retain their unit on reopen; migration is out of scope. The `initialize()` logic must only override the unit when the annotation has never had its unit explicitly set (e.g., default `METERS` and no key in settings) — or more precisely, the new default only applies to freshly-created annotations. Reopening an annotation that was last edited in meters must still open in meters.

A clean implementation: apply the global default only when creating a **new** `ImageAnnotation` (at creation time, not in `initialize()`). The ViewModel's `initialize()` just reads whatever `imageAnnotation.unit` is — no override needed. The settings-read logic belongs in the code that constructs a new `ImageAnnotation` before passing it to the editor.

## Pitfall 7: `platformSettings` May Be Null

`AnnotationEditorScreen` accepts `platformSettings: Settings? = null`. The ViewModel must null-check before reading/writing settings. Fall back to `FEET_INCHES` when `platformSettings == null`.
