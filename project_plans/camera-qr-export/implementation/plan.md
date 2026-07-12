# Implementation Plan: camera-qr-export

**Feature**: Air-gapped page transfer between SteleKit devices over an optical (camera↔screen) QR channel, built on a transport-agnostic fountain-coded wire protocol with a pluggable `FrameTransport` extension point (QR is the first and only v1 transport).
**Date**: 2026-07-11
**Status**: Ready for implementation
**ADRs**: ADR-001 (wire format), ADR-002 (`CameraFrameSource` interface), ADR-003 (platform sequencing), ADR-004 (fps/WCAG), ADR-005 (Web out of scope), ADR-006 (`FrameTransport` vs. `SyncTransport` naming distinction)

---

## v1 scope — explicit cuts (read first)

Two scope calls are recorded here so they are visible, not silent:

1. **Single-page transfer only.** requirements.md's round-trip-fidelity gate says
   "a page (or set of pages)," but every type in this plan is single-page-shaped
   (`LogseqPageSerializer.serialize(page, blocks)`, `QrEncodeViewModel.start(pageUuid)`,
   `QrImportService.import(payload, targetName, …)`). **Multi-page / graph-set
   transfer is cut from v1.** requirements.md's "page (or set of pages)" framing is
   satisfied for v1 by running the single-page flow once per page; a batched /
   multi-page session (one QR stream carrying N pages) is explicitly deferred to a
   follow-on. The chunk/FEC protocol is payload-agnostic, so a future multi-page
   payload needs no Layer-1/2 redesign — only a multi-page serializer and a
   send/receive UI that iterates pages.
2. **Platform receive matrix** — Android is the only v1 receiver; Desktop/iOS send
   only; Desktop/iOS receive and all Web/WASM deferred (ADR-003 / ADR-005). See the
   "v1 Definition of Done" section at the end.

---

## Creative Pass — approaches considered (Step 0.5)

Three delivery shapes were brainstormed before committing:

1. **Android-round-trip-first + cross-platform send, thin `FrameTransport` seam** (CHOSEN).
   - *Strength*: delivers the full success-metric round trip inside the appetite by requiring only one hard receiver (Android/CameraX already works) while proving the send side is platform-independent.
   - *Weakness*: Desktop/iOS users cannot receive into that same device in v1.
2. **All-6-combos flat, full BC-UR envelope, rich transport registry now.**
   - *Strength*: maximal completeness and future-proofing; every platform symmetric on day 1.
   - *Weakness*: three greenfield camera stacks (JVM/iOS/WASM) + Bytewords overhead blow the Large appetite outright — violates requirements.md "cut scope, don't move the deadline."
3. **Single-file protocol prototype: sequential (non-fountain) chunking, Android-only, no `FrameTransport` seam, hardcoded QR screen.**
   - *Strength*: fastest path to a demo; minimal abstraction.
   - *Weakness*: sequential chunking is the fragile median prior-art design (indefinite "waiting for frame N" loops); no seam means the required pluggable-transport shape has to be retrofitted later — throwaway work.

**Chosen: Approach 1.** It is the only one that both fits the appetite and satisfies the two hard requirements (fountain-coded robustness + a minimal `FrameTransport` seam). Approaches 2 and 3 are recorded as rejected in Pattern Decisions.

---

## Domain Glossary
| Term | Definition | Notes |
|------|-----------|-------|
| `FrameTransport` | Minimal Strategy interface (`FrameTransportSender` + `FrameTransportReceiver`) over opaque byte frames that the protocol layer sits on; QR is the only v1 implementation. | `commonMain/.../transfer/FrameTransport.kt`; extension point for future WebRTC/BLE/audio. **Distinct from `SyncTransport` (ADR-016)** — see ADR-006: `FrameTransport` is stateless / offline / one-shot over opaque byte frames; `SyncTransport` is stateful / networked / versioned file sync. Different layer, different lifecycle, no shared code. |
| `FrameTransportSender` | Send half of a `FrameTransport`: consumes a paced `Flow<ByteArray>` of opaque frames and physically emits them (QR: renders each as a QR frame). | Frame-out only; no knowledge of pages or fountain codes. `send` is `suspend` so the QR pacing loop (~400 ms/frame) is cancellable. |
| `FrameTransportReceiver` | Receive half of a `FrameTransport`: produces a `Flow<ByteArray>` of raw decoded frames from the physical channel (QR: decoded QR payloads). | Frame-in only; hands bytes up to the protocol layer. |
| `QrFrameTransport` | The v1 `FrameTransport` adapter (Story 2.1.4, which also creates `QrScanner.kt` — see the `QrScanner` row below). Send-side composes `FountainEncoder` → `ChunkFrameCodec` → `QrCodec.encode` → frame emission; receive-side composes `CameraFrameSource.frameStream()` → `QrScanner.decode` (returns `ScanResult`) → filters to the `Decoded` case → frame emission (non-decoded `ScanResult`s are dropped at this layer, never surfaced through the seam). `QrEncodeViewModel` drives the send half directly; `QrDecodeViewModel` drives the receive half only indirectly, via `QrTransferCoordinator`. Neither ViewModel touches `QrCodec`/`QrScanner`/`CameraFrameSource` directly. | `commonMain/.../transfer/qrcode/QrFrameTransport.kt`; the concrete proof the seam is real, not paper-only. |
| `QrScanner` | Layer-2 `CameraFrame`→`ScanResult` scanner: `decode(frame): ScanResult` = `QrCodec.decode` → `ChunkFrameCodec.decode`, returning `Decoded(chunk)` / `NotSteleKitCode` / `NoCodeDetected`. **Created in Story 2.1.4** — it is a dependency of `QrFrameTransport`'s receive-side composition, one epic before Story 3.2.2, which previously (incorrectly) listed it as new. Two consumers: (1) `QrFrameTransport`'s internal receive pipeline (Story 2.1.4, unchanged — only the `Decoded` case crosses the `FrameTransport` seam); (2) from Story 3.2.2 onward, a second, directly-injected reference held by `QrTransferCoordinator` purely to derive `ScanHint` diagnostics — a scoped, documented exception to "the coordinator only touches the seam," since rich scan diagnostics are QR-specific UX (gap G5), not part of the generic transport data path. | `commonMain/.../transfer/qrcode/QrScanner.kt`; single file, single creation point (Story 2.1.4) — Story 3.2.2 references it, does not recreate it. |
| `LogseqPageSerializer` | Pure function `serialize(page, blocks): String` producing canonical on-disk Logseq markdown (tab-indented bullets, `key:: value` props, no YAML), extracted from `GraphWriter.buildMarkdown`. | The ONLY round-trippable serializer; `MarkdownExporter` must NOT be used. |
| `FountainCodec` | Layer-1 pure entry point exposing `FountainEncoder` and `FountainDecoder`; no I/O, no platform deps. | `commonMain/.../transfer/qrcode/FountainCodec.kt`; mirrors `OutlinerPipeline` purity. |
| `FountainEncoder` | Turns original markdown bytes into an unbounded lazy sequence of `FountainChunk` fountain parts (BC-UR LT algorithm, ADR-001). | Loops indefinitely; receiver can start at any frame. |
| `FountainDecoder` | Accepts `FountainChunk`s (dup/out-of-order/redundant-tolerant), reports coverage, and on sufficient coverage reassembles + integrity-checks the payload. | Owns the "prove completeness" gate. |
| `FountainChunk` | One fountain part + its wire header (magic, version, `TransferId`, payloadLen, payloadCrc, `ChunkIndex`, fragLen, fragment, chunkCrc) per ADR-001. | Medium-neutral Layer-1 type (renamed from `QrChunk` — carries no QR assumption; requirements.md: Layer 1 "must not assume QR or any other specific physical medium"). Value type; serialized to a raw byte frame by `ChunkFrameCodec`. `fragLen`/`payloadLen` are intentionally raw structural byte lengths (not swappable identifiers), so they stay plain `Int`/`UShort` and are deliberately NOT wrapped in newtypes. |
| `ChunkBuffer` | Layer-1 stateful accumulator of admitted `FountainChunk`s for one `TransferId`; exposes `accept`, `coverage`, `isComplete`, and the `reassemble(): Either<QrTransferError, VerifiedTransferPayload>` proof gate. | `isComplete` is necessary but NOT sufficient; `reassemble` verifies whole-payload CRC and is the ONLY producer of `VerifiedTransferPayload`. |
| `VerifiedTransferPayload` | Newtype `value class VerifiedTransferPayload internal constructor(val markdown: String)` — a string that has provably passed the CRC32 whole-payload proof gate (property public-readable so `QrImportService` can consume `.markdown`; constructor `internal`). | Producible only from `ChunkBuffer.reassemble()`'s success branch — the `internal` constructor is minted only in `transfer/qrcode/`, so no code elsewhere can fabricate one. `QrImportService.import()` accepts this, never a raw `String`, so "passed the proof gate" is a compile-time distinction (Parse-Don't-Validate). |
| `TransferId` | Newtype `value class TransferId(val value: Int)` identifying one transfer session; carried in every frame to reject a 2nd concurrent sender. | Not raw `Int` (Pattern Decisions). |
| `ChunkIndex` | Newtype `value class ChunkIndex(val value: Int)` for a fountain part's sequence/seed index. | Not raw `Int`. |
| `PayloadChecksum` | Newtype `value class PayloadChecksum(val value: Int)` = CRC32 over original markdown bytes; the whole-payload proof. | Distinct type from `ChunkChecksum`. |
| `ChunkChecksum` | Newtype `value class ChunkChecksum(val value: Int)` = CRC32 over a single frame's bytes; per-frame admission check. | Corrupted frames dropped silently, recovered by FEC. |
| `QrTransferError` | New `sealed interface QrTransferError : DomainError` (ADR/Architecture): `ChunkDecodeFailed`, `IncompleteTransfer`, `IntegrityCheckFailed`, `PayloadTooLarge`, `TransferCancelled`, `MarkdownParseFailed`. | Added to `error/DomainError.kt` with `toUiMessage` branches. |
| `ChunkFrameCodec` | Layer-1 pure serializer between `FountainChunk` and its raw opaque byte frame (`ByteArray`); no QR library dependency, no QR assumption. | Medium-neutral (renamed from `QrPayloadCodec`). Separates frame framing (pure Layer 1) from QR raster (`QrCodec`, Layer 2 lib). |
| `QrCodec` | Layer-2 `expect`/`actual` boundary: `encode(bytes): QrMatrix` and `decode(frame: CameraFrame): ByteArray?`. | ZXing on JVM/Android; CoreImage/Vision on iOS. |
| `QrMatrix` | Platform-agnostic boolean bit grid (`BooleanArray` + size) representing one QR code, rendered by Compose Canvas. | No raster/bitmap type crosses `commonMain`. |
| `CameraFrameSource` | New parallel interface (ADR-002): `isAvailable` + `frameStream(): Flow<Either<SensorError, CameraFrame>>`. | Separate from `CameraProvider`; NOT a new method on it. |
| `CameraFrame` | `data class(luminanceBytes, width, height, rotationDegrees)` — one grayscale camera frame ready for QR decode. | Rotation MUST be applied before decode. |
| `QrEncodeViewModel` | Owns its own `CoroutineScope(SupervisorJob()+Default)` + `close()`; drives serialize→chunk step, then hands the paced frame `Flow` to `QrFrameTransport.send(...)`; exposes `QrEncodeUiState`. | Per CLAUDE.md scope-ownership; models on `AnnotationEditorViewModel`. Drives the `FrameTransportSender` half — never calls `QrCodec.encode` directly. |
| `QrDecodeViewModel` | Owns its own scope + `close()` + `CoroutineExceptionHandler`; owns UI-state only and delegates the frame→scan→buffer→reassemble pipeline to `QrTransferCoordinator`, collecting the coordinator's output `Flow` to drive `QrDecodeUiState`. | Reduced to scope/UI-state ownership (SRP), mirroring `AnnotationEditorViewModel` + `DepthEstimationCoordinator`. Does NOT touch `CameraFrameSource`/`QrScanner`/`ChunkBuffer` directly. |
| `QrTransferCoordinator` | Non-Compose class (models `DepthEstimationCoordinator`) taking **two constructor collaborators**: `frameTransportReceiver: FrameTransportReceiver` (the transfer-data path — `frames()` → `ChunkFrameCodec.decode` → `TransferSession`/`ChunkBuffer` → `reassemble()`) and a directly-injected `qrScanner: QrScanner` (diagnostics-only — derives `ScanHint` from `ScanResult`/luminance; output never feeds `ChunkBuffer`). Emits coordinator events (`FragmentAdmitted`, `Stalled`, `Verified(VerifiedTransferPayload)`, `Failed`). | `commonMain/.../transfer/qrcode/QrTransferCoordinator.kt`; independently unit-testable without a ViewModel — fakes for both collaborators, per Task 3.2.2d. |
| `QrEncodeUiState` | Sealed sum type: `Idle`, `Serializing`, `Displaying(frameIndex, totalCycled, chunkCount, estBytes)`, `Paused(frameIndex, totalCycled, chunkCount, estBytes)`, `Complete`, `Cancelled`, `Failed(QrTransferError)`. | Per `DepthModelUiState` (commonMain mirror of `DepthModelDownloader.ModelState`) precedent — `DepthModelUiState` is the actually-correct analog since `QrEncodeUiState` also lives in `commonMain`. `Paused` carries the same payload as `Displaying` so reduce-motion tap-to-advance and lifecycle pause resume at the exact frame. `Failed` covers pre-flight `PayloadTooLarge` / serialization failure (UX gap G1). `Complete` = "finished broadcasting", NOT "confirmed received" (no back-channel; UX gap G2). |
| `QrDecodeUiState` | Sealed sum type: `Idle`, `PreflightFailed(reason)`, `Scanning(uniqueFragments, stalledSeconds, hint: ScanHint?)`, `Reassembling`, `Importing`, `Success(pageName)`, `Failed(QrTransferError)`, `Cancelled`. | Non-linear progress shown as fragment count, not %. `hint` carries the differentiated scan guidance (wrong-code / low-light / stalled) the UX promises (gap G5). |
| `ScanResult` | `sealed interface ScanResult` returned by `QrScanner.decode(frame)`: `Decoded(chunk: FountainChunk)`, `NotSteleKitCode` (QR found but wrong magic/version), `NoCodeDetected` (no QR in frame). | Introduced together with `QrScanner` in Story 2.1.4 (not a later replacement of a simpler return type). Differentiating failure causes lets `QrTransferCoordinator` (from Story 3.2.2, via its directly-injected `qrScanner`) pick the right `ScanHint` (gap G5). |
| `ScanHint` | Enum driving differentiated `Scanning` copy: `WrongCode`, `LowLight`, `Stalled` (and `null` = normal progress). | `WrongCode` from `ScanResult.NotSteleKitCode`; `LowLight` from a mean-luminance heuristic over `CameraFrame.luminanceBytes`; `Stalled` from the stall timer. |
| `QrImportService` | Receive-side service (models on `ImageImportService`): `VerifiedTransferPayload` → `OutlinerPipeline` → collision resolution → `DatabaseWriteActor` write, with compensating rollback (delete orphaned page row) if `saveBlocks` fails after `savePage`. | Constructor DI; routes writes through actor, never raw queries. Accepts `VerifiedTransferPayload`, never a raw `String`. |
| `QrTransferSettings` | Feature flag + tunables (`enabled`, `framesPerSecond` **rejected if > 3**, `maxFragmentBytes`, `ecLevel`, `reduceMotion`) backed by the existing `platform.Settings` abstraction (per `TagSettings`) — no new dependency. | Follows `TagSettings` pattern; default `enabled=false`. |
| `TransferSession` | Aggregate root for one receive, introduced in **Story 3.2.2** (held by `QrTransferCoordinator` from the start, not retrofitted): wraps `TransferId`, `ChunkBuffer`, first-frame timestamp, last-new-fragment timestamp. | Story 3.3.2 later only adds stall-timer *behavior* to this existing aggregate. Survives backgrounding within the same VM lifetime. |
| `AirGapGuardTest` | Regression test walking `transfer/` package imports; fails the build if any network/git/Google-API import appears. | Structural air-gap enforcement, à la `MigrationRunnerSchemaSyncTest`. |

---

## Pattern Decisions
| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `FrameTransport` plugin interface | **Strategy** (GoF) — two thin interfaces `FrameTransportSender`/`FrameTransportReceiver` over opaque `ByteArray` frames | GoF | Fat single interface with QR-specific methods (`renderMatrix`, `cameraFrame`); OR `sealed class FrameTransport` with a variant per transport | Fat interface over-fits QR's frame-per-image model and forces future WebRTC/audio to implement irrelevant methods (ISP). A `sealed class` closes the set at compile time — the whole point is out-of-tree extension, so an open interface is required. Frame-in/frame-out is the minimal seam that plausibly fits a second transport on paper (validated, not built — requirements.md). |
| `FountainCodec` / `ChunkBuffer` | **Pure function core + Parse-Don't-Validate** (type-driven) | type-driven-design | Stateful decoder that exposes a mutable "partial page" that callers poll | A pollable partial-page invites persisting a half-decoded page. `reassemble(): Either<QrTransferError, VerifiedTransferPayload>` yields either an error or one fully-verified, proof-gated payload — no intermediate "looks like a page" state exists to accidentally write, and the newtype makes "passed the CRC32 gate" a compile-time fact (Architecture research). |
| `TransferId`, `ChunkIndex`, `PayloadChecksum`, `ChunkChecksum` | **Newtype / Value Object** (`value class`) | type-driven-design / DDD | Raw `Int`/`String` | Primitive obsession: a `ChunkIndex` and a `PayloadChecksum` are both `Int` and trivially swappable at a call site; newtypes make that a compile error. Matches this repo's existing `PageUuid`/`BlockUuid` `value class` idiom (`model/Uuid.kt`). |
| `QrEncodeUiState` / `QrDecodeUiState` | **Sum type / State pattern** (sealed interface) | type-driven-design | Boolean/enum flags (`isScanning`, `isDone`, `errorMessage: String?`) | Flag soup admits illegal combinations (`isDone && isScanning`). Sealed states make the state machine exhaustive and compiler-checked, per the `DepthModelUiState` precedent (features research; `DepthModelUiState` is `commonMain`'s own sealed mirror of `androidMain`'s `DepthModelDownloader.ModelState`, and the correct analog since `QrEncodeUiState`/`QrDecodeUiState` are also `commonMain` types). |
| Wire format | **BC-UR fountain algorithm + SteleKit-minimal binary frame** | ADR-001 | Full BC-UR (Bytewords+CBOR+`ur:` URI); OR bespoke fountain scheme with no oracle | Full envelope ~doubles payload for interop never used; bespoke scheme forfeits the cross-language test-vector oracle for the risky erasure math (build-vs-buy research). |
| `CameraFrameSource` | **Separate interface (ISP)** | ADR-002 | New `frameStream()` method on `CameraProvider` | Single-shot photo and continuous scan are different lifecycles; adding the method forces all photo-only implementors to carry it. |
| `QrCodec` platform split | **`expect`/`actual` Adapter** | GoF Adapter | One `commonMain` pure-Kotlin QR lib for everything | No uniform cross-platform barcode API exists; per-platform wrapping (ZXing / CoreImage) is standard KMP practice, not a shortcut. |
| `QrImportService` | **Service Layer + template-method pipeline** (models `ImageImportService`) | PoEAA | Bespoke wire-format→model mapping bypassing `OutlinerPipeline` | Reusing the existing parser sidesteps every property/escaping/traversal edge case already solved for other ingestion paths (pitfalls research). |
| Feature flag | **Settings-backed toggle** (existing `platform.Settings` abstraction, per `TagSettings`) | existing `TagSettings` | New generic feature-flag framework; a new `multiplatform-settings` third-party dependency | No flag framework exists and no new dependency is warranted — `platform.Settings` (the abstraction `TagSettings` already uses) covers it (features/pitfalls research). |
| Overall delivery | **Android-round-trip-first, cross-platform send** | Creative Pass / ADR-003 | All-6-combos flat (Approach 2); sequential-chunk Android-only prototype (Approach 3) | Approach 2 blows the appetite (3 greenfield cameras); Approach 3's sequential chunking is the fragile prior-art design and its no-seam shape is throwaway work. |

---

## Migration Plan
No SQLDelight schema change. The decoder writes reconstructed pages/blocks through
the **existing** `DatabaseWriteActor` typed methods (`savePage`/`saveBlocks`) via a
new `GraphLoader` in-memory entry point — same tables, same rows as any disk import.
Therefore **no new `CREATE TABLE`** and **no `MigrationRunner.all` entry** is
required (CLAUDE.md migration rule is N/A here). `QrTransferSettings` uses the
existing `platform.Settings` abstraction (per `TagSettings`) — no DB, no new dependency.

## Observability Plan
- **Logs** (structured, existing logger): `qr_transfer_started{transferId, role, estBytes, chunkCount}`; `qr_frame_sent{transferId, frameIndex}` (debug); `qr_frame_decoded{transferId, chunkIndex, admitted}`; `qr_chunk_rejected{transferId, reason}` (debug/count); `qr_reassembly{transferId, uniqueFragments, result=success|integrity_fail}`; `qr_transfer_ended{transferId, role, outcome=success|cancelled|failed, elapsedMs, framesSent, framesDecoded, retries}`. Frame counts sent-vs-decoded and retries are explicitly required by requirements.md Observability.
- **Metrics**: none new beyond the above counters logged in-process; no metrics backend exists to wire to.
- **Alerts**: no new alerts required (requirements.md: no new alerting/oncall).

## Risk Control
- **Feature flag**: `QrTransferSettings.enabled`, key `qr_transfer.enabled`, **default `false`**. Gates BOTH entry points (page menu "Send via QR", import menu "Import via camera") AND the receive pre-flight. When off, no QR UI is reachable.
- **Rollback procedure**: flip the flag off (no build needed) — entry points vanish; no schema to revert; in-flight transfers are VM-scoped and die with the screen. If a build rollback is needed, the entire feature is additive (new package `transfer/`, new interface, additive `DomainError` cases) and can be reverted as one commit range with no data migration.
- **Staged rollout**: dogfood on Android↔Android across all three platforms' *send* first behind the flag; enable broadly only after the manual cross-device demo passes the round-trip fidelity gate on Android, and after `AirGapGuardTest` + `FountainCodecVectorTest` are green in CI.

## Unresolved Questions
- [ ] **UQ-1 (blocking): Maximum practical dense-QR fragment size that still scans reliably at arm's length under normal indoor lighting.** Determines `QrTransferSettings.maxFragmentBytes` and the QR version/EC-level defaults, and is the hinge of the ADR-004 density-vs-fps tradeoff. **Blocks Story 2.1.3 (encoder chunk sizing).** Resolved early by the empirical spike **Task 2.1.3a** (print/display QR at increasing `fragLen` × EC-level {L,M,Q}, scan from ~30–50 cm on a mid-range Android device, record the largest reliably-decoding size). Owner: implementer, before finalizing the encoder. Until resolved, use a conservative provisional `maxFragmentBytes = 512`, EC level M, QR version ≤ v20.
- [ ] **UQ-2: Empirical steady-state frame interval floor** (display ms/frame) at which the Android receiver reliably locks on, within the ≤3 fps ceiling (ADR-004). **Blocks final default of `QrTransferSettings.framesPerSecond`.** Resolved by the hardware-in-the-loop reliability pass **Task 3.3.5a** (real transfers across varied lighting/distance, recording lock-on/frame-loss at 2.0/2.5/3.0 fps). Owner: implementer. Provisional default 2.5 fps.
- [ ] **UQ-3: Does app backgrounding on Android preserve a `TransferSession`'s `ChunkBuffer` long enough to resume?** requirements.md/UX flag "recovery from abandoned partial transfer" as a must-decide. **Blocks Story 3.3.2 acceptance.** Decision to record in Task 3.3.2a: fragments survive within the same VM/process (VM retained across config change) but a process kill resets — UX copy says "Paused — reopen camera to continue," encoder keeps looping so a resumed receiver re-converges. Owner: implementer.
- [ ] **UQ-4: Does `qrcode-kotlin`/ZXing produce a usable `wasmJs` artifact?** Non-blocking for v1 (Web out of scope, ADR-005) but gates any future Web phase. Owner: deferred.

## Dependency Visualization
```
                         ┌─────────────────────────────────────────────┐
                         │  Phase 1: Layer 1 (pure commonMain protocol) │
                         │  LogseqPageSerializer  FountainCodec         │
                         │  FountainChunk  ChunkFrameCodec  ChunkBuffer │
                         │  QrTransferError                             │
                         │  (BC-UR vectors, AirGapGuardTest)            │
                         └───────────────┬──────────────────────────────┘
                                         │ (pure, no platform deps)
                         ┌───────────────▼──────────────────────────────┐
                         │  Phase 2: Layer 2 (QR codec, still commonMain)│
                         │  QrCodec(expect)  QrMatrix  QrScanner         │
                         │  CameraFrameSource(interface)                │
                         │  FrameTransport(seam) + QrFrameTransport     │
                         │  + JVM/Android QrCodec actual (ZXing)         │
                         └───────┬──────────────────────┬────────────────┘
                                 │                      │
             ┌───────────────────▼────────┐   ┌─────────▼──────────────────────┐
             │ Phase 3: Android round trip │   │ Phase 4 (deferred within/after │
             │ QrEncodeViewModel (send)    │   │ appetite):                     │
             │ QrDecodeViewModel (receive) │   │  4a Desktop send (Canvas only) │
             │ AndroidCameraFrameSource    │   │  4b iOS send                   │
             │ QrImportService  UI  Flag   │   │  4c Desktop receive (webcam)   │
             │  ── v1 FIDELITY GATE ──     │   │  4d iOS receive (AVFoundation) │
             └─────────────────────────────┘   │  (Web = out of scope, ADR-005) │
                                                └────────────────────────────────┘
```
Strict one-directional dependency: Layer 3 (UI/camera) → Layer 2 (QR) → Layer 1 (protocol). Nothing in Layer 1 imports Compose, a QR lib, or a camera type.

---

## Phase 1: Protocol core (pure `commonMain`, no device required)

### Epic 1.1: Round-trippable serialization + error types + air-gap guard
**Goal**: Establish the pure foundation everything else builds on: a shared serializer that round-trips through this app's own parser, the new error type, and the structural air-gap guarantee — all unit-testable with zero hardware.

#### Story 1.1.1: Extract `LogseqPageSerializer` from `GraphWriter.buildMarkdown`
**As a** developer, **I want** the canonical on-disk markdown builder as a shared pure function, **so that** the QR encoder serializes exactly what `GraphLoader`/`OutlinerPipeline` parses back, guaranteeing round-trip fidelity.
**Acceptance Criteria**:
- `LogseqPageSerializer.serialize(page, blocks)` returns byte-identical output to the current `GraphWriter.buildMarkdown` for the same input, and `GraphWriter` now delegates to it.
  - *Given* a `Page(name="Test", ...)` with two `Block`s `"- root"` / `"\t- child"` and a `pageProperty("tags", "a,b")`, *When* `LogseqPageSerializer.serialize(page, blocks)` is called, *Then* the string equals what `GraphWriter.buildMarkdown` produced before extraction (tab-indented bullets, `tags:: a,b`, no YAML frontmatter, no synthetic heading).
- No behavioural change to disk writes (existing `GraphWriter` tests stay green).
  - *Given* the existing GraphWriter round-trip test suite, *When* run after extraction, *Then* all pass unchanged.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/LogseqPageSerializer.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt` (delegate lines 561-595)

##### Task 1.1.1a: Create `LogseqPageSerializer` object (~4 min)
- Move the body of `GraphWriter.buildMarkdown` (lines 561-595) verbatim into `object LogseqPageSerializer { fun serialize(page: Page, blocks: List<Block>): String }`; keep it pure (no `GraphWriter` field access — pass everything as params).
- Files: `db/LogseqPageSerializer.kt`

##### Task 1.1.1b: Delegate `GraphWriter.buildMarkdown` to the serializer (~2 min)
- Replace `buildMarkdown`'s body with `LogseqPageSerializer.serialize(page, blocks)`; keep it `private` so no other caller changes.
- Files: `db/GraphWriter.kt`

##### Task 1.1.1c: Round-trip unit test (~4 min)
- Test: `serialize` → `OutlinerPipeline` parse → compare `Block.contentHash` set equals original (the success-metric lossless gate).
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/db/LogseqPageSerializerTest.kt` (new)

#### Story 1.1.2: Add `QrTransferError` to `DomainError`
**As a** developer, **I want** typed transfer errors, **so that** every decode stage returns `Either<DomainError, T>` and the UI shows exhaustive, compiler-checked messages.
**Acceptance Criteria**:
- `QrTransferError` sealed interface with `ChunkDecodeFailed`, `IncompleteTransfer(received, total)`, `IntegrityCheckFailed`, `PayloadTooLarge(sizeBytes, maxBytes)`, `TransferCancelled`, `MarkdownParseFailed` exists and each has a `toUiMessage()` branch.
  - *Given* `QrTransferError.PayloadTooLarge(sizeBytes=90000, maxBytes=65536)`, *When* `.toUiMessage()` is called, *Then* it returns a user-facing string like "This page is too large to send via QR" (not a raw byte count dump), and the `when` in `toUiMessage` compiles without an `else`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.1.2a: Add the sealed interface + branches (~4 min)
- Add `sealed interface QrTransferError : DomainError` mirroring the `GitError` nested-block style; add its exhaustive branches to `toUiMessage()` (lines 115-160).
- Files: `error/DomainError.kt`

#### Story 1.1.3: Newtypes for transfer identifiers and checksums
**As a** developer, **I want** `TransferId`/`ChunkIndex`/`PayloadChecksum`/`ChunkChecksum` as `value class`es, **so that** identifiers and checksums cannot be swapped at a call site.
**Acceptance Criteria**:
- Four `value class`es exist; a function taking `ChunkIndex` rejects a `PayloadChecksum` at compile time.
  - *Given* `fun frameFor(index: ChunkIndex)`, *When* a caller passes `PayloadChecksum(42)`, *Then* it fails to compile.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/TransferTypes.kt` (new)

##### Task 1.1.3a: Define the four value classes (~3 min)
- `@JvmInline value class TransferId(val value: Int)`, `ChunkIndex(val value: Int)`, `PayloadChecksum(val value: Int)`, `ChunkChecksum(val value: Int)`.
- Files: `transfer/TransferTypes.kt`

#### Story 1.1.4: CRC32 + `AirGapGuardTest` scaffolding
**As a** developer, **I want** a pure CRC32 and a package-import guard test, **so that** integrity checks work on all targets and the air-gap is structurally enforced.
**Acceptance Criteria**:
- Pure-Kotlin `Crc32.of(bytes): Int` returns the standard CRC32 for known vectors.
  - *Given* the ASCII bytes of `"123456789"`, *When* `Crc32.of(...)` is called, *Then* it returns `0xCBF43926.toInt()` (the CRC32 check value).
- `AirGapGuardTest` fails if any file under `transfer/` imports `io.ktor`, git-sync, or a Google API package.
  - *Given* a hypothetical `import io.ktor.client.HttpClient` added to any `transfer/` file, *When* `AirGapGuardTest` runs, *Then* it fails with the offending file path.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/Crc32.kt` (new), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/transfer/AirGapGuardTest.kt` (new)

##### Task 1.1.4a: Pure CRC32 (~3 min)
- Standard table-driven CRC32; `commonMain` (no `java.util.zip`).
- Files: `transfer/Crc32.kt`

##### Task 1.1.4b: Air-gap import-walker test (~4 min)
- Walk `kmp/src/commonMain/.../transfer/` sources, assert none contain forbidden import substrings; model enforcement on `MigrationRunnerSchemaSyncTest`.
- Files: `jvmTest/.../transfer/AirGapGuardTest.kt`

### Epic 1.2: Fountain codec + chunk buffer (the risky math, anchored to vectors)
**Goal**: The BC-UR-informed fountain encoder/decoder and the completeness-proving `ChunkBuffer`, validated bit-for-bit against reference test vectors (ADR-001).

#### Story 1.2.1: `FountainChunk` model + `ChunkFrameCodec` framing
**As a** developer, **I want** the wire frame struct and its byte (de)serializer, **so that** frames round-trip losslessly independent of QR.
**Acceptance Criteria**:
- `ChunkFrameCodec.encode(chunk)`/`decode(bytes)` round-trip a `FountainChunk`, and `decode` returns `null` on a bad `magic`, wrong `version`, or failing `chunkCrc`.
  - *Given* a `FountainChunk(transferId=TransferId(7), chunkIndex=ChunkIndex(3), payloadCrc=PayloadChecksum(0xAABBCCDD.toInt()), fragment=byteArrayOf(1,2,3))`, *When* `decode(encode(chunk))` runs, *Then* it returns an equal `FountainChunk`; *and When* the first byte is flipped from `0x53`, *Then* `decode` returns `null`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainChunk.kt` (new), `.../qrcode/ChunkFrameCodec.kt` (new)

##### Task 1.2.1a: `FountainChunk` data class (~3 min)
- Fields per ADR-001 layout, using the newtypes.
- Files: `transfer/qrcode/FountainChunk.kt`

##### Task 1.2.1b: `ChunkFrameCodec` fixed-width binary (de)serializer (~5 min)
- Big-endian read/write of the 20-byte header + fragment + trailing CRC; validate magic/version/chunkCrc on decode.
- Files: `transfer/qrcode/ChunkFrameCodec.kt`

##### Task 1.2.1c: Round-trip + tamper unit test (~3 min)
- Files: `commonTest/.../transfer/qrcode/ChunkFrameCodecTest.kt` (new)

#### Story 1.2.2: `FountainEncoder`
**As a** sender, **I want** markdown bytes turned into an unbounded stream of fountain parts, **so that** a receiver can start mid-stream and still reconstruct.
**Acceptance Criteria**:
- `FountainEncoder(payloadBytes, maxFragmentBytes).parts()` yields an infinite sequence whose parts match the BC-UR reference algorithm for a fixed seed.
  - *Given* payload `"hello world"` bytes with `maxFragmentBytes=4` and `TransferId(1)`, *When* the first 5 parts are taken, *Then* their `ChunkIndex` values are `0,1,2,3,4` and part 0 equals the BC-UR reference part-0 for that input (asserted in the vector test).
- Refuses payloads over a hard cap.
  - *Given* a payload of 200_000 bytes and `maxPayloadBytes=65536`, *When* the encoder is constructed, *Then* it returns `Left(QrTransferError.PayloadTooLarge(200000, 65536))`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainEncoder.kt` (new)

##### Task 1.2.2a: LT encoder core (~5 min)
- Deterministic Xoshiro256 seeded per `TransferId`; BC-UR degree distribution; XOR part mixing; emit `FountainChunk`s lazily.
- Files: `transfer/qrcode/FountainEncoder.kt`

##### Task 1.2.2b: Payload-size guard (~2 min)
- Bound payload length before allocation; return `PayloadTooLarge`.
- Files: `transfer/qrcode/FountainEncoder.kt`

#### Story 1.2.3: `FountainDecoder` + `ChunkBuffer` with proof gate
**As a** receiver, **I want** to accept lossy/reordered/redundant parts and only ever hand up bytes that pass the whole-payload checksum, **so that** no corrupted or partial page can reach the database.
**Acceptance Criteria**:
- `ChunkBuffer.accept(chunk)` is idempotent for duplicates and order-independent; `reassemble()` returns `Right(VerifiedTransferPayload(markdown))` only when the reassembled bytes' CRC32 equals the frame-carried `payloadCrc`, else `Left(IntegrityCheckFailed)`. `reassemble()`'s success branch is the ONLY place a `VerifiedTransferPayload` is constructed (its primary constructor is `internal` to `transfer/qrcode/`), so "passed the CRC32 proof gate" is a type-level fact (Parse-Don't-Validate).
  - *Given* parts for payload `"page body"` fed in reverse order with one duplicate and one dropped, but enough total coverage, *When* `reassemble()` is called, *Then* it returns `Right(VerifiedTransferPayload("page body"))`.
  - *Given* a buffer whose parts reconstruct to bytes whose CRC32 ≠ the header `payloadCrc`, *When* `reassemble()` is called, *Then* it returns `Left(QrTransferError.IntegrityCheckFailed(...))` and never a `VerifiedTransferPayload`.
- `isComplete()` true does not imply `reassemble()` succeeds (necessary-not-sufficient).
  - *Given* coverage that satisfies `isComplete()` but a corrupted fragment slipped past (constructed test), *When* `reassemble()` runs, *Then* it returns `Left(IntegrityCheckFailed)`.
- Allocation is bounded by the header's `payloadLen` checked against `maxPayloadBytes` before buffering.
  - *Given* a first frame claiming `payloadLen=5_000_000` with `maxPayloadBytes=65536`, *When* `accept` is called, *Then* it is rejected and no 5 MB buffer is allocated.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainDecoder.kt` (new), `.../qrcode/ChunkBuffer.kt` (new), `.../qrcode/VerifiedTransferPayload.kt` (new — `value class` with an `internal` constructor, minted only by `reassemble()`)

##### Task 1.2.3a: `ChunkBuffer` accept/coverage/dedup (~5 min)
- Dedup by fountain fragment identity (not frame count); tolerate out-of-order; bound `payloadLen` before allocating.
- Files: `transfer/qrcode/ChunkBuffer.kt`

##### Task 1.2.3b: LT reconstruction + `reassemble` proof gate + `VerifiedTransferPayload` (~5 min)
- XOR-chain solve; verify whole-payload CRC before wrapping the string in `VerifiedTransferPayload` and returning `Right`; map failures to `QrTransferError`. Define `value class VerifiedTransferPayload internal constructor(val markdown: String)` (public property, internal constructor) so no other module code can mint one.
- Files: `transfer/qrcode/FountainDecoder.kt`, `transfer/qrcode/VerifiedTransferPayload.kt`

##### Task 1.2.3c: `FountainCodec` facade (~2 min)
- `object FountainCodec { fun encoder(...); fun decoder(...) }` — the Layer-1 public entry point.
- Files: `transfer/qrcode/FountainCodec.kt`

#### Story 1.2.4: BC-UR reference vector cross-validation
**As a** developer, **I want** the fountain math checked against published cross-language vectors, **so that** it cannot silently mis-converge on real frames (the build-vs-buy crux).
**Acceptance Criteria**:
- `FountainCodecVectorTest` replays official BC-UR reference vectors and matches part bytes exactly.
  - *Given* the BC-UR published test payload and seed, *When* `FountainEncoder` produces parts 0..N, *Then* each equals the reference fragment bytes; *and When* those parts are fed to `FountainDecoder`, *Then* `reassemble()` returns the original payload.
- End-to-end lossy round-trip property test passes.
  - *Given* a random 2 KB payload and a simulated channel dropping 30% of frames in random order, *When* enough parts are consumed, *Then* `reassemble()` returns the exact original bytes.
**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainCodecVectorTest.kt` (new), `.../FountainCodecRoundTripTest.kt` (new), `kmp/src/commonTest/resources/bcur-vectors/` (new)

##### Task 1.2.4a: Port BC-UR vectors into test resources (~5 min)
- Copy published reference part vectors; load in `commonTest`.
- Files: `commonTest/resources/bcur-vectors/*`, `FountainCodecVectorTest.kt`

##### Task 1.2.4b: Lossy-channel round-trip property test (~4 min)
- Random payload + random drop/reorder; assert exact reconstruction.
- Files: `FountainCodecRoundTripTest.kt`

---

## Phase 2: QR transport layer + `FrameTransport` seam (still device-light)

### Epic 2.1: `FrameTransport` seam + `QrCodec` + encoder chunk sizing + `QrFrameTransport` adapter
**Goal**: The minimal pluggable seam, the QR raster boundary, the max-payload spike, and the concrete `QrFrameTransport` adapter that wires the seam to real QR send/receive (so the seam is verified by v1 code, not left paper-only).

#### Story 2.1.1: `FrameTransport` Strategy interface
**As a** developer, **I want** a minimal frame-in/frame-out `FrameTransport` seam, **so that** future WebRTC/BLE/audio transports plug in without touching Layers 1–2.
**Acceptance Criteria**:
- `suspend fun FrameTransportSender.send(frames: Flow<ByteArray>)` and `FrameTransportReceiver.frames(): Flow<ByteArray>` exist with no QR-specific types in their signatures. `send` is `suspend` (not a plain `Sequence` consumer) so the sender paces frame emission (~400 ms/frame) in a cancellable way, consistent with this repo's coroutine-scope-ownership conventions.
  - *Given* the `FrameTransport` interface source, *When* inspected, *Then* no parameter or return type references `QrMatrix`, `CameraFrame`, or any QR type — only `ByteArray`/`Flow`; and `send` carries the `suspend` modifier.
- A paper-validation doc-comment shows a hypothetical **`AudioTransport`** fitting the same interface (validate-on-paper requirement, no implementation). Audio is chosen deliberately as the paper target because the Rabbit Holes section flags it as the transport *most* likely to break the frame-in/frame-out abstraction (much lower bandwidth, different framing than QR's frame-per-image model) — validating against the hardest case, not the closest fit.
- The interface KDoc cross-references ADR-006 to disambiguate `FrameTransport` from the pre-existing `SyncTransport` (ADR-016).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/FrameTransport.kt` (new)

##### Task 2.1.1a: Define `FrameTransportSender`/`FrameTransportReceiver` (~3 min)
- `suspend fun send(frames: Flow<ByteArray>)` on the sender; `fun frames(): Flow<ByteArray>` on the receiver; ADR-006 cross-reference in KDoc; `AudioTransport` paper-validation doc-comment.
- Files: `transfer/FrameTransport.kt`

#### Story 2.1.2: `QrCodec` expect + `QrMatrix` + JVM/Android actual (ZXing)
**As a** developer, **I want** a platform QR encode/decode adapter, **so that** Layer 3 renders and scans without knowing the QR library.
**Acceptance Criteria**:
- `expect object QrCodec { fun encode(bytes: ByteArray): QrMatrix; fun decode(frame: CameraFrame): ByteArray? }` with a JVM/Android ZXing `actual`.
  - *Given* `QrCodec.encode(byteArrayOf(1,2,3))` on JVM, *When* the resulting `QrMatrix` is fed back through a ZXing decode of a rendered bitmap in test, *Then* the decoded bytes equal `[1,2,3]`.
  - *Given* a `CameraFrame` whose luminance encodes a known payload but `rotationDegrees=90`, *When* `decode` runs, *Then* rotation is applied before scanning and the payload decodes (guards the documented rotation-drift failure).
**Files**: `kmp/src/commonMain/.../transfer/qrcode/QrCodec.kt` (expect, new), `.../qrcode/QrMatrix.kt` (new), `kmp/src/jvmMain/.../transfer/qrcode/QrCodec.jvm.kt` (new), `kmp/src/androidMain/.../transfer/qrcode/QrCodec.android.kt` (new)

##### Task 2.1.2a: `expect` + `QrMatrix` (~3 min)
- Files: `commonMain/.../qrcode/QrCodec.kt`, `QrMatrix.kt`

##### Task 2.1.2b: SPIKE — ZXing dependency pin with go/no-go checkpoint (~5 min primary path; contingency below)
- **This is a dependency spike, not a throughput task**: every downstream Layer-2/3 story depends on it transitively, so it carries an explicit go/no-go checkpoint and a documented fallback rather than being assumed to succeed.
- **Primary path**: add `com.google.zxing:core:3.5.4` to `kmp/build.gradle.kts` (jvmMain+androidMain) AND `MODULE.bazel` `maven.install`; run `bazel run @maven//:pin`; commit `maven_install.json`. **Checkpoint**: `bazel build //kmp:jvm_tests` resolves ZXing AND Gradle `./gradlew :kmp:compileKotlinJvm` resolves it — both green = GO.
- **Fallback A (artifact/coordinate conflict)**: if `rules_jvm_external` pinning fails or version-conflicts, try an alternate coordinate/version (e.g. `com.google.zxing:core:3.5.3`, or the `core` + `javase` split) and re-pin.
- **Fallback B (Bazel pinning blocks)**: if Bazel resolution cannot be made green quickly, sequence **Gradle-first** — land the ZXing `actual`s + all Layer-2/3 work behind the Gradle build, and track the Bazel `maven_install.json` wiring as a fast-follow task (`Task 2.1.2b-bazel`) rather than a hard blocking dependency for all of Phase 2+. Gradle remains a supported build per CLAUDE.md, so this unblocks progress without waiting on the Bazel pin. **Acknowledged deviation (triad review fix)**: sequencing Gradle-first is in tension with CLAUDE.md's "Bazel is the canonical build system" policy — this is a tracked, temporary, intentional deviation scoped to this one dependency-pin spike, not a precedent for skipping Bazel elsewhere; `Task 2.1.2b-bazel` closes the gap and is not optional follow-up.
- Record the chosen path in the task notes so downstream stories know which build resolves ZXing.
- Files: `kmp/build.gradle.kts`, `MODULE.bazel`, `rules_jvm_external++maven+maven_install.json`

##### Task 2.1.2c: JVM + Android ZXing actuals (~5 min)
- Encode via `QRCodeWriter`→`BitMatrix`→`QrMatrix`; decode via `PlanarYUVLuminanceSource` (rotation-corrected) → `MultiFormatReader`; `--try_harder` off (false-positive risk).
- Files: `jvmMain/.../QrCodec.jvm.kt`, `androidMain/.../QrCodec.android.kt`

#### Story 2.1.3: Encoder chunk sizing + max-payload spike (resolves UQ-1)
**As a** sender, **I want** an empirically-grounded fragment size, **so that** frames scan reliably at arm's length at the WCAG-safe frame rate.
**Acceptance Criteria**:
- `QrTransferSettings.maxFragmentBytes` is set from the spike result; the encoder pre-checks estimated frame count/size and surfaces it before sending.
  - *Given* a 2 KB page and the spike-derived `maxFragmentBytes`, *When* the encoder pre-flight runs, *Then* it reports an estimated chunk count and byte size that the UI shows before any frame is displayed (fail-fast on oversize).
**Files**: `kmp/src/commonMain/.../transfer/qrcode/QrTransferSettings.kt` (new)

##### Task 2.1.3a: EMPIRICAL SPIKE — max dense-QR payload (~5 min to wire; manual scan run) [resolves UQ-1]
- Render QR at increasing `fragLen` × EC {L,M,Q}; scan from ~30–50 cm on a mid-range Android device; record largest reliably-decoding size; set `maxFragmentBytes` default.
- Files: `transfer/qrcode/QrTransferSettings.kt` (record result in KDoc + default)

##### Task 2.1.3b: Pre-flight size/count estimate (~3 min)
- Compute `ceil(payloadLen / fragLen)` × redundancy; expose for UI.
- Files: `transfer/qrcode/FountainEncoder.kt`

#### Story 2.1.4: `QrFrameTransport` adapter — the seam's only v1 implementation
**As a** developer, **I want** a concrete `QrFrameTransport` implementing both halves of the seam, **so that** the pluggable-transport claim is verified by v1's own code rather than left as an unimplemented interface both ViewModels bypass. *(Depends on Stories 2.1.1–2.1.3: needs the `FrameTransport` interface, `QrCodec`, and the sized `FountainEncoder`.)*

*(Prerequisite note: this story's receive-side composition also depends on `CameraFrameSource.frameStream()` — see Story 2.2.1 — even though Story 2.2.1 follows this one in reading order; both land in the same Phase 2 delivery unit, per the Dependency Visualization.)*
**Acceptance Criteria**:
- `QrFrameTransport` implements `FrameTransportSender` and `FrameTransportReceiver`. Send-side `send(frames)` composes `FountainEncoder` → `ChunkFrameCodec` → `QrCodec.encode` → emits `QrMatrix` frames for the screen to render. Receive-side `frames()` composes `CameraFrameSource.frameStream()` → `QrScanner.decode` (returns `ScanResult`, internally applying `QrCodec.decode` → `ChunkFrameCodec.decode`) → filters to the `Decoded` case → emits raw decoded frame bytes. **`QrScanner.kt` is created by this story**, not by Story 3.2.2, since it is a Phase-2 dependency of this composition (see Domain Glossary — `QrScanner`).
  - *Given* a payload and `maxFragmentBytes`, *When* `send(...)` is driven, *Then* it yields paced QR frames and touches `FountainEncoder`/`ChunkFrameCodec`/`QrCodec` only through this adapter.
  - *Given* a `CameraFrameSource` scripted with frames encoding a known transfer, *When* `frames()` is collected, *Then* it emits the decoded chunk bytes and the ViewModel above it never references `QrCodec`/`QrScanner`/`CameraFrameSource` directly. (`QrTransferCoordinator`, introduced in Story 3.2.2, is the one documented exception — it holds a second, directly-injected `QrScanner` reference for `ScanHint` diagnostics only, never for the data path; see Story 3.2.2.)
- `QrEncodeViewModel.start()` drives this sender's `send(...)`; `QrDecodeViewModel`/`QrTransferCoordinator` collect this receiver's `frames()` (enforced in Stories 3.1.2 / 3.2.2).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/QrFrameTransport.kt` (new), `.../transfer/qrcode/QrScanner.kt` (new — see Domain Glossary; Story 3.2.2 references this file, it does not recreate it)

##### Task 2.1.4a: `QrFrameTransport` sender + receiver composition (~5 min)
- Send: `FountainEncoder.parts()` → `ChunkFrameCodec.encode` → `QrCodec.encode`, emitted as a paced `Flow`; Receive: `frameStream()` → `QrScanner.decode` (returns `ScanResult`) → filter to `Decoded` → emit as `Flow<ByteArray>`.
- Files: `transfer/qrcode/QrFrameTransport.kt`

##### Task 2.1.4b: Create `QrScanner.kt` (~3 min)
- `QrScanner.decode(frame: CameraFrame): ScanResult` = `QrCodec.decode` → `ChunkFrameCodec.decode`; sealed `ScanResult` = `Decoded(chunk)` / `NotSteleKitCode` / `NoCodeDetected`. Consumed internally by `QrFrameTransport`'s receive composition (Task 2.1.4a) from this story onward. Story 3.2.2 adds a second, directly-injected use of this same class (for `ScanHint` diagnostics) — it does not recreate this file.
- Files: `transfer/qrcode/QrScanner.kt` (new)

##### Task 2.1.4c: Adapter round-trip unit test (~4 min)
- Fake `CameraFrameSource` + in-process `QrCodec` fixture: assert `send` output fed into `frames()` reconstructs the payload through the adapter with no direct codec calls above it.
- Files: `commonTest/.../transfer/qrcode/QrFrameTransportTest.kt` (new)

### Epic 2.2: `CameraFrameSource` interface + Android frame source (ADR-002)
**Goal**: The new receive-side camera abstraction and its only v1 implementation (Android CameraX `ImageAnalysis`).

#### Story 2.2.1: `CameraFrameSource` interface + `CameraFrame` + No-op + `SensorModule` wiring
**As a** developer, **I want** the streaming camera abstraction separate from `CameraProvider`, **so that** photo-only platforms are unaffected (ISP).
**Acceptance Criteria**:
- `CameraFrameSource` with `isAvailable` + `frameStream()` exists; `NoOpCameraFrameSource` returns `isAvailable=false`/`emptyFlow()`; `SensorModule` exposes a `@Volatile var cameraFrameSource` defaulting to the no-op.
  - *Given* a JVM process with no wiring, *When* `SensorModule.cameraFrameSource.isAvailable` is read, *Then* it is `false` and the decode pre-flight rejects with `HardwareUnavailable` before entering `Scanning`.
**Files**: `kmp/src/commonMain/.../platform/sensor/CameraFrameSource.kt` (new), `.../sensor/CameraFrame.kt` (new), `.../sensor/NoOpCameraFrameSource.kt` (new), `.../sensor/SensorModule.kt` (edit)

##### Task 2.2.1a: Interface + `CameraFrame` + no-op (~4 min)
- Files: `platform/sensor/CameraFrameSource.kt`, `CameraFrame.kt`, `NoOpCameraFrameSource.kt`

##### Task 2.2.1b: `SensorModule` field (~2 min)
- Files: `platform/sensor/SensorModule.kt`

#### Story 2.2.2: `AndroidCameraFrameSource` (CameraX `ImageAnalysis`)
**As an** Android receiver, **I want** a continuous luminance stream, **so that** QR frames decode in real time without rebinding the camera per frame.
**Acceptance Criteria**:
- Binds one `ImageAnalysis` use case (`STRATEGY_KEEP_ONLY_LATEST`), emits `CameraFrame` (Y-plane + `rotationDegrees` from `imageInfo`), closes each `ImageProxy`, and stops on flow cancellation; native/permission failures map to `SensorError`.
  - *Given* camera permission granted and a QR on screen, *When* `frameStream()` is collected, *Then* `CameraFrame`s emit at the camera rate with correct `rotationDegrees`, and *When* the collector cancels, *Then* the use case unbinds (no leak).
  - *Given* permission denied, *When* collected, *Then* the flow emits `Left(SensorError.PermissionDenied("camera"))` and completes.
**Files**: `kmp/src/androidMain/.../platform/sensor/AndroidCameraFrameSource.kt` (new)

##### Task 2.2.2a: `ImageAnalysis` bind + Y-plane extraction (~5 min)
- `callbackFlow`; keep-latest backpressure; extract `planes[0]` luminance; carry rotation; `catch(Throwable)` around native bind (Error subclasses).
- Files: `androidMain/.../AndroidCameraFrameSource.kt`

##### Task 2.2.2b: Wire in Android startup (~2 min)
- Set `SensorModule.cameraFrameSource = AndroidCameraFrameSource(...)` where `AndroidCameraProvider` is wired today.
- Files: `androidMain` startup/DI (same site as existing camera wiring)

---

## Phase 3: Android full round trip — the v1 fidelity gate

### Epic 3.1: Encoder (send) — ViewModel + display UI
**Goal**: Full-screen (inset-card, ADR-004) animated QR sender on Android/Desktop/iOS-capable Compose, driven by a scope-owning ViewModel.

#### Story 3.1.1: `QrTransferSettings` feature flag
**As a** user, **I want** QR transfer behind an opt-in flag, **so that** it's dogfood-gated and hidden by default.
**Acceptance Criteria**:
- `QrTransferSettings(settings)` with `enabled` (default false), `framesPerSecond` (**rejected if > 3**, ADR-004 — not clamped), `reduceMotion`, `ecLevel`, `maxFragmentBytes`, backed by `platform.Settings`. **Decision: reject-with-error, not silent clamp** — clamping would hide the user's actual requested value from logs/support and mask a misconfiguration; a rejected value returns `Left(QrTransferError.…)` / throws at construction so the bad value is visible, never silently rewritten to 3.
  - *Given* `QrTransferSettings` with a request for 8 fps, *When* `framesPerSecond` is set, *Then* construction **rejects with an error** and does not store any value (it does NOT silently clamp to 3).
**Files**: `kmp/src/commonMain/.../transfer/qrcode/QrTransferSettings.kt` (edit from 2.1.3)

##### Task 3.1.1a: Settings-backed flag + fps reject-on-out-of-range (~4 min)
- Model on the existing `platform.Settings` abstraction (per `TagSettings`); namespace keys `qr_transfer.*`; reject `framesPerSecond > 3` at construction (no clamp).
- Files: `transfer/qrcode/QrTransferSettings.kt`

#### Story 3.1.2: `QrEncodeViewModel` + `QrEncodeUiState`
**As a** sender, **I want** a ViewModel that serializes, chunks, and drives the send loop within its own scope, **so that** it survives recomposition and tears down cleanly on cancel (CLAUDE.md scope-ownership).
**Acceptance Criteria**:
- Owns `CoroutineScope(SupervisorJob()+Default)` + `CoroutineExceptionHandler` + `close()`; exposes `StateFlow<QrEncodeUiState>`; `start(pageUuid)` → `Serializing` → `Displaying(...)`; `cancel()` → `Cancelled` and stops the loop.
- **Drives the seam, not the codec**: after serialize+chunk, the ViewModel hands the paced frame `Flow` to `QrFrameTransport.send(...)` (Story 2.1.4) and renders the emitted `QrMatrix` frames. It does NOT call `FountainEncoder`/`ChunkFrameCodec`/`QrCodec.encode` directly — those are reached only through `QrFrameTransport`.
  - *Given* a page of 3 blocks and `framesPerSecond=2.5`, *When* `start(pageUuid)` runs, *Then* state moves `Idle→Serializing→Displaying(frameIndex=0, chunkCount=N, estBytes=~2048)` and advances one frame every ~400 ms via `QrFrameTransport.send`.
  - *Given* `Displaying`, *When* `cancel()` is called, *Then* state becomes `Cancelled`, the loop stops within one tick, and `close()` cancels the scope with no `ForgottenCoroutineScopeException`.
- **`Failed` on fallible construction (UX gap G1)**: serialization failure or a pre-flight `PayloadTooLarge` transitions `Serializing → Failed(QrTransferError)` — never a broken/partial QR, and distinct from `Cancelled` (user intent) and `Idle` (not started).
  - *Given* a page whose serialized payload exceeds `maxFragmentBytes`-derived `maxPayloadBytes`, *When* `start(pageUuid)` runs, *Then* state moves `Idle→Serializing→Failed(PayloadTooLarge(size, max))` and no `Displaying` frame is ever emitted; the screen shows the `toUiMessage()` string.
- **`Paused` carries frame position + explicit trigger/exit (UX gaps G2/G3)**: `Paused` holds the same payload as `Displaying` (`frameIndex, totalCycled, chunkCount, estBytes`). **Trigger**: the host lifecycle signals background/screen-off via `pause()` → `Displaying(payload) → Paused(payload)` (frame-advance loop suspended, frame frozen — no WCAG flash risk while paused). **Exit**: `resume()` (foreground return, or the reduce-motion tap) → `Paused(payload) → Displaying(payload)` resuming at the same `frameIndex`. In reduce-motion mode, `Paused(awaitingTap)` is the between-taps resting state and each tap advances one frame.
  - *Given* `Displaying(frameIndex=4, …)`, *When* `pause()` fires, *Then* state is `Paused(frameIndex=4, …)` and no frame advances; *When* `resume()` fires, *Then* state is `Displaying(frameIndex=4, …)` — position preserved, not reset to 0.
- **Known limitation — `Complete` means "finished broadcasting," not "confirmed received" (UX gap G2)**: QR is one-directional with no back-channel, so the sender cannot learn whether the receiver imported. `Complete` is reached only by an explicit user "Done sending" action (Story 3.1.3), and its UX copy must reflect this ("Sent — ask the other device to confirm it imported"), not imply confirmed delivery. The receiver's own `Success` state is the only true confirmation.
**Files**: `kmp/src/commonMain/.../ui/transfer/QrEncodeViewModel.kt` (new), `.../ui/transfer/QrEncodeUiState.kt` (new)

##### Task 3.1.2a: `QrEncodeUiState` sealed type (~3 min)
- Include `Failed(QrTransferError)` and `Paused(frameIndex, totalCycled, chunkCount, estBytes)` (payload mirrors `Displaying`).
- Files: `ui/transfer/QrEncodeUiState.kt`

##### Task 3.1.2b: ViewModel scope + serialize→chunk→drive `QrFrameTransport.send` (~5 min)
- Load page/blocks via repository (point lookup, not `getAllPages`); `LogseqPageSerializer.serialize`; construct `QrFrameTransport`; drive `send(...)`; map failures to `Failed`; wire `pause()`/`resume()` transitions; guard with `CoroutineExceptionHandler`.
- **Testability note**: the frame-advance delay loop must take an injectable clock/tick (`kotlinx-coroutines-test` virtual time, or an injected `tick: suspend () -> Unit`) so `QrEncodeViewModelTest` asserts pacing without real-time `delay`.
- Files: `ui/transfer/QrEncodeViewModel.kt`

##### Task 3.1.2c: ViewModel unit test (~4 min)
- Fake repo + injected virtual clock; assert `Idle→Serializing→Displaying` transitions, `Failed` on oversize payload, `pause()`/`resume()` preserving `frameIndex`, and cancel teardown.
- Files: `businessTest/.../ui/transfer/QrEncodeViewModelTest.kt` (new)

#### Story 3.1.3: Encoder Compose screen (inset card, air-gap assertion, WCAG-safe)
**As a** sender, **I want** a full-screen sender UI showing the animated QR in an inset card with a pre-flight summary and a persistent "no network" assertion, **so that** transfer is safe, discoverable, and trustworthy.
**Acceptance Criteria**:
- Renders `QrMatrix` on Compose Canvas inside a bordered card ≤60% viewport area on a static background; shows a pre-send summary (page title, size, block count, est. frames/time); shows a persistent "No internet connection used" line; frame advance ≤3 fps.
  - *Given* `QrEncodeUiState.Displaying(chunkCount=12, estBytes=2048)` for page "Meeting Notes" with 5 blocks, *When* the screen renders, *Then* it shows "Meeting Notes · 5 blocks · ~2 KB · ~12 frames", the QR animates in an inset card, the background does not flash, and "No internet connection used" is visible throughout.
- Accessible transfer mode drops to 1–2 fps + tap-to-advance.
  - *Given* `reduceMotion=true`, *When* displaying, *Then* frames advance only on tap and never faster than 2 fps (`Paused(awaitingTap)` between taps, per Story 3.1.2).
- **Explicit "Done sending" action reaching `Complete` (UX gap G2)**: `Displaying` shows a "Done sending" affordance (distinct from Cancel) that transitions `Displaying → Complete`. Its completion copy states "Sent — ask the other device to confirm it imported" (or equivalent), **not** implying confirmed delivery, since the sender has no back-channel.
  - *Given* `Displaying`, *When* the user taps "Done sending", *Then* state → `Complete` and the copy does not claim the receiver imported anything.
- **First-use explainer (triad review fix)**: the first time a user opens this screen (S3, `Displaying`), a one-time, dismissible banner appears before/over the animated QR stating what is about to happen (e.g. "This is a continuous scan, not a photo — keep both screens visible for about 30–60 seconds"), reusing the expectation-setting copy already drafted in `design/ux.md` §S3. Tracked via a local "seen this before" flag persisted through `platform.Settings` — shown once per device, not on every send.
  - *Given* a device that has never opened this screen before, *When* `Displaying` first renders, *Then* the explainer banner is shown and dismissible without pausing or cancelling the transfer; *and Given* the "seen this before" flag is already set from a prior session, *When* `Displaying` renders again, *Then* the banner does not appear.
**Files**: `kmp/src/commonMain/.../ui/transfer/QrEncodeScreen.kt` (new)

##### Task 3.1.3a: Canvas QR renderer + inset card layout (~5 min)
- Draw `QrMatrix` cells; enforce ≤60% area; static background; status line.
- Files: `ui/transfer/QrEncodeScreen.kt`

##### Task 3.1.3b: Pre-flight summary + air-gap assertion + reduce-motion + Done action (~4 min)
- Summary row; persistent offline copy; tap-to-advance when `reduceMotion`; "Done sending" → `Complete` with no-confirmed-delivery copy.
- *(Future enhancement, not v1 — UX gap G4)*: `reduceMotion` could auto-enable from the OS accessibility "reduce motion" setting; v1 ships the explicit toggle only (a discoverable manual toggle already satisfies WCAG 2.3.1).
- Files: `ui/transfer/QrEncodeScreen.kt`

##### Task 3.1.3c: First-use explainer banner (~3 min)
- One-time-ever (per-device, `platform.Settings`-backed flag) dismissible explainer overlay shown on first `Displaying` render only.
- Files: `ui/transfer/QrEncodeScreen.kt`, `transfer/qrcode/QrTransferSettings.kt` (add `seenEncoderExplainer` flag)

#### Story 3.1.4: Send entry point (page menu, flag-gated)
**As a** user, **I want** "Send via QR" on the page menu, **so that** the feature is discoverable, not buried in settings (JTBD).
**Acceptance Criteria**:
- A "Send via QR" action appears in the page context/share menu only when `QrTransferSettings.enabled`; it opens `QrEncodeScreen` for that page.
  - *Given* `enabled=true` and an open page, *When* the page menu opens, *Then* "Send via QR" is present and launches the encoder; *and Given* `enabled=false`, *Then* the action is absent.
**Files**: `kmp/src/commonMain/.../ui/screens/` (page menu host — exact file located at implementation), `.../ui/transfer/QrTransferEntryPoints.kt` (new)

##### Task 3.1.4a: Flag-gated menu action (~4 min)
- Files: `ui/transfer/QrTransferEntryPoints.kt`, page menu composable

### Epic 3.2: Decoder (receive) — ViewModel + scan UI + import
**Goal**: Android continuous-scan receiver that proves completeness before writing.

#### Story 3.2.1: `QrImportService` (models `ImageImportService`)
**As a** receiver, **I want** validated markdown parsed and written through the existing pipeline, **so that** reconstructed pages persist safely via `DatabaseWriteActor`.
**Acceptance Criteria**:
- `import(payload: VerifiedTransferPayload, targetName, collisionChoice)` accepts only a proof-gated `VerifiedTransferPayload` (never a raw `String`), parses via `OutlinerPipeline`, and writes via `DatabaseWriteActor` typed methods; never calls raw `SteleDatabaseQueries`; returns `Either<DomainError, PageName>`.
  - *Given* a `VerifiedTransferPayload` for a page and no existing page of that name, *When* `import(...)` runs, *Then* it parses via `OutlinerPipeline` and calls `DatabaseWriteActor.savePage`/`saveBlocks`, returning `Right(PageName("Meeting Notes"))`.
  - *Given* checksum-valid markdown that `OutlinerPipeline` cannot parse, *When* `import` runs, *Then* it returns `Left(QrTransferError.MarkdownParseFailed(...))` — a distinct terminal state, not "success".
- **Compensating rollback for the non-atomic write (Architecture concern)**: `DatabaseWriteActor.savePage` and `saveBlocks` are **separate** calls (no single atomic page+blocks method exists — verified against `db/DatabaseWriteActor.kt`), so if `saveBlocks` fails after `savePage` succeeds, the service must delete the orphaned page row (`DatabaseWriteActor.deletePage(pageUuid)`) before returning `Left`, mirroring `ImageImportService`'s compensating-action pattern (`fileSystem.deleteFile(destPath)` on failure). No page row with zero blocks is ever left visible.
  - *Given* a page whose `savePage` succeeds but whose `saveBlocks` returns `Left`, *When* `import` runs, *Then* the service calls `deletePage` to remove the orphaned page and returns `Left` — a subsequent query finds no such page.
- **Name never becomes a raw filesystem path (Adversarial concern)**: the imported page name is never used to construct a raw filesystem path. `QrImportService` writes exclusively through `DatabaseWriteActor` (DB rows), not raw file I/O, so no traversal sink exists on this path; any later disk materialization goes through the canonical `FileUtils.sanitizeFileName` (as `GraphWriter`/`GraphLoader` already do). Name validation still runs to reject malformed names early.
  - *Given* a payload whose page name contains `../etc` or a leading `/` (e.g. `/etc/passwd`), *When* `import` runs, *Then* the name is validated/normalized and the write goes only to a DB row via `DatabaseWriteActor` — no raw path is constructed from the name, so no file outside the graph is ever touched.
  - *Note*: the prior citation of commit `fc5661eb14` was **incorrect** — that commit resolved legitimate `saf://` URIs to real paths for JGit, it was not a traversal-defense precedent. It is retained here only as a general reminder that this repo has been bitten by path-handling bugs before, not as a direct precedent for this AC.
**Files**: `kmp/src/commonMain/.../transfer/qrcode/QrImportService.kt` (new), `kmp/src/commonMain/.../db/GraphLoader.kt` (new in-memory entry point)

##### Task 3.2.1a: `GraphLoader` in-memory parse entry point (~5 min)
- Add `suspend fun importMarkdownString(markdown: String, pageName: PageName): Either<DomainError, Pair<Page, List<Block>>>` reusing the existing parse/outliner tail (no `FileSystem` read).
- Files: `db/GraphLoader.kt`

##### Task 3.2.1b: `QrImportService` pipeline + compensating rollback (~5 min)
- Numbered pipeline w/ invariant comments (à la `ImageImportService`): accept `VerifiedTransferPayload` → validate name → parse → collision → `savePage` → `saveBlocks`, and on `saveBlocks` `Left` call `deletePage` to roll back the orphaned page; wrap `Page`/`Block` construction to catch `IllegalArgumentException`→`Left`.
- Files: `transfer/qrcode/QrImportService.kt`

##### Task 3.2.1c: Import service test (rollback, parse-fail, happy path) (~4 min)
- Include a `saveBlocks`-fails-after-`savePage` case asserting the page is deleted (no orphan) and a malformed-name case asserting DB-only write.
- Files: `businessTest/.../transfer/qrcode/QrImportServiceTest.kt` (new)

#### Story 3.2.2: `QrDecodeViewModel` + `QrTransferCoordinator` + `TransferSession` + `QrDecodeUiState`
**As a** receiver, **I want** a scope-owning ViewModel that delegates the receive pipeline to a coordinator and only writes verified pages, **so that** scanning is crash-safe, single-responsibility, and testable without Compose.
**Acceptance Criteria**:
- **SRP split (Architecture concern)**: a non-Compose `QrTransferCoordinator` (models `DepthEstimationCoordinator`) owns the frame→scan→buffer→reassemble pipeline; `QrDecodeViewModel` is reduced to scope + `CoroutineExceptionHandler` + `close()` + UI-state ownership, collecting the coordinator's event `Flow` to drive `QrDecodeUiState`. The ViewModel does not touch `CameraFrameSource`, `QrScanner`, or `ChunkBuffer` directly.
- **Two collaborators, one data path (Architecture concern — resolves the seam-purity/`ScanHint` conflict)**: `QrTransferCoordinator`'s constructor takes both `frameTransportReceiver: FrameTransportReceiver` (Story 2.1.4) and a directly-injected `qrScanner: QrScanner` (also from Story 2.1.4 — see Domain Glossary; this story does not create `QrScanner.kt`). The **transfer-data path** (frames → chunks → reassembly) goes only through `frameTransportReceiver.frames()` — the coordinator never calls `QrCodec` and never collects `CameraFrameSource.frameStream()` itself. The **one documented exception**: `ScanHint` diagnostics (`WrongCode`/`LowLight`/`Stalled`) are sourced from the directly-injected `qrScanner` reference, because rich scan diagnostics are a legitimately QR-specific UX feature (gap G5) that the generic, medium-neutral `FrameTransport` seam is not meant to serve — inventing a generic `FrameEvent`/`SignalQuality` taxonomy on the seam just to carry this one QR-specific feature would over-engineer the seam for a need no other transport (WebRTC/Bluetooth/audio) actually has. This is a narrow, explicitly documented exception to "the coordinator only touches the seam for data" — diagnostics ≠ data path, and the `qrScanner` output never feeds `ChunkBuffer`.
- **`TransferSession` held from the start (Architecture concern)**: the coordinator holds a `TransferSession` (wrapping `TransferId`, `ChunkBuffer`, and timestamps) as the aggregate root from the first frame — it does NOT hold a bare `ChunkBuffer` to be retrofitted later. Story 3.3.2 only adds stall-timer *behavior* to this existing aggregate.
- Pre-flight rejects if `!cameraFrameSource.isAvailable`; frames collected with `.conflate()`; on `reassemble()` `Right(VerifiedTransferPayload)` → `QrImportService.import(payload, …)` → `Success`; on `Left` → `Failed`.
  - *Given* `cameraFrameSource.isAvailable=false`, *When* `start()` runs, *Then* state goes directly to `PreflightFailed(HardwareUnavailable)` without entering `Scanning`.
  - *Given* a stream delivering enough parts for "page body", *When* collected, *Then* state moves `Scanning(uniqueFragments++, hint=null)`→`Reassembling`→`Importing`→`Success("page body"-page)`, and `QrImportService` is called only after `reassemble()` yields `Right(VerifiedTransferPayload)`.
  - *Given* frames arrive faster than decode, *When* collected, *Then* `.conflate()` drops stale frames and the newest is always processed (no unbounded backlog).
  - *Given* an `OutOfMemoryError` thrown in the frame loop, *When* it propagates, *Then* the `CoroutineExceptionHandler` surfaces a `Failed` state rather than killing the Android process (CLAUDE.md Throwable rule).
- **Differentiated scan hints (UX gap G5) — via the directly-injected `qrScanner`**: `QrScanner.decode(frame): ScanResult` (created in Story 2.1.4) returns `Decoded(chunk)`, `NotSteleKitCode`, or `NoCodeDetected`. The coordinator observes its directly-injected `qrScanner` reference (the documented exception above) and maps `NotSteleKitCode`→`ScanHint.WrongCode`, a mean-luminance-below-threshold reading over `CameraFrame.luminanceBytes`→`ScanHint.LowLight`, and its own stall timer→`ScanHint.Stalled`, surfaced in `Scanning(uniqueFragments, stalledSeconds, hint)`.
  - *Given* a frame carrying a non-SteleKit QR, *When* decoded, *Then* `decode` returns `NotSteleKitCode` and the state carries `hint=WrongCode` (drives "That's not a SteleKit transfer code."), distinct from a stall.
**Files**: `kmp/src/commonMain/.../ui/transfer/QrDecodeViewModel.kt` (new), `.../ui/transfer/QrDecodeUiState.kt` (new), `.../transfer/qrcode/QrTransferCoordinator.kt` (new — constructor takes both `frameTransportReceiver` and `qrScanner`), `.../transfer/qrcode/TransferSession.kt` (new — introduced here, not 3.3.2), `.../transfer/qrcode/QrScanner.kt` (**reference existing** — created in Story 2.1.4; this story adds its second, diagnostics-only consumer, it does not recreate the file)

##### Task 3.2.2a: `QrDecodeUiState` + `ScanHint` (~3 min)
- `Scanning` gains a `hint: ScanHint?` field; add the `ScanHint` enum (`WrongCode`/`LowLight`/`Stalled`). `QrScanner`/`ScanResult` already exist from Story 2.1.4 — this task does not create or modify `QrScanner.kt`.
- Files: `ui/transfer/QrDecodeUiState.kt`

##### Task 3.2.2b: `QrTransferCoordinator` + `TransferSession` pipeline (two collaborators) (~5 min)
- Constructor takes `frameTransportReceiver: FrameTransportReceiver` and `qrScanner: QrScanner` (existing, from Story 2.1.4). Data path: collects `frameTransportReceiver.frames()`; holds `TransferSession` (aggregate) from frame 0; `.conflate()`; `catch(Throwable)`; produces `VerifiedTransferPayload` on `reassemble()` `Right`. Diagnostics path (documented exception): observes `qrScanner` separately to map `ScanResult`/luminance/stall → `ScanHint`, never feeding `ChunkBuffer`.
- Files: `transfer/qrcode/QrTransferCoordinator.kt`, `transfer/qrcode/TransferSession.kt`

##### Task 3.2.2c: ViewModel delegating to coordinator + pre-flight (~4 min)
- Pre-flight availability guard; collect coordinator events → `QrDecodeUiState`; call `QrImportService` only after a `Verified` event; `CoroutineExceptionHandler`.
- Files: `ui/transfer/QrDecodeViewModel.kt`

##### Task 3.2.2d: Coordinator + ViewModel tests (pre-flight, proof gate, OOM guard, hints) (~5 min)
- Fake `FrameTransportReceiver` (data path) AND a fake `QrScanner` (diagnostics path) — the coordinator's two constructor collaborators — emitting scripted frames/`ScanResult`s independently; assert no write before a `Verified` event; assert OOM→`Failed`; assert a faked `NotSteleKitCode` from the fake `QrScanner`→`hint=WrongCode`, with no effect on `ChunkBuffer`.
- Files: `businessTest/.../transfer/qrcode/QrTransferCoordinatorTest.kt` (new), `businessTest/.../ui/transfer/QrDecodeViewModelTest.kt` (new)

#### Story 3.2.3: Decoder Compose screen (camera preview, reticle, multi-modal progress)
**As a** receiver, **I want** a scan screen with a reticle, non-linear progress, stall detection, and haptic/audio ticks, **so that** scanning is understandable and accessible.
**Acceptance Criteria**:
- Shows camera preview + reticle with `contentDescription`; progress shows "Receiving… (N unique fragments)" (NOT linear %); haptic/audio tick on each new fragment; copy is driven by `Scanning.hint` (Story 3.2.2): `Stalled`→"Not receiving new data — move closer / adjust angle", `WrongCode`→"That's not a SteleKit transfer code.", `LowLight`→"Too dark to scan — add light", `null`→normal progress.
  - *Given* `QrDecodeUiState.Scanning(uniqueFragments=7, stalledSeconds=0, hint=null)`, *When* rendered, *Then* it shows "Receiving… (7 fragments)" and a haptic tick fired on the 7th; *and Given* `hint=Stalled` (stalledSeconds≥9), *Then* the "move closer" hint appears (bar does not animate without genuine progress); *and Given* `hint=WrongCode`, *Then* the "not a SteleKit transfer code" copy appears — distinct from the stall copy.
- **Handheld-fatigue mitigation tip (triad review fix, pre-mortem item #5)**: once `Scanning` has been continuously active for **more than 15 seconds**, a one-time, non-blocking, dismissible tip appears: "Tip: try propping your phone against something stable for a steadier scan." Shown at most once per scan session — it does not re-appear on every stall or every re-entry into `Scanning` within the same session.
  - *Given* `Scanning` has been active for >15s, *When* the threshold is crossed, *Then* the tip banner appears exactly once, is dismissible, and does not block the camera preview or Cancel action; *and Given* the tip was already dismissed this session, *When* scanning continues past 15s again (e.g. after a brief stall), *Then* it does not reappear.
- **First-use explainer (receiver side, triad review fix)**: the first time a user opens the decoder's `Scanning` surface (S8/S9), a one-time, dismissible explainer states what is about to happen (e.g. "Point your camera at the other device's screen — this is a continuous scan, not a photo, and may take 30–60 seconds"), before or overlaid on the camera preview. Tracked via a local "seen this before" flag persisted through `platform.Settings` (same pattern as Story 3.1.3's sender-side explainer) — not shown on every subsequent use.
  - *Given* a device that has never opened the decoder screen before, *When* `Scanning` starts, *Then* the explainer is shown once and is dismissible without blocking Cancel; *and Given* the "seen this before" flag is already set, *When* `Scanning` starts again, *Then* the explainer does not appear.
- **Haptic/audio feedback is an enhancement, never the sole channel (triad review fix)**: the "Receiving… (N fragments)" text line is always rendered and always sufficient on its own to track progress, regardless of whether haptic ticks fire — some devices disable haptics at the OS level or lack a vibration motor, and audio may be muted.
  - *Given* haptics are disabled/unavailable at the OS level, *When* a new fragment is admitted, *Then* the text fragment count still increments correctly and no progress information is lost — the haptic/audio tick is additive, not load-bearing.
**Files**: `kmp/src/commonMain/.../ui/transfer/QrDecodeScreen.kt` (new)

##### Task 3.2.3a: Camera preview + reticle + fragment-count progress (~5 min)
- Files: `ui/transfer/QrDecodeScreen.kt`

##### Task 3.2.3b: Stall detection copy + haptic/audio tick + wrong-QR state (~4 min)
- Files: `ui/transfer/QrDecodeScreen.kt`

##### Task 3.2.3c: Handheld-fatigue tip (>15s) + first-use explainer banner (~4 min)
- One-time-per-session 15s handheld-fatigue tip; one-time-ever (per-device, `platform.Settings`-backed flag) first-use explainer overlay; both dismissible, neither blocks Cancel or the camera preview.
- Files: `ui/transfer/QrDecodeScreen.kt`, `transfer/qrcode/QrTransferSettings.kt` (add `seenDecoderExplainer` flag)

#### Story 3.2.4: Permission ask + import-confirmation dialogs (reuse existing shapes)
**As a** receiver, **I want** the standard permission rationale and a collision-resolution dialog, **so that** the flow matches app conventions and never silently overwrites.
**Acceptance Criteria**:
- Reuses `CameraPermissionRationaleDialog` shape for the scan permission; post-decode collision uses a `CapturePreviewDialog`-style modal with [Keep both] [Overwrite] [Cancel import]; disabled-during-import button swaps to `CircularProgressIndicator`; `onDismissRequest` guarded while importing.
  - *Given* a decoded page "Meeting Notes" that already exists, *When* import proceeds, *Then* the modal offers Keep both/Overwrite/Cancel and never overwrites without a choice; while writing, the confirm button shows a spinner and dismiss is blocked.
**Files**: `kmp/src/commonMain/.../ui/transfer/QrImportConfirmDialog.kt` (new), reuse `kmp/src/commonMain/.../ui/components/CapturePreviewDialog.kt` pattern

##### Task 3.2.4a: Collision-resolution dialog (~4 min)
- Files: `ui/transfer/QrImportConfirmDialog.kt`

##### Task 3.2.4b: Permission rationale reuse + import entry point (~3 min)
- Flag-gated "Import via camera" action; reuse existing permission dialog.
- Files: `ui/transfer/QrTransferEntryPoints.kt`

### Epic 3.3: Round-trip validation + resilience
**Goal**: Prove the v1 fidelity gate and handle stall/cancel/backgrounding.

#### Story 3.3.1: Automated round-trip fidelity test (success-metric gate)
**As a** developer, **I want** an end-to-end encode→decode fidelity test, **so that** the byte-identical success metric is CI-enforced.
**Acceptance Criteria**:
- `serialize → FountainEncoder → (simulated lossy channel) → FountainDecoder → GraphLoader.importMarkdownString → OutlinerPipeline` yields a page whose `Block.contentHash` set equals the source page's.
  - *Given* a fixture page with 20 blocks, nested properties, and a Unicode block "café ☕ — note", encoded and passed through a 25%-drop channel, *When* reassembled and re-parsed, *Then* every `Block.contentHash` matches the original set (lossless), including the multi-byte UTF-8 block.
**Files**: `kmp/src/commonTest/.../transfer/qrcode/QrRoundTripFidelityTest.kt` (new)

##### Task 3.3.1a: Fixture + full-pipeline fidelity assertion (~5 min)
- Include a UTF-8-split-across-chunk-boundary case (codepoint-aware chunking guard).
- Files: `commonTest/.../QrRoundTripFidelityTest.kt`

#### Story 3.3.2: Stall-timer behavior + cancel / backgrounding resilience (resolves UQ-3)
**As a** receiver, **I want** clean stall detection, cancellation teardown, and defined backgrounding behavior, **so that** the flow never hangs or half-writes.
**Note**: `TransferSession` already exists as the coordinator's aggregate root from **Story 3.2.2** — this story only *adds stall-timer behavior* to it (last-new-fragment tracking + `ScanHint.Stalled` emission) and the cancel/backgrounding wiring; it does not introduce the type.
**Acceptance Criteria**:
- The existing `TransferSession` tracks last-new-fragment time and drives `ScanHint.Stalled`; cancel tears down the coordinator scope; backgrounding within the same VM preserves accumulated fragments (UQ-3 decision).
  - *Given* a `Scanning` session with 5 fragments, *When* the user backgrounds and returns within the VM lifetime, *Then* the 5 fragments persist and scanning resumes; *and When* the user cancels, *Then* state→`Cancelled`, scope cancelled, no write occurs.
**Files**: `kmp/src/commonMain/.../transfer/qrcode/TransferSession.kt` (edit — add stall timer), `.../transfer/qrcode/QrTransferCoordinator.kt` (edit), `ui/transfer/QrDecodeViewModel.kt` (edit)

##### Task 3.3.2a: Add stall timer to existing `TransferSession` + backgrounding decision doc (~4 min) [resolves UQ-3]
- Files: `transfer/qrcode/TransferSession.kt`, `transfer/qrcode/QrTransferCoordinator.kt`

#### Story 3.3.3: Structured transfer logging
**As an** operator, **I want** start/end + frame-count logs, **so that** transfers are diagnosable (requirements.md Observability).
**Acceptance Criteria**:
- Both ViewModels emit the structured events in the Observability Plan.
  - *Given* a completed receive of 12 fragments in 34 s, *When* it ends, *Then* a `qr_transfer_ended{role=receiver, outcome=success, elapsedMs≈34000, framesDecoded=12}` log line is produced.
**Files**: `ui/transfer/QrEncodeViewModel.kt`, `ui/transfer/QrDecodeViewModel.kt`

##### Task 3.3.3a: Emit structured logs at start/frame/end (~3 min)
- Files: both ViewModels

#### Story 3.3.4: Concurrent-sender rejection + version-mismatch handling
**As a** receiver, **I want** frames from a second `TransferId` or an unknown `version` handled cleanly, **so that** a mixed environment fails safely, not silently.
**Acceptance Criteria**:
- `ChunkBuffer` bound to a `TransferId` ignores frames from a different `TransferId`; `ChunkFrameCodec.decode` rejects an unknown `version` byte.
  - *Given* an active session for `TransferId(7)`, *When* a frame for `TransferId(9)` arrives, *Then* it is ignored (logged); *and Given* a frame with `version=0x02` (future), *When* decoded, *Then* it returns `null` and is counted as rejected.
- **UI warning on concurrent second sender is binding, not optional (triad review fix)**: when a frame from a different `TransferId` is dropped while a session is active, `QrTransferCoordinator` MUST emit a signal the decoder UI surfaces as an explicit, visible message — e.g. "Another transfer started — ignoring it" — not a silent drop with no user-visible signal. This is a required AC, not a "may warn" suggestion.
  - *Given* an active `Scanning` session for `TransferId(7)`, *When* a frame for `TransferId(9)` is dropped, *Then* the decoder UI shows the "Another transfer started — ignoring it" message (or materially equivalent copy) at least once, without disrupting the in-progress reception of `TransferId(7)`.
**Files**: `transfer/qrcode/ChunkBuffer.kt`, `transfer/qrcode/ChunkFrameCodec.kt` (already version-checking from 1.2.1), `transfer/qrcode/QrTransferCoordinator.kt` (emit concurrent-sender signal), `ui/transfer/QrDecodeScreen.kt` (render the warning)

##### Task 3.3.4a: TransferId binding + version reject test (~3 min)
- Files: `commonTest/.../ChunkBufferConcurrencyTest.kt` (new)

##### Task 3.3.4b: Concurrent-sender UI warning (~3 min)
- Coordinator emits a one-shot "concurrent transfer detected" event on dropped-`TransferId` frames; decoder screen renders it as a transient, visible message (toast/snackbar-equivalent) without pausing reception of the active session.
- Files: `transfer/qrcode/QrTransferCoordinator.kt`, `ui/transfer/QrDecodeScreen.kt`

#### Story 3.3.5: Hardware-in-the-loop reliability pass (real camera, varied conditions)
**As a** developer, **I want** measured frame-loss/false-decode rates from real transfers across varied lighting and distance, **so that** `maxFragmentBytes`/`framesPerSecond` defaults are tuned against real camera artifacts, not just synthetic random-drop.
**Note**: requirements.md Feasibility Risks explicitly asks for "a hardware-in-the-loop testing pass, not just unit tests." This is **distinct** from the payload-size spike (Task 2.1.3a, which measures scannable density, not FEC recovery), distinct from Story 3.3.1/1.2.4's *synthetic* lossy-channel tests, and distinct from the Risk Control "dogfood before enabling broadly" rollout gate.
**Acceptance Criteria**:
- Owner: implementer, **hardware required** (two real devices). Run N (≥20) real end-to-end transfers of a representative page across varied lighting (bright/dim/backlit), distance (30/50/70 cm), and angle; record observed frame-loss rate, false-decode rate, and time-to-transfer; confirm no corrupted page ever passes the CRC32 proof gate (integrity holds under real corruption). Feed observed rates back into `maxFragmentBytes`/`framesPerSecond` defaults (updating UQ-1/UQ-2 resolutions) and record the results in the task notes.
**Files**: none (manual hardware task; results recorded in task notes + `QrTransferSettings` KDoc defaults)

##### Task 3.3.5a: Run N real transfers, record loss/false-decode rates, tune defaults (~5 min to script harness; manual scan runs) [hardware]
- Files: `transfer/qrcode/QrTransferSettings.kt` (update defaults + KDoc with measured rates)

---

## Phase 4: Cross-platform send + deferred receive (ADR-003)

### Epic 4.1: Desktop (JVM) send — encode-only, no camera
**Goal**: Prove the send side is platform-independent by shipping Desktop send (Canvas render only; no webcam needed).

#### Story 4.1.1: Desktop send wiring
**As a** desktop user, **I want** "Send via QR" from the desktop app, **so that** I can broadcast a page to my phone.
**Acceptance Criteria**:
- `QrEncodeScreen` renders and animates on JVM desktop via the existing Compose Canvas path (JVM `QrCodec.encode` actual from 2.1.2); no camera dependency.
  - *Given* the desktop app with `enabled=true`, *When* "Send via QR" is chosen, *Then* the inset-card animated QR displays at ≤3 fps and an Android device can scan and import it (cross-platform send validated).
**Files**: JVM entry-point wiring only (reuses `commonMain` screen/VM)

##### Task 4.1.1a: Desktop menu action + wiring (~6 min)
- New-platform integration (desktop entry-point host, Compose Canvas render path on JVM), not an edit to existing code — estimate reflects that.
- Files: desktop page menu host, `ui/transfer/QrTransferEntryPoints.kt`

##### Task 4.1.1b: JVM `QrCodec.encode` same-process round-trip unit test (~4 min)
- Encode a fixture payload via the JVM `QrCodec.encode` actual, render to a bitmap, decode via the JVM decode path; assert bytes match — an automated analogue to the manual cross-device demo, so Desktop send has test coverage, not only a manual smoke.
- Files: `jvmTest/.../transfer/qrcode/QrCodecJvmRoundTripTest.kt` (new)

### Epic 4.2: iOS send — encode-only
**Goal**: iOS send via CoreImage QR encode (no camera).

**Known, pre-existing CI gap (triad review fix — verified against `.github/workflows/ci-ios.yml`)**: the engineering reviewer's specific claim that iOS CI runs `KotlinNativeCompile`/`KotlinCompileCommon` with `continue-on-error: true` does **not** check out — no such `continue-on-error` exists anywhere in `ci-ios.yml`. The actual situation is a different, and arguably more severe, version of the same underlying concern: the `ios-framework` job does not compile Kotlin/Native for iOS **at all**. Per that workflow's own comments, `compileKotlinIos*` is entirely blocked by a documented Gradle/AGP classloader bug (Gradle #17559 / KT-68400), so CI instead validates `commonMain` sources only via `compileKotlinJvm` as a proxy — this is unrelated to this feature and pre-existing. Practical consequence for Epic 4.2: `QrCodec.ios.kt`'s CoreImage `CIQRCodeGenerator` cinterop code (Task 4.2.1a) will **not** be exercised by any CI job — a build failure specific to that iOS actual could merge silently, since the JVM-proxy job cannot catch it. **Not this feature's job to fix** (the underlying Gradle/AGP bug is tracked separately), but the implementer should manually run a local iOS build (e.g. `xcodebuild` or Kotlin/Native compile via Xcode/`./gradlew :kmp:compileKotlinIosSimulatorArm64` if run outside CI) for Epic 4.2's tasks rather than trusting CI green as proof the iOS actual compiles.

#### Story 4.2.1: iOS `QrCodec` encode actual + send wiring
**As an** iOS user, **I want** "Send via QR", **so that** I can broadcast a page.
**Acceptance Criteria**:
- iOS `QrCodec.encode` actual (CoreImage `CIQRCodeGenerator`) produces a `QrMatrix`; `QrEncodeScreen` animates; decode actual may throw `NotImplementedError` (receive deferred).
  - *Given* the iOS app with `enabled=true`, *When* "Send via QR" runs for a 2 KB page, *Then* the animated inset-card QR displays and an Android device imports it byte-identically.
**Files**: `kmp/src/iosMain/.../transfer/qrcode/QrCodec.ios.kt` (encode only, new)

##### Task 4.2.1a: iOS CoreImage encode actual (~10 min)
- New-platform integration: CoreImage `CIQRCodeGenerator` → `QrMatrix` cinterop bridging with no existing iOS QR code to edit; estimate reflects a greenfield actual, not an edit. Budget extra per requirements.md Feasibility Risks (iOS is the least-exercised path in this repo).
- Files: `iosMain/.../QrCodec.ios.kt`

##### Task 4.2.1b: iOS `QrCodec.encode` round-trip unit test (~5 min)
- Same-process test: encode a fixture payload via the iOS `QrCodec.encode` actual and assert the produced `QrMatrix` decodes back (via a shared fixture / the iOS decode path once available, or a `QrMatrix`→bytes structural assertion in the interim) — an automated analogue to the manual cross-device demo, so iOS send is not validated by manual demo alone.
- Files: iOS test source set (`iosTest/.../QrCodecIosRoundTripTest.kt`, new)

##### Task 4.2.1c: iOS send entry-point wiring (~5 min)
- New-platform entry-point host wiring (not an edit to existing menu code).
- Files: iOS menu host

### Epic 4.3: DEFERRED — Desktop receive (webcam) [explicit follow-on, ADR-003]
**Goal**: Add Desktop as a receiver. **Not required for v1 sign-off.**

#### Story 4.3.1: `DesktopCameraFrameSource` (new webcam dependency)
**As a** desktop user, **I want** to scan QR from my desktop webcam. *(Deferred; scoped, not built in v1.)*
**Acceptance Criteria**:
- A JVM `CameraFrameSource` backed by a webcam library (JavaCV or webcam-capture — new NON-network dependency; added to Gradle+Bazel+lock) emits `CameraFrame`s; `isAvailable` reflects webcam presence.
  - *Given* a connected webcam and this source wired, *When* `frameStream()` is collected against an on-screen QR, *Then* `CameraFrame`s emit and the existing `QrDecodeViewModel` imports with no ViewModel change (proves the abstraction).
**Files**: `kmp/src/jvmMain/.../platform/sensor/DesktopCameraFrameSource.kt` (new), `kmp/build.gradle.kts`, `MODULE.bazel`, `maven_install.json`

##### Task 4.3.1a: Webcam dependency + JVM frame source (~5 min, deferred)
- Files: as above

### Epic 4.4: DEFERRED — iOS receive (AVFoundation) [explicit follow-on, ADR-003]
**Goal**: Add iOS as a receiver. **Not required for v1 sign-off.**

#### Story 4.4.1: `IOSCameraFrameSource` + privacy manifest
**As an** iOS user, **I want** to scan QR on iPhone. *(Deferred; scoped, not built in v1.)*
**Acceptance Criteria**:
- iOS `CameraFrameSource` via AVFoundation `AVCaptureVideoDataOutput` emits `CameraFrame`s; iOS `QrCodec.decode` actual via Vision/`AVCaptureMetadataOutput`; `PrivacyInfo.xcprivacy` + `NSCameraUsageDescription` added (App Store gate).
  - *Given* camera permission and the privacy manifest present, *When* scanning an on-screen QR, *Then* the existing `QrDecodeViewModel` imports the page unchanged.
**Files**: `kmp/src/iosMain/.../platform/sensor/IOSCameraFrameSource.kt` (new), `kmp/src/iosMain/.../transfer/qrcode/QrCodec.ios.kt` (add decode), iOS `PrivacyInfo.xcprivacy` + `Info.plist`

##### Task 4.4.1a: AVFoundation frame source + decode + privacy manifest (~5 min, deferred)
- Files: as above

---

## v1 Definition of Done (what ships within the appetite)
- **Ships**: Layers 1+2 (all targets compile); Android send; Android receive; Desktop send; iOS send. Round-trip fidelity gate green (Story 3.3.1); `AirGapGuardTest` + `FountainCodecVectorTest` green; feature flag default off.
- **Explicitly deferred (named)**: Desktop receive (Epic 4.3), iOS receive (Epic 4.4), all Web/WASM (ADR-005).
- **Cross-platform send proven** by: Desktop-or-iOS send → Android receive → byte-identical page.
- **Hardware-in-the-loop reliability pass (Story 3.3.5) is REQUIRED for broad rollout, not optional** *(pre-mortem P1 #3)*: Story 3.3.1's simulated/random-drop CI test does NOT satisfy requirements.md's "hardware-in-the-loop testing pass, not just unit tests" ask on its own — it only proves the fountain math, not real camera artifacts (glare, motion blur, autofocus hunting, correlated frame loss). The feature flag must not be enabled beyond the developer's own device until either (a) Story 3.3.5 has recorded ≥20 real transfers across varied lighting/distance/angle — including at least one genuinely **handheld, unpropped** condition for the full transfer duration, not just a stable/braced mount (the handheld-fatigue/motion-blur vicious cycle is a distinct failure mode from simple low light) — or (b) its omission is an explicit, dated, accepted-risk note added here.
- **Sustained real-world dogfooding is REQUIRED before broad enablement, not a one-time demo** *(pre-mortem P1 #1)*: given zero existing UX precedent for this feature category and a fully opt-in flag, adoption cannot be assumed — it must be observed. Before flipping `QrTransferSettings.enabled` default toward broader rollout: (a) the developer uses the feature for actual personal page transfers (not staged demos) over a period of weeks, and (b) a local, no-network, no-telemetry-service usage counter (e.g. a debug/settings-screen tally of completed transfers) is added so "is anyone using this" is an observable fact rather than an assumption — this does not violate the "no new alerting/oncall" NFR since it's a local counter, not a reporting pipeline. **Kill-criteria / time-box (triad review fix)**: if, after 4 weeks of the feature being available to the developer, the local usage counter shows fewer than 2 real (non-demo) transfers, treat that as a signal to deprioritize further investment in this feature line (Epics 4.1–4.4, cross-platform send/receive expansion) rather than continuing to build it out — re-evaluate scope and appetite before committing further work, don't silently keep extending platform coverage for a feature nobody is actually using.
- *(Pre-mortem P2 items #2, #4, #5 — non-blocking but tracked)*: UQ-1's payload-size spike result must be a recorded measurement or an explicit dated fallback-acceptance note, not silent inheritance of the provisional default; ADR-003's phone-receives-first sequencing bet should be explicitly tested against real (not just assumed) dominant JTBD direction during dogfooding, with a decision recorded on whether Desktop-receive (Epic 4.3) needs pulling forward; Story 3.3.5's real-world test matrix must include a genuinely handheld (not tripod-braced) condition per the item above.
