# Features Research: Image Measurement & Annotation

**Date**: 2026-05-16  
**Agent**: FEATURES research agent  
**Purpose**: Competitive landscape, state-of-the-art technology, and integration patterns for SteleKit Image Meter

---

## 1. ImageMeter — Incumbent Analysis

### What It Does Well

ImageMeter (de.dirkfarin.imagemeter) is the most mature single-image measurement app on Android/iOS. Its core workflow:

1. Take or import a photo
2. Draw a reference line on a known-length object to set image scale
3. Draw lines, polygons, and angles; app computes real-world distances and areas from the calibration ratio
4. Perspective-correction mode handles oblique camera angles (not perpendicular to the subject plane)
5. Bluetooth laser rangefinder integration: measured distance is automatically injected into the selected annotation

**Supported measurement types**: lengths, angles, circles, areas, freehand drawings, geometric shapes.  
**Supported device protocols**: BLE GATT (Leica DISTO, Bosch GLM, Stanley, Stabila, Hilti, and ~30 other brands), Classic Bluetooth Serial Port Profile (SPP), and Bluetooth HID keyboard emulation. The app selects protocol automatically but allows manual override.

**Export**: PDF with overlaid annotations, spreadsheet-compatible data tables, automatic cloud upload (Google Drive, OneDrive, Dropbox, Nextcloud) in Business tier.  
**Model-scale mode**: displays both real-world and scaled sizes simultaneously — useful for architect drawings.

### Critical Limitations (Improvement Targets for SteleKit)

| Limitation | Details |
|---|---|
| Siloed app, no note context | Images live in ImageMeter's own folder structure, not inside notes or pages. No bidirectional linking to project context. |
| Flat folder organization | Organization is color-coded subfolders only — no tags, no full-text search, no queryable metadata |
| Measurements are opaque | Measurement values are stored in proprietary format; not usable in external calculations without manual copy-paste |
| No desktop peer | The Windows/Linux desktop version (v1.5.1, May 2026) is a second-class port — the primary workflow is phone-only |
| No math/formula integration | Measurements cannot be combined, summed, or referenced in formulas within the same workspace |
| Image search/gallery is weak | No cross-image gallery with filtering by measurement type, date range, or project |
| Workspace edge crop issue | Objects cropped to the image edge cannot be measured — reported as a consistent user complaint |
| Business features require subscription | Auto-upload and multi-device sync cost extra; the base app is free with ads |

### Key Design Insight

ImageMeter treats each photo as an isolated artifact. SteleKit's advantage is treating annotated images as first-class **block-embedded data** inside pages, making measurements queryable via Logseq block properties and reusable in calculations.

---

## 2. Competing Construction Management Apps

### PlanGrid (now Autodesk Build)

- Primary use: annotating PDF plans/blueprints, not field photos
- Photo management: attach photos to issues and tasks, basic markup (arrows, text), no photogrammetric measurement
- Strength: document version control, RFIs, and field reports tied to plan locations
- Gap: no "measure from photo" capability — annotations are qualitative, not metric

### Fieldwire

- Primary use: task management and plan viewing on mobile
- Photo sharing with in-app messaging; basic drawing markup on plans
- No photogrammetric measurement — measurement tools operate on imported CAD/PDF plans (using known scale) not field photos
- Strength: task assignment tied to plan locations; strong subcontractor coordination

### Buildertrend

- Aimed at residential contractors; photo documentation with notes and organization by project
- Blueprint measurement tools for estimate quantities, but only on uploaded digital drawings
- Photo annotations are text/arrow overlays, not metric measurements
- Strength: financials + scheduling + CRM integrated with photos

### Canvas / Twindo (iOS LiDAR)

- Uses iPhone Pro/iPad Pro LiDAR to scan interior spaces in minutes
- Produces editable CAD/BIM as-builts with 99% accuracy claim
- Exports to Revit, AutoCAD, SketchUp, Chief Architect, Archicad, Vectorworks
- **iOS/LiDAR only** — not available on Android; requires LiDAR-capable hardware (iPhone 12 Pro+)
- Interactive 3D web viewer allows post-scan measurement
- Key insight: Canvas solves the scale problem with hardware depth, not image calibration

### Hover

- Exterior measurement focus: photographs an exterior (siding, roof) from multiple angles
- AI reconstructs a 3D model of the exterior with metric dimensions
- Trusted by 300K+ professionals and major insurance carriers for damage assessment
- Not for interior or single-photo scenarios; requires walking around the structure
- Key insight: multi-photo exterior measurement is a solved problem; single-interior-photo is the gap

### Key Competitive Gap

No app in the construction space combines:
- Photogrammetric measurement from a single photo
- Tight integration with a note-taking/project management knowledge graph
- Laser rangefinder BLE integration for auto-calibration
- Queryable measurement data in block properties

SteleKit can occupy this unique position.

---

## 3. Open-Source Photogrammetry Approaches

### Core Algorithm: Pixels-Per-Metric Ratio

The foundation of single-image measurement is simple: once a reference length is established, every distance measurement is:

```
real_distance = pixel_distance * (reference_real_length / reference_pixel_length)
```

This "pixels per metric" ratio is computed from the user-drawn reference segment and the known real-world length. All subsequent measurements reuse this ratio.

**Limitation**: the ratio is only valid in the same plane as the reference object. Objects at different depths from the camera will be measured incorrectly unless perspective/homography correction is applied.

### Perspective Correction with Homography

When the camera is not perpendicular to the measurement plane, OpenCV's `findHomography()` (minimum 4 point correspondences) produces a 3×3 matrix that rectifies perspective distortion. The corrected image allows correct measurement even from oblique angles. This is the same technique ImageMeter uses for its "perspective correction" mode.

Implementation path: OpenCV Java/Kotlin bindings are available for Android; for KMP the JVM platform can use OpenCV via JNI.

### ArUco Markers — Automatic Scale Detection

ArUco markers (OpenCV `aruco` module) are printed fiducials that provide:
- Automatic detection in images (no user interaction)
- Pose estimation (rotation + translation relative to camera)
- Scale recovery: because marker physical size is known, the camera-to-marker distance is computable
- The marker size parameter sets units (meters, mm, inches) and all distances are returned in those units

**Workflow**: place a printed ArUco marker of known size (e.g. 100mm × 100mm) in the scene. The system auto-detects it, computes scale, and all measurements in the image are immediately calibrated.

ArUco outperforms QR codes for this use case: QR codes store more data but are harder to detect at low resolutions and odd angles; ArUco markers are designed for pose estimation.

### COLMAP — Multi-Photo Reconstruction

COLMAP (Structure-from-Motion + Multi-View Stereo) reconstructs metric 3D models from unordered photo sets. Python bindings (`pycolmap`) are available.

- Scale is up to a scale ambiguity unless known-distance markers or GPS are provided
- With ground control points (measured distances or GPS), the model is metric
- Too heavy for on-device mobile inference; suitable for desktop post-processing of photo sets
- Out of scope for v1 (which targets single-photo measurement), but relevant for future multi-photo jobs

---

## 4. Monocular Depth Estimation — State of the Art

### Model Landscape (2024–2025)

| Model | Type | Params (smallest) | Relative/Metric | Notes |
|---|---|---|---|---|
| Depth Anything V2 (ViT-S) | Foundation | 24.8M | Both | NeurIPS 2024; ONNX for Android; Core ML (iOS) |
| ZoeDepth | Metric fine-tune | ~345M | Metric | Fastest inference (0.17s); large model size |
| MiDaS v3.1 (MiDaS-small) | Relative | ~21M | Relative only | Older; baseline for comparison |
| Metric3D v2 | Foundation | varies | Metric | Zero-shot metric; IEEE TPAMI 2024 |
| UniDepth | Foundation | varies | Metric | CVPR 2024; best cross-domain generalization |

### Key Accuracy Findings

- **Relative depth** (MiDaS, standard Depth Anything V2): produces depth maps where values are proportional to depth, but absolute scale is unknown. Useful for understanding scene structure, not for metric measurements without a calibration reference.
- **Metric depth** (ZoeDepth, Depth Anything V2 metric fine-tune, Metric3D v2, UniDepth): predicts absolute depth in meters from a single image. Requires camera intrinsics to generalize well.
- Indoor accuracy: when fine-tuned for indoor scenes (NYUv2 dataset), AbsRel error is ~0.05 (5% absolute relative error). Zero-shot (no fine-tuning) on unseen domains, AbsRel rises to ~0.5 — much too noisy for construction measurement at centimeter precision.
- **Practical conclusion**: monocular depth estimation alone is **insufficient for centimeter-accurate construction measurement**. It is useful as a fallback with a visible confidence indicator (per US-2.3/US-2.5), but should not be presented as a primary calibration method.

### Recommended On-Device Deployment

- Depth Anything V2 ViT-S (24.8M parameters) is the best mobile candidate:
  - ONNX format available, opset 17, dynamic shapes supported
  - Core ML conversion for iOS
  - Qualcomm AI Hub has an optimized version
  - Inference ~180ms on A100 GPU; expect 500ms–2s on a Snapdragon 695 for ViT-S
- For on-device inference, consider INT8 quantization via NNCF to halve model size and latency

### Integration Strategy for SteleKit

Use depth estimation as a confidence-rated hint, not ground truth:
1. **Primary** (US-2.1): User draws reference segment on known object → pixel-per-metric ratio
2. **Secondary** (US-2.4): BLE laser rangefinder auto-calibrates a selected segment
3. **Tertiary** (US-2.2): ARCore Depth API (available on 87%+ of Android devices as of Oct 2025) provides hardware-assisted depth with ToF fusion
4. **Last resort** (US-2.5): Metric Depth Anything V2 with confidence score displayed; warn user that accuracy is ~5–50%

---

## 5. Reference Object & Calibration Techniques

### User-Drawn Reference (ImageMeter Pattern)

The simplest and most reliable approach:
1. User draws a line segment on a known-length object
2. User types the known length (e.g. "2400 mm")
3. System computes `scale = known_length / segment_pixel_length`
4. All subsequent measurements use this scale

Works perfectly for a single plane. Fails silently when objects are at different depths without perspective correction.

### ArUco Marker Auto-Detection

Best for workflows where the user can place a marker in the scene:
- Print a marker, place it on the wall/floor
- OpenCV `detectMarkers()` → `estimatePoseSingleMarkers()` → scale is automatic
- Works even with partial occlusion; supports error correction
- No user interaction needed after photo import
- **Recommended for power users / repeat site visits**

### Tape Measure in Image

A tape measure visible in a photo creates a calibrated reference grid along its length. This can be handled as a special case of the user-drawn reference: user marks two tick marks and types the interval (e.g. "500 mm"). More complex auto-reading of tape measure markings via CV is research-level and not recommended for v1.

### Camera EXIF + Sensor Data

Focal length (from EXIF) + camera sensor size → horizontal field of view. Combined with ARCore's tracked device pose (including accelerometer pitch/roll), an approximate scale can be recovered for objects on known planes (floor, wall). Accuracy is ±20–30% at best without additional reference — useful only as a rough sanity-check indicator.

### BLE Laser Rangefinder (Primary External Calibration)

The most accurate single-shot approach:
1. User points laser at a feature and pulls the trigger
2. Device sends a measurement reading over BLE GATT
3. App injects this reading as the length of the selected line annotation
4. Image is now calibrated to physical reality

No image processing needed — the laser is ground truth (±1mm accuracy for Leica DISTO/Bosch GLM).

---

## 6. Laser Rangefinder Integration

### Market Leaders and Most Common Models

**Leica DISTO** dominates the professional construction segment:
- D2: entry-level BLE; most common in field use
- D510 / E7100i: mid-range; area/volume computation on-device
- S910 / X4: premium; 360° tilt sensor, point-to-point measurement
- All modern DISTO models support Bluetooth LE (no pairing required)

**Bosch GLM** dominates the prosumer/DIY segment:
- GLM 50C / 50CX: most popular Bluetooth-enabled models
- GLM 100C / 120C / 150C: higher range; BLE + USB data output
- Interface: Bluetooth serial (SPP-style) or BLE GATT depending on model

### BLE GATT Protocol Details

**Leica DISTO**: Uses a proprietary BLE GATT service. Community reverse engineering (github.com/seichter/d2relay) shows:
- Service UUID: `3ab10100-f831-4395-b29d-570977d5bf94` (Leica custom)
- Measurement characteristic: notify-capable; emits little-endian float (meters) on trigger
- Remote trigger: write a command byte to the control characteristic
- No BLE pairing required (LE Privacy mode)

**Bosch GLM**: Uses Bluetooth SPP (classic BT) on older models; newer GLM 50C+ have BLE. Community implementation (github.com/philipptrenz/BOSCH-GLM-rangefinder) uses serial-over-BLE UUID `0x1101` (SPP UUID). Data format is ASCII: `MM:D<float_value>\r\n`.

**Keyboard Emulation (HID)**: Many budget laser meters and some Bosch models advertise as Bluetooth HID keyboards. Measurement is sent as keystrokes of the distance value followed by Enter. ImageMeter supports this via its "keyboard device" protocol mode.

**USB Serial / OTG**: Some meters (older Bosch, Hilti) export via USB-CDC (virtual COM port). Android supports USB Host mode; reads via Android USB Manager API.

### Protocol Abstraction Design

The plugin-based protocol layer (US-6.3) should define:
```
interface LaserRangefinderProtocol {
    suspend fun connect(device: BleDevice): Either<DeviceError, Unit>
    fun measurements(): Flow<MeasurementReading>
    suspend fun triggerMeasurement(): Either<DeviceError, Unit>
    suspend fun disconnect()
}
```

Implementations: `LeicaDistoProtocol`, `BoschGlmProtocol`, `HidKeyboardProtocol`, `UsbSerialProtocol`.

---

## 7. Construction Annotation Standards

### Industry Context

There is no single universal standard for field photo markup annotations. Different contexts use different conventions:

- **Blueprint/drawing annotations**: governed by the National CAD Standard (NCS v6), which defines symbols for dimensions, section cuts, detail callouts, and material indications
- **Field photo markup**: informal — apps like JobTread and Fieldwire use free-draw, shapes, arrows, and text with color coding (red = critical issue, green = positive, yellow = question)
- **Construction photography documentation**: Clemson University / institutional standards call for timestamps, location metadata, directional annotations, and issue tagging — not metric measurement overlays

### Practical Annotation Vocabulary

For construction measurement, the standard primitives in use across tools are:

| Annotation Type | Common Use |
|---|---|
| Linear dimension with arrows | Wall length, opening width, clearance |
| Polygon + area label | Floor area, ceiling tile count |
| Angle arc | Roof pitch, corner angle |
| Text callout with leader | Material specification, defect note |
| Circle/radius | Pipe diameter, hole size |
| Grid overlay | Reference scale across a surface |

SteleKit should implement all six. Export should include these as SVG-compatible vector overlays baked into JPEG for PDF output, and stored separately as structured data in block properties.

---

## 8. Image Organization in Note-Taking Apps

### Logseq

- **Storage**: images are placed in `assets/` directory; embedded as `![name](../assets/img.jpg)` in Markdown
- **Block properties**: any block can have properties via `::key:: value` syntax; these are queryable
- **Limitations**: no native gallery view for images; no bulk image search; image organization relies entirely on page/block structure; local file path embedding can break if graph is moved
- **Pattern**: the Logseq way is to embed `![img](path)` inside a block that also carries measurement properties — exactly what SteleKit Image Meter should produce

### Obsidian

- **Storage**: configurable assets folder; `![[image.jpg]]` wiki syntax or standard Markdown
- **Metadata**: YAML frontmatter or inline fields via Dataview plugin; Dataview plugin enables SQL-like queries on properties
- **Gallery**: no built-in gallery; third-party plugins (Image Gallery, Mosaic) provide it
- **Key lesson**: users strongly want a gallery view for images — it's a popular plugin category, suggesting the built-in tools don't deliver it

### Notion

- **Storage**: cloud-hosted; images uploaded to Notion CDN
- **Organization**: images inside pages as blocks; gallery views are built-in for database entries
- **Properties**: database properties (number, select, multi-select, formula) are the primary organization mechanism
- **Key lesson**: Notion's gallery database view — filter by tag, sort by date, formula-derived fields — is the gold standard for structured image organization; SteleKit should emulate this for the annotated image gallery

### Design Principles for SteleKit

1. **Local-first** (NFR-7): store images referenced by path in block content, not embedded in SQLDelight
2. **Block properties for all measurement metadata**: `::length:: 3.2m`, `::area:: 14.5m²`, `::calibration-ref:: aruco-100mm`, `::device:: leica-disto-d2`
3. **Gallery view** (US-4.2): a dedicated screen that queries all blocks with image + measurement properties — this is the single most-requested missing feature across Obsidian and Logseq
4. **Tags as multi-select block properties** (US-4.3): compatible with Logseq's query system
5. **Backlinks** (US-4.4): standard Logseq block reference mechanism works unchanged

---

## Summary of Key Findings

1. **ImageMeter's core algorithm is simple but its organizational gap is enormous**: the pixel-per-metric ratio + perspective homography for single-image measurement is well-understood and implementable with OpenCV. ImageMeter's real weakness is that images are isolated artifacts with no note context, no queryable properties, and no integration with project knowledge — exactly where SteleKit wins by treating annotated images as block-embedded data.

2. **BLE laser rangefinder is the highest-value calibration path**: Leica DISTO and Bosch GLM are the dominant devices (covering >80% of the professional market). Their BLE GATT protocols are reverse-engineered and community implementations exist. The laser provides ±1mm accuracy that no software-only depth method can match. Implementing the abstracted protocol layer with `LeicaDistoProtocol` and `BoschGlmProtocol` first delivers 80% of the market immediately, with HID keyboard emulation as a universal fallback for everything else.

3. **Monocular depth estimation (Depth Anything V2) is a confidence-rated fallback, not a primary calibration method**: metric depth models achieve ~5% AbsRel error on in-domain indoor scenes (fine-tuned) but degrade to ~50% on unseen scenes — far too imprecise for construction measurement. The correct architecture is: user reference → BLE laser → ARCore Depth API → monocular depth (with explicit accuracy warning). The ViT-S ONNX model (24.8M params) is the right choice for on-device inference, targeting ~500ms–2s on Snapdragon 695.
