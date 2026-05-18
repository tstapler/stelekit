# ADR-003: Primary Calibration Path: BLE Laser Rangefinder First, ARCore Second

**Date**: 2026-05-16
**Status**: Accepted
**Deciders**: Tyler Stapler

## Context

The image-meter feature requires calibrating pixel coordinates to real-world metric distances so that annotated measurements are accurate. Multiple calibration mechanisms are available, each with different accuracy profiles:

| Method | Realistic Accuracy | Notes |
|---|---|---|
| BLE laser rangefinder (Leica DISTO / Bosch GLM) | ±1 mm at 1–30 m | Laser-precision; limited to single distance |
| Reference object (manual 2-point scale) | ±1–3% of measured distance | Limited by user precision in clicking endpoints |
| ARCore software depth (depth-from-motion) | ±8–10 cm at 1–5 m | Fails on white walls, glass, dark surfaces; degrades beyond 4 m |
| ARCore + hardware ToF sensor | ±1–2 cm at 1–3 m | Minority of devices have ToF |
| EXIF focal length + sensor size | ±5–15% | EXIF sensor size data frequently incorrect |
| Monocular ML depth (Depth Anything V2) | ±3–8% after reference scale | Relative depth only without metric fine-tuning |

The target use case is construction site measurement: measuring room dimensions, wall lengths, door/window openings. Construction tolerances are typically 5 mm – 1 cm. ARCore's baseline 8–10 cm error is one to two orders of magnitude too large for this domain.

ARCore depth also fails on the most common construction surfaces: white painted drywall (textureless), glass windows and mirrors (reflective/transparent), dark flooring (low contrast), and outdoor direct-sunlight scenes (overexposure bleaches feature tracking). These are not edge cases — they are the dominant surfaces in residential and commercial construction.

## Decision

**BLE laser rangefinder** is the primary and preferred calibration mechanism. The supported device families are Leica DISTO (D series, X series) and Bosch GLM (50C, 100C), both of which expose a BLE GATT service. Integration uses the Kable library (JuulLabs) for coroutine-native GATT communication on Android and iOS.

**ARCore depth** is implemented as a secondary "best effort" calibration supplement with mandatory user-visible accuracy warnings. It is never the recommended path and is never presented without an explicit accuracy disclosure that names the ±8–10 cm error floor.

**Reference object calibration** (user marks two endpoints of a known object in the image and enters its real-world length) is the universal fallback available on all devices regardless of BLE hardware or ARCore support.

The calibration precedence hierarchy, reflected in `CalibrationMethod` enum ordering and UI affordances, is:

```
BLE_LASER  >  ARCORE_DEPTH (with ToF)  >  MANUAL_REFERENCE  >  MONOCULAR_ML  >  EXIF_FOCAL  >  NONE
```

`CalibrationMethod.ARCORE_DEPTH` is gated on `session.isDepthModeSupported(DepthMode.AUTOMATIC)` returning true at runtime. The "ARCore" option is hidden from the calibration UI on devices where this returns false.

For ARCore-derived measurements, the UI displays: a confidence heatmap overlay, a `±~10 cm` uncertainty label on every measurement value, and color-coding (yellow) that distinguishes these from BLE-calibrated measurements (green). The maximum allowed distance for ARCore calibration is capped at 5 m; beyond this threshold, the UI forces the user to use BLE or reference-object calibration.

## Alternatives Considered

**ARCore depth as primary calibration mechanism**
- Rejected on accuracy grounds: ±8–10 cm is incompatible with construction measurement requirements.
- Also rejected on coverage grounds: ARCore depth fails on the most common construction surfaces (white walls, glass, dark floors).
- Adopting ARCore as primary would systematically produce wrong measurements in the target use environment, damaging user trust.

**Monocular ML depth (Depth Anything V2) as primary mechanism**
- Rejected: produces relative depth only. Metric calibration requires either a reference object in the same frame or a separate scale source, reducing it to an augmented version of manual reference calibration at significantly higher computational cost.
- Model inference takes 400–800 ms on mid-range Snapdragon CPUs. This is acceptable as a one-shot calibration assist but not as the primary interaction path.
- No official TFLite export exists; community ONNX conversions have known compatibility gaps with NNAPI/GPU delegates. Reliability on the target device range is uncertain.

**EXIF focal length calibration as primary**
- Rejected: ±5–15% error is too high. EXIF `FocalLengthIn35mmFilm` and sensor size data are frequently incorrect or absent in manufacturer-specific EXIF extensions. Produces systematically biased measurements that cannot be corrected post-hoc.

**Hardware-only: require BLE device, no software fallback**
- Rejected: not all users own a laser rangefinder. Reference object calibration covers the case where only a phone is available (e.g., a tape measure in the frame).

## Consequences

**Positive**
- BLE-calibrated measurements achieve ±1 mm accuracy, appropriate for construction tolerances.
- The reference object fallback ensures the feature is useful without any specialized hardware.
- ARCore is preserved as a convenience tool for users who want a quick estimate; the explicit accuracy warning prevents misuse.
- The `CalibrationMethod` enum and `Calibration.confidencePercent` field provide a data model that can be stored, exported, and displayed to users — every measurement carries its provenance.
- Color-coded confidence display (green/yellow/red) communicates measurement quality at a glance without requiring users to understand the underlying technical differences.

**Negative / Risks**
- BLE integration carries significant Android implementation complexity: mandatory `ForegroundService` (API 31+), GATT error 133 exponential backoff, `gatt.close()` on every disconnect path, MTU negotiation, and a user-visible reconnect recovery flow (see ADR-005).
- ARCore depth will be available but deprioritized; users who discover it may expect better accuracy than it delivers. The accuracy warning must be prominent and specific, not hidden in settings.
- The Leica DISTO BLE protocol is proprietary with no official documentation; implementation depends on community reverse engineering (`d2relay`, `CaveSurvey` wiki, `d7knight/Disto-App`). The protocol must be validated against physical hardware before shipping.
- Bosch GLM protocol is similarly community-documented. Characteristic UUID variations across firmware versions require runtime discovery rather than hardcoded UUIDs.
- Desktop target has no BLE support (Kable does not support JVM/Linux). Desktop users are limited to reference object and EXIF calibration. This is acceptable since desktop is not the primary construction-site use case.
