# Measurement accuracy, camera profiles and depth: requirements

Status: DRAFT for owner review. Access date for all web sources in this folder: 2026-11-11.
Labels: VERIFIED = source opened or code read in this pass; INFERRED = reasoned from verified facts; UNVERIFIED = not confirmed, must be checked before relying on it.

## 1. Problem

The owner photographs a knife next to a ruler, calibrates on the ruler, and the annotation editor reports "just over 18 cm" for a knife that is exactly 18 cm. Every distance today is `pixel distance / pixelsPerMeter` from one reference line (`CalibrationService.computeFromReference`, VERIFIED in `kmp/src/commonMain/.../calibration/CalibrationService.kt`), so any position- or height-dependent scale error goes straight into the answer.

The lens agent's simulation (PR #638, `project_plans/annotation-lens-distortion/design.md`, VERIFIED by reading the branch) bounds the error sources for an 18 cm object, 30 cm ruler, 4000x3000 photo:

| Source | Effect on 18 cm | Source of number |
|---|---|---|
| Barrel distortion 1% to 2% | 0.05 to 0.10 cm | simulation in #638 |
| Object 1 cm above ruler plane at 60 cm range | 1.7% = 0.30 cm (high) | simulation; matches the pinhole identity scale = D / (D - h) (INFERRED, section 3 of research/single-view-metrology.md) |
| 5 deg plane tilt, object 25 cm sideways of ruler, 60 cm range | about 3.6% = 0.66 cm | simulation in #638 |
| +-1 px endpoint error on a 60 px reference line | 3.3% = 0.60 cm | simulation in #638 |
| +-1 px on 300 px / 2400 px reference | 0.12 cm / 0.015 cm | simulation in #638 |

Conclusion carried through this plan: lens distortion is the smallest of the four large terms. Depth mismatch, short reference lines and tilt are the likely cause of "just over 18". A per-camera distortion profile is worth building, but it is not the first lever.

## 2. Owner requests (paraphrased from a garbled message)

- A. Robustly plan and test the feature set of the library, lens, annotate-existing-image, zoom/pan and calibration features.
- B. Find out whether the owner can plug in the distortion profile of their specific phone camera(s).
- C. Find out whether deeper depth APIs (ToF, LiDAR, depth maps, monocular metric depth) can improve calculations.

## 3. Success metrics

Standard test ("knife and ruler"): a 18.0 cm straight-edged object lying flat next to a ruler, one photo, calibrated from the ruler.

| ID | Metric | Target | Conditions X |
|---|---|---|---|
| M1 | Reading of the 18.0 cm object | within +-0.1 cm (+-0.55%) | Protocol P1 in implementation/qa-protocol.md: top-down (reported tilt < 2 deg), object and ruler on the same plane or height offset entered, reference line >= 1/3 of the image long side with >= 3 ticks, object in the central 60% of the frame, main (1x) camera |
| M2 | Same test, but with object 1 cm thick and no height offset entered | app shows a "height above reference plane" warning; reading error may exceed M1 | warns, never silently reports a falsely confident value |
| M3 | Same test, but 5 deg tilt | app warns (today's threshold is 15 deg pitch / 10 deg roll, VERIFIED in `AnnotationEditorScreen.kt` lines ~371-376, too lax for M1) | warning fires at the threshold derived in ADR-002 |
| M4 | Synthetic golden scenes (known distortion, perspective, height offset) | reading within the tolerance stated per scene in plan.md section 4 | runs in CI |
| M5 | No regression: legacy (basis 0) and native (basis 1) annotations render identical values before and after | exact | migration tests |
| M6 | Owner device log | at least 10 repeated readings of the P1 knife logged in the QA sheet, spread (max-min) <= 0.15 cm | owner-run, manual |

## 4. Scope

In scope: measurement-accuracy corrections in the annotation editor (reference-line quality, height offset, plane homography, tilt, optional camera profile), the capability-gated depth path as later phases, and the test and QA strategy for library, lens, annotate-existing, zoom/pan and calibration.

## 5. Non-goals

- No metric monocular depth model in v1 (ADR-003, ADR-004).
- No use of Camera2 `LENS_DISTORTION` values on saved JPEGs (ADR-001: risk of double correction).
- No new database tables for profiles in v1 (device-local settings plus optional sidecar key, ADR-001).
- No redesign of the Library; only test coverage and the in-flight PR dependencies listed in plan.md.
- No claim of sub-millimetre accuracy on gallery images of unknown provenance.

## 6. Constraints from the repo

- Public repo: no private names, addresses or real slugs in fixtures or docs.
- Bazel canonical, Gradle for iOS/screenshots; test placement rules in CLAUDE.md (commonTest default, businessTest for non-UI, androidUnitTest for Compose behaviour, jvmTest only for pixels or desktop-only).
- Additive persistence only: any new `Calibration` field needs a `MigrationRunner.all` entry and sidecar key; legacy rows untouched (existing rule from #638 design).

## 7. Open questions for the owner

1. Phone model(s) used for the knife photos.
2. Which lens: main (1x), ultra-wide, telephoto? Zoom level (digital zoom) at the time?
3. Photos taken in the in-app camera, the stock camera app, or picked from gallery? (Gravity data exists only for in-app capture; see research/existing-scaffolding.md.)
4. Is the knife flat on the ruler's surface, or lying on top of the ruler (adds the ruler thickness as height)?
5. Is iOS a target for the measuring workflow, or Android only for now?
