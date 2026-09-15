# Architecture Research: camera-qr-export

**Date**: 2026-07-11
**Author**: Architecture research agent (Phase 2, Agent 3)
**Scope**: Protocol/codec layering, integration points, data flow, air-gap failure modes, state machine (EventStorming)

**Builds on**: `project_plans/image-meter/research/architecture.md` §4 "Sensor Abstraction Layer" (lines 235–299), which documents the `CameraProvider` `expect`/`actual` pattern and per-platform implementation table (lines 284–289). This document does **not** re-derive that design — it extends it and, critically, corrects a discrepancy between what that prior document assumed and what actually exists in the repo today (see §2.1). Also reviewed `project_plans/stelekit-images/research/architecture.md` (image attachment pipeline, `PlatformDispatcher.IO` for file I/O, expect/actual service pattern) for precedent on platform-specific service wiring.

---

## 0. Ground truth: what actually exists today (corrects prior research assumptions)

The requirements.md constraint states "Android/iOS/JVM/WASM implementations already exist" for `CameraProvider`. That is true only in the narrowest sense (the classes exist and compile). The actual current interface and implementations are materially simpler than the image-meter research doc's speculative interface (which included `startPreview(): Flow<PlatformImageFrame>` / `stopPreview()` at architecture.md:246–248 — **that method does not exist in the real interface**).

**Actual interface** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/sensor/CameraProvider.kt:17-40`):
```kotlin
interface CameraProvider {
    val isAvailable: Boolean
    suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile>
}
```
Single-shot only. No continuous-frame / preview / streaming method anywhere in the interface.

**Actual per-platform state**:

| Platform | File | `isAvailable` | Capability |
|---|---|---|---|
| Android | `kmp/src/androidMain/.../AndroidCameraProvider.kt:50,55,65` | `true` | Real CameraX `ImageCapture` single-shot photo, never decodes full bitmap into memory |
| JVM/Desktop | `kmp/src/jvmMain/.../WebcamCameraProvider.kt:17,25,33` | **`false`** | Stub — `capturePhoto()` always returns `HardwareUnavailable` |
| iOS | `kmp/src/iosMain/.../IOSCameraProvider.kt:17,19,21` | **`false`** | Stub — same |
| WASM | `kmp/src/wasmJsMain/.../WebCameraProvider.kt:16,18,20` | **`false`** | Stub — same |
| (fallback) | `kmp/src/commonMain/.../NoOpCameraProvider.kt` | `false` | Explicit no-camera fallback, used today by JVM/WASM in place of a real implementation |

**Implication for this feature**: only Android currently has a working camera path at all. Desktop and iOS camera capture are unimplemented stubs, not "existing implementations that need a new method." This means:
- The **decode/receive** side (needs continuous frame streaming) has zero working platforms to build on except Android — it is greenfield on JVM/iOS/WASM, not an extension.
- The **encode/send** side (full-screen QR display) needs no camera at all — it is pure UI + `Canvas`/bitmap rendering and has no platform-availability risk.
- The requirements' "6 send/receive combos" risk (Rabbit Holes) is asymmetric: all 6 *send* combos are low-risk (rendering only), but 3 of the 4 *receive* platforms (JVM, iOS, WASM) require first landing real continuous camera capture — this is a materially larger scope than the requirements.md appetite (3–6 weeks) implies if camera streaming has to be built from scratch on non-Android platforms. Flag this back to planning (Phase 3) as a scope/appetite risk, not just a "reliability" rabbit hole.

---

## 1. Layering: protocol/codec as pure `commonMain`, platform layers at the edges

Three layers, strictly one-directional dependency (codec has zero platform deps):

```
┌─────────────────────────────────────────────────────────────────┐
│ Layer 3: Platform rendering / camera consumption (per-platform)  │
│   Encoder: full-screen QR frame renderer (Compose Canvas,        │
│            commonMain UI — no platform split needed for render)  │
│   Decoder: continuous frame source — NEW CameraProvider method   │
│            (see §2.2) — genuinely platform-specific per source   │
├─────────────────────────────────────────────────────────────────┤
│ Layer 2: QR-specific transport (commonMain, platform-agnostic    │
│          *within* the QR concern, but depends on a QR lib)       │
│   Encoder: chunk payload → QR matrix (bit grid) → passed to      │
│            Layer 3 for rendering                                 │
│   Decoder: camera frame (luminance buffer) → QR matrix scan →    │
│            decoded chunk bytes                                   │
│   (No existing zxing/QR dependency in kmp/build.gradle.kts today │
│   — this is a net-new external dependency, distinct from "no    │
│   new *network* dependency" constraint, which this doesn't       │
│   violate.)                                                       │
├─────────────────────────────────────────────────────────────────┤
│ Layer 1: Protocol/codec — PURE commonMain, no platform deps,     │
│          no I/O, fully unit-testable without any device          │
│   markdown string → chunker (fountain/FEC encode) → chunk[]      │
│   chunk[] (possibly lossy/reordered) → reassembler → markdown    │
│   string (only once reconstruction is *proven* complete —        │
│   see §4)                                                         │
└─────────────────────────────────────────────────────────────────┘
```

**Why this layering matters architecturally**: Layer 1 (chunking/FEC/reassembly) has no reason to touch `CameraProvider`, Compose, or any `expect`/`actual` — it is a pure function over byte arrays and should live in e.g. `commonMain/.../transfer/qrcode/FountainCodec.kt` with `commonTest` coverage that never instantiates a camera or a renderer. This mirrors the existing `outliner/OutlinerPipeline.kt` pattern (pure parsing logic, no I/O) rather than the `db/` or `platform/` patterns. Keeping Layer 1 pure is also what makes the air-gap guarantee in §4 checkable by a compiler/test, not just a code-review convention.

### Where the encoder gets its payload — reuse `GraphWriter.buildMarkdown`, NOT `MarkdownExporter`

There are two existing markdown-serialization paths in the codebase and they are **not interchangeable**:

- `GraphWriter.buildMarkdown(page, blocks)` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt:561-595`, currently `private`) — the canonical **on-disk Logseq format**: tab-indented `- content` bullets, `key:: value` property lines, no YAML frontmatter, no synthetic H1. This is exactly what `GraphLoader`/`OutlinerPipeline` parses back on read.
- `MarkdownExporter.export(...)` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/MarkdownExporter.kt`, used by `ExportService.exportToClipboard`) — a **human-readable export format**: adds YAML frontmatter (`title:`, page properties minus `id`), a synthetic `# heading` from journal date or page name. This is designed for clipboard/share to external tools, not for round-tripping through this app's own outliner parser.

**Decision**: the encoder must serialize via the same code path `GraphWriter` uses to write pages to disk (`buildMarkdown`), not `ExportService`/`MarkdownExporter`. Since round-trip fidelity is the hard gate (requirements.md Success Metrics), the payload must be byte-identical to what `GraphLoader` already knows how to parse — reusing an export path built for human consumption elsewhere risks silent lossy transforms (frontmatter, heading synthesis) that would fail the fidelity gate. Recommend extracting `buildMarkdown` out of `GraphWriter` into a shared, `internal`-visibility (or public) pure function — e.g. `LogseqPageSerializer.serialize(page, blocks): String` in `commonMain` — called by both `GraphWriter` (disk write) and the new QR encoder (chunk payload source). This also gives Layer 1 something to unit-test against without touching `GraphWriter` at all.

On the decode side, the reconstructed markdown string is parsed via the **existing** `GraphLoader`/`OutlinerPipeline` pipeline exactly as if it were a file read from disk — no new parser. This is the "ties into GraphLoader's import/outliner pipeline" integration point from the requirements: it is a data hand-off (validated markdown string in), not a code change to `GraphLoader` itself. The likely shape is a new entry point parallel to `GraphLoader.loadFullPage`/`loadDirectory` that accepts an in-memory markdown string + target page name instead of a file path — `GraphLoader` currently only reads from `FileSystem` (see `readFileDecrypted`/`readFileDecryptedSuspend`, lines 108, 146), so a new method is needed rather than reuse of the existing private file-read functions, but the parse/outliner/DB-write tail of the pipeline (`buildHierarchy`-equivalent → `DatabaseWriteActor`) is fully reusable.

---

## 2. Integration points with existing systems

### 2.1 `Either<DomainError, T>` — new error cases needed

`DomainError.kt` already has two sealed interfaces this feature should extend rather than duplicate:
- `SensorError` (lines 52-60: `PermissionDenied`, `HardwareUnavailable`, `CaptureFailed`) — reusable as-is for camera-permission/hardware failures during scanning; no change needed, the decoder scan loop reports through these existing cases.
- `ExportError` (lines 105-109: `SerializationFailed`, `ClipboardFailed`, `ShareFailed`) — the encoder's markdown→chunk serialization failure fits `SerializationFailed` as-is.

New sealed interface needed, following the exact pattern of `GitError`/`AttachmentError` (nested `sealed interface X : DomainError` blocks in the same `DomainError.kt` file, each case a `data class`/`data object` with `message`):

```kotlin
sealed interface QrTransferError : DomainError {
    data class ChunkDecodeFailed(override val message: String) : QrTransferError
    data class IncompleteTransfer(val receivedChunks: Int, val totalChunks: Int) : QrTransferError {
        override val message: String = "Incomplete transfer: $receivedChunks/$totalChunks chunks"
    }
    data class IntegrityCheckFailed(override val message: String) : QrTransferError   // checksum mismatch post-reassembly — see §4
    data class PayloadTooLarge(val sizeBytes: Int, val maxBytes: Int) : QrTransferError {
        override val message: String = "Payload $sizeBytes bytes exceeds max $maxBytes bytes for QR transfer"
    }
    data object TransferCancelled : QrTransferError {
        override val message: String = "Transfer cancelled by user"
    }
}
```
Also add corresponding `toUiMessage()` branches (line ~115 onward) following the existing exhaustive-`when` pattern — the compiler enforces this is not forgotten since `DomainError.toUiMessage()` is exhaustive over the sealed hierarchy.

### 2.2 `CameraProvider` — needs a genuinely new method, not an extension

Per §0, the existing interface is single-shot-only and 3 of 4 platforms are stubs. Continuous QR scanning cannot be bolted onto `capturePhoto()`. Add a second method to the interface (additive, non-breaking to existing single-shot callers):

```kotlin
interface CameraProvider {
    val isAvailable: Boolean
    suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile>

    /**
     * Continuous frame stream for real-time analysis (QR scanning). Distinct from
     * capturePhoto(): frames are delivered as they arrive, not saved to disk, and the
     * stream is expected to run for the duration of a scanning session then be cancelled
     * by the collector (structured concurrency — no explicit stop() needed).
     */
    fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>>
}

data class CameraFrame(val luminanceBytes: ByteArray, val width: Int, val height: Int, val rotationDegrees: Int)
```
This mirrors the *shape* the image-meter research doc speculated (`startPreview()/stopPreview()`, architecture.md:246-248) but as a single cold `Flow` (idiomatic in this codebase — matches `MotionSensorProvider.sensorDataFlow` at architecture.md:265-266 and the `externalFileChanges: SharedFlow` pattern in `GraphLoader.kt:433`) rather than paired start/stop calls, so cancellation is structural (flow collector cancellation) rather than requiring manual lifecycle bookkeeping — consistent with this repo's stated aversion to caller-managed scopes (`rememberCoroutineScope` rule in root `CLAUDE.md`).

Per-platform: Android implements this on top of CameraX `ImageAnalysis` use case (separate from the existing `ImageCapture` use case already wired in `AndroidCameraProvider.kt`, both can run concurrently on CameraX). JVM/iOS/WASM need real implementations for the first time (§0) — JavaCV frame grabber loop for JVM, `AVCaptureVideoDataOutput` for iOS, `MediaDevices.getUserMedia` + `<video>`/`ImageCapture`-track sampling for WASM.

### 2.3 Dispatcher for continuous frame processing

None of the four existing `PlatformDispatcher` values (`IO`, `Main`, `Default`, `DB` — `kmp/src/commonMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.kt:9-15`) is a perfect fit off the shelf, but no new dispatcher needs to be added to `PlatformDispatcher` itself:

- QR frame decode (luminance buffer → matrix scan → bytes) is **CPU-bound, not I/O** → `PlatformDispatcher.Default`, not `IO` (the CLAUDE.md dispatcher matrix already reserves `IO` for "non-database IO (files, network)" — CPU decode work doesn't belong there) and obviously not `DB`.
- On Android, CameraX `ImageAnalysis.Analyzer` callbacks arrive on a CameraX-owned executor thread already off the main thread; the QR-decode step inside that callback should still hop to `Dispatchers.Default` (via `PlatformDispatcher.Default`) if decode cost per frame is non-trivial, to avoid backing up CameraX's analyzer queue.
- Recommend bounding the collector with `.conflate()` (same technique already used for O(N²)-scan protection in `repository/DbFlowExtensions.kt` reads per CLAUDE.md's Graph-scale-reads section) so a slow decode doesn't queue up stale frames — always decode the newest frame, drop intermediate ones. This is directly analogous to the existing `conflate()` guidance for standing observers.

### 2.4 `DatabaseWriteActor` — decoder's DB write is a normal write, gated on full validation

Once reassembly is proven complete (§4), the decoder hands the reconstructed page/blocks to the same write path any import uses — `DatabaseWriteActor` via its typed methods (`savePage`/`saveBlocks` equivalents, `db/DatabaseWriteActor.kt:106-324`). No new `WriteRequest` variant is needed if the decoder produces a `Page` + `List<Block>` from the outliner pipeline exactly like `GraphLoader` does today — it should look like `WriteRequest.SavePage`/`SaveBlocks` already listed at lines 114-153. The architectural requirement is one-directional and ordering-strict: **no write reaches `DatabaseWriteActor` until Layer 1 reassembly + integrity check both succeed** — see §4.

---

## 3. Data flow trace

**Encoder (send) side**:
```
Page + List<Block>  (existing domain model)
  → LogseqPageSerializer.serialize()   [extracted from GraphWriter.buildMarkdown — §1]
  → markdown: String
  → FountainCodec.encode(markdown, chunkSize)  [Layer 1, pure, commonMain]
      → chunks: List<QrChunk>  (each chunk: header {transferId, chunkIndex, totalChunks or "unbounded" for fountain code, payload bytes, chunk-level checksum})
  → QrPayloadRenderer.toQrMatrix(chunk)  [Layer 2, uses QR lib]
      → BooleanArray-backed bit matrix per chunk
  → full-screen animated display loop (Layer 3, Compose Canvas, commonMain UI,
    platform-agnostic — no expect/actual needed for rendering itself)
      → cycles through chunks at a configurable frame interval (empirical tuning
        per Rabbit Holes — frame rate vs. camera capture rate)
```

**Decoder (receive) side**:
```
CameraProvider.frameStream()  [NEW method, §2.2, per-platform expect/actual]
  → CameraFrame (luminance buffer)
  → QrScanner.decode(frame)  [Layer 2, QR lib scan]
      → QrChunk?  (null if no QR in frame, or frame is a duplicate of last-seen chunk index — dedup here)
  → ChunkBuffer.accept(chunk)  [Layer 1, pure, commonMain — accumulates by chunkIndex/transferId,
    tolerant of duplicates, out-of-order, and (for fountain codes) redundant/derived chunks]
  → ChunkBuffer.isComplete(): Boolean  [Layer 1 — the ONLY point allowed to claim completeness —
    see §4 for what "complete" must mean beyond "chunk count reached"]
  → ChunkBuffer.reassemble(): Either<QrTransferError, String>  [Layer 1 — verifies transfer-level
    checksum BEFORE returning Right; this is the proof gate, not an assumption]
  → markdown: String  (only reachable after integrity proof)
  → GraphLoader new entry point (in-memory markdown string, NOT file path — see §1) 
  → OutlinerPipeline (existing, unchanged) → Page + List<Block>
  → DatabaseWriteActor.savePage/saveBlocks (existing, unchanged)
```

Consistency requirement: the decoder-side page write must go through the identical `OutlinerPipeline` code path as a normal file import, so that any existing invariant enforced there (UUID assignment, block hierarchy validation, property parsing) applies uniformly — no parallel/duplicate parsing logic for "QR-imported" pages. This also means a QR-imported page is indistinguishable from a disk-imported page once written, other than perhaps a provenance property (e.g. `::imported-via:: qr-transfer`) if product wants an audit trail — that would be a plan-phase decision, not an architecture constraint.

---

## 4. Air-gap and corrupted-transfer failure-mode analysis

### 4.1 Structural guarantee against silent network fallback

The security classification requires this be **structurally**, not just conventionally, incapable of routing through a network transport. Architectural mechanisms, in order of strength:

1. **No network-capable type reachable from the QR transfer module's dependency graph.** Layer 1 (`FountainCodec`, `ChunkBuffer`) takes only `String`/`ByteArray`/`Flow<CameraFrame>` — it has no visibility into `HttpClient`, `GitSyncService`, `GoogleApiClient`, or any Ktor engine. This is enforceable by **module/package boundary, not just discipline**: if this codebase adopts Gradle/Bazel module boundaries (or even a lint rule / `detekt` custom rule forbidding imports of `io.ktor.*` or `dev.stapler.stelekit.git.*` / `dev.stapler.stelekit.platform.google.*` from the new `transfer/qrcode/` package), a network dependency creeping in becomes a build-time failure, not a review miss. This is the direct analogue of the existing `@DirectSqlWrite` enforcement pattern in this codebase (`db/RestrictedDatabaseQueries.kt`) — an annotation/lint-gated architectural boundary rather than a comment. Recommend an equivalent `@OptIn`-style or detekt custom rule (e.g. `NoNetworkImportsInQrTransfer`) enforced by a dedicated test that walks the compiled bytecode or import list of the `transfer/qrcode/` package the same way `MigrationRunnerSchemaSyncTest` walks `.sq` files — i.e., a compile-time-adjacent regression test, not just an architecture-review checklist item.
2. **Constructor injection makes the encoder/decoder classes structurally unable to accept a network client.** If `QrEncoderViewModel`/`QrDecoderViewModel` take only `CameraProvider`, `LogseqPageSerializer`/`GraphLoader`-entry-point, and `DatabaseWriteActor` in their constructors (no `HttpClient` or `GitSyncService` parameter exists on the type), there is no code path by which a future refactor could "helpfully" add a network fallback without changing the constructor signature — which is a visible, reviewable diff.
3. **Explicit UI copy and telemetry**: this is a product/UX concern, not architecture, but worth flagging — no "retry over network" affordance should exist in the failure UI, since its presence would suggest to users that failure silently falls back (requirements.md explicitly rules this out).

### 4.2 Corrupted/partial transfer — decoder must *prove*, not assume, completeness

A corrupted partial write to the database is explicitly called out as worse than a rejected transfer. This requires reassembly to be a hard gate before any `DatabaseWriteActor` call:

- **Chunk-level integrity**: every `QrChunk` carries its own checksum (e.g. CRC32 or truncated hash) computed at encode time and verified at decode time *before* the chunk is admitted to `ChunkBuffer` — corrupted individual QR reads (camera misread, partial occlusion) are dropped silently and re-requested implicitly by the sender's loop (the sender doesn't know what was missed in a pure send-only animated loop — this is exactly why fountain-style redundancy, not strict ordered chunking, is required per requirements.md Scope: enough redundant/derived chunks cycle through that a dropped read is very likely recovered by a later frame without a back-channel).
- **Transfer-level integrity**: `ChunkBuffer.reassemble()` must independently verify a whole-payload checksum/hash (computed over the *original* markdown bytes at encode time, itself transmitted as metadata in every chunk header or a dedicated header chunk) against the reassembled bytes, and only return `Right(markdown)` if it matches. `isComplete()` returning true (chunk-count/coverage satisfied) is a *necessary but not sufficient* condition — the checksum match is the actual proof. This distinction matters: with fountain codes it's possible to reach "enough chunks received to attempt decode" while the decode itself is wrong (rare but non-zero with certain FEC schemes), so the explicit post-decode checksum is the non-negotiable gate, not chunk-count alone.
2. **No partial writes, ever**: the decoder never calls `DatabaseWriteActor` with a partially-reassembled page. Because the reassembly step in Layer 1 either yields `Either.Left(QrTransferError.IntegrityCheckFailed(...))` or a single fully-verified `String`, there is no intermediate state exposed to Layer 3/UI that looks like a page and could accidentally be persisted — this is a "parse, don't validate" pattern (construct the verified-markdown type only once integrity is proven, never expose an unverified-but-plausible intermediate).
3. **User-facing failure is terminal, not degraded**: on `IntegrityCheckFailed` or a user-initiated cancel, the receiving UI shows a rejected-transfer state and requires the sender to restart the encode loop — no attempt to write a "best effort" partial page. This matches the requirement's explicit framing.

---

## 5. Event-Command-Policy table (EventStorming grammar)

| Domain Event (what happened) | Policy trigger (whenever X, then…) | Command (intent to change state) | Actor / System |
|---|---|---|---|
| `TransferInitiated` | Whenever a user opts into QR export on a page | `StartEncoding(pageUuid)` | Sender user → `QrEncoderViewModel` |
| `PageSerialized` | Whenever `StartEncoding` completes serialization | `ChunkPayload(markdown, fecRate)` | `QrEncoderViewModel` → `FountainCodec` (Layer 1) |
| `PayloadChunked` | Whenever chunking succeeds | `BeginDisplayLoop(chunks)` | `QrEncoderViewModel` → Layer 3 renderer |
| `FrameDisplayed` | Whenever the display loop advances a tick | `RenderNextFrame()` | Layer 3 renderer (internal, self-driven) |
| `SendCancelledByUser` | Whenever the sender user cancels mid-display | `StopDisplayLoop()` | Sender user → `QrEncoderViewModel` |
| `AllChunksCycled` | Whenever the display loop completes N full cycles with no ack (no back-channel by design) | `MarkSendComplete()` | `QrEncoderViewModel` (self-timeout, since there's no receiver ack) |
| `ScanSessionStarted` | Whenever a user opts into QR import on the receiving device | `StartScanning()` | Receiver user → `QrDecoderViewModel` |
| `CameraFrameCaptured` | Whenever `CameraProvider.frameStream()` emits | `AttemptDecodeFrame(frame)` | `QrDecoderViewModel` → `QrScanner` (Layer 2) |
| `ChunkDecoded` | Whenever a frame yields a valid, checksum-passing chunk | `AcceptChunk(chunk)` | `QrScanner` → `ChunkBuffer` (Layer 1) |
| `ChunkRejected` | Whenever a frame yields a QR read that fails its own chunk checksum | (no state change — logged/counted only) | `QrScanner` (internal — silently dropped, per §4.2) |
| `ChunkBufferUpdated` | Whenever `AcceptChunk` changes buffer coverage | `EvaluateCompleteness()` | `ChunkBuffer` (internal) |
| `CoverageThresholdReached` | Whenever `EvaluateCompleteness` indicates enough chunks are present to attempt decode | `AttemptReassembly()` | `ChunkBuffer` → itself |
| `ReassemblySucceeded` | Whenever `AttemptReassembly` produces bytes matching the transfer-level checksum | `ParseMarkdown(markdown)` | `ChunkBuffer` → `GraphLoader` new entry point |
| `ReassemblyFailed` (checksum mismatch) | Whenever `AttemptReassembly` bytes fail the checksum | `RejectTransfer(QrTransferError.IntegrityCheckFailed)` | `ChunkBuffer` → `QrDecoderViewModel` (UI failure state, §4.2 item 3) |
| `MarkdownParsed` | Whenever `ParseMarkdown` succeeds via `OutlinerPipeline` | `WritePageToDatabase(page, blocks)` | `GraphLoader` entry point → `DatabaseWriteActor` |
| `MarkdownParseFailed` | Whenever the reconstructed (checksum-valid) markdown still fails outliner parsing (e.g. pre-existing malformed-markdown edge case, unrelated to transfer integrity) | `RejectTransfer(DomainError.ParseError...)` | `OutlinerPipeline` → `QrDecoderViewModel` |
| `PageWritten` | Whenever `WritePageToDatabase` succeeds | `MarkReceiveComplete()` | `DatabaseWriteActor` → `QrDecoderViewModel` (success UI state) |
| `WriteFailed` | Whenever `WritePageToDatabase` fails (existing `DomainError.DatabaseError` cases) | `RejectTransfer(DomainError.DatabaseError...)` | `DatabaseWriteActor` → `QrDecoderViewModel` |
| `ReceiveCancelledByUser` | Whenever the receiver user cancels mid-scan | `StopScanning()` | Receiver user → `QrDecoderViewModel` |
| `CameraUnavailable` | Whenever `CameraProvider.isAvailable == false` at `StartScanning` time (all non-Android platforms today, per §0) | `RejectTransfer(DomainError.SensorError.HardwareUnavailable)` immediately, before entering scanning state | `QrDecoderViewModel` (pre-flight guard) |

State machines implied by the table (sender: `Idle → Encoding → Displaying → {Complete, Cancelled}`; receiver: `Idle → Scanning → Reassembling → Validating → {Complete, Failed, Cancelled}`) map directly onto this table's Command targets — `Reassembling`/`Validating` are the two sub-states inside `ChunkBufferUpdated → CoverageThresholdReached → ReassemblySucceeded/Failed`, which is exactly where the "prove, don't assume" gate from §4.2 lives.

---

## Key Architectural Decisions Summary

1. **Three-layer split, with Layer 1 (chunking/FEC/reassembly) as pure `commonMain` with zero platform or network-capable dependencies** — enforced ideally by a package-boundary lint rule (detekt custom rule + regression test, mirroring `MigrationRunnerSchemaSyncTest`'s enforcement style), not just code review. This is both the cleanest layering and the mechanism that makes the air-gap guarantee structural rather than conventional.
2. **The existing `CameraProvider` interface (`platform/sensor/CameraProvider.kt:17-40`) is single-shot-only and 3 of 4 platform implementations are non-functional stubs today** (`WebcamCameraProvider`, `IOSCameraProvider`, `WebCameraProvider` all report `isAvailable = false`) — contrary to the prior image-meter research's assumption of a richer already-existing interface. A new `frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>>` method must be added, and real continuous-capture implementations must be built essentially from scratch on JVM/iOS/WASM. This is the single biggest scope/feasibility finding to carry into Phase 3 planning — appetite and platform sequencing (e.g., "Android-only for v1, other receive platforms follow") should be reconsidered against the stated 3–6 week appetite.
3. **Reuse `GraphWriter`'s private `buildMarkdown` (extract to a shared `LogseqPageSerializer`), not `ExportService`/`MarkdownExporter`**, as the encoder's payload source — the latter is a lossy human-export format (YAML frontmatter, synthetic headings) that would not round-trip through `OutlinerPipeline` and would silently violate the lossless-reconstruction gate.
4. **Completeness must be proven via a transfer-level checksum verified inside the pure Layer 1 reassembly step, not inferred from chunk-count coverage alone** — no partially-reassembled or unverified markdown is ever exposed to a type that could reach `DatabaseWriteActor`. New `DomainError.QrTransferError` sealed interface (`IncompleteTransfer`, `IntegrityCheckFailed`, `ChunkDecodeFailed`, `PayloadTooLarge`, `TransferCancelled`) added to `DomainError.kt` alongside the existing `SensorError`/`ExportError` interfaces it reuses for camera and serialization failures respectively.
