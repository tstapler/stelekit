# Research: camera intrinsics, distortion and camera profiles

Access date for all URLs: 2026-11-11.

## 1. Android Camera2 keys (what exists, what they mean)

All rows VERIFIED against https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics unless marked.

| Key | API | Semantics |
|---|---|---|
| `LENS_INTRINSIC_CALIBRATION` (`float[]`) | 23 | `[f_x, f_y, c_x, c_y, s]`, in pixels of the `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` coordinate system. Optional: "may be null on some devices". Needs CAMERA permission. |
| `LENS_DISTORTION` (`float[]`) | 28 | Brown-Conrady: three radial `[kappa_1..kappa_3]` and two tangential `[kappa_4, kappa_5]` coefficients, unitless, origin at the optical centre `[c_x, c_y]`. The doc defines `correctedImage(x_i, y_i) = sample_at(x_c, y_c, inputImage)`, i.e. the coefficients map corrected coordinates to input (distorted) coordinates. Optional/null on some devices. |
| `LENS_RADIAL_DISTORTION` | deprecated 28 | "inconsistently defined in terms of its normalization. Use LENS_DISTORTION instead." |
| `LENS_POSE_ROTATION`, `LENS_POSE_TRANSLATION` | 23 | Camera pose relative to the sensor/reference frame. |
| `LENS_POSE_REFERENCE` | 28 | Origin and accuracy of the pose: `_PRIMARY_CAMERA`, `_GYROSCOPE`, `_UNDEFINED`, `_AUTOMOTIVE`. |
| `DISTORTION_CORRECTION_AVAILABLE_MODES` / `CaptureRequest.DISTORTION_CORRECTION_MODE` | API level UNVERIFIED (class page only says 21) | Modes `_OFF` ("No distortion correction is applied"), `_FAST`, `_HIGH_QUALITY`. Per the AOSP metadata docs summarised via search (https://android.googlesource.com/platform/system/media/+/3dc363c9/camera/docs/metadata_definitions.xml): correction applies to processed outputs (YUV, JPEG, DEPTH16), never to RAW; metadata coordinates (faces, metering regions) are not corrected. Devices without the feature list only OFF. |
| `REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT` | 23 (constant on `CameraMetadata`) | "The camera device can produce depth measurements from its field of view." |

Not found: how common `LENS_INTRINSIC_CALIBRATION` / `LENS_DISTORTION` are across phones. Documentation only says "optional". UNVERIFIED; the plan includes a device-capability probe (plan.md story 1.3) to measure it on the owner's phone instead of guessing.

## 2. Are phone JPEGs already distortion-corrected?

Answer: PROBABLY YES for the main camera, UNKNOWN for ultra-wide, and device-specific either way.

- VERIFIED: Camera2 defines a distortion-correction block that applies to processed outputs including JPEG, and has an OFF mode. A device that supports the feature lists FAST and HIGH_QUALITY.
- NOT FOUND: the default value of `DISTORTION_CORRECTION_MODE`, and any statement that a given phone's stock camera app emits corrected JPEGs. The stock camera app may use vendor-private pipelines that do not follow Camera2 at all.
- INFERRED: ultra-wide cameras have large native distortion, so vendors almost always correct them in the ISP or the camera app (and often crop/stretch the edges), but the amount left over is unknown. Main cameras are usually corrected to a small residual.
- Consequence (decision in ADR-001): the app must never apply a distortion model taken from `LENS_DISTORTION` to a saved JPEG. Doing so would double-correct if the ISP already corrected it, and `LENS_DISTORTION` coordinates are in the pre-correction frame, which differs from the JPEG frame. A profile must describe the pixels as saved, which only an empirical calibration from saved JPEGs guarantees.
- The simulation in PR #638 already shows the residual distortion we care about is at the 1% to 2% corner level, so even a perfect profile moves the 18 cm reading by only 0.05 to 0.10 cm.

## 3. EXIF in a saved JPEG, and what is lost

`androidx.exifinterface.media.ExifInterface` has constants for `TAG_FOCAL_LENGTH` ("FocalLength", mm), `TAG_FOCAL_LENGTH_IN_35MM_FILM`, `TAG_SUBJECT_DISTANCE` (meters), `TAG_LENS_MODEL`, `TAG_MAKE`, `TAG_MODEL`, `TAG_DIGITAL_ZOOM_RATIO`, `TAG_PIXEL_X_DIMENSION`, `TAG_ORIENTATION`, `TAG_SUBJECT_AREA` (VERIFIED, https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface). Whether a given phone writes each tag: UNVERIFIED per device.

What is lost (INFERRED from how the repo and platforms work, VERIFY on device):
- A gallery pick via a content URI may strip location tags without a media-location permission; other tags usually survive. UNVERIFIED.
- Any re-encode (the app's own import/downscale path, messaging apps, cloud sync) drops EXIF unless explicitly copied. The repo has `asset/pipeline/ExifStripper.kt` (VERIFIED file exists) which strips on purpose for cloud; the measurement path must read EXIF before stripping and persist the subset into `ImageSensorData` (today only `focalLengthMm`, `focalLength35mmEq`, `cameraMake`, `cameraModel`, pitch/roll/GPS, VERIFIED in `model/ImageAnnotation.kt`; no LensModel, DigitalZoomRatio, or pixel dimensions at capture).
- Crop and digital zoom change the effective focal length in pixels; EXIF `DigitalZoomRatio` is the only hint.

## 4. DNG and OpcodeList3

DNG can carry `WarpRectilinear` in OpcodeList3 for geometric distortion and lateral CA; it runs after demosaic and so cannot be pre-applied to raw (https://community.adobe.com/questions-563/how-does-the-opcode-operates-on-the-dng-image-182675, via search summary). Some DJI DNGs carry it (https://discuss.pixls.us/t/looking-for-samples-smartphone-dngs-with-embedded-gainmap/27296/76). I found no source confirming which phone DNGs include it for ultra-wide: UNVERIFIED. Decision: out of scope for v1 (DNG capture is not in the app); a possible later source for a profile if the owner shoots DNG.

## 5. lensfun and Adobe profiles

- lensfun: code LGPL, database CC BY-SA 3.0 (https://github.com/CAHEK7/lensfun and package notices in https://metadata.ftp-master.debian.org/changelogs/main/l/lensfun/unstable_copyright, via search). Phone coverage is poor and structurally awkward: a forum user with a Galaxy S22 reported lensfun entries need one crop factor per lens entry while phones have several cameras (https://discuss.pixls.us/t/smartphone-with-several-lenses-lensfun-darktable/56264). CC BY-SA share-alike makes bundling data into a distributed app a licence decision for the owner. Decision: do not bundle; at most support importing a lensfun-derived value by hand.
- Adobe .lcp: licence/coverage for phones NOT researched (UNVERIFIED). Not pursued.

## 6. Calibrating your own phone (OpenCV)

The OpenCV tutorial (https://docs.opencv.org/4.x/dc/dbb/tutorial_py_calibration.html) returned HTTP 403 to the fetcher, so details here are UNVERIFIED against the page, from general knowledge: `cv2.calibrateCamera` over >= 10 views of a printed checkerboard returns camera matrix and distortion coefficients `(k1, k2, p1, p2, k3)` and a reprojection RMS error; ChArUco tolerates partial views. Two models are relevant: Brown-Conrady polynomial (OpenCV, Camera2) and the one-parameter division model that PR #638 uses (`undistorted = c + (p - c) / (1 + k r^2)`). A one-parameter division model fits mild residual barrel distortion with a single number, which is the right size of model for an ISP-corrected JPEG; Brown-Conrady k1,k2 is a superset.

Offline workflow (recommended v2 deliverable): a small script in `stapler-scripts/` or documented `uvx` one-liner that takes 10 to 20 JPEGs of a printed checkerboard shot with the stock camera app at the exact settings the owner uses, and emits the profile JSON of ADR-001. In-app calibration (shoot a printed A4 checkerboard) is a later option once the homography code exists, since the same plane-homography machinery recovers the points.

## 7. iOS, desktop, web

- iOS: `AVCameraCalibrationData` exposes `intrinsicMatrix`, `lensDistortionLookupTable`, `inverseLensDistortionLookupTable`, `lensDistortionCenter`, `intrinsicMatrixReferenceDimensions`, `extrinsicMatrix`, `pixelSize`. UNVERIFIED: the fetch of https://developer.apple.com/documentation/avfoundation/avcameracalibrationdata returned only the page title, so these names come from model knowledge and must be checked against the page before coding. It is delivered with depth/photo capture, not stored in an ordinary saved HEIC/JPEG, so it applies only to in-app capture.
- Desktop JVM / Web: no camera calibration data; manual profile import only.

## 8. Self-calibration (PR #638) versus an external profile

| Aspect | Multi-tick self-calibration (#638) | External profile |
|---|---|---|
| Needs | 3+ ticks on the ruler in each photo | one-time calibration session per camera and setting |
| Estimates | k from ruler residuals; weakly constrained when ruler is near the centre or short | k, principal point from many views; well-constrained |
| Per-photo cost | every photo | zero after setup |
| Risk | tap noise (guarded by the 60%-residual-removal rule) | wrong profile applied to wrong camera/zoom/crop |
| Combine | profile supplies the prior k; self-calibration can still verify (residual check) and the app warns if the profile makes residual worse | use the prior as `LensMode.Fixed` default, `Auto` as a check |

## 9. Sources

- https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics (VERIFIED, sections LENS_INTRINSIC_CALIBRATION, LENS_DISTORTION, LENS_POSE_REFERENCE read in full)
- https://developer.android.com/reference/android/hardware/camera2/CaptureRequest (mode constants VERIFIED; detail section NOT read, truncated by fetcher)
- https://android.googlesource.com/platform/system/media/+/3dc363c9/camera/docs/metadata_definitions.xml (via search summary only)
- https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface (constants VERIFIED)
- https://discuss.pixls.us/t/smartphone-with-several-lenses-lensfun-darktable/56264 (via search summary)
