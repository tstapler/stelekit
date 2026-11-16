# ADR-002: Editing a measurement's value is an override, not a calibration change

**Status**: Accepted pending owner review (Phase 4 patch, 2026-11-14; was Proposed)
**Date**: 2026-11-14
**Project**: sync-lock-and-image-annotator (Epic 3)

## Context

`MeasurementAnnotation.valueMeters`/`valueDisplay` are stored values; `updateCalibration` (AnnotationEditorViewModel.kt ~L897-903) recomputes all of them when calibration changes. Editing a value has two distinct meanings: "this label is wrong" and "this segment really is X" (a known distance). Treating every edit as calibration lets one typo rescale the photo; prior art ranks short, noisy references as a dominant error term (`measurement-accuracy-and-camera-profiles/requirements.md`). UX research recommends an explicit choice with preview and undo (`research/ux.md` 1.4).

## Decision

1. A value edit stores `valueSource = USER_OVERRIDE` plus `originalValueMeters` on the measurement. Geometry and calibration are untouched.
2. An override becomes a calibration constraint only through the explicit action **Use as known distance**, which creates a `CalibrationConstraint(source = FROM_MEASUREMENT, measurementUuid)` and re-fits (ADR-003). The editor asks which meaning on every edit when the photo is already calibrated (remembered for the session), and defaults to known-distance only when the photo is "Not calibrated".
3. Recalibration never changes an override: the recompute loop skips `USER_OVERRIDE` rows and counts them in the preview ("2 measurements keep your edited values"). Dragging an override's points clears it only after a confirm; the drag otherwise keeps the override flag and shows it stale-marked.
4. The editor previews the effect ("3 other measurements will change") before commit and one undo entry covers edit plus any re-fit.
5. Persistence: new `value_source TEXT NOT NULL DEFAULT 'COMPUTED'` and `value_original_meters REAL` columns on `measurement_annotations` (the table has no JSON column), added in **both** the `.sq` `CREATE TABLE` (fresh databases) and `MigrationRunner` `SchemaOp.AddColumn` (existing databases), plus regenerated SQLDelight sources. The existing write stub for `insertMeasurementAnnotation` in `RestrictedDatabaseQueries.kt` (~L620-630; it is `INSERT OR REPLACE` with an explicit column list) must change **signature**, not just gain new stubs, and every caller and test follows. Sidecar `SidecarMeasurement` gains `valueSource` and `originalValueMeters` with defaults, so absent keys read as `COMPUTED`.
6. **Domain type forbids illegal pairs.** The pair (`ValueSource`, `originalValueMeters`) permits COMPUTED-with-an-original and OVERRIDE-without. `MeasurementAnnotation` exposes a sealed `MeasurementValue { Computed(meters), Overridden(meters, originalMeters) }`; the repository maps it to the two columns at the boundary, and a row that violates the pairing on read is normalised (OVERRIDE without an original reads as `Overridden(meters, meters)` and logs once).
6a. (Triad repair 2, OC-19) Scope: the editor opens for Distance measurements only; Area and Angle values are not editable because `MeasurementValue` stores metres and an angle cannot calibrate (extending them adds a value kind here). The editor's unit chip sets the entry unit of that edit only, never the global display unit or the stored metres.
7. **Override on a photo with a constraint fit.** Overrides are skipped by the recompute loop regardless of the calibration basis (scale, plane or constraint fit); "Use as known distance" is the only path from an override to a constraint (ADR-003).

## Consequences

- Needs a real migration (Story 3.2); it is additive and defaulted, so rollback of the app leaves old rows valid.
- A downgrade to 0.113.3 that re-saves a sidecar drops the flag (and ADR-003's constraints); the override value remains in `valueMeters` but is then recomputed on the next calibration change (known, documented). The downgraded build still reads the data without error.
- The existing GRID_REF dialog is reused for entering the number.

## Alternatives Considered

- **Every edit is calibration** (Canvas/Polycam style constraint solving): one typo rescales everything; hides the dominant error term. Rejected.
- **Override only, no path to calibration**: leaves the owner's real goal (several known distances) without the quickest entry point. Rejected.
- **Store the override as a label string**: breaks unit conversion and export. Rejected.
