# Research: single-view metrology without a depth sensor

Labels: VERIFIED / INFERRED / UNVERIFIED. The geometry below is standard pinhole projective geometry (INFERRED from first principles, not from a cited page; the unit tests in plan.md section 4 are what turn it into verified behaviour). Numbers marked "sim" come from the #638 design note.

## 1. Same-plane homography (best fix for tilt and lateral offset)

Idea: if a known rectangle lies in the same plane as the object, 4 corner taps give a homography H from image to plane coordinates. Any two points on that plane then map to metric positions, independent of tilt and of lateral position. This replaces the single scalar scale.

Reference objects (all must lie flat in the object's plane):
- ID-1 card: 85.60 x 53.98 mm, thickness 760 +- 80 micrometres (VERIFIED, https://en.wikipedia.org/wiki/ISO/IEC_7810). Small; the 54 mm short side limits the baseline for 4-corner homography, so a corner tap error of 1 px on a 600 px card side is 0.17% (INFERRED arithmetic), still fine.
- A4 sheet (210 x 297 mm) and US Letter (8.5 x 11 in = 215.9 x 279.4 mm): standard sizes (well-known; not re-fetched, treat as VERIFIED-by-convention). Printing scale can be off: print-scaled PDFs change size by a percent or more; only trust unscaled blank paper.
- Printed checkerboard / ChArUco, or two perpendicular ruler segments.

Error model: homography estimated from 4 taps amplifies tap noise when the 4 points are close together or nearly collinear; choose the rectangle to span at least a third of the image (INFERRED). Provide residual feedback by letting the user tap a 5th known point (e.g. the ruler end) and show the disagreement.

Precision caveat: 4 points give an exact solve with no redundancy; use DLT with normalisation, and allow >4 points for least squares (INFERRED).

## 2. Height offset (best fix for depth mismatch)

Setup: reference plane at camera-to-plane distance D along the optical axis, object top surface at height h above that plane (toward the camera). Pinhole scale at a surface is proportional to 1 / distance, so features at height h look larger by D / (D - h). Therefore: true_length = measured_length_calibrated_on_plane x (D - h) / D (INFERRED, standard).

Check against #638: h = 1 cm, D = 60 cm gives factor 59/60 = 0.9833, i.e. reading is 1.7% high (matches "1.7% = 0.30 cm" in the #638 sim). VERIFIED by arithmetic.

Getting D: from the calibrated reference. D = f_px x L_real / L_px, where f_px is focal length in pixels and the ruler's L_px is its length in pixels measured along the plane parallel to the image (INFERRED, pinhole). f_px from EXIF: `FocalLength` (mm) x image_width_px / sensor_width_mm, or from `FocalLengthIn35mmFilm` using the diagonal definition: f_px = f35 x image_diagonal_px / 43.27 (INFERRED; 35 mm frame diagonal is about 43.27 mm; this is standard but not cited here). Digital zoom: if `DigitalZoomRatio` > 1 the EXIF focal may or may not be scaled (UNVERIFIED), so warn. A 10% error in D changes the correction for h = 1 cm by about 0.17% of 18 cm = 0.03 cm (INFERRED arithmetic), well inside the budget. So D needs only rough accuracy; the dominant input is the user's h.

UX: "Object thickness above the ruler surface" field (default 0), with presets (credit card 0.76 mm, coin, "lying on top of ruler" = ruler thickness entered). Show the correction applied. Warn when D is unknown (no EXIF focal): fall back to a manual "camera height" input.

## 3. Tilt from gravity

The app already stores `pitchDeg` and `rollDeg` at capture (VERIFIED, `ImageSensorData`) from the rotation vector / accelerometer (`AndroidMotionSensorProvider`, VERIFIED lines 91-92 convert `orientationAngles[1]` and `[2]` to degrees) and shows "Camera tilted - measurements may be inaccurate" when pitch > 15 or roll > 10 degrees (VERIFIED, `AnnotationEditorScreen.kt`).
- For a horizontal table and a phone lying flat, those angles are near 0 when shooting straight down (INFERRED from Android sensor axes; verify on device). Then pitch/roll estimate the tilt of the sensor plane against the target plane.
- Foreshortening for a uniform tilt theta is cos(theta): 5 degrees gives 0.4% (INFERRED, cos 5 deg = 0.9962). The 3.6% number in the sim comes from the scale GRADIENT across a 25 cm lateral offset at 60 cm range, which is larger than the uniform term (consistent with 5 deg tilt changing the distance to the plane by about 25 cm x sin 5 deg = 2.2 cm, i.e. 3.6% of 60 cm; INFERRED arithmetic matches the sim).
- So the warning threshold (15 degrees) is far too loose for the 0.1 cm target: at 3 degrees the gradient term over 25 cm is about 2.2% (INFERRED scaling of the above). ADR-002 sets the proposed threshold to 3 degrees for the "standard test" and keeps the 15/10 degree text as a hard "unreliable" tier.
- Limits: gravity sensors give tilt only for in-app capture. For gallery images (PR #639 annotate-existing) there is no pitch/roll; the homography route (section 1) is the only tilt fix and the UI must say "tilt unknown".
- Correction from gravity for a flat horizontal target = apply a rotation-only homography. Needs the intrinsics (f_px, principal point). INFERRED feasible but gravity sensor error (about a degree, UNVERIFIED) and unknown yaw make this a second-tier option behind the 4-point homography.

## 4. Two-photo / stereo from motion

Baseline from IMU requires double integration of accelerometer data, which drifts quadratically (INFERRED); a few-cm baseline with mm accuracy is not realistic without visual-inertial tracking (that is ARCore's job). Not recommended; ARCore's own depth-from-motion (see depth-sources.md) is the productised version and is live-session only.

## 5. Recommended composition (feeds ADR-002)

1. Undistort (optional profile / self-calibrated k).
2. Plane homography from the reference (if >= 4 coplanar points) else scalar scale from the reference line.
3. Apply to both object endpoints in plane coordinates; then multiply by the height-offset factor (D - h)/D for the object's surface.
4. Report with an uncertainty estimate built from reference-line length, tick residuals, tilt tier and whether h/D were provided.
