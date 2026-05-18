# Requirements: SteleKit Image Meter

**Author**: Tyler Stapler  
**Date**: 2026-05-16  
**Status**: Draft  
**Use case**: Replace the ImageMeter Android app for construction management and project planning, adding tighter integration with SteleKit pages, Google Photos/Drive, and external measurement devices.

---

## Problem Statement

Tyler currently uses ImageMeter (third-party Android app) to photograph construction sites and annotate images with scaled measurements. He wants this capability native to SteleKit so:
1. Annotated images live inside Logseq-style pages and journals (no app-switching)
2. Measurements integrate with block math / calculations
3. Images are organized via SteleKit's tagging and linked to Google Photos / Drive
4. External devices (laser rangefinders) feed measurements directly into annotations

---

## User Stories

### Epic 1 — Image Capture & Import

**US-1.1** As a contractor on-site, I can take a photo from within SteleKit (Android/iOS) so that the image is immediately available for annotation without leaving the app.

**US-1.2** As a user, I can import existing images from Google Photos or Google Drive so that I can annotate photos I've already taken.

**US-1.3** As a desktop user, I can import images from disk or via a connected webcam/USB camera so that the platform is not restricted to mobile.

**US-1.4** As a web user, I can import images from my file system or any connected camera device so that the feature is accessible from a browser.

---

### Epic 2 — Calibration & Scale Setting

**US-2.1** As a user, I can mark a reference object of known length on the photo to set the image scale so that all measurements derived from that image are accurate.

**US-2.2** As a user on an ARCore-capable Android device, the app can automatically determine image scale from depth sensor data (ARCore / LiDAR) so that I don't need a reference object.

**US-2.3** As a user, the app can use EXIF focal length + camera sensor size metadata to infer approximate scale, with a confidence indicator, so that I have a calibration option when no reference object is visible.

**US-2.4** As an advanced user, I can connect a Bluetooth LE laser rangefinder (e.g. Leica DISTO, Bosch GLM series) and trigger a reading that sets the distance for a selected line segment in the image, automatically calibrating that segment.

**US-2.5** As a researcher, the system supports state-of-the-art monocular depth estimation models (e.g. Depth Anything v2, ZoeDepth) for scale recovery from a single image when hardware depth is unavailable.

**US-2.6** The system supports USB serial / OTG and Bluetooth HID (keyboard emulation) external device protocols in addition to BLE, covering the broadest range of laser measurement tools.

---

### Epic 3 — Measurement & Annotation Tools

**US-3.1** I can draw a line between two points on a photo and see the real-world distance (in my chosen unit) rendered on the image.

**US-3.2** I can define a polygon on the photo and see its area (and optionally volume if depth is known) calculated.

**US-3.3** I can measure angles between three points on the image (e.g. roof pitch, wall corner).

**US-3.4** I can add text labels with leader arrows to annotate regions or point to features.

**US-3.5** I can create a calibrated reference grid overlaid on the image (e.g. from a tape measure visible in the photo) so that the entire plane is scaled automatically.

**US-3.6** External device readings (from laser rangefinder) are inserted as labeled measurement annotations directly on the image at the location I designate.

**US-3.7** Measurements support unit conversion (mm, cm, m, in, ft) and the app remembers my preferred unit per project/graph.

**US-3.8** I can run arithmetic expressions on named measurements (e.g. `wall_A + wall_B`, `area_room_1 * 0.092`) within SteleKit block math.

---

### Epic 4 — SteleKit Integration

**US-4.1** Annotated images are embedded in Logseq-style pages as image blocks, with measurement data stored as block properties (e.g. `::length:: 3.2m`, `::area:: 14.5m²`).

**US-4.2** A dedicated gallery view shows all annotated images across the current graph, filterable by tags and sortable by date, project, or measurement type.

**US-4.3** Images can be tagged (single or multi-label) and those tags are queryable via SteleKit's existing block query system.

**US-4.4** I can navigate from a gallery image to the page block that contains it (and vice versa via back-links).

**US-4.5** Measurements on images can be referenced in block calculations using named identifiers (e.g. `{{measure: living-room-floor.width}}`).

**US-4.6** Journal integration: capturing an image from the camera auto-inserts the annotated block into today's journal page.

---

### Epic 5 — Google Integration

**US-5.1** I can browse and import images from my Google Photos library directly within SteleKit.

**US-5.2** I can import images from Google Drive folders.

**US-5.3** I can export annotated images (with visual overlays baked in) plus a JSON sidecar of raw measurements to a Google Drive folder.

**US-5.4** (Optional) I can write annotated image copies back to a designated Google Photos album.

---

### Epic 6 — Platform & Hardware Extensibility

**US-6.1** On platforms with webcams (desktop, web), the live camera feed can be used as an image source with real-time measurement overlay.

**US-6.2** On Android/iOS, all available device sensors are surfaced: accelerometer (pitch/roll for perspective correction), compass (bearing annotation), GPS (location tagging), depth/LiDAR (auto scale).

**US-6.3** The external device protocol layer is plugin-based so new sensor types (e.g. thermal cameras, 3D scanners) can be added without modifying core annotation logic.

**US-6.4** The system supports connecting to and parsing data from any device that exposes a standard protocol (BLE GATT, USB HID, USB serial/FTDI, keyboard emulation).

---

## Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR-1 | Annotation layer renders at ≥ 30 fps on mid-range Android (Snapdragon 695) |
| NFR-2 | Measurement data is stored in the Logseq graph (plain text / SQLDelight), not in a proprietary binary blob — survives graph export |
| NFR-3 | All measurement computation is deterministic and reproducible from stored calibration data |
| NFR-4 | Google OAuth tokens are stored securely using platform credential storage (Android Keystore, iOS Keychain, system keyring on desktop) |
| NFR-5 | The feature degrades gracefully on platforms without camera / sensors — import-only mode |
| NFR-6 | No mandatory internet connectivity for annotation — cloud sync is opt-in |
| NFR-7 | Image storage is local-first; large images are not replicated into SQLDelight but referenced by path |

---

## Out of Scope (v1)

- Real-time collaborative annotation (multi-user simultaneous editing of the same image)
- Video annotation / time-coded measurements
- 3D point cloud generation from stereo images
- Integration with BIM software (Autodesk, Revit) — future epic

---

## Constraints

- Must integrate with existing SteleKit KMP architecture (`commonMain`, `androidMain`, `jvmMain`, `iosMain`)
- Must use Arrow `Either` for all repository-boundary error handling
- Must route all SQL writes through `DatabaseWriteActor`
- Must use `PlatformDispatcher.DB` for database operations
- UI must be implemented in Compose Multiplatform
- New dependencies must be evaluated for KMP compatibility before inclusion

---

## Success Criteria

1. Tyler can replace his ImageMeter workflow end-to-end using SteleKit on Android
2. A photo taken on-site becomes an annotated, measurement-tagged page block within 60 seconds
3. Measurements from a Leica DISTO (BLE) appear as annotations without manual typing
4. Images are queryable by tag in the SteleKit gallery and findable via block back-links
5. Annotated images can be exported to Google Drive as image + JSON in one tap
