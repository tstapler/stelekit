# Asset Management — Pitfalls & Risks

Researched 2026-06-13. Covers Phase 1 (organization + asset browser) and Phase 2 (on-device ML + cloud-optional).

---

## 1. ONNX Runtime — Android/JVM Compatibility

### Problem

ONNX Runtime ships two distinct Maven artifacts:
- `com.microsoft.onnxruntime:onnxruntime` — pure JVM (desktop)
- `com.microsoft.onnxruntime:onnxruntime-android` — Android AAR with bundled native `.so` files

These cannot both appear in `commonMain`. Each must be gated to its platform source set (`jvmMain` vs `androidMain`), which means the shared `AssetPipelinePlugin` interface must be defined in `commonMain` with `expect/actual` or a service interface — neither artifact can be in `commonMain` because `commonMain` has no native binary support.

### ABI Splits

The `onnxruntime-android` AAR bundles `.so` files for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. If `android.splits.abi` is enabled in the app's `build.gradle.kts`, each ABI split will include the correct `.so`. However, if `abiFilters` is used to restrict to a subset (e.g., `arm64-v8a` only for release), the universal APK/AAB that Play uses will not include the excluded `.so`s — devices with a different ABI will crash at `OrtEnvironment.getEnvironment()`. The safe mitigation is to leave ABI splits disabled and rely on App Bundle delivery, or explicitly list `["arm64-v8a", "x86_64"]` at minimum for device coverage.

### API Shape

Both artifacts expose the same Java package `ai.onnxruntime` and the same core classes (`OrtEnvironment`, `OrtSession`, `OrtTensor`, `NodeInfo`). The API is intentionally symmetric so the same Kotlin code compiles against both. The significant behavioral difference is that on Android, loading a model larger than the JVM heap limit fails with an `OrtException` (or native OOM) because the current implementation reads the entire `.ort`/`.onnx` file into a JVM byte array before handing it to native code (GitHub issue #19599). This is not a problem on JVM where the heap limit is typically in the GB range.

### Gradle Configuration Pitfall

KMP projects that have both an `androidTarget()` and a `jvm()` target must not add either ONNX artifact to `commonMain.dependencies`. Doing so causes the Kotlin Multiplatform Gradle plugin to include the Android AAR on the JVM classpath, which will fail because `.so` files are stripped out and the JAR inside the AAR is not on the path. The correct pattern is:

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        androidMain.dependencies {
            implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.x")
        }
        jvmMain.dependencies {
            implementation("com.microsoft.onnxruntime:onnxruntime:1.20.x")
        }
    }
}
```

Both versions must be kept in sync; the artifacts have separate release cadences on Maven Central and a version mismatch can cause `OrtSession` serialization incompatibilities for models saved as ORT format.

### Memory Consumption

A quantized INT8 MobileNetV2 model is approximately 3.5–6 MB on disk. FP32 weights are ~14 MB. ONNX Runtime Mobile (`onnxruntime-mobile`) further strips the runtime to ~2 MB by removing unused ops. At inference time, the runtime allocates activation buffers proportional to input size: a 224×224×3 RGB input requires roughly 50–100 MB of working memory on Android. Peak allocation includes input tensor, intermediate activations, and output tensor simultaneously. On mid-tier devices with 2–3 GB RAM, this is manageable but must not overlap with a large image decode (see §9).

### Mitigation

- Keep ONNX artifacts in `androidMain`/`jvmMain` only; use a `commonMain` `expect interface InferenceEngine`.
- Pin the same version in both source sets via `libs.versions.toml`.
- Use `onnxruntime-android` (not `onnxruntime-mobile`) unless a custom ORT build is produced; the mobile package requires a separate model optimization step.
- Limit the JVM heap size passed to the Android model load by using the file-path–based `OrtSession.SessionOptions` loading API rather than `ByteArray` loading when it becomes available in a future ORT release.

---

## 2. ML Kit — KMP Wrapper Availability

### Problem

There is no official KMP wrapper for ML Kit. All ML Kit Vision APIs (`ImageLabeling`, `TextRecognition`, `ObjectDetection`) are Android-only (`com.google.mlkit:*` artifacts) and target the Android SDK. Including any of them in `commonMain` breaks the iOS and JVM Kotlin compilation immediately with `unresolved reference` errors, because neither iOS targets nor the JVM target have access to Android SDK classes.

### Failure Mode

If `implementation("com.google.mlkit:image-labeling:17.x")` is placed in `commonMain.dependencies`, the Kotlin/Native and Kotlin/JVM compilers will fail at the `import com.google.mlkit.*` statement. The Android-specific Gradle variant resolution will not help here — Kotlin Multiplatform resolves `commonMain` against a metadata classpath, not per-target, so the dependency is always visible (and always fails) during common compilation.

### Community KMP Wrappers

There are no widely-adopted community KMP wrappers for ML Kit as of 2026. The typical approach documented in community blogs is to create a `commonMain` `expect interface` (e.g., `ImageLabeler`) with `actual` implementations in `androidMain` (backed by ML Kit) and stub `actual` implementations in `jvmMain`/`iosMain` (backed by ONNX Runtime or Core ML respectively). This is the architecture SteleKit should use for `AssetPipelinePlugin`.

### iOS Path

On iOS, `Core ML` + `Vision` framework provides equivalent label/OCR functionality via `actual` Kotlin/Native interop. This is the recommended Apple path, not an ML Kit port. It is available from iOS 11+ (Vision) and iOS 12+ (Core ML 2+), which comfortably covers the minimum iOS version for any KMP app targeting modern devices.

### Mitigation

- Define `expect interface AssetInferencePipeline` in `commonMain`; provide per-platform `actual` implementations.
- Keep the `com.google.mlkit:*` dependencies strictly in `androidMain`.
- Provide a `jvmMain` stub that delegates to ONNX Runtime.
- Never reference ML Kit class names in `commonMain` Kotlin files, including in `@Suppress` or KDoc.

---

## 3. PDF Rendering on Android API < 26

### Problem

Android's built-in `PdfRenderer` (API 21+) renders pages as bitmaps only. It has no text extraction layer, no searchable content, and no vector fidelity — only rasterized output. For the OCR path (render page to bitmap, then run ML Kit `TextRecognition`) this is acceptable, but the memory implications are severe for large PDFs (see below).

### PDFBox-Android

`com.tom-roush:pdfbox-android` is the most actively maintained port of Apache PDFBox for Android. The current release is 2.0.27.0 (January 2023); the project has not received a major update since. The minimum SDK is governed by the `ANDROID_BUILD_MIN_SDK_VERSION` property in the project's `gradle.properties`, and community reports confirm the sample app targets API 14, meaning the library itself works on API 21+ in practice. However, a 2022 release (around 2.0.26.0) bumped the compile SDK to 33, so building with `compileSdk 34/35` requires checking for Desugaring compatibility — the library uses some `java.nio` and `java.time` classes that require core library desugaring on API < 26 when compiled with AGP 7+.

Known issues on API 24/25:
- `java.util.concurrent.CompletableFuture` is required by newer Apache Harmony-based code paths but is only available from API 24 (24+). With core library desugaring enabled this is resolved.
- `Base64` encoding path differs between API 26+ (`java.util.Base64`) and below; PDFBox-Android uses its own codec to work around this.
- Text extraction on certain CID-keyed fonts (Korean, Chinese) is unreliable on all API levels due to incomplete font-encoding tables.

**Maintenance risk:** The TomRoush fork has been community-maintained since 2017 with no corporate backing. There are several open forks (VivyTeam, nhochdrei) but none have significantly diverged from the original. If a critical API-26 bug emerges, SteleKit may need to patch the library directly or adopt an alternative.

### Alternatives and Licensing

| Library | License | Notes |
|---|---|---|
| PdfBox-Android (`com.tom-roush`) | Apache 2.0 | Safe for commercial use; no copyleft. Current recommendation. |
| iText 7 | AGPL-3.0 (OSS) or commercial | AGPL requires open-sourcing the entire application if distributed commercially. Commercial license is expensive (~$9k/year). **Not viable** without a commercial license. |
| MuPDF (`com.artifex.mupdf`) | AGPL-3.0 (OSS) or commercial | Same AGPL problem as iText. The commercial license from Artifex is available but non-trivial. |
| Android `PdfRenderer` (built-in) | Apache 2.0 (bundled with AOSP) | No text extraction; raster only; API 21+. Sufficient for thumbnail generation. |

**Recommendation:** Use the built-in `PdfRenderer` for thumbnail/bitmap rendering (Phase 2 ML path), and `com.tom-roush:pdfbox-android` for text extraction only if needed, with the explicit understanding that the library is community-maintained and may lag behind security patches.

### Memory Implications for PDF-to-OCR Path

A single A4 page rendered at 150 DPI = 1240 × 1754 pixels. Each pixel at ARGB_8888 = 4 bytes → ~8.7 MB per page. At 300 DPI (better OCR accuracy) = ~35 MB per page. A 20-page PDF loaded page-by-page requires allocating and releasing ~35 MB per iteration. On a mid-tier Android device with a 512 MB heap cap, processing more than 2–3 pages concurrently will trigger GC pressure or OOM. The mitigation is to process one page at a time, call `Bitmap.recycle()` immediately after `TextRecognition.process()` returns, and run PDF OCR in a bounded coroutine context with `limitedParallelism(1)`.

---

## 4. TOCTOU Races on Asset Move + Reference Update

### Problem

Moving a file to a subfolder is a two-step operation:
1. `File.renameTo()` / `Files.move()` — the file is at the new path.
2. Update all markdown blocks that reference the old path.

If the app crashes, is killed (Android low-memory), or is force-stopped between steps 1 and 2, the file exists at the new path but all markdown links still point to the old path. The reverse is also possible if the update is written first and the rename fails.

A second race involves the `GraphFileWatcher`: if it polls between the rename and the markdown update, it will detect the old path as "missing" and potentially emit an `ExternalFileChange` event or mark the file as deleted. This can trigger an erroneous "broken link" UI state or, worse, delete the DB record for the moved asset.

A third race is a double-move: if the user triggers the same move twice (network latency, click debounce failure), the second rename will find the source already gone (or the destination already existing) and fail unpredictably.

### Failure Modes

| Scenario | Observable Failure |
|---|---|
| Crash after rename, before ref update | File accessible at new path; all markdown links broken permanently |
| Crash before rename, after ref update | Markdown updated; file still at old path; links resolve to missing file |
| Watcher polls mid-move | Transient "broken link" flash, or spurious DB page reload with stale content |
| Double-move | `FileAlreadyExistsException` or silent data loss if destination overwritten |

### Mitigations

**Write-ahead log (WAL) pattern:** Before moving, append a log entry to a `pending_moves.json` (or a `pending_asset_moves` SQLite table) with `{from, to, markdown_refs[]}`. After both the file rename and the markdown update succeed, delete the log entry. On startup (and on graph open), replay any incomplete entries — the rename is idempotent (`Files.move` with `REPLACE_EXISTING` is safe to re-run if both source and destination exist), and re-applying markdown updates is also idempotent (writing the same reference twice is a no-op).

**Suspend the file watcher during moves:** `GraphLoader.fileWatcher` already has the `activePageFilePaths` guard to skip auto-reload for open pages. Extend this to cover assets being moved: register the source path as "pending move" before the rename and remove it after the markdown update is committed. The watcher's `addDirty`/`checkAndClearDirty` mutex can gate this.

**Atomic rename:** On POSIX systems, `Files.move(src, dst, ATOMIC_MOVE)` is atomic within the same filesystem. On Android (same partition), this works. Across partitions (e.g., internal storage to SD card) it is not atomic. Keep moves within the `<graphRoot>/assets/` tree to guarantee same-partition atomicity.

**Idempotent markdown update:** The reference updater should use the `DatabaseWriteActor` (which already serializes writes) and emit the update as a single `Either`-returning suspend fun. Use a `Mutex` per asset path to prevent concurrent moves of the same file.

**Double-click guard:** Gate the UI move action with a `Mutex` or a boolean flag in the ViewModel that is set before the move and cleared after completion or failure.

---

## 5. SQLDelight Migration Safety for the `assets` Table

### Problem

As documented in `CLAUDE.md`: `SteleDatabase.Schema.create(driver)` is called first on every startup, but on existing databases it fails immediately at the first `CREATE TABLE pages` (which has no `IF NOT EXISTS`) and all subsequent DDL is silently skipped. This means a new `assets` table added to `SteleDatabase.sq` will never be created on existing user databases unless the table also appears in `MigrationRunner.all`.

This project's `MigrationRunnerSchemaSyncTest` enforces this at CI time — it reads the `.sq` file, extracts every `CREATE TABLE IF NOT EXISTS <name>`, and asserts each name appears in `MigrationRunner.all`. If the migration is forgotten, CI fails before the PR can merge.

### Correct Migration Pattern

Add a single migration entry to `MigrationRunner.all` containing the complete `CREATE TABLE IF NOT EXISTS assets (...)` DDL plus any indexes:

```kotlin
Migration(
    name = "assets_table",
    statements = listOf(
        """
        CREATE TABLE IF NOT EXISTS assets (
            uuid                TEXT NOT NULL PRIMARY KEY,
            graph_path          TEXT NOT NULL,
            file_path           TEXT NOT NULL,
            subfolder           TEXT NOT NULL DEFAULT '',
            display_name        TEXT NOT NULL,
            media_type          TEXT NOT NULL DEFAULT 'UNKNOWN',
            size_bytes          INTEGER NOT NULL DEFAULT 0,
            content_hash        TEXT,
            imported_at_ms      INTEGER NOT NULL DEFAULT 0,
            last_seen_at_ms     INTEGER NOT NULL DEFAULT 0,
            ml_tags             TEXT NOT NULL DEFAULT '[]',
            ml_tags_source      TEXT NOT NULL DEFAULT 'NONE',
            ml_processed_at_ms  INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_assets_graph_path ON assets(graph_path)",
        "CREATE INDEX IF NOT EXISTS idx_assets_file_path ON assets(file_path)",
        "CREATE INDEX IF NOT EXISTS idx_assets_subfolder ON assets(graph_path, subfolder)"
    )
)
```

**Rules specific to this project (from `MigrationRunner` KDoc):**
- All statements must use `IF NOT EXISTS` / `IF EXISTS` for idempotency — `applyAll` swallows the resulting errors but the hash is still recorded.
- `ALTER TABLE … ADD COLUMN` does **not** support `IF NOT EXISTS` in SQLite; write the plain form and let `applyAll`'s duplicate-column swallow handle re-runs.
- Do not use `ALTER TABLE IF NOT EXISTS` — it is not valid SQLite syntax and will be silently swallowed, leaving the hash recorded without the column being added (the `pages_backlink_count` bug in the existing migration list is a documented example of this failure).
- Use `fnv1a64` hashing is automatic; do not compute it manually.

### Risk of Data Loss

If the migration is omitted and a release ships, users who have the app installed (existing DB) will never have the `assets` table created. Any write attempt targeting that table will throw `no such table: assets`. The `DatabaseWriteActor` wraps writes in `Either`, so the app will not crash, but all asset metadata will silently fail to persist. The fix (adding the migration in the next release) will create the table on next startup — but all backfill work from the first release will be lost and must be re-run.

---

## 6. Backfill Performance on Large Graphs

### Problem

The Phase 1 backfill scans `<graphRoot>/assets/` on every graph open to index new or changed files. On Android (especially on SAF-backed storage or older eMMC flash), a `stat()` call per file takes 0.5–5 ms. For 500 files this is 0.25–2.5 seconds of blocking I/O. On cold startup with a large graph, this compounds the existing progressive load time. Running it on the main thread would block the UI; even on `Dispatchers.IO` it competes with the existing `indexRemainingPages` background scan.

`GraphLoader.indexRemainingPages()` demonstrates the correct pattern: bounded batch drain with a `getUnloadedPages(limit, offset)` query pair, cancellable via `backgroundIndexJob`, with `cancelBackgroundWork()` called from `onTrimMemory`. Asset backfill should mirror this exactly.

### Index Staleness

If files are deleted or renamed externally (e.g., the user uses a file manager), the asset index will contain stale entries. The index's `last_seen_at_ms` column should be updated on every backfill pass; entries not updated in the most recent pass can be marked stale or pruned. However, pruning on every startup would re-trigger an O(N) scan each time. A more efficient approach:

1. On each pass, record the scan start timestamp.
2. Query `SELECT uuid, file_path FROM assets WHERE graph_path = ? AND last_seen_at_ms < ?` (the scan start) after the pass completes.
3. For each result, confirm the file is actually missing before deleting the index record (avoids false deletes if the scan was interrupted).

### "Last Backfill Timestamp" Strategy

Store a `last_backfill_ms` value in the `metadata` table (already exists in `MigrationRunner.all`). On each startup, only stat files whose `mtime` is newer than `last_backfill_ms`. This requires:
- A reliable `mtime` API: `File.lastModified()` on JVM (reliable), `DocumentFile.lastModified()` on Android SAF (reliable for local storage, unreliable for cloud-backed storage like Google Drive mounts).
- Handling clock skew: if the device clock is adjusted backward, `mtime` comparisons become unreliable. Guard with a `mtime > 0` check and fall back to a full scan if `last_backfill_ms` is more than 7 days old.
- The first run (no `last_backfill_ms`) is always a full scan; cache its result.

### Bounded Query Requirement

Per CLAUDE.md: no unbounded `getAllAssets()` queries. The asset repository must expose only:
- `getAssets(graphPath, limit, offset)` — paginated
- `getAssetByPath(filePath)` — point lookup
- `getAssetsBySubfolder(graphPath, subfolder, limit, offset)` — paginated
- `countAssetsNeedingMlProcessing(graphPath)` — O(1) count for progress display

The `MigrationRunnerSchemaSyncTest` / `QueryPlanAuditTest` analogs should cover these.

---

## 7. Logseq Link Format Compatibility

### Problem

Logseq's canonical asset path format for drag-dropped files is `../assets/<filename>` (flat, relative to the page file in `pages/` or `journals/`). Moving assets to subfolders changes the reference to `../assets/<subfolder>/<filename>`. Logseq's desktop and mobile apps must be able to resolve this path for cross-tool compatibility.

### Logseq Subfolder Support

Community reports and Logseq GitHub issues (notably #9035) confirm that Logseq v0.9.1+ is stricter about asset paths than earlier versions. Specifically:
- Images that resolve outside the `assets/` directory via `../` paths stopped rendering in v0.9.1.
- Users who had stored images in non-standard locations (e.g., `./images/`) found their images broken after upgrading.
- Logseq's own drag-and-drop always writes to `<graphRoot>/assets/` (flat). Subfolders within `assets/` appear to render correctly because they resolve under `assets/`, but this is not officially documented.

**Assessment:** Subfolder paths like `../assets/images/photo.jpg` (from a file in `pages/`) should resolve correctly in Logseq because the path stays under `<graphRoot>/assets/`. However, any path that traverses outside `assets/` (e.g., `../../other-folder/`) will likely break in recent Logseq versions. SteleKit must never produce such paths.

### Path Format Contract

The `ImageStoragePathResolver` already uses `assets/images/<date>-<uuid>.jpg`. The new subfolder routing must maintain the `assets/<subfolder>/` prefix, never an absolute path or a path that leaves `assets/`. The reference update step must rewrite markdown as:

```
![alt](../assets/<subfolder>/<filename>)
```

(relative from `pages/` or `journals/`) or:

```
![alt](assets/<subfolder>/<filename>)
```

(relative from the graph root, for root-level pages).

Both forms must be tested in Logseq before shipping Phase 1.

### Known Logseq Bug Risk

Logseq's database-version migration (the "DB version" experimental branch) changes how assets are referenced fundamentally. If SteleKit users are on the Logseq DB version, subfolder path assumptions may not apply. SteleKit should document which Logseq version is supported for bidirectional compatibility.

---

## 8. Cloud API Quota and Cost Risks

### Problem

The Claude API charges per image token (~$3–8 per 1 000 images for `claude-haiku-3-5` at approximately 1 000–2 000 input tokens per image at typical quality). A user with 500 images enabling cloud analysis on backfill would generate $1.50–$4.00 in API costs per user per session. At scale (10 000 users), an unguarded backfill launch costs $15 000–$40 000 in a single day. Google Vision API is cheaper ($1.50/1 000 images) but not zero.

### Failure Modes

- **Runaway backfill:** User enables cloud analysis; app processes all 500+ images in a single session with no throttle or cost cap.
- **Re-backfill on reinstall:** ML tags are stored only in the local SQLite DB. On app reinstall or DB corruption, all images are re-submitted to the cloud API.
- **Rate limit 429 errors:** Both Claude API and Google Vision impose rate limits. A burst of 500 simultaneous requests from a single API key will hit these limits and generate useless error retries.

### Mitigations

**Explicit opt-in per image or per folder:** Cloud analysis must be a deliberate user action, not automatic. The `ml_tags_source` column (suggested in §5's schema) should store `'LOCAL'`, `'CLOUD_VISION'`, or `'CLAUDE_API'` so already-processed images are not resubmitted.

**Per-session cost cap:** Expose a `maxCloudAnalysisImagesPerSession` setting (default: 20). Once reached, stop and prompt the user. Store the count in-session (not persisted) so it resets on app restart.

**Persisted "cloud processed" flag:** The `ml_processed_at_ms` column (suggested schema) serves as the idempotency guard. Before submitting an image, check `ml_processed_at_ms > 0 AND ml_tags_source IN ('CLOUD_VISION', 'CLAUDE_API')`. Skip if already processed.

**Batched and throttled requests:** Use `limitedParallelism(2)` for cloud API calls and add a 500 ms delay between batches to stay well below rate limits.

**User consent and data privacy:** The privacy implications must be disclosed in-app before any image leaves the device. Cloud analysis must be disabled by default. This aligns with the GDPR and App Store / Play Store data disclosure requirements. Images sent to the Claude API are subject to Anthropic's data handling policy (as of 2025, users can opt out of training data inclusion but must actively do so).

---

## 9. Memory Pressure from ML Inference on Large Assets

### Problem

ML Kit `ImageLabeler` (and ONNX Runtime on JVM) load the entire image into a `Bitmap` before inference. A 4 K image (3840 × 2160 pixels) at `ARGB_8888` = 33 MB. A 12 MP camera JPEG decompressed = ~36 MB. On a mid-tier Android device with a typical 256–512 MB heap cap for a foreground process, loading 2–3 such images simultaneously for batched ML inference will trigger a GC cascade and potentially an OOM, which on Android kills the process (per `CLAUDE.md`'s documented behavior).

The existing `LargeGraphWarmStartCrashTest` / `StelekitViewModelCrashReproductionTest` suite demonstrates that OOM errors are critical-path bugs on Android.

### Recommended Downscale Resolution

ML Kit's built-in image labeling model accepts arbitrary resolution but internally downscales to fit its input tensor (typically 224 × 224 for MobileNetV2). Downscaling before inference:
- Reduces heap allocation from ~33 MB to ~0.2 MB (224 × 224 × 4 bytes).
- Reduces JPEG decode time by deferring only the needed region (using `BitmapFactory.Options.inSampleSize`).
- Does not materially affect label accuracy for broad category labels (people, vehicles, nature, food, etc.).

For OCR (`TextRecognition`), resolution matters more. A 1280 × 720 decode is a reasonable floor (text must be legible at pixel level). At this resolution: 3.5 MB, well within heap budget.

**Recommended strategy:**
1. Read image dimensions using `BitmapFactory.Options.inJustDecodeBounds = true` (zero allocation).
2. Compute `inSampleSize = max(width, height) / targetMax` where `targetMax = 512` for labeling and `1280` for OCR.
3. Decode with the computed `inSampleSize`.
4. Run inference on the downscaled bitmap.
5. Call `bitmap.recycle()` immediately after inference completes.

### Coroutine Guard

Wrap inference in a `try/catch (e: Throwable)` (not `Exception`) inside the `parallelScope`-launched coroutine, consistent with `GraphLoader`'s warm reconcile handler. An `OutOfMemoryError` during bitmap decode must be caught at the inference coroutine boundary, emitted as `DomainError.InferenceFailed`, and not propagated to the default uncaught exception handler.

### Batching Cap

Cap concurrent ML inference to `limitedParallelism(1)` on Android (one image in memory at a time). On JVM desktop, `limitedParallelism(2)` is reasonable given typical 8+ GB heap.

---

## Summary of Cross-Cutting Risks

| Risk | Severity | Confidence |
|---|---|---|
| ONNX artifact in commonMain breaks KMP compilation | High — compile-time failure | Certain |
| Large model load OOM on Android via `ByteArray` path | High — process kill | Confirmed (ORT issue #19599) |
| ML Kit in commonMain breaks iOS/JVM builds | High — compile-time failure | Certain |
| PdfBox-Android not maintained; API 26 desugaring needed | Medium — future breakage | High confidence |
| TOCTOU crash between file rename and ref update | High — permanent stale links | Certain without guard |
| assets table missing from MigrationRunner.all | High — silent data loss on upgrade | Certain (CI enforced) |
| Backfill full scan on every startup (500+ files) | Medium — startup latency | High on Android w/ SAF |
| Logseq incompatibility if path leaves assets/ | Medium — broken links in Logseq | Confirmed v0.9.1+ |
| Runaway cloud API cost on backfill | High — financial risk | Certain without cap |
| 4K image OOM during ML inference | High — process kill on Android | Certain without downscale |
