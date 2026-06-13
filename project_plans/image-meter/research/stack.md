# Stack Research: SteleKit Image Meter

**Date**: 2026-05-16  
**Scope**: KMP-compatible libraries and frameworks for image annotation with measurements

---

## Recommended Stack (Summary Table)

| Concern | Recommended | Platform | Notes |
|---|---|---|---|
| Image loading | **Coil 3** (v3.4.0+) | commonMain | KMP-native, Ktor-backed, AsyncImage in Compose |
| Camera capture | **CameraK** (experimental) | androidMain / iosMain | Wraps CameraX (Android) + AVFoundation (iOS); KMP wrapper |
| Camera (Android production) | **CameraX 1.5** | androidMain | Stable, RAW capture, use if CameraK proves insufficient |
| Annotation canvas | **Compose Canvas / DrawScope** | commonMain | Built-in; drawLine, drawPath, drawText, pointerInput gestures |
| Image zoom/pan | **ZoomImage** or **Zoomable** | commonMain | Both KMP-native; ZoomImage more feature-complete |
| BLE communication | **Kable** (JuulLabs) | android / iOS / macOS | Coroutine-native; no JVM/Linux BLE support |
| USB serial (OTG) | **usb-serial-for-android** (kai-morich fork) | androidMain | Only option; supports FTDI/CDC/CH340/Prolific |
| Monocular depth (ML) | **LiteRT** (formerly TFLite) + **Depth Anything V2 ONNX** | androidMain | ONNX via onnxruntime-android; iOS via Core ML export |
| ARCore depth | **ARCore Depth API** (`Config.DepthMode.AUTOMATIC`) | androidMain | Hardware-required; graceful fallback needed |
| Google Photos import | **REST API** (Photos Library API v1) | androidMain / iosMain / jvmMain | No Android SDK; OAuth via Credential Manager (Android) |
| Google Drive export | **Drive REST API v3** + **Ktor** | commonMain (HTTP) | Drive Android API deprecated Dec 2018; REST is canonical |
| Google OAuth (Android) | **Credential Manager API** (Jetpack) | androidMain | Modern replacement for GoogleSignIn; returns ID token |
| Token storage | Platform Keystore/Keychain/system keyring | expect/actual | Per NFR-4; no cross-platform KMP abstraction needed |
| Skia/canvas rendering | **Skiko** (via Compose) | commonMain | Used implicitly by Compose CMP; direct Skiko for baked export |

---

## 1. Image Canvas and Annotation Drawing

### Approach: Compose Multiplatform DrawScope (commonMain)

Compose Multiplatform's `Canvas` composable + `DrawScope` is the correct approach for building the annotation overlay. All rendering goes through Skiko/Skia under the hood on Desktop/iOS and through Android's hardware canvas on Android, providing consistent behavior at ≥30 fps.

**Key APIs available in commonMain:**
- `DrawScope.drawLine()` — line measurements between two points
- `DrawScope.drawPath()` — polygon area overlays
- `DrawScope.drawText()` (via `TextMeasurer`) — measurement labels and callouts
- `Modifier.pointerInput { detectDragGestures(...) }` — drag to draw annotation handles
- `GraphicsLayer` (Compose 1.7+ / CMP 1.7+) — render composable content off-screen for baking annotations into exported image

**Skiko direct access** (`org.jetbrains.skia.Canvas`) is available but should be reserved for the image-export path (baking annotation overlays into a pixel buffer for Drive export), not for interactive UI. Compose's `DrawScope` is sufficient for all interactive annotation rendering.

**ZoomImage** (`io.github.panpf.zoomimage:zoomimage-compose-*`) provides pinch-zoom and pan on the base image, with a composable overlay slot where annotation DrawScope layers can be stacked. Supports CMP: Android, macOS, Windows, Linux. iOS support is listed but less battle-tested.

**Zoomable** (`net.engawapg.lib:zoomable`) is a lighter alternative: Compose Multiplatform, MIT license, handles pinch/double-tap/scroll zoom. Simpler API but less control over subsampling for very large images.

**Recommendation**: Use `ZoomImage` as the base image host (handles large-image subsampling important for construction photos), with a custom `Canvas` overlay composable for annotation drawing stacked on top via `Box`.

---

## 2. Camera Capture

### CameraK (KMP wrapper — experimental)

- **Repo**: `github.com/Kashif-E/CameraK`
- **Maven**: `io.github.kashif-mehmood-km:camerak`
- **Platforms**: Android (CameraX), iOS (AVFoundation), Desktop/JVM (JavaCV)
- **Status**: Experimental as of early 2026. Published to Maven Central. Active maintenance.
- **Features**: Front/back toggle, flash, photo capture, QR scanning plugin, OCR plugin, `StateFlow`-based reactive state via `CameraKStateHolder`.

**Risk**: Experimental label means API surface may change. However, for SteleKit's needs (capture JPEG, basic controls) the scope is bounded.

### CameraX 1.5 (Android-only fallback)

- **Released**: November 2025
- **Key additions**: RAW/DNG capture, slow-motion video, new Feature Group API, `SessionConfig` for streamlined setup
- **License**: Apache 2.0 (Jetpack)
- **Kotlin-first**: Yes; `@ExperimentalCamera2Interop` for lower-level Camera2 access

**Recommendation**: Use CameraK for the KMP camera abstraction (`expect`/`actual` interface `CameraCapture`). If CameraK's androidMain implementation proves limiting (e.g., EXIF access, RAW), replace only the androidMain actual with a direct CameraX 1.5 implementation without touching commonMain logic.

---

## 3. ARCore Depth Estimation (Android)

### ARCore Depth API

- **Package**: `com.google.ar:core` (ARCore SDK for Android)
- **Kotlin API**: `session.configure(session.config.apply { depthMode = Config.DepthMode.AUTOMATIC })`
- **Depth modes**: `AUTOMATIC` (smooth+raw), `RAW_ONLY`, `SMOOTH_AND_RAW`
- **Accuracy**: 0–65m range; best at 0.5–5m
- **Device requirement**: ARCore-capable device. Not all Android devices support depth. Must query `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`.
- **Permission**: `android.permission.SCENE_UNDERSTANDING_FINE` required for Jetpack XR depth.
- **Monocular fallback**: ARCore's depth-from-motion algorithm works on single-camera phones using multiple frames; does not require dedicated depth hardware for basic estimation.

**Integration note**: ARCore is `androidMain`-only. The `CameraCapture` interface should expose an optional `suspend fun getDepthFrame(): DepthFrame?` returning null on non-ARCore devices.

---

## 4. Bluetooth LE — Laser Rangefinder Integration

### Kable (JuulLabs) — Primary Recommendation

- **Repo**: `github.com/JuulLabs/kable`
- **Platforms**: Android, iOS, macOS, JavaScript; **JVM (Linux/Windows) not supported** (no native BLE API on JVM)
- **API**: Coroutine-native. `Scanner { }` for scanning, `peripheral(advertisement)` for connection, `peripheral.write(characteristic, data)` / `peripheral.observe(characteristic)` for GATT I/O.
- **License**: Apache 2.0
- **Status**: Actively maintained by JuulLabs (IoT-focused company). Used in production IoT applications as of October 2025.

**Leica DISTO / Bosch GLM protocol notes**:
- Both device families expose a custom BLE GATT service. The measurement value characteristic UUID varies by model family.
- Leica DISTO transfer BT LE uses a custom BLE profile; open-source implementations (d2relay, CaveSurvey wiki) document the service/characteristic UUIDs for D1/D2/D510 series.
- Bosch GLM 50C/100C use a similar notification-based characteristic.
- BLE HID keyboard-emulation mode (some cheaper devices): these appear as a HID keyboard peripheral and "type" the measurement; handled via Android BluetoothHidDevice API (androidMain only).

### Blue Falcon — Alternative

- **Repo**: `github.com/Reedyuk/blue-falcon`
- **Platforms**: Android, iOS, macOS, Windows, JavaScript, Raspberry Pi (JVM via BLESSED)
- **Advantage over Kable**: JVM support via BLESSED wrapper (useful for Desktop target if BLE hardware present)
- **Disadvantage**: Less idiomatic Kotlin coroutine API than Kable; less active community

**Recommendation**: Use Kable for Android and iOS (primary platforms for BLE rangefinder use). For Desktop, expose a stub `expect` that returns "BLE not available on Desktop" — construction site use of Desktop BLE is not a realistic scenario.

---

## 5. USB Serial / OTG (Laser Rangefinder Backup Protocol)

### usb-serial-for-android (kai-morich fork)

- **Repo**: `github.com/kai-morich/usb-serial-for-android`
- **JitPack**: `com.github.kai-morich:usb-serial-for-android`
- **Status**: Most actively maintained fork (kai-morich fork more current than mik3y original as of 2024–2025)
- **Chipsets**: FTDI, CDC-ACM, Silicon Labs CP210x, Prolific PL2303HX, CH340/CH341, STM32
- **API**: Java/Kotlin; raw `read()`/`write()` on a `SerialPort`; compatible with Kotlin flows for coroutine streaming
- **Platform**: Android-only (`androidMain`)

**HID keyboard emulation**: Devices that emulate a USB HID keyboard (typing measurements) are handled automatically by Android's input system — no library needed. The measurement value arrives as a `KeyEvent` in whatever text field has focus.

---

## 6. Google Photos and Google Drive Integration

### Google Drive — REST API v3 via Ktor

The Drive Android API was deprecated in December 2018. The current canonical approach is the Drive REST API v3.

- **HTTP client**: Use existing Ktor client (already likely in the project for network calls) targeting `commonMain`
- **Auth**: OAuth 2.0 access token acquired per-platform (see §7)
- **Key endpoints**:
  - `POST /upload/drive/v3/files` (multipart) — upload annotated image
  - `POST /drive/v3/files` (metadata only) — create JSON sidecar
  - `GET /drive/v3/files` with `q` parameter — browse Drive folder

### Google Photos — REST API (Photos Library API v1)

- **Important breaking change**: April 1, 2025 — several Library API scopes were removed. Check current scope list at `developers.google.com/photos/overview/authorization`.
- **No Kotlin/Android SDK**: The `google/java-photoslibrary` client library is Java-only and not KMP compatible. Use REST via Ktor.
- **Key constraint**: Media `baseUrl` values expire after ~60 minutes. Store only `mediaItemId`; re-fetch URL on demand.
- **Scopes needed**: `photoslibrary.readonly` (browse/import), `photoslibrary.appendonly` (write-back to album — US-5.4)

### OAuth 2.0 — Android: Credential Manager API

- **Library**: `androidx.credentials:credentials` + `com.google.android.libraries.identity.googleid:googleid`
- **Flow**: Credential Manager bottom sheet → Google ID token → exchange for access token via token endpoint
- **Token storage**: Android Keystore (EncryptedSharedPreferences or directly via Keystore API)
- **Note**: Requires two OAuth client IDs: one Android type (package name + SHA-1), one Web type (for server-side token verification)

### OAuth 2.0 — iOS / Desktop

- iOS: ASWebAuthenticationSession + iOS Keychain for token storage
- Desktop (JVM): System browser OAuth redirect + OS keyring (via `java.security.KeyStore` on JVM or `secret-service` on Linux)
- KMP abstraction: Expose `expect class OAuthTokenStore` with `actual` per platform

---

## 7. Monocular Depth Estimation (On-Device ML)

### LiteRT (formerly TensorFlow Lite) — Android

- **Maven**: `com.google.ai.edge.litert:litert:2.1.0`
- **Status**: Graduated to production at Google I/O '25. Successor to TFLite. 1.4× faster GPU than TFLite.
- **Hardware acceleration**: Automatic backend selection (CPU / GPU / NPU); asynchronous execution.
- **API**: `CompiledModel` (modern) or `Interpreter` (legacy); Kotlin-first bindings.

### Depth Anything V2 — Model

- **Model sizes**: Small (25M params) → Large (1.3B params). For mobile, use Small or ONNX-quantized.
- **ONNX export**: `fabio-sim/Depth-Anything-ONNX` provides pre-exported ONNX models with fused pre/postprocessing.
- **Android runtime**: `com.microsoft.onnxruntime:onnxruntime-android` — ONNX Runtime for Android; confirmed working with Depth Anything V2 (see onnxruntime-inference-examples issue #383).
- **iOS runtime**: ONNX Runtime iOS pod, or export to Core ML via `onnx-coreml` / `coremltools`.
- **Qualcomm AI Hub**: Pre-optimized Depth Anything V2 for Snapdragon NPU available at `aihub.qualcomm.com/mobile/models/depth_anything_v2`.
- **Expected latency**: Small model ~50–150ms on mid-range Snapdragon (Snapdragon 695 class); acceptable for post-capture processing, not real-time.

**Integration strategy**:
1. Try ARCore depth first (if device supports it and user is in AR session).
2. Fall back to LiteRT + Depth Anything V2 Small via ONNX Runtime for single-image depth estimation.
3. Final fallback: manual reference-object calibration (US-2.1).

---

## 8. Annotation Canvas Architecture in Compose Multiplatform

### Pattern: Layered Box with ZoomImage + DrawScope Overlay

```
Box(modifier = Modifier.fillMaxSize()) {
    // Layer 1: Base image with zoom/pan
    ZoomImage(
        painter = rememberCoilPainter(imageUri),
        modifier = Modifier.fillMaxSize()
    )
    // Layer 2: Annotation overlay (no background, transparent)
    Canvas(modifier = Modifier.fillMaxSize().pointerInput(annotations) {
        detectDragGestures(
            onDragStart = { offset -> /* start annotation at image-space coords */ },
            onDrag = { _, delta -> /* extend line/polygon */ },
            onDragEnd = { /* finalize annotation */ }
        )
    }) {
        annotations.forEach { annotation ->
            when (annotation) {
                is LineAnnotation -> drawAnnotationLine(annotation, scale, pan)
                is PolygonAnnotation -> drawAnnotationPolygon(annotation, scale, pan)
                is TextLabel -> drawAnnotationText(annotation, textMeasurer, scale, pan)
            }
        }
    }
}
```

**Coordinate system note**: ZoomImage exposes its current zoom and pan state, which must be applied to annotation coordinates before drawing. All annotation coordinates should be stored in normalized image space (0.0–1.0) to survive zoom/pan changes and be resolution-independent.

### Image Export (Baking Overlays)

Use `GraphicsLayer` (CMP 1.7+ / Compose 1.7+) to render the annotated composable to an `ImageBitmap`, then encode to JPEG/PNG via Skiko's `org.jetbrains.skia.Image.makeFromBitmap()`. This path is `jvmMain`/`androidMain` specific if Skiko direct APIs are needed; alternatively use platform `expect`/`actual` for bitmap encoding.

---

## Dependency Evaluation Summary

| Library | KMP? | License | Maturity | Concern |
|---|---|---|---|---|
| Coil 3 | Yes (commonMain) | Apache 2.0 | Production (v3.4.0) | None |
| CameraK | Android+iOS+JVM | MIT | Experimental | API instability risk |
| CameraX 1.5 | Android only | Apache 2.0 | Stable | Android-only; fine for `androidMain` |
| ZoomImage | Yes (CMP) | Apache 2.0 | Active | iOS less tested |
| Kable | Android+iOS+macOS+JS | Apache 2.0 | Production | No JVM/Linux BLE |
| Blue Falcon | Wider (incl. JVM) | Apache 2.0 | Active | Less idiomatic API |
| usb-serial-for-android | Android only | LGPL 2.1 | Production | LGPL; review linking terms |
| LiteRT | Android only | Apache 2.0 | Production | Android-only ML |
| ONNX Runtime Android | Android only | MIT | Production | Model packaging size |
| ARCore | Android only | Apache 2.0 | Stable | Hardware-gated |
| Skiko | Yes (via CMP) | Apache 2.0 | Production | Implicit via Compose |

**LGPL note on usb-serial-for-android**: LGPL 2.1 allows dynamic linking without GPL propagation. On Android, the library is consumed as a `.aar` via Gradle (dynamic linking equivalent). Legal risk is low but should be confirmed with project licensing stance.

---

## Sources

- [JetBrains/skiko GitHub](https://github.com/JetBrains/skiko)
- [Compose Multiplatform 1.10.0 release blog](https://blog.jetbrains.com/kotlin/2026/01/compose-multiplatform-1-10-0/)
- [CameraX 1.5 Android Developers Blog (Nov 2025)](https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html)
- [CameraK GitHub](https://github.com/Kashif-E/CameraK)
- [ARCore Depth API developer guide](https://developers.google.com/ar/develop/java/depth/developer-guide)
- [ARCore Depth API quickstart](https://developers.google.com/ar/develop/java/depth/quickstart)
- [Jetpack XR ARCore depth](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/depth)
- [JuulLabs/kable GitHub](https://github.com/JuulLabs/kable)
- [How to Create an IoT App in KMP (droidcon, Oct 2025)](https://www.droidcon.com/2025/10/16/how-to-create-an-iot-app-in-kotlin-multiplatform/)
- [Reedyuk/blue-falcon GitHub](https://github.com/Reedyuk/blue-falcon)
- [NordicSemiconductor/KMM-BLE-Library](https://github.com/NordicSemiconductor/KMM-BLE-Library)
- [Google Photos API overview](https://developers.google.com/photos/overview/about)
- [Google Photos Library API REST reference](https://developers.google.com/photos/library/reference/rest)
- [Google Drive API overview](https://developers.google.com/drive/api/guides/about-sdk)
- [Migrate from Drive Android API](https://developers.google.com/drive/api/guides/android-api-deprecation)
- [Android Credential Manager API](https://developers.google.com/identity/android-credential-manager)
- [Google Sign-In with Credential Manager (ProAndroidDev)](https://proandroiddev.com/google-sign-in-with-credential-manager-c54762376170)
- [LiteRT overview](https://ai.google.dev/edge/litert/overview)
- [LiteRT for Android](https://ai.google.dev/edge/litert/android)
- [LiteRT: Universal Framework for On-Device AI (Google Developers Blog)](https://developers.googleblog.com/litert-the-universal-framework-for-on-device-ai/)
- [Depth Anything V2 project page](https://depth-anything-v2.github.io/)
- [Depth-Anything-ONNX GitHub](https://github.com/fabio-sim/Depth-Anything-ONNX)
- [ONNX Runtime inference examples - Depth Anything Android issue](https://github.com/microsoft/onnxruntime-inference-examples/issues/383)
- [Qualcomm AI Hub - Depth Anything V2](https://aihub.qualcomm.com/mobile/models/depth_anything_v2)
- [mik3y/usb-serial-for-android GitHub](https://github.com/mik3y/usb-serial-for-android)
- [kai-morich/usb-serial-for-android GitHub](https://github.com/kai-morich/usb-serial-for-android)
- [ZoomImage GitHub](https://github.com/panpf/zoomimage)
- [Zoomable GitHub](https://github.com/usuiat/Zoomable)
- [Compose Canvas drawing - Android Developers](https://developer.android.com/develop/ui/compose/graphics/draw/overview)
- [SVGs on Canvas with Compose Multiplatform (2025)](https://eevis.codes/blog/2025-01-15/using-svgs-on-canvas-with-compose-multiplatform/)
- [coil-kt/coil GitHub](https://github.com/coil-kt/coil)
- [Coil 3 KMP complete guide (Sep 2025)](https://www.kmpwithsuraj.com/2025/09/coil-3-image-loading-in-kotlin.html)
- [Leica DISTO BLE - d2relay GitHub](https://github.com/seichter/d2relay)
- [B4X forum: Leica Disto + Bosch GLM BLE](https://www.b4x.com/android/forum/threads/ble2-leica-disto-and-bosch-laser-rangefinder.160390/)
