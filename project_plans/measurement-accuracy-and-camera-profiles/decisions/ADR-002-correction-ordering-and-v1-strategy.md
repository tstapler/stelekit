# ADR-002: Correction ordering and the v1 accuracy strategy

Status: PROPOSED | Epic: 2, 3, 5 | Depends on: #638

## Context
Four error terms matter (requirements.md section 1). They compose geometrically: lens distortion is in image space, perspective is a plane-to-image map, and depth is a scale on the object's own surface. Applying them in the wrong order mixes frames (undistorting after a homography is not equivalent).

## Decision
Ordering for every measured point pair:
1. Normalise stored points to native pixels (basis 1/2 rules unchanged; legacy basis 0 never rescaled).
2. Undistort with the active lens model (profile k per ADR-001, else self-calibrated k, else 0).
3. Map undistorted pixels to plane coordinates: homography H if the reference supplied >= 4 coplanar points; otherwise the scalar scale from the reference line (current behaviour, with the multi-tick fit from #638).
4. Measure the plane-space distance.
5. Apply the height-offset factor (D - h) / D to a segment whose surface is h above the reference plane (D from f_px and reference size, or manual). h = 0 by default and then no change.
6. Report with an uncertainty estimate and the active correction list.

v1 strategy (what ships first, in value order, see plan.md):
- v1a: guidance and guardrails: short-reference warning, tick-count nudge, tilt threshold tiers, shooting guidance text. No change to numbers.
- v1b: height-offset input with correction (step 5).
- v1c: plane homography from a known rectangle (step 3) with presets A4, US Letter, ID-1 card, custom rectangle.
- v1d: camera profile import (ADR-001, step 2 prior).
Depth sensors are v2/v3 behind capability detection (ADR-003).

Tilt tiers (proposal; calibrate with golden scenes): pitch/roll <= 3 degrees: no message; 3 to 10 degrees: "Tilt about N deg may add about X% across the frame; use a reference rectangle or reshoot top-down"; above today's 15/10 degrees: existing "Camera tilted" banner. Gravity data is absent for gallery images; then show "Tilt unknown" in the accuracy panel.

Uncertainty: a simple first-order sum of independent terms (INFERRED approach): endpoint term `sqrt(2) * 1px / L_ref_px * value`, residual term from tick residual, tilt term from tier, height term `dh/D * value` when h is unknown (default dh = 0.5 cm if not entered AND the user marked "object may be raised"). Shown as plus-or-minus next to the value, never as a claim stronger than the inputs support.

## Alternatives rejected
- Homography first, then undistort: wrong frame order.
- Use gravity tilt alone as a correction (without H): needs extrinsics yaw and accurate f_px; less robust than a 4-point H; kept as a warning only in v1.
- Silent automatic height guess from depth models: error exceeds signal (research/depth-sources.md section 5).

## Consequences
New pure-Kotlin geometry module in `commonMain/.../calibration/` (testable in `commonTest`), a `Calibration.geometry` additive block (plane H, D, h), basis version 3 only if stored geometry changes meaning of existing fields (otherwise additive, no bump).
