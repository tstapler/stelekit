# Annotation lens distortion and geometric error

## Problem
A user calibrated scale with a ruler in the photo, then measured an object that is at most 18.0 cm and the
editor read "just over 18 cm". Today every distance is `pixel distance / pixelsPerMeter` from ONE reference
line (`CalibrationService.computeFromReference`, `AnnotationEditorViewModel.computeMeasurement`), so any
position-dependent scale error goes straight into the answer.

## Measured error budget (18 cm object, 30 cm ruler, 4000x3000 photo)
Simulated in `LensDistortionSimulationTest` (forward polynomial barrel, a = coefficient at the corner):

| Source | Magnitude on 18 cm | Direction |
|---|---|---|
| Barrel distortion, 1% (a=-0.01), ruler along the lower edge, object at centre | +0.05 cm (18.05) | reads high |
| Same, 2% | +0.10 cm | high |
| Same, 1%, object near the edge instead | -0.14 cm (17.86) | reads low |
| Plane tilt 5 deg, object 25 cm sideways of the ruler at 60 cm range | about 3.6% = 0.66 cm | either |
| Depth: object 1 cm above the ruler plane at 60 cm range | 1.7% = 0.30 cm | high (closer = bigger) |
| Endpoint error +-1 px, 300 px reference line | 0.67% = 0.12 cm | either |
| Endpoint error +-1 px, 60 px reference line | 3.3% = 0.60 cm | either |
| Endpoint error +-1 px, 2400 px reference line | 0.08% = 0.015 cm | either |
| JPEG downscale rounding | below 1 px per endpoint, same order as endpoint error | either |

Lens distortion alone gives "18.0x", not "18.3". An object lying on or above the ruler (depth) or a short
reference line are the larger and more likely causes of "just over 18"; lens distortion explains the last
0.05-0.1 cm. Barrel distortion makes the object read HIGH only when it is nearer the centre than the ruler.

## Chosen model (calibration basis 2, `CalibrationBasis.NATIVE_PIXELS_LENS`)
- Multi-point ruler calibration: 2+ ticks with exact real positions; least-squares scale along the fitted axis;
  residuals exposed (`residualPercent` = worst tick error as a percent of the ruler span) as the direct signal of
  lens distortion or tilt along the ruler.
- One-parameter division model `undistorted = c + (p - c) / (1 + k r^2)`, r normalized by the half-diagonal.
  k is a user preset (`LensMode.Fixed`) or estimated (`LensMode.Auto`) by minimizing along-ruler plus off-axis
  tick residuals. Auto only accepts k when 3+ ticks leave at least 1 px of residual and the fit removes 60% of it
  (otherwise k = 0), so tap noise cannot invent distortion.
- Measuring maps both points through the undistortion first. Stored geometry stays normalized pixel points.
- `Calibration.lens` (k, ticks, residual) persists in new column `image_annotations.calibration_lens_json`
  and the additive sidecar key `calibrationLens`. Legacy (0) and native (1) rows and old sidecars are untouched.
- `LensMeasurement.assess` warns when the object's radius differs from the ruler's (first-order estimate
  `2 * 0.01 * |r_obj^2 - r_ref^2|`, reduced 4x when k is corrected) or when ticks disagree by more than 1%.

## Not solved (software cannot fix)
- Depth mismatch: an object raised above the ruler plane looks larger. Warn only.
- Perspective/plane tilt needs a homography with 4+ known coplanar points; residuals only detect it.
- A weakly constrained k (ruler near the centre, few ticks) stays 0; the user can pick a preset instead.
- Per-camera lens profiles from EXIF are not used.

## Shooting guidance (shown in the calibration UI text, `LensMeasurement.SHOOTING_GUIDANCE`)
Ruler beside the object on the same flat surface, camera square-on, object near the centre, fill the frame,
3+ ticks over the longest span.
