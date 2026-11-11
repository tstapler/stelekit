# ADR-001: Camera profile format, matching and storage

Status: PROPOSED | Epic: 4 | Depends on: PR #638 (lens basis v2)

## Context
The owner asked whether the distortion profile of their specific phone camera can be plugged in. Evidence (research/camera-intrinsics-distortion.md): Camera2 `LENS_DISTORTION`/`LENS_INTRINSIC_CALIBRATION` are optional and describe the pre-correction sensor frame, whereas saved JPEGs are post-ISP and possibly already corrected; the residual distortion is worth only about 0.05 to 0.10 cm on 18 cm (design.md in #638).

## Decision
1. A profile describes pixels AS SAVED by the camera path the user actually uses. It is produced empirically (checkerboard shots of saved JPEGs, offline script first), never derived from Camera2 `LENS_DISTORTION`. This avoids double-correction by construction. Camera2/iOS calibration data may be stored in the profile as informational `reference` only.
2. Model: the division model already in #638 (`k`), optionally Brown-Conrady `k1,k2,p1,p2`. Both normalised by the half-diagonal so they survive resolution changes (same convention as #638 `RadialLens`).
3. Format (JSON, versioned, additive):
```
{
  "profileVersion": 1,
  "id": "cam-<random8>",
  "label": "Main camera, 1x",
  "match": {
    "make": "<EXIF Make, case-insensitive>",
    "model": "<EXIF Model>",
    "lensModel": "<EXIF LensModel or null>",
    "focalLengthMm": 6.9, "focalLengthToleranceMm": 0.15,
    "focalLength35mm": null,
    "digitalZoomRatio": 1.0,
    "aspect": "4:3",
    "pixelWidth": [4000, 4000]
  },
  "model": { "type": "division", "k": -0.012, "cx": 0.5, "cy": 0.5 },
  "intrinsics": { "fPx": 3100, "referenceWidth": 4000, "referenceHeight": 3000 },
  "validity": { "minWidth": 1000, "maxCropFraction": 0.0, "orientationsAllowed": "any" },
  "source": { "kind": "checkerboard-offline", "tool": "<name+version>", "images": 14, "rmsPx": 0.4, "createdAt": "2026-11-11" }
}
```
`cx, cy` are fractions of width/height (principal point), so they scale with resolution. `intrinsics.fPx` is scaled by `imageWidth / referenceWidth` when the image is resized with the same aspect.
4. Matching rules, in order; all that are present must pass, and a failure on any means NO MATCH (never nearest-guess):
   - Make and Model equal (case-insensitive, trimmed).
   - LensModel equal if present in both; if the profile has none, require focal length within tolerance.
   - Aspect ratio equal after applying EXIF orientation.
   - DigitalZoomRatio within 0.02 of the profile's (default 1.0); any crop (image dimensions not equal to profile's native aspect at some scale) fails.
   - Image width within `validity` bounds.
   Multiple matches: pick the most specific (more matched fields), then newest; ties show a picker.
5. Storage: device-local settings (key `measurement.cameraProfiles`, JSON array) is the source of truth, because a profile belongs to a camera, not to a graph. When applied, the annotation stores a SNAPSHOT of the used values (`k`, profile id, label) in the existing `Calibration.lens` block from #638, so old annotations remain reproducible if the profile later changes. Optional: export/import profiles as a `.json` file; sidecar only carries the snapshot (no private device identifiers beyond Make/Model, which are already in `ImageSensorData`).
6. Fallback when no profile matches, or the profile fails validity: use `k = 0` or #638 `LensMode.Auto` self-calibration unchanged; show "No camera profile for this photo" as an information line, not a warning.
7. Self-check: after applying a profile, if the 3+ tick residual from #638 gets worse than with `k = 0` by more than 20%, drop the profile for this annotation and show "profile did not fit this photo". This is the guard against a wrong or stale profile (threshold is a proposal; tune with the golden scenes).
8. Platforms: Android, iOS, desktop and web all consume the same JSON; none of them auto-generate in v1. iOS `AVCameraCalibrationData` and Android Camera2 intrinsics may seed the `intrinsics` block for in-app capture only (UNVERIFIED API names, see research doc).

## Alternatives rejected
- Ship a bundled phone database (lensfun): poor phone coverage, CC BY-SA share-alike (research doc section 5).
- Trust Camera2 `LENS_DISTORTION` on JPEG: double-correction risk, null on some devices.
- A new table: the profile belongs to the device, not the graph; the snapshot lives in the existing lens JSON.

## Consequences
Small core code (parser, matcher, snapshot), a script outside the app produces profiles, and the owner can use it with their own phone without any new platform API.
