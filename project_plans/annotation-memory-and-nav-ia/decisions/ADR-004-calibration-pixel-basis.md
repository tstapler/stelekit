# ADR-004: Calibration scale is anchored to the native image size, not the Coil decode size

**Status**: Proposed (needs spike C.1a on storage shape) | **Date**: 2026-11-08

## Context
features.md C1 (INFERRED, high plausibility): `setImageSize` receives `success.painter.intrinsicSize` (Screen:430-441), which follows the layout-driven Coil decode; `pixelsPerMeter` is stored as an absolute pixel value and reused in `computeMeasurement` in later sessions, so fold state, rotation or device change scales every distance. This is a silent-wrong-number defect and a CRITICAL.

## Options
1. **Native size from file bounds** (`BitmapFactory.Options.inJustDecodeBounds` + EXIF orientation on Android; `ImageIO` on JVM) fed to `setImageSize`; calibration stays "pixels per metre at native width". Strength: no schema change if the stored value is already meant to be native. Weakness: legacy values were captured at an unknown decode size.
2. **Store calibration as a width-relative ratio** (`metresPerImageWidth`). Strength: independent of any pixel basis. Weakness: schema/serialization change -> `.sq`/`MigrationRunner` rules, regenerated SQLDelight sources.
3. Pin the Coil decode size to a fixed value. Weakness: defeats memory bounding.

## Decision
Option 1 for new reads (a `NativeImageSize` value type), plus a one-time legacy policy decided in spike C.1a: if the stored value can be proven to be decode-size-relative only for sessions after PR #556/#558 (git history), legacy values are flagged `needsRecalibration` in the UI rather than silently rescaled. If C.1a shows the value is persisted in a typed column that must change, Option 2 applies with a `MigrationRunner` entry and regenerated SQLDelight sources (CLAUDE.md rules) and this ADR is amended.

**Amendment (repair pass, 2026-11-08)**: the legacy marker is a persisted value, so this is a real migration. Proposed shape (confirmed or replaced by spike C.1a before any code): `image_annotations.calibration_basis_version INTEGER NOT NULL DEFAULT 0` (0 = decode-relative legacy, 1 = native pixels), added via `MigrationRunner` plus `SteleDatabase.sq` and regenerated sources. Status stays Proposed until C.1a records the final shape.

## Consequences
- New property test: distance in metres is invariant to the decoded size for the same normalized points.
- A silent rescale of existing user measurements is explicitly rejected.

**Amendment (Repair 2, 2026-11-08)**: "flagged, not silently rescaled" is stricter than a badge. Displayed values must not change: a version-0 annotation shows, in the editor, the Gallery and the export, the same distance it showed before the update (the read path keeps the legacy basis for version-0 rows until the user recalibrates). Only a user-initiated recalibration writes `calibration_basis_version = 1`. The migration was **ALLOWED by the owner on 2026-11-08** (OD-6 DECIDED, requirements.md Amendment 2); legacy measurements are never silently rescaled.
