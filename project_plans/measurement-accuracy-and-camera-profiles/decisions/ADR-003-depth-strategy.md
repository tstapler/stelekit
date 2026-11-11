# ADR-003: Depth strategy (v1 none, v2/v3 behind capability detection)

Status: PROPOSED | Epic: 6

## Context
Findings (research/depth-sources.md): depth sensors measure absolute distance at roughly cm-level accuracy, but the knife case needs the object's height above the reference plane to about 5 mm. ARCore depth and LiDAR are live-session sources and cannot be applied to a photo already taken. Monocular metric models have scene-scale errors larger than the 1 cm signal and are heavy. Only depth embedded in the file (Dynamic Depth XMP) works for photos already taken, and its availability is unknown. The repo has scaffolding that is unwired (research/existing-scaffolding.md).

## Decision
- v1: no depth sensors. Coplanar homography + height offset + warnings (ADR-002).
- v2 (capability-gated, optional): a "Measure with depth assist" capture mode for the in-app camera. Uses ARCore Depth / ARKit LiDAR only to estimate D (camera-to-reference-plane distance, needs about 10% accuracy) and to detect gross height difference between two tapped regions, with the per-pixel confidence filter. Hidden unless `DepthSensorProvider.isAvailable` and capture is in-app. Result feeds `Calibration.geometry` (D, h with `source = depth-sensor`), never `pixelsPerMeter`.
- v2b: read-only Dynamic Depth / GDepth probe on opened JPEGs; if a depth map is present, offer h estimation between two taps. Ship only if the probe finds depth in the owner's files.
- v3: reconsider monocular metric depth only if a licence-clean, phone-runnable model reaches a documented error under 5% at 0.3 to 1 m close range (not met by anything found).
- Existing code: keep `DepthSensorProvider`, `DepthFrame`, `IOSLidarDepthProvider`, `ARCoreDepthProvider`. Fix or hide `computeFromDepthFrame`, `computeFromMLDepth` and EXIF maths (field of view missing). Remove `CalibrationFallbackChain` from the design or rewire it to produce `Calibration.geometry` suggestions; do not let `ARCORE_DEPTH`/`MONOCULAR_ML` replace a manual reference. Keep the "Depth model ready" banner text honest until a consumer exists (replace with "Depth assist is not available yet" or hide the panel behind a flag).

## Alternatives rejected
- Using depth as the primary scale (as `CalibrationFallbackChain` does when no reference exists): confidence is 15 to 20% in the repo's own settings; a photo with no reference should say "not calibrated" rather than present a depth-derived number as a measurement.
- Two-photo stereo from IMU: baseline drift (research/single-view-metrology.md section 4).

## Consequences
The owner's knife test is fixed without hardware. Depth work is incremental, device-gated and cannot regress the manual path.
