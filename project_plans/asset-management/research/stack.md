# Asset Management — Technology Stack Research

Researched: 2026-06-13

---

## Already on the Classpath (from `kmp/build.gradle.kts`)

| Library | Coordinate | Version | Source Set |
|---|---|---|---|
| Coil 3 | `io.coil-kt.coil3:coil-compose` | 3.2.0 | commonMain |
| Coil 3 network | `io.coil-kt.coil3:coil-network-ktor3` | 3.2.0 | commonMain |
| kotlinx-serialization-json | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.10.0 | commonMain |
| ONNX Runtime (Android) | `com.microsoft.onnxruntime:onnxruntime-android` | 1.20.0 | androidMain |
| Compose Multiplatform | managed by `org.jetbrains.compose` plugin | (plugin-managed) | commonMain |

---

## 1. ML Kit — KMP Availability

**Finding: No official KMP support from Google. Requires platform-specific source sets with a service interface.**

Google ML Kit is Android-only and iOS-only (via the Firebase/Google pod), with no KMP artifact published to Maven Central. Google's 2025 KMP expansion (announced at I/O 2025 and KotlinConf 25) covers Jetpack libraries — Room, DataStore, ViewModel, Paging — but ML Kit is **not** on that list.

### Recommended pattern: service interface + expect/actual or platform injection

The right architectural approach is to define a pure-Kotlin interface in `commonMain` and supply platform implementations in `androidMain` and `iosMain`:

```kotlin
// commonMain
interface ImageLabeler {
    suspend fun labelImage(bytes: ByteArray): List<Label>
}
data class Label(val text: String, val confidence: Float)

// androidMain — backed by com.google.mlkit:image-labeling
// iosMain      — backed by Core ML / Vision framework (Swift interop)
// jvmMain      — backed by ONNX Runtime or stub
```

There is a community wrapper [MLKit-KMP](https://github.com/RufenKhokhar/MLKit-KMP) that provides a shared Kotlin API over the native SDKs for Android and iOS. It is not published to Maven Central; users add it via JitPack or source dependency. It covers image labeling and text recognition. **Not recommended for production yet** — low bus factor, no published stable release on Maven Central, no iOS AAR equivalent.

### Official Android ML Kit coordinates (androidMain only)

| Feature | Coordinate | Latest |
|---|---|---|
| Image Labeling | `com.google.mlkit:image-labeling` | 17.0.9 |
| Text Recognition | `com.google.mlkit:text-recognition` | 16.0.1 |
| On-device LLM (Gemini Nano) | `com.google.mlkit:genai-prompt` | 1.0.0-beta2 (already in build) |

**Verdict**: Use a service interface in `commonMain`. Wire `androidMain` to ML Kit SDK. Wire `iosMain` to Core ML/Vision via Swift interop or Kotlin/Native interop. Wire `jvmMain` to ONNX Runtime (see below).

---

## 2. ONNX Runtime — KMP Availability

**Finding: No dedicated KMP artifact. Two separate JARs exist — one for Android (AAR), one for JVM desktop. Shared Java API surface, different artifact IDs.**

Microsoft publishes two distinct Maven artifacts:

| Artifact | Coordinate | Latest Version | Target |
|---|---|---|---|
| JVM/Desktop | `com.microsoft.onnxruntime:onnxruntime` | **1.26.0** | jvmMain |
| Android | `com.microsoft.onnxruntime:onnxruntime-android` | **1.21.0** (1.20.0 already in build) | androidMain |

There is no `onnxruntime-kmp` or multiplatform Kotlin artifact. Both artifacts expose the same Java API (`OrtEnvironment`, `OrtSession`, `OrtTensor`) so a thin `commonMain` interface wrapping them is straightforward.

### Key API differences (Android vs JVM)

- Android artifact bundles native `.so` for arm64-v8a and x86_64 inside the AAR. The JVM artifact bundles native libs for Linux x64, Windows x64, macOS x64/arm64 inside the JAR.
- Android supports NNAPI and XNNPACK execution providers; JVM supports CPU, CUDA, DirectML (platform-dependent).
- Both use identical Java session creation: `OrtEnvironment.getEnvironment()` / `env.createSession(modelPath, options)`.

### Alternative: KInference (pure Kotlin, true KMP)

[KInference](https://github.com/JetBrains-Research/kinference) by JetBrains Research is a pure-Kotlin ONNX inference engine with KMP support (commonMain, jvmMain, jsMain). It requires no native binary at runtime. The trade-off is lower performance vs. native ONNX Runtime, and it supports a subset of ONNX operators. Useful if iOS target needs on-device ONNX without a native bridge.

**Verdict for SteleKit**: Use `onnxruntime:onnxruntime:1.26.0` in `jvmMain` and keep the existing `onnxruntime-android:1.20.0` (or upgrade to 1.21.0) in `androidMain`. Define the `ImageLabeler` service interface in `commonMain`.

---

## 3. TensorFlow Lite / LiteRT for JVM Desktop

**Finding: TFLite for non-Android JVM is poorly supported. The library was rebranded to LiteRT in 2024 but JVM desktop is a second-class citizen. ONNX Runtime is the better choice for jvmMain.**

Google rebranded TensorFlow Lite as **LiteRT** (`com.google.ai.edge.litert`) in late 2024. The primary platforms remain Android, iOS, and embedded Linux. Official desktop JVM (macOS, Windows, Linux x64 as a plain JAR) support has been a long-standing gap — a GitHub issue from 2020 tracking JVM non-Android support remains unresolved.

| Artifact | Coordinate | Latest | Notes |
|---|---|---|---|
| LiteRT (Android) | `com.google.ai.edge.litert:litert` | 1.0.1 | AAR, androidMain only |
| Old TFLite (Android) | `org.tensorflow:tensorflow-lite` | 2.17.0 | Deprecated; redirects to litert |
| TF Java (JVM) | `org.tensorflow:tensorflow-core-platform` | 1.0.0 | Full TF, not Lite; very large (~500MB natives) |

**Verdict**: Do **not** use TFLite/LiteRT for `jvmMain`. Use ONNX Runtime (`onnxruntime:1.26.0`) for desktop ML inference instead. ONNX Runtime has first-class JVM desktop support, a stable Java API, and models are easily converted from TFLite to ONNX format.

---

## 4. Compose Multiplatform — `LazyVerticalGrid`

**Finding: `LazyVerticalGrid` from `androidx.compose.foundation.lazy.grid` is available in Compose Multiplatform for all targets (Android, JVM Desktop, iOS). No platform differences in the grid API itself.**

Compose Multiplatform 1.8.0 (released May 2025) declared iOS stable. The foundation grid APIs — `LazyVerticalGrid`, `GridCells.Adaptive`, `GridCells.Fixed`, `GridItemSpan` — are part of `compose.foundation` which is shared via `commonMain` in this project (already in the build).

The import path is identical across targets:

```kotlin
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
```

Key parameters:
- `columns: GridCells` — use `GridCells.Adaptive(minSize = 120.dp)` for responsive gallery
- `state: LazyGridState` — for programmatic scroll/position
- `contentPadding`, `verticalArrangement`, `horizontalArrangement`

**No differences between Desktop and Android targets** for the grid API. The JVM Desktop target does not have overscroll effects by default (overscroll is Android-specific behavior), but that is cosmetic.

The project already has `implementation(compose.foundation)` in `commonMain`, so no new dependency is needed.

---

## 5. Coil 3 — KMP Support

**Finding: Coil 3 is a fully KMP-native library already present on the classpath at 3.2.0. Stable version is 3.5.0. No new artifacts needed for the asset browser; upgrade is optional.**

Coil 3 (`io.coil-kt.coil3`) was rebuilt from the ground up as a KMP library. It supports:

| Target | Support |
|---|---|
| Android | Full (androidMain) |
| JVM Desktop | Full (jvmMain) |
| iOS | Full (iosMain) — via Darwin HTTP client |
| WASM/JS | Full |

The project already has `io.coil-kt.coil3:coil-compose:3.2.0` and `io.coil-kt.coil3:coil-network-ktor3:3.2.0` in `commonMain`. The `SteleKitAssetFetcher` wraps Coil. No new Coil artifacts are needed for the asset browser — the existing integration covers thumbnail rendering via `AsyncImage`.

The stable version as of June 2026 is **3.5.0**. The upgrade from 3.2.0 is non-breaking (minor releases). Consider upgrading if the asset browser needs new features like video thumbnails.

**Important**: The `coil-compose-core` artifact mentioned in the requirements is an alias that maps to `coil-compose`. Use `io.coil-kt.coil3:coil-compose` (already present).

---

## 6. Apache PDFBox — KMP and Android

**Finding: Two entirely separate libraries. Neither is KMP. Use `org.apache.pdfbox:pdfbox` in `jvmMain` only and `com.tom-roush:pdfbox-android` in `androidMain` only.**

Apache PDFBox has no KMP artifact and is not designed for shared code. PDF text extraction must be behind a platform interface.

| Target | Library | Coordinate | Latest Version |
|---|---|---|---|
| JVM Desktop | Apache PDFBox | `org.apache.pdfbox:pdfbox` | **3.0.4** (Jan 2025) |
| Android | PDFBox-Android (port) | `com.tom-roush:pdfbox-android` | **2.0.27.0** |

PDFBox-Android (`com.tom-roush:pdfbox-android`) is a community port maintained by TomRoush. It lags behind the upstream Apache PDFBox version but supports text extraction, form field reading, and basic rendering.

### Service interface pattern

```kotlin
// commonMain
interface PdfTextExtractor {
    suspend fun extractText(filePath: String): String
}

// jvmMain — org.apache.pdfbox:pdfbox:3.0.4
// androidMain — com.tom-roush:pdfbox-android:2.0.27.0
// iosMain — stub or PDFKit via Swift interop
```

**iOS**: Apple's `PDFKit` framework provides native PDF text extraction. Access it from `iosMain` via Kotlin/Native interop with `UIKit`/`PDFKit` (no third-party library needed).

---

## 7. Okio — Cross-Platform File Operations

**Finding: Okio 3.x is a true KMP library supporting `FileSystem` in commonMain. Suitable for recursive directory scanning. Latest stable: 3.17.0.**

Okio (`com.squareup.okio:okio`) is fully KMP since version 3.0. The `FileSystem` API in `commonMain` supports:
- `FileSystem.SYSTEM` — the real filesystem on all platforms
- `FileSystem.list(path)` — directory listing
- `FileSystem.metadata(path)` — size, modification time
- `FileSystem.source(path)` / `FileSystem.sink(path)` — streaming reads/writes
- `Path` / `toPath()` extension for string paths

```kotlin
// commonMain — works on JVM, Android, iOS, Native
val assetsDir = graphRoot.toPath() / "assets"
FileSystem.SYSTEM.list(assetsDir).forEach { path ->
    val meta = FileSystem.SYSTEM.metadata(path)
    // meta.size, meta.lastModifiedAtMillis
}
```

| Artifact | Coordinate | Latest Stable | Source Sets |
|---|---|---|---|
| Core | `com.squareup.okio:okio` | **3.17.0** | commonMain, jvmMain, androidMain, iosMain, jsMain |
| JVM platform jar | `com.squareup.okio:okio-jvm` | 3.17.0 | Resolved automatically from `okio` in KMP |
| Fake filesystem (testing) | `com.squareup.okio:okio-fakefilesystem` | 3.17.0 | commonTest |

Okio is not currently in the SteleKit build. It would need to be added to `commonMain`. It is already an indirect transitive dependency (Ktor and OkHttp pull it in) but declaring it explicitly for `commonMain` gives access to the `FileSystem` API in shared code.

**Caveat**: For recursive scanning on Android, `FileSystem.SYSTEM` works on internal storage. For SAF (Storage Access Framework) paths on Android (external/scoped storage), Okio cannot traverse `DocumentFile` trees — that still requires `androidx.documentfile:documentfile` (already in the build) or a separate SAF-aware adapter.

---

## 8. kotlinx-serialization-json

**Finding: Already on the classpath at 1.10.0. No changes needed. Latest stable is 1.11.0.**

`org.jetbrains.kotlinx:kotlinx-serialization-json` is a true KMP library in `commonMain`. The project uses it at **1.10.0**, declared in both `commonMain` and `commonTest`.

The plugin `kotlin("plugin.serialization")` is already applied in `kmp/build.gradle.kts`.

For the asset metadata index (JSON sidecar per asset), the existing `@Serializable` + `Json.encodeToString` / `Json.decodeFromString` pattern used by `ImageSidecarSchema` can be extended directly — no new dependency or upgrade required.

Latest stable upstream: **1.11.0** (includes JSON schema improvements). The upgrade from 1.10.0 is non-breaking.

---

## Summary Table

| Question | Library | Coordinate | Latest Stable | KMP Support | Source Set | Status in Build |
|---|---|---|---|---|---|---|
| ML Kit | Google ML Kit | `com.google.mlkit:image-labeling` | 17.0.9 | **None** (Android/iOS only) | androidMain / iosMain | Not present |
| ML Kit | Google ML Kit | `com.google.mlkit:text-recognition` | 16.0.1 | **None** | androidMain / iosMain | Not present |
| ONNX Runtime (JVM) | Microsoft ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime` | **1.26.0** | None — JVM JAR only | jvmMain | Not present |
| ONNX Runtime (Android) | Microsoft ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime-android` | 1.21.0 | None — AAR only | androidMain | **1.20.0 present** |
| TFLite / LiteRT (JVM) | LiteRT | `com.google.ai.edge.litert:litert` | 1.0.1 | Android only | — | **Not recommended** |
| LazyVerticalGrid | Compose Multiplatform | `compose.foundation` (plugin alias) | 1.8.0 (stable) | **Full KMP** | commonMain | **Already present** |
| Coil 3 | Coil | `io.coil-kt.coil3:coil-compose` | **3.5.0** | **Full KMP** | commonMain | **3.2.0 present** |
| PDFBox (JVM) | Apache PDFBox | `org.apache.pdfbox:pdfbox` | **3.0.4** | None — JVM only | jvmMain | Not present |
| PDFBox (Android) | PDFBox-Android | `com.tom-roush:pdfbox-android` | **2.0.27.0** | None — AAR only | androidMain | Not present |
| File ops | Okio | `com.squareup.okio:okio` | **3.17.0** | **Full KMP** | commonMain | Not present (transitive) |
| JSON serialization | kotlinx-serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | **Full KMP** | commonMain | **1.10.0 present** |

---

## Recommended New Dependencies

New additions required by Phase 1 and Phase 2 (items not currently in the build):

```kotlin
// commonMain — recursive directory scanning and file metadata
implementation("com.squareup.okio:okio:3.17.0")

// jvmMain — PDF text extraction for desktop
implementation("org.apache.pdfbox:pdfbox:3.0.4")

// jvmMain — ONNX Runtime for desktop ML inference (image labeling)
implementation("com.microsoft.onnxruntime:onnxruntime:1.26.0")

// androidMain — PDF text extraction for Android
implementation("com.tom-roush:pdfbox-android:2.0.27.0")

// androidMain — ML Kit image labeling (Phase 2, Android)
implementation("com.google.mlkit:image-labeling:17.0.9")

// androidMain — ML Kit text recognition (Phase 2, Android)
implementation("com.google.mlkit:text-recognition:16.0.1")

// commonTest — fake filesystem for Okio-based scanner tests
testImplementation("com.squareup.okio:okio-fakefilesystem:3.17.0")
```

No changes are required to `LazyVerticalGrid` (use `compose.foundation` already present), Coil (already present), or kotlinx-serialization (already present).

---

## Architecture Decision Points

1. **ML inference interface must live in `commonMain`** — `ImageLabeler` and `PdfTextExtractor` service interfaces with platform implementations in `androidMain` (ML Kit + PDFBox-Android), `jvmMain` (ONNX Runtime + Apache PDFBox), and `iosMain` (Core ML/Vision + PDFKit via Swift interop).

2. **No KMP ML library exists** — both ML Kit and ONNX Runtime require platform-specific source sets. This is expected; the service interface pattern is the idiomatic KMP solution.

3. **Okio `FileSystem` for backfill scanner** — the `commonMain` backfill scanner (scan `assets/` on graph load) should use `FileSystem.SYSTEM` from Okio for cross-platform recursive listing. SAF paths on Android require a separate adapter; internal-storage graphs are fully covered by Okio.

4. **`LazyVerticalGrid` is ready to use** — no new import or dependency. Use `GridCells.Adaptive(minSize = 120.dp)` for the asset browser gallery.
