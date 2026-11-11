# Research: depth sources for better measurements

Access date for all URLs: 2026-11-11. Labels: VERIFIED / INFERRED / UNVERIFIED.

## 0. What accuracy do we actually need from depth?

The knife problem is a RELATIVE depth problem: the object sits h above the ruler plane, and the scale error is about h / D (VERIFIED arithmetic: 1 cm at 60 cm is 1.7%, matching the #638 simulation). To correct to 0.1 cm on 18 cm we need h to about +-0.5 cm at D = 60 cm. Absolute depth accuracy of several cm (the repo cites ARCore at +-8 to 10 cm, from its own ADR-005 comment in `ARCoreDepthProvider.kt`, VERIFIED as a repo claim, not externally verified) cannot resolve a 1 cm height step. Depth hardware therefore does not solve the knife case. It helps only when it yields a dense, locally accurate map, or when absolute camera-to-plane distance D is the missing input (D only needs about 10% accuracy to correct a 1 cm height: error in correction = h * dD / D^2 is tiny).

## 1. ARCore Depth API (Android)

- VERIFIED (https://developers.google.com/ar/develop/depth): depth is built by comparing images from different angles as the user moves; "robust, accurate depth estimates from 0 to 65 meters", "most accurate ... half a meter to about five meters"; "Valid depth data are only available after the user has started moving their device around"; it can merge a hardware ToF sensor when present.
- VERIFIED (https://developers.google.com/ar/devices): "As of November 2026, over 85% of active devices support the Depth API"; ToF-marked models in the global table: LG V60 ThinQ, Samsung Galaxy A80, Note10+, S10 5G, S20+, S20 Ultra 5G, Sharp AQUOS R5G (10 table entries incl. variants).
- Raw Depth API and confidence images: not on the fetched page; UNVERIFIED here (https://developers.google.com/ar/develop/depth links to a Raw Depth page not read).
- Photos already taken: NOT possible. ARCore depth belongs to a live session and the device's motion; it describes the scene now, not a saved JPEG. `DepthSensorProvider.acquireDepthFrame()` is documented "at the current moment" (VERIFIED in repo), so it is capture-time only.
- Repo conversion defect (INFERRED from reading `CalibrationService.computeFromDepthFrame`, not run): `pixelsPerMeter = imageWidthPx / depthM` ignores field of view. A pinhole scale is `f_px / depthM`. For a 4000 px image with a typical 70 degree horizontal FOV (f_px about 2900 INFERRED) the repo formula is off by about 1.4x. Same issue in `computeFromMLDepth` and in `ExifCalibrationService` (which uses 36 mm as the width of the 35 mm-equivalent frame although 35 mm equivalence is defined on the diagonal, INFERRED, so wrong for 4:3 sensors). These methods are low-confidence fallbacks (15 to 20%), but the maths should be fixed or the methods hidden before anyone trusts them (plan story 6.1).

## 2. Camera2 depth outputs

- VERIFIED (https://developer.android.com/reference/android/graphics/ImageFormat): `DEPTH16` (API 23; 13-bit range in mm plus 3-bit confidence, "When produced by a camera, the units for the range are millimeters"), `DEPTH_POINT_CLOUD` (API 23; float X,Y,Z,confidence; meters when the device has `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT`), `DEPTH_JPEG` (API 29; "JPEG compressed main image along with XMP embedded depth metadata following ISO 16684-1:2011(E)").
- Which phones expose `DEPTH_OUTPUT`: not found in a citable list. UNVERIFIED; plan story 1.3 probes the owner's device instead of guessing.
- Per AOSP via search summary (https://android.googlesource.com/platform/external/dynamic_depth/+/refs/heads/main/README.android): a DYNAMIC_DEPTH stream requires devices that support depth output with a dense Y16 depth map plus BLOB.

## 3. Depth already inside existing JPEGs (Dynamic Depth / GDepth)

- VERIFIED: the Dynamic Depth 1.0 spec exists (https://developer.android.com/training/camera2/Dynamic-depth-v1.0.pdf) defining a "Depth Photo" profile with a required DepthMap and primary Image, embedded as XMP in JPEG/PNG/HEIF.
- Older "GDepth" XMP stores base64 depth image data, with Extended XMP if over 64 KB (https://docs.rs/crate/zenjpeg/0.8.3/source/src/decode/depth.rs, third-party parser doc, via search). No official Google GDepth spec found.
- Which camera apps write it today (Pixel portrait mode etc.): NOT confirmed. UNVERIFIED. Depth in portrait photos is a blurred-background aid, not metrology grade (INFERRED); resolution and units/format (range-inverse per AOSP library) need checking.
- The Ultra HDR format (https://developer.android.com/media/platform/hdr-image-format, VERIFIED) is a gain map, NOT depth; do not confuse them.
- Feasibility: reading it is a pure parser task usable on any existing JPEG that happens to carry it, so it is the one depth source that works for photos already taken. Reach is unknown until we sniff the owner's own files (plan story 6.2: a read-only probe that reports whether depth XMP exists, with no dependency on the result).

## 4. iOS

- LiDAR scene depth: `ARFrame.sceneDepth` with `ARDepthData` `depthMap` (meters) and `confidenceMap`, enabled via the `sceneDepth` frame semantic on a world-tracking configuration; LiDAR devices at introduction were iPhone 12 Pro/Pro Max and iPad Pro (VERIFIED via search of https://developer.apple.com/documentation/arkit/ardepthdata.md and /arframe/scenedepth.md). Apple published no accuracy or range spec in what I found; third-party sources cite about 5 m useful range (UNVERIFIED, forum/secondary). A developer thread reports AVFoundation depth was not clearly better than ARKit on iPhone 12/13 Pro (https://developer.apple.com/forums/thread/724152, via search).
- AVDepthData in HEIC portrait photos: exists in principle; resolution, accuracy and read path UNVERIFIED.
- Repo: `IOSLidarDepthProvider.kt` (181 lines) already returns `sceneDepth` frames (VERIFIED it reads sceneDepth; comment says "full impl in Epic 8").
- Again live-capture only unless the HEIC carries embedded depth.

## 5. Monocular depth models

| Model | Metric? | Licence / size | Evidence |
|---|---|---|---|
| Depth Pro (Apple) | Yes, zero-shot, no intrinsics needed | repo LICENSE (type not stated on the page); size not stated | "2.25-megapixel depth map in 0.3 seconds" on "a standard GPU" (https://github.com/apple/ml-depth-pro, VERIFIED); paper says V100 (https://arxiv.org/html/2410.02073v1 via search). Not a phone-class runtime. |
| Depth Anything V2 metric (Hypersim indoor up to 20 m, VKITTI outdoor up to 80 m) | Yes, per-domain | Hypersim metric variants CC-BY-NC-4.0, VKITTI CC-BY-NC-SA-3.0, only small models Apache-2.0 per LightlyTrain docs (https://docs.lightly.ai/train/stable/depth_estimation.html; not confirmed upstream, UNVERIFIED) | trained per domain, not on close-range tabletop |
| Metric3D v2, UniDepth v1/v2 | Yes (needs or predicts intrinsics) | not checked | NYUv2 ranking from a third-party summary only (https://arxiv.org/pdf/2511.10647 via search); no verified AbsRel numbers pulled |
| MiDaS / Depth Anything (relative) | No: affine-invariant | n/a | gives order, not metres |

The repo's `OnnxMonocularDepthEstimator` downloads `depth_anything_v2_small.onnx` (VERIFIED in file header) and `CalibrationMethod.MONOCULAR_ML` claims +-15%. The repo header says the map is "relative"; `computeFromMLDepth` then treats it as metres (VERIFIED code). This is why the UI banner says "Depth model ready - estimation coming in a future update": the model is wired, the consumer is not (section 6 of research/existing-scaffolding.md).

Why even a good metric monocular model cannot fix the knife case: published benchmarks are for room or street scenes at metre scales (INFERRED from the dataset descriptions above), where errors are a few percent of distance. At 60 cm that is about 1 to 3 cm, larger than the 1 cm height step to be resolved. Relative models are worse: no scale. Verdict: do not build for measurement correction (ADR-004).

## 6. Ranking: accuracy gain per cost, and reach (this question)

| Rank | Option | Fixes knife case? | Cost | Reach |
|---|---|---|---|---|
| 1 | Shooting protocol + longer reference lines (no code) | yes, mostly | none | all |
| 2 | Reference-line quality warnings (short line, few ticks) | yes (1 px term) | XS | all |
| 3 | Height-offset input + `D/(D-h)` correction, D from focal length and ruler size | yes (depth term) | S to M | all photos with EXIF focal; manual D otherwise |
| 4 | Plane homography from 4 coplanar points (A4/Letter, ID-1 card, checkerboard) | yes (tilt + lateral) | M to L | all |
| 5 | Tilt-aware warning/correction from gravity | partly (in-app capture only) | S warning, M correction | in-app camera only |
| 6 | Camera profile (division k, principal point) | only the 0.05 to 0.1 cm term | M | all, one-time setup |
| 7 | Dynamic Depth XMP reader | maybe | M | only files that carry it (unknown) |
| 8 | ARCore/LiDAR live capture of D | gives D, not h | L | ARCore devices (>85% of active devices per Google) / LiDAR iOS |
| 9 | Monocular metric model | no (error > signal) | L | heavy device |
| 10 | Two-photo / motion stereo from IMU baseline | would need cm-level baseline from IMU double integration, which drifts (INFERRED) | L, research | all |
