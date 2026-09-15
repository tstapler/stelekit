# Pitfalls & Risks: camera-qr-export

Agent 4 (Pitfalls) — Phase 2 research, SDD workflow. Date: 2026-07-11.

## 0. Architectural showstopper found in codebase — read this first

The requirement says "Must reuse existing cross-platform `CameraProvider` abstraction."
That abstraction (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/sensor/CameraProvider.kt`)
is **single-shot photo capture only**:

```kotlin
interface CameraProvider {
    val isAvailable: Boolean
    suspend fun capturePhoto(): Either<DomainError.SensorError, PlatformImageFile>
}
```

There is no live frame stream, no `ImageAnalysis`-equivalent callback, no continuous
preview surface in the shared interface. Per-platform state today:

| Platform | Implementation | Live frames? |
|---|---|---|
| Android | `AndroidCameraProvider` — CameraX `ImageCapture`, single JPEG to disk, ~400ms bind delay before shutter | No — photo only |
| iOS | `IOSCameraProvider` — stub, `isAvailable = false`, always returns `HardwareUnavailable` | No |
| JVM Desktop | `WebcamCameraProvider` — stub, `isAvailable = false`, falls back to a file picker | No |
| WASM/JS | `WebCameraProvider` — stub, `isAvailable = false`, comment says `getUserMedia` deferred | No |

Animated-QR decode fundamentally needs a **continuous frame stream** (10-15+ fps,
ImageAnalysis/AVCaptureVideoDataOutput/getUserMedia `<video>` + canvas sampling) fed into
a barcode reader per frame, plus a live preview surface for the user to aim the camera.
`capturePhoto()` cannot be called in a loop as a substitute — each Android call pays camera
bind + `delay(400L)` + JPEG encode/write/EXIF-fix, which is orders of magnitude too slow
per frame and would thrash the camera lifecycle (repeated `bindToLifecycle`/`unbindAll`).

**This means the "reuse CameraProvider" constraint, as literally stated, is infeasible for
the decode side.** Options to surface in planning: (a) treat this as a new
`CameraFrameStreamProvider`/`QrScannerProvider` abstraction that sits alongside
`CameraProvider` rather than reusing it, reusing only the permission-check and
availability-check patterns; (b) explicitly renegotiate the constraint in Phase 3 planning.
Either way, this is a Phase 3 architecture decision (ADR-worthy) — flag it, don't silently
build a second interface without reconciling the requirement.

Also note: encode/display-side needs no camera at all (`ImageBitmap`/`Painter` rendering of
generated QR frames) — only the *decode* side is blocked by this gap. iOS, Desktop, and Web
currently have **zero live-capture code path** (all three `isAvailable = false`), so all
"any -> any" receive-side combos except Android-receives are starting from scratch, not a
retrofit — this substantially widens the "6 send/receive combos" rabbit hole already called
out in requirements.md.

## 1. Known pitfalls from real animated-QR / fountain-code transfer implementations

Sources: Blockchain Commons UR/Animated-QR docs, Sparrow Wallet / Specter-desktop GitHub
issues, `divan.dev` fountain-code write-ups.

- **Frame-rate vs. camera-lock mismatch is empirical, not a fixed number.** Practical
  guidance from working implementations converges on **~8-12 fps** display rate with
  steady (not accelerating/flashy) animation and a **stable quiet zone** every frame —
  higher rates measurably increase the probability of a phone camera missing a frame
  because camera exposure time effectively caps the frames it can cleanly resolve below
  the nominal display fps. Testing in the wild found that at ~11 fps with certain
  chunk-size/redundancy ratios, miss probability was "quite high," producing indefinite
  re-scan loops.
- **Indefinite "waiting for missing frame" loops are the most commonly reported real-world
  failure**, per Sparrow Wallet issues (#1814 "QR signing loops on Ubuntu — Coldcard Q &
  Jade Plus multisig") — the progress UI shows partial completion and never resolves. This
  is a platform-conditional bug (worked on macOS, failed on Ubuntu with the same hardware),
  implicating camera driver/exposure behavior, not just the fountain-code math. **Design
  implication**: the UI must have an explicit timeout + "restart transfer" affordance, not
  just a spinner — do not assume decode will always terminate.
- **Format mismatches between encoder and decoder cause silent total failure, not partial
  failure.** Sparrow could parse Specter's `crypto-psbt`/UR format QR and SD-card export but
  failed specifically on Specter's "text" format QR — i.e. per-implementation payload/
  encoding-scheme drift (even within the "same" fountain-code family) breaks
  interoperability entirely. For this feature, since encoder and decoder are both
  SteleKit-controlled, this is more a **versioning/protocol-compatibility** risk than an
  interop risk — but it still means the wire protocol needs an explicit version byte from
  day one, since "SteleKit v1 phone -> SteleKit v2 phone" is a real cross-version scenario
  (app updates roll out unevenly) even without third-party interop.
- **Fountain codes (Luby transform / rateless codes) genuinely solve out-of-order/missed
  frames** — this part of the architecture is well-validated by the UR ecosystem's
  production use for PSBT transfer. The risk is in engineering the *decoder UX* around it
  (timeout, retry, progress-that-can-stall), not the underlying coding theory.
- **ZXing decode false positives / failures** are a known long-tail issue: `--try_harder`
  style aggressive decode modes have documented false-positive extraction of a *wrong*
  barcode from a frame containing multiple encoded regions or crease/glare artifacts, and
  image rotation from `ImageProxy` must be corrected before handing to the
  `LuminanceSource` or decode silently fails. For a QR-only, single-code-per-frame use
  case this is lower risk, but **frame rotation handling (portrait/landscape, front vs
  back camera) must be verified per platform** or decode will intermittently fail with no
  clear error.

## 2. Stack-specific risks (ZXing, CameraX, AVFoundation, getUserMedia)

- **CameraX (Android)**: `AndroidCameraProvider` already pays a `delay(400L)` after
  `bindToLifecycle` before triggering capture — this is CameraX's known auto-exposure/
  auto-focus settle time. A live QR-scan use case must bind an `ImageAnalysis` use case
  once, then keep the pipeline running (`STRATEGY_KEEP_ONLY_LATEST` backpressure strategy is
  the standard pattern to avoid frame queue buildup when the analyzer can't keep up with
  sensor fps) — do not rebind per frame the way `capturePhoto()` does per shot.
- **Android 13+ granular media permissions**: `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`
  replace `READ_EXTERNAL_STORAGE` for *media library* access, but this feature primarily
  needs **`CAMERA`** (already handled by `AndroidCameraProvider`'s existing
  `ContextCompat.checkSelfPermission` + `requestPermission` callback pattern) — that
  pattern can be reused for the scan flow's permission gate even if the frame-stream API
  itself needs to be new.
- **iOS privacy manifest (`PrivacyInfo.xcprivacy`)**: since May 2024 App Store submissions,
  camera usage must be declared in the privacy manifest in addition to
  `NSCameraUsageDescription` in Info.plist — both are required, and the current
  `IOSCameraProvider` stub predates any real camera usage, so **neither exists yet in this
  repo** and both must be added when iOS capture is implemented, or App Store submission
  will be rejected independent of whether the code works.
- **Camera cold-start latency** compounds with the "waiting for missing frames" problem:
  if the receiving device's camera takes noticeable time to autofocus/auto-expose (CameraX
  settle time, AVFoundation session start latency), the first several fountain-code frames
  displayed on the sending screen may be missed before the receiver's camera has locked on
  — for a bounded (non-rateless-forever) frame loop this could push transfer time past the
  "30-60s for ~2KB" quality bar in the requirements, especially on repeat/first-scan
  attempts.
- **Desktop (JVM) has no webcam library today** (`WebcamCameraProvider` is a stub) —
  bringing in JavaCV/webcam-capture is a **new third-party dependency**, which brushes
  against "no new network dependency" but is not literally a network dependency — worth
  flagging as a scope question for Phase 3 (is a webcam library acceptable, and which one,
  license-compatible with the rest of the stack).
- **WASM/Web `getUserMedia`**: needs a `<video>` element + `<canvas>` frame sampling loop
  (no native ImageAnalysis equivalent) — common real-world gotchas are autoplay-policy
  blocking (`video.play()` must be inside a user-gesture handler) and permission-prompt
  timing when a page's own scan loop conflicts with a simultaneous file-input flow (this
  exact bug is documented against `html5-qrcode`). Given Web is explicitly one of the 3
  target platforms per requirements ("Desktop (JVM), Android, and iOS" — note Web/WASM is
  *not* listed in Users/Consumers despite being a KMP target elsewhere in this repo), Phase
  3 should confirm whether Web is actually in scope for this feature at all, since
  requirements.md's own "Users/Consumers" section omits Web.

## 3. Data-integrity pitfalls reconstructing Logseq markdown from a lossy transport

Checked against `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`
(`Validation` object, `Page`, `Block` init blocks):

- **UUID format is validated but not integrity-checked against corruption**:
  `Validation.validateUuidString` only checks charset (`a-zA-Z0-9-`) and length ≤36 — a
  bit-flip during transport that turns one valid-looking UUID into another
  equally-valid-looking (but wrong) UUID string will **pass model validation silently**.
  Since `Block.parentUuid`/`leftUuid` encode the outline tree structure, a corrupted-but-
  well-formed UUID reference could re-parent a block to the wrong parent, or break the
  linked-list `leftUuid` ordering, without tripping any `require()` in the model. **The
  wire protocol needs its own integrity check (checksum/CRC per chunk, plus one for the
  whole reassembled payload) — model-level validation is not a substitute for
  transport-level integrity**, because the model's job is "is this a plausible value," not
  "did this survive transport intact."
- **Content length ceilings could silently reject legitimately-reassembled data**:
  `MAX_CONTENT_LENGTH = 10_000_000` and `MAX_STRING_LENGTH = 10_000` — reassembly must
  make sure any concatenation/joining step (e.g. block content reconstructed from multiple
  chunks, or property values) respects these ceilings *before* constructing the `Block`/
  `Page`, or the `require()` in the `init` block throws an uncaught `IllegalArgumentException`
  deep in decode/import — this needs to be caught and turned into a
  `DomainError` (see §5) rather than propagating as a raw exception.
- **Control-character / null-byte rejection is strict**: `validateString` rejects ` `
  and most C0/C1 control characters (allowing only `\n`/`\r`/`\t` when
  `allowWhitespace = true`). QR codes commonly encode payloads as Base45 (per the ISO
  18004 / EU DGC convention) or raw binary — **whatever encoding is chosen for the wire
  format must be decoded back to a valid UTF-8 markdown string before it ever reaches
  `Block`/`Page` construction**, and multi-byte UTF-8 sequences split across a chunk
  boundary (e.g. mid-codepoint truncation if chunking is done byte-wise without care) will
  produce either invalid UTF-8 or valid-but-wrong replacement characters (`�`) that
  silently corrupt content while still passing `validateContent`.
- **Page name validation forbids bare `..` path segments and backslashes**
  (`Validation.validateName`) — if the transferred page's name is itself corrupted by a
  transport error in a way that produces `..`-as-a-segment or a backslash, the import will
  throw at `Page` construction time; this is actually a *good* backstop (traversal
  protection) but the decoder needs a designed failure path for it (surface "page name
  corrupted, retry" to the user) rather than a generic crash.
- **`contentHash` (SHA-256 of normalised content) is exactly the mechanism to use for
  round-trip verification** — `Block.contentHash` already exists and is used elsewhere
  (`DiskConflictBlockMatcher`, `MarkdownPageParser`, `DiffMerge`) for detecting whether
  content changed. The decoder should compute/compare this hash as the **gate for "did
  this page transfer losslessly"** (matching the Success Metric "lossless markdown
  reconstruction") rather than inventing a new verification mechanism — but note it's
  computed over *normalised* content, so byte-for-byte transport verification (CRC per
  frame) is still needed at a lower layer; the content hash verifies the *parsed* result,
  not the *raw bytes in flight*.
- **Page properties and block properties are `Map<String, String>`** with each key/value
  independently validated (`validateName`/`validateContent`) — a fountain-code reassembly
  that reconstructs markdown text and then re-parses it through the existing
  `OutlinerPipeline`/`MarkdownPageParser` (rather than trying to reconstruct `Block`/`Page`
  objects directly from the wire format) sidesteps most of this risk, since the existing
  parser already handles property-block boundaries and content escaping correctly for
  every other markdown ingestion path (file load, git pull, etc.) — **reusing the existing
  parser as the sole path from "reassembled markdown string" to `Block`/`Page` is the
  lowest-risk design**, versus inventing a second markdown->model mapping specific to this
  transport.

## 4. This repo's own hard-won lessons — what to design against up front

Straight from CLAUDE.md, mapped onto this specific feature:

- **Uncaught `Throwable` in long-lived coroutine scopes kills the Android process.** The
  camera-frame-processing loop (whatever new abstraction replaces/wraps `CameraProvider`
  for streaming) is exactly this pattern: a standing collector running for the 30-60s (or
  longer, on repeat attempts) transfer window. It must run inside a scope with a
  `CoroutineExceptionHandler` attached (per `StelekitViewModel.scope` /
  `GraphLoader.parallelScope` precedent), and must `catch (e: Throwable)`, not
  `catch (e: Exception)` — a `try_harder`-style aggressive ZXing decode path or a
  malformed-frame `OutOfMemoryError` (large camera frame buffers are a classic OOM vector
  on Android) should degrade to a UI error state, not crash the app.
- **`rememberCoroutineScope` must not escape composition.** The QR scanner screen will
  want a scan loop (start camera -> collect decoded frames -> reassemble). That loop's
  scope must be owned internally by a non-Compose class instantiated via
  `remember { ScannerController() }` with its own `CoroutineScope(SupervisorJob() +
  Dispatchers.Default)`, never a `rememberCoroutineScope()` value threaded into it — this
  is the exact violation pattern CLAUDE.md calls out by name.
- **Graph-scale unbounded reads / writes.** Not directly triggered by decoding a single
  page, but if this feature is later extended to transfer *multiple* pages in one session
  (requirements.md explicitly says "page (or set of pages)"), writing N reconstructed pages
  back to the DB must go through `DatabaseWriteActor` per-page/per-block (as everywhere
  else in the codebase), not a single unbounded transaction batching all N pages' blocks —
  otherwise this reintroduces the same class of problem the `getAllPages()` removal was
  designed to prevent, just on the write side instead of the read side.
- **`@DirectSqlWrite` / `RestrictedDatabaseQueries` gate** — the decoder's "commit
  reconstructed page to DB" step is a mutating write and must route through
  `DatabaseWriteActor.execute { }` or a typed method (`savePage`/`saveBlock`), never call
  `SteleDatabaseQueries` insert/update directly. This is an easy thing to get wrong if the
  import path is written as "new" code rather than reusing `GraphLoader`'s existing
  markdown-import machinery — reusing `GraphLoader`'s existing page-write path (rather than
  writing a bespoke "import from QR" writer) gets this for free.
- **Android `Application.onCreate` `catch (e: Throwable)` precedent** generalizes: any new
  platform init code this feature adds (e.g. registering a new CameraX use case, JavaCV
  native lib load on desktop) should follow the same catch-`Throwable`-not-`Exception`
  discipline, since native camera library loading failures are exactly the
  `UnsatisfiedLinkError`/`NoClassDefFoundError` class CLAUDE.md warns about.

## 5. Security pitfalls at the decoder's trust boundary

The requirements explicitly frame the air-gap as the value proposition, which makes the
*decoded payload* the one truly untrusted external input in this whole feature — treat it
with the same suspicion as any network input, not as "data from another SteleKit," because
a QR code displayed on a screen can be substituted, adversarially generated (printed
sticker over the real one, timing-based frame injection into someone else's transfer), or
simply corrupted in a way that happens to still be well-formed.

- **This is precisely what Arrow `Either` is for at a trust boundary**: every stage of
  decode — QR raw-bytes decode, fountain-code chunk reassembly, payload deserialization,
  markdown parse, model construction — should return `Either<DomainError, T>` (a new
  `DomainError.TransferError` or similar sealed interface, following the existing
  `SensorError`/`BleError` pattern in `DomainError.kt`) rather than throwing. Concretely:
  `Validation`'s `require()` calls throw `IllegalArgumentException` on invalid
  reconstructed content — the decoder must wrap `Page(...)`/`Block(...)` construction in a
  `try/catch (e: IllegalArgumentException)` (or pre-validate before construction) and map
  to a `Left`, exactly the "never let exceptions propagate raw" rule already stated for
  SQLite in CLAUDE.md, applied here to model construction instead.
- **Decompression / expansion-ratio bombs**: if the wire protocol includes *any*
  compression step (likely, to fit more markdown per QR frame budget), a malicious or
  corrupted payload could claim a small compressed size but decompress to something huge —
  classic zip-bomb pattern. Any decompression step must enforce a **hard output-size cap
  before/while decompressing** (not just after — a naive "decompress fully then check
  length" is itself the vulnerability), independent of and prior to `MAX_CONTENT_LENGTH`
  model validation.
- **Chunk-count / total-size claims in the fountain-code header are attacker-controlled
  input** — if the protocol's frame header declares "there are N total chunks of size S,"
  the decoder must bound N*S against a sane max *before* allocating reassembly buffers
  sized to the claimed total, or a malicious/corrupted first frame could trigger an
  unbounded allocation (OOM — see §4's Throwable-handling requirement, which becomes the
  second line of defense if the first-line size cap is missed).
- **Path/name injection via reconstructed page name or file path**: `Page.filePath` is
  validated only via `validateContent` (length + control chars), not `validateName`'s
  traversal check — if the decoder derives a filesystem write path from a
  QR-transported page name/filePath, it must run that value through the *traversal-safe*
  path (`validateName`, which explicitly rejects `..` segments and backslashes) before
  ever touching the filesystem, not just `validateContent`. This is the same class of bug
  the recent `PlatformFileSystem.resolveSafToRextendPath` / SAF-URI git fix (see recent
  commit history: `fc5661eb14 fix(git): resolve saf:// URIs to real paths before passing to
  JGit`) addressed for a different input channel — worth an explicit regression test here
  too, given this repo has already been bitten by path-resolution trust-boundary bugs
  once.
- **No existing feature-flag framework in the codebase** — a `grep` for
  `featureFlag`/`FeatureFlags`/`isEnabled` patterns across `commonMain` found no dedicated
  feature-flag module (hits were unrelated fields like editor persistence flags, jank-stats
  flags, tag-suggestion settings — all local boolean settings, not a generic gating
  mechanism). Requirements.md calls for "Feature-flagged, opt-in UI entry points on both
  ends" — Phase 3 planning needs to decide the mechanism (a simple settings-backed toggle
  is likely sufficient given no existing infra to plug into) rather than assuming a
  flag system already exists to hook into.
- **Trust framing risk**: because the marketing/UX framing is "air-gapped and therefore
  safe," there's a real risk the implementation under-invests in validating the decoded
  payload on the assumption "it's just markdown from another SteleKit instance, why would
  it be hostile." The correct mental model for Phase 3/5 is: **the air-gap is a network
  property, not a trust property** — the payload still crosses a fully adversarial-capable
  channel (a camera can be pointed at anything, including a maliciously crafted QR
  sequence), so decoder-side validation rigor should be undiminished by the air-gap
  framing, not relaxed because of it.

## Summary of research sources (external)

- Blockchain Commons Animated QR / UR developer docs and `Airgapped-Wallet-Community`
  issue #4 (fountain-code rationale for animated QR).
- Sparrow Wallet GitHub issues #1814 (indefinite QR-scan loop, Ubuntu-specific) and #662
  (format-specific parse failure between Specter DIY and Sparrow).
- ZXing GitHub issue #553 (false-positive multi-barcode extraction under
  `--try_harder`); CameraX + ZXing integration write-ups on `ImageProxy` rotation and
  `LuminanceSource`.
- Android Developers: "Behavior changes: apps targeting Android 13" (granular media
  permissions); community write-ups on CameraX cold-start/bind latency.
- Apple Developer docs: `NSCameraUsageDescription`; 2024 privacy-manifest
  (`PrivacyInfo.xcprivacy`) requirement for camera usage declared apps.
- `divan.dev` fountain-code / animated-QR engineering write-ups (fps vs. camera-lock
  trade-off, chunk-size/redundancy tuning).
- OWASP/security write-ups on malicious QR payload categories; zip-bomb/decompression-bomb
  general pattern.
- html5-qrcode GitHub issues (getUserMedia permission/autoplay interaction with
  simultaneous image-upload mode) as representative of Web/WASM camera-scan gotchas.
