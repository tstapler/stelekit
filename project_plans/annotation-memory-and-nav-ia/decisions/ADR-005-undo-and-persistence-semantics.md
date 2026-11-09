# ADR-005: Undo restores the committed set only; all editor writes go through one serialized persistence queue

**Status**: Proposed | **Date**: 2026-11-08

## Context
features.md C3 (VERIFIED by reading): `pushUndo(st)` runs after `addPoint` already wrote `inProgressPoints`, so undo resurrects ghost points. M1: saves outside `persistLock`, `undoCalibration` drops annotations added after the scale change, initial load vs first mutation can delete persisted rows. `AnnotationEditorViewModel.kt` is 1,049 lines with `@Suppress("LargeClass")` (architecture.md section 8).

## Options
1. Patch each path inside the ViewModel. Weakness: adds more to a God Object.
2. **Extract `UndoHistory` and `AnnotationPersistenceQueue` as separate classes**; undo snapshots contain only `committedAnnotations` and, for calibration entries, `calibration`; tool, selection, in-progress points and tags are not restored. Strength: testable in isolation, removes the lock gaps structurally.
3. Command-log undo (per-action inverse). Strength: closest to mature apps (features.md section 2). Weakness: largest change.

## Decision
Option 2. A calibration change records a typed `UndoEntry.Calibration(before, after)` that does not touch annotations (fixes `undoCalibration` data loss). The queue is a single-consumer `Channel` owned by the editor scope (with `CoroutineExceptionHandler`), serializing commit, delete, calibration, tag and bulk rewrites; the initial load merges rather than overwrites when `hasBeenMutated`. Option 3 is recorded as a possible follow-up, not required.

## Consequences
- Behaviour change: undo no longer switches tool or reverts tags. Release note and test updates required (`AnnotationEditorViewModelTest`, `...BranchTest`).
- Amended 2026-11-08: measurement-annotation writes are routed through `DatabaseWriteActor.execute` (`DatabaseWriteActor.kt:763`) in Refactor-first task C.5c, before the queue (C.5a); no `DirectRepositoryWrite` opt-in remains in `ANN/`. The queue owns its scope with an explicit `close()`; `UndoHistory` owns none.
- Amended 2026-11-08 (Repair 2, pre-mortem failure 4): `DatabaseWriteActor` has HIGH and LOW channels with no cross-channel ordering (`DatabaseWriteActor.kt:104,218-219`), so a commit at HIGH and an undo set-save at LOW could invert order and resurrect an undone row. Every measurement-annotation write (commit, delete, undo/redo set-save, calibration re-save, bulk/tag rewrite) uses one priority, `Priority.HIGH`, through a single wrapper in `SqlDelightMeasurementAnnotationRepository`; ordering and race tests run against the real actor with interleaved LOW graph-load writes. `invalidate` is scoped to the annotation tables; the queue's `close()` cancels pending awaits when the actor shuts down.
