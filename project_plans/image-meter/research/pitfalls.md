# Pitfalls & Risk Areas: SteleKit Image Meter

**Author**: Research Agent  
**Date**: 2026-05-16  
**Status**: Complete

---

## 1. ARCore Depth API — Accuracy and Device Coverage

### Known Failure Surfaces

ARCore's software depth (depth-from-motion) fails or degrades severely on:
- **Textureless / uniform surfaces**: white walls, painted drywall, concrete. The algorithm depends on visual feature tracking across frames — no features means no depth estimate for those pixels.
- **Transparent/reflective surfaces**: glass, mirrors, polished metal. Light bounces back unpredictably; depth estimates are nonsensical (may read as the distance to the reflection, not the surface).
- **Dark surfaces**: low contrast prevents feature detection; depth confidence drops to zero.
- **Outdoor direct sunlight**: SLAM-based depth degrades under washing sunlight that bleaches texture. ToF sensors are also affected by IR interference outdoors.
- **Moving subjects**: any object that moves between frames (person walking, swinging door) creates depth artifacts since motion-stereo assumes a static scene.

### Accuracy Numbers

Research measurements in real-world scenes show systematic error of **8–10 cm** at typical room distances (1–5 m) for software depth. This is unsuitable for millimeter-accuracy construction measurements. Dedicated ToF sensors improve this substantially but are present on fewer devices.

The depth image is also stored at **lower resolution** than the camera image (typically ~256×192 px), so a single pixel of depth error covers a large image patch.

### Distance Degradation

Depth from motion accuracy degrades roughly linearly with distance. Beyond ~4 m the noise floor rises significantly. At 8–10 m (common for exterior wall measurements) software depth becomes unreliable.

### Device Coverage Gap

The Depth API is enabled on a subset of ARCore-certified devices — approximately 88–94% of active ARCore devices support it. More importantly, hardware depth sensors (ToF) are only on a fraction of those. The app **must** gate on `session.isDepthModeSupported(DepthMode.AUTOMATIC)` and fall back to reference-object or EXIF calibration for devices without it.

### Mitigations

- Always check `ArSession.isDepthModeSupported()` at startup; expose calibration fallback paths.
- Display a confidence heatmap to the user so they see which regions have unreliable depth.
- Use raw depth (`DEPTH_MODE_RAW`) for measurement (less smooth but more geometrically accurate than the default filtered mode).
- Require the user to "walk" the camera for 2–3 seconds before accepting a depth reading.
- Set a maximum distance threshold (e.g., 5 m) beyond which ARCore depth is disallowed and the user must use BLE or reference-object calibration.
- Document in UI that glass, mirrors, and white walls cannot be measured with auto-depth.

---

## 2. BLE Reliability on Android — GATT Stack Instability

### GATT Status 133 — The Notorious Catch-All

Android's BLE stack returns `GATT_ERROR (133)` for an enormous range of underlying failures: connection timeout, device out of range, stack inconsistency after rapid reconnects, and Android 14 tablet-specific bugs where the entire BLE stack enters a broken state until reboot. It is not a single recoverable error — it is a symptom of many distinct problems.

Key causes specific to this feature:
- **Rapid reconnect without `gatt.close()`**: the BLE stack has a 30-connection object limit (native layer); leaked GATT objects cause all subsequent connections to fail with 133.
- **Android 14 regression**: repeated GATT 133 errors on tablets until device reboots, unrelated to the peripheral.
- **MTU mismatch**: Android 12+ sends max MTU (517 bytes) by default; older Leica DISTO firmware may not handle this.

### Leica DISTO Specifics

The DISTO uses BLE with a proprietary GATT service, not standard SPP. Known issues:
- The device expects an acknowledgment within **2 seconds**; failure to send ACK causes "Error 240" on the DISTO side and drops the connection.
- Older DISTO models (D2, pre-X series) use BLE 4.0 and may not support the Android 12 max MTU. Explicitly negotiate MTU down to 23 bytes as a safe fallback.
- The `d7knight/Disto-App` open source implementation and `seichter/d2relay` provide working reference implementations of the GATT characteristic layout — study these before implementing the protocol.
- Pairing (bonding) is unreliable across some Android OEMs (Nexus, older Motorola). Implement a "forget and re-pair" recovery flow.

### Background Scanning Restrictions (API 26+)

- From API 26+, background BLE scanning is throttled. From API 31+, a `ForegroundService` is required to initiate connections while backgrounded.
- New runtime permissions since API 31: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`. Missing any of these causes **silent failure** — the scan simply returns no results rather than throwing a `SecurityException`.
- Android 13 adds aggressive battery optimization for new installs; BLE foreground services are killed more aggressively.

### Mitigations

- Always call `gatt.disconnect()` followed by `gatt.close()` before any reconnect attempt.
- Implement **exponential backoff** (min 2s, max 60s) on GATT 133 before retrying.
- Use `autoConnect = false` for the initial connection, switch to `autoConnect = true` after first successful connect for background reconnection.
- Queue all BLE operations serially — never issue a second operation before the callback for the first returns.
- Declare `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` at runtime; handle the case where they are silently missing (check with `ContextCompat.checkSelfPermission` before every scan).
- Keep the BLE logic inside a dedicated `ForegroundService` with a persistent notification to survive Android's process death on API 31+.
- Negotiate MTU explicitly to 100–150 bytes as a middle ground between throughput and DISTO compatibility.

---

## 3. Google Photos API — Scope Deprecation (Breaking Change, March 2025)

### What Changed

As of **March 31, 2025**, Google removed the following scopes from the Google Photos Library API:
- `photoslibrary.readonly` — previously used to browse the entire photo library
- `photoslibrary.sharing` — shared album access
- `photoslibrary` (full access)

**Result**: Any app calling these scopes now receives `403 PERMISSION_DENIED`. Third-party apps can no longer read arbitrary photos from the user's library.

### What Remains Available

- `photoslibrary.appendonly`: upload media and create albums (still works).
- `photoslibrary.edit.appcreateddata`: read and edit only items your app uploaded.
- **New Google Photos Picker API**: a system-level photo picker (similar to the Android Photo Picker) where the user selects photos in Google's UI and grants access to only those specific items.

### Impact on This Feature

US-5.1 ("browse and import images from Google Photos library") **cannot be implemented using the Library API** if users want to import pre-existing photos they took outside SteleKit. The Picker API must be used, which means:
- No programmatic browsing/search of the user's full library.
- No listing of albums the user didn't create through SteleKit.
- The import flow becomes "user taps → system Photos picker → selects → app receives URI" — similar to the Android Photo Picker pattern.

US-5.4 ("write annotated images back to a Google Photos album") is only possible for albums created by SteleKit itself via `appendonly` scope.

### Mitigations

- Replace the "browse Google Photos" UI with the **Google Photos Picker API** (REST-based overlay picker): https://developers.google.com/photos/picker
- For Google Drive import (US-5.2), the Drive API has no equivalent restrictions — use Drive as the primary cloud import path.
- Make clear in UI copy that SteleKit can only access photos the user explicitly selects, not their full library.
- Implement the OAuth flow requesting only `photoslibrary.appendonly` + `photoslibrary.edit.appcreateddata` to minimize scope rejection friction.
- Rate limits: 10,000 requests/day for general API; 75,000 for media bytes. For a single-user app this is unlikely to be hit, but implement retry-with-backoff on `429` responses.

---

## 4. Camera2/CameraX — Rotation and EXIF Pitfalls

### EXIF Orientation Inconsistency Across Manufacturers

This is one of the most common Android camera bugs in production apps:
- Samsung devices have a **known bug** where the EXIF orientation tag is written incorrectly when the image is saved in landscape mode.
- Some devices write `ORIENTATION_TRANSVERSE` (diagonal flip) for front camera portrait photos — a value most image-loading libraries do not handle, resulting in mirrored or diagonally flipped images.
- CameraX deliberately does not rotate pixel data — it only sets EXIF metadata. Libraries that ignore EXIF will display rotated images.

### Locked Orientation Pitfall

When the app forces portrait or landscape orientation lock (common for an annotation editor), CameraX cannot reliably determine device orientation. This causes `targetRotation` to become undefined, and the EXIF data may be written as 0° regardless of how the phone is held.

**Impact for this feature**: If the image is captured sideways but the annotation coordinate system assumes portrait, all measurement coordinates will be 90° off. This is a subtle bug that only manifests on certain devices.

### Memory Pressure with Full-Resolution Images

Modern Android phones produce 50–200 MB JPEG files at full resolution. Loading these directly into a `Bitmap` for display will trigger `OutOfMemoryError` on any device with less than 4 GB RAM. The standard pattern (load at screen resolution, then load a full-res crop for annotation precision) is non-trivial to implement correctly.

### Lens Distortion

Wide-angle lenses (default on most phones) introduce barrel distortion. Measuring a straight line near the edge of the frame without undistortion correction will produce systematically wrong measurements — up to several percent error depending on lens. EXIF does not reliably encode distortion coefficients.

### Mitigations

- Always read EXIF orientation using `ExifInterface` (not system metadata) and rotate the `Bitmap` programmatically before displaying.
- Use `OrientationEventListener` or `RotationProvider` (CameraX) to set `targetRotation` before `takePicture()`, even in locked-orientation activities.
- Load images using `BitmapFactory.Options.inSampleSize` set to the nearest power-of-2 that fits the display resolution; load full-res only for the active annotation crop region.
- Apply `Camera2` lens distortion correction coefficients from `CameraCharacteristics.LENS_DISTORTION` (available API 28+) before computing measurements from pixel coordinates.
- Test on a Samsung Galaxy device and a Google Pixel device at minimum — these cover the two most divergent EXIF behaviors.

---

## 5. Monocular Depth ML Models — Size, Latency, and Failure Modes

### Model Size vs. Accuracy

Depth Anything V2 model sizes:
- Small (ViT-S): ~24.8M parameters, ~50–100 MB as TFLite. This is the only size practical for on-device inference.
- Base (ViT-B): ~97.5M parameters — too large for real-time mobile use.
- Large/Giant: server-only.

### TFLite Conversion Is Not Officially Supported

The Depth Anything V2 repository does **not** ship an official TFLite model. Community attempts to convert via ONNX → TFLite encounter "not implemented" errors for transformer attention ops. The **NNAPI** and **GPU delegate** may not support all ops in ViT-based models, falling back silently to CPU, negating the speedup.

Qualcomm AI Hub has an optimized Depth Anything V2 for Snapdragon (QNN), but this is vendor-specific and not portable.

### NNAPI Pitfalls

- NNAPI can be **slower than CPU** on some devices (documented: 170ms NNAPI vs 70ms CPU on Snapdragon 660).
- NNAPI delegate initialization is asynchronous and adds 500ms–2s cold start latency.
- Not all NNAPI implementations support transformer self-attention — unsupported ops fall back to CPU, breaking the "fast path" silently.

### Real Latency

For ViT-S-based depth estimation at 384×384 input on a Snapdragon 695 (mid-range target), expect:
- CPU (XNNPACK): ~400–800 ms per frame — suitable for "tap to measure" but not real-time overlay.
- GPU delegate (if ops supported): ~100–200 ms — marginal for 30fps; cannot be used for the annotation overlay at full frame rate.

### Failure Modes for Construction Scenes

- **Textureless walls**: same failure as ARCore — the model cannot infer depth from a uniform surface, outputs flat or random depth.
- **Narrow corridors**: depth scale is relative (metric depth requires fine-tuned models like ZoeDepth); a relative depth map is useless for absolute measurements without a reference.
- **High dynamic range**: bright windows in a dark room cause the model to either clip or mis-scale depth across the scene boundary.
- **Outdoor direct sun**: overexposure causes the same issues as dark surfaces.

### Mitigations

- Use ML depth as a **calibration assist** (set scale from depth), not as a real-time measurement sensor. A single inference at "set calibration" time is acceptable at 400–800ms.
- Gate ML depth on `Build.SUPPORTED_ABIS` and minimum memory (require 3 GB+ RAM before loading the model).
- Use ZoeDepth or MiDaS v3.1 Small as fallback — these have established TFLite exports and better-known mobile performance profiles.
- Offer a "compute depth" button that runs inference once on the captured photo (not live), keeping the real-time annotation overlay at native speed.
- Display a confidence warning when the model returns low-variance depth (indicates textureless scene where results are unreliable).

---

## 6. Google Drive API — Large File Upload Reliability

### Resumable Upload Requirements

For files > 5 MB (any full-resolution construction photo), the resumable upload protocol must be used. Key pitfalls:
- **Session expiry**: resumable upload URIs expire after **7 days**. If the app is backgrounded mid-upload and the session expires, the entire upload must restart.
- **Chunk size constraint**: must be multiples of 256 KB; using non-aligned chunks causes server rejection.
- **Server can issue a new Location URI** mid-upload; clients that ignore the `Location` response header will upload to a stale URI and silently succeed from the client's perspective while the server discards the data.
- **Android process death**: the upload `InputStream` is broken if the process is killed mid-chunk. The app must persist the upload session URI to disk and resume after restart.

### Mitigations

- Store the resumable upload session URI in SQLDelight as soon as it is obtained; clear it only on confirmed completion.
- Use the Google Drive Android API client library (google-api-java-client) which handles chunking and resumption automatically, rather than implementing the raw HTTP protocol.
- Schedule large uploads via `WorkManager` with `NetworkType.CONNECTED` constraint to survive process death.
- Set chunk size to 2–4 MB for a reasonable throughput-vs-resume-overhead tradeoff on mobile.

---

## 7. Android Image Storage — Scoped Storage Pitfalls

### Content URI vs. File URI

Since API 29 (Android 10), direct file path access to shared storage is blocked. All shared media must go through `MediaStore` content URIs or the Storage Access Framework (SAF). Key failure modes:
- Passing a `file://` URI to an `<img>` tag or `Intent` from API 24+ throws `FileUriExposedException`.
- `MediaStore` URIs obtained on one device session may not be valid after a reboot (the `_id` column can change on rescan).
- The `WRITE_EXTERNAL_STORAGE` permission is completely ignored on API 33+; apps must use `MediaStore.Images.Media.insert()` to write to shared storage.

### Photo Picker API (API 33+ / Backported to API 21 via Play Services)

The Android Photo Picker is now the recommended way to let users select photos; it does not require `READ_MEDIA_IMAGES` permission. However:
- It cannot be pre-filtered to show only images from specific albums or dates without using the Google Photos Picker API integration.
- The returned URI is a temporary grant — the app must copy the bytes to its own app-specific storage immediately if it needs persistent access.

### App-Specific vs. Shared Storage Strategy

For this feature, the recommended pattern is:
- Annotated images are stored in **app-specific storage** (`context.filesDir` or `context.getExternalFilesDir()`), not shared storage. This avoids all scoped storage complexity.
- Only the final export (for sharing or Drive upload) writes to MediaStore/shared storage or goes directly to Drive without touching MediaStore at all.

### Mitigations

- Never use `file://` URIs in Intents. Use `FileProvider` for sharing to external apps.
- Copy imported images to app-specific storage immediately and store the internal path in SQLDelight.
- Use `FileProvider` with a well-defined authority declared in `AndroidManifest.xml` for any inter-app sharing.
- Do not attempt to use `MediaStore` for app-internal image storage — use `context.filesDir` instead.

---

## 8. Measurement Accuracy vs. User Expectations

### Realistic Accuracy by Method

| Method | Realistic Accuracy | Notes |
|---|---|---|
| Reference object calibration (2-point scale) | ±1–3% of measured distance | Limited by user's ability to click endpoints precisely |
| ARCore software depth | ±5–10 cm absolute at 1–3m | Degrades to ±20cm+ at 5m; fails on glass/white walls |
| ARCore + ToF sensor | ±1–2 cm at 1–3m | Only on devices with hardware depth (minority) |
| EXIF focal length + sensor size (US-2.3) | ±5–15% | Highly variable; sensor size data often wrong in EXIF |
| Monocular ML depth (calibrated, ZoeDepth) | ±3–8% | Relative depth scaled by reference; fails textureless |
| BLE laser rangefinder (Leica DISTO) | ±1 mm at 1–30m | Highly accurate; limited to the single measured distance |

### Common User Errors in Construction

- **Parallax error**: user clicks on the image but the camera was not perpendicular to the measured surface. A 5° tilt at 2m introduces ~1.5 cm error in a 1m measurement.
- **Calibration on a non-planar surface**: the reference object and the measured object are at different depths — this invalidates the scale.
- **Assuming flat-world for 3D measurements**: image-based measurement only produces accurate results for objects in the same plane as the calibration reference.

### Communicating Uncertainty

- Displaying a raw measurement number (e.g., "3.24 m") implies false precision. Measurements should display a confidence range (e.g., "3.24 m ± 5 cm") derived from the calibration method used.
- The UI should indicate the calibration source with color coding (green = BLE laser, yellow = reference object, red = EXIF/ML estimate).

### Mitigations

- Never display more than 1 decimal place (cm-level) for ARCore or ML-derived measurements; reserve mm precision for BLE-calibrated measurements.
- Provide an in-app calibration guide that shows correct photo technique (perpendicular, adequate texture, known reference in same plane).
- Store the calibration method and confidence alongside every measurement in SQLDelight so it can be shown on export.

---

## 9. KMP-Specific Pitfalls

### No Stable KMP Camera Library

There is no mature, production-ready KMP library for camera capture. Every existing solution (peekaboo, CameraKit, etc.) requires `expect/actual` wrappers. For this feature:
- **Android**: CameraX is mature but JVM/KMP integration requires careful `androidMain` isolation.
- **iOS**: AVFoundation must be called from `iosMain` via Kotlin/Native.
- **Desktop**: JavaCV or `javax.imageio` for import only; live camera access is limited.
- **Web**: `getUserMedia()` API via Kotlin/Wasm; no KMP abstraction layer exists.

The camera expect/actual surface must cover at minimum: launch camera, receive image bytes, get camera metadata. This is a non-trivial multi-platform implementation effort.

### BLE / Sensor Access Is Fully Platform-Specific

There is no KMP library for BLE, accelerometer, GPS, or depth sensors. Every sensor listed in US-6.2 requires fully separate `androidMain` and `iosMain` implementations with no shared code possible at the native API layer. The shared `commonMain` layer can only define interfaces and data model types.

### Serialization of Annotation Data

`kotlinx.serialization` supports KMP but complex nested sealed classes (the likely shape of `AnnotationOverlay`) require careful use of `@SerialName` and `@Polymorphic` annotations. Bugs here cause silent data loss when the overlay is deserialized — potentially losing all measurements for an image.

### Compose Multiplatform Canvas Limitations

Drawing annotation overlays (lines, polygons, text with leader arrows) uses `Canvas` in Compose. Known limitations:
- `drawPath` with complex strokes is significantly slower on iOS Compose than on Android (backed by different rendering pipelines).
- Text rendering in Canvas (`drawText`) on Desktop (Skiko) uses different font metrics than on Android — leader arrow label placement will need platform-specific tuning.
- `BlendMode` operations for annotation highlight effects are not supported on all Skiko targets.

### Mitigations

- Define a `CameraController` expect interface in `commonMain` with minimal surface area; implement fully in each `*Main`.
- Isolate all BLE code in `androidMain` behind a `BluetoothMeasurementSource` interface defined in `commonMain`.
- Write round-trip serialization tests for all `@Polymorphic` annotation types as part of `commonTest` before shipping.
- Test annotation Canvas rendering on Desktop and iOS early — do not assume Android behavior transfers.

---

## 10. Performance — Compose Rendering and SQLDelight

### Large Image Memory

A full-resolution 50 MP camera photo decoded as ARGB_8888 requires ~200 MB of heap. On Android, heap limits for a typical app are 256–512 MB. Loading such an image directly causes `OutOfMemoryError`.

The annotation editor must:
1. Display a downsampled preview at screen resolution (e.g., 1080×1440).
2. Maintain the original image coordinates for annotation (measurement calculations use pixel coordinates on the full-res image).
3. Load a high-res crop only when the user zooms in for precision annotation.

### Annotation Overlay at 30 fps (NFR-1)

Drawing 20–30 annotation primitives (lines, polygons, text) in Compose Canvas each frame is feasible at 30fps on Snapdragon 695 **only if**:
- The canvas state is not recomposed unnecessarily — use `remember` for annotation paths, not recomputed from the model on each frame.
- Use `drawIntoCanvas` and retain `Path` objects across frames rather than reconstructing them.
- Avoid allocating new `Paint` / `Path` objects inside `drawWithContent` — allocate once, update in-place.

### SQLDelight with Many Annotated Images

Querying all annotated images across a large Logseq graph (thousands of pages) with JOIN on block properties requires proper indexing. SQLDelight does not auto-index — without explicit `CREATE INDEX` on the `block_uuid` and image path columns, the gallery view query will degrade to a full table scan.

### Mitigations

- Use `BitmapFactory.Options.inSampleSize` to load at 1/4 or 1/8 resolution for the editor canvas; compute scale factor to map display coordinates back to full-res coordinates.
- Profile on a real Snapdragon 695 device (Moto G Power 5G, Redmi Note 11) before declaring NFR-1 met — emulators do not reflect GPU constraints.
- Add `CREATE INDEX` for all annotation join columns in the initial schema migration.
- Use `SubcomposeLayout` or `GraphicsLayer` for the annotation overlay to avoid full recomposition on each annotation change.

---

## Risk Summary Matrix

| Risk | Probability | Impact | Severity |
|---|---|---|---|
| Google Photos API scope removal makes library browsing impossible | **CERTAINTY** (already happened Mar 2025) | Blocks US-5.1 as designed | **CRITICAL** |
| BLE GATT 133 instability on Android 14 / OEM devices | High | Blocks US-2.4, US-3.6 on subset of devices | **HIGH** |
| ARCore depth too inaccurate for construction measurements (cm-level error) | High | US-2.2 accuracy expectations will not be met | **HIGH** |
| Depth Anything V2 has no official TFLite export; NNAPI unreliable | High | US-2.5 requires substantial conversion effort | **HIGH** |
| CameraX EXIF orientation bugs (Samsung) break measurement coordinates | Medium | Silent measurement errors on Samsung devices | **MEDIUM** |
| KMP camera/sensor require fully separate platform implementations | Certainty | Significant engineering overhead | **MEDIUM** |
| Scoped storage content URI grants expire, causing import failures | Medium | US-1.2 import flow breaks | **MEDIUM** |
| OOM on full-resolution image load in annotation editor | Medium | App crashes on mid-range devices | **MEDIUM** |
| Annotation Canvas 30fps target not met on Snapdragon 695 | Low-Medium | NFR-1 violated | **LOW-MEDIUM** |
| Drive resumable upload session expiry during background upload | Low | Export flow silently fails | **LOW** |
