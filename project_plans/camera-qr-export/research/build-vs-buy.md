# Build vs. Buy — camera-qr-export

Agent 6, Phase 2 research. Evaluates whether the fountain-code/animated-QR transport and
the QR generate/scan layer should be built from scratch, sourced from an existing library,
or forked/adapted, given this repo's constraints: KMP (Android/JVM/iOS/Web), Gradle+Bazel
dual build, solo developer, no new network dependency, air-gapped by design.

## 1. Fountain-code / animated-QR transport layer

### `bc-ur` (Blockchain Commons Uniform Resources) — the de-facto standard

`bc-ur` (BCR-2020-005) is the spec used by hardware wallets (Keystone, Foundation
Passport, Jade) and desktop/companion apps (Sparrow, Specter) to move PSBTs and seeds
over animated QR. It is exactly the prior art requirements.md calls out.

- **Reference implementation language**: C++ (`BlockchainCommons/bc-ur`), the canonical
  reference. Blockchain Commons also maintains/endorses a Swift port (`URKit`,
  `BlockchainCommons/URKit`) and a Rust wrapper (`bc-ur-rust` around the `ur` crate,
  published on crates.io). The community (not BC) maintains a JS/TS port
  (`ngraveio/bc-ur`, forked from an earlier `BlockchainCommons/URKit`-adjacent JS
  effort) used by several wallet UIs.
- **No official JVM/Kotlin port from Blockchain Commons.** The closest thing found is a
  **third-party pure-Kotlin-Multiplatform reimplementation**: `bcur-kotlin` by a solo
  maintainer (`gorunjinian`), published to Maven-adjacent registries and used inside
  their own Android Bitcoin-signing app `MetroVault`. It targets Android/JVM and
  Kotlin/Native (iOS, macOS, watchOS) — no wasmJs/browser target confirmed. It is
  MIT-licensed. This is a single-maintainer, low-visibility library (bundled as a
  vendored dependency inside another solo dev's wallet app, not an independent
  widely-adopted package) — worth evaluating directly but not something to bet the
  project on without vetting.
- **License**: Blockchain Commons's own repos (bc-ur C++, URKit Swift) are typically
  BSD-2-Clause-Patent. `bcur-kotlin` (third-party) is MIT. Both are compatible with
  SteleKit's Elastic-2.0 licensing (permissive-in, no copyleft conflict).
- **Maintenance**: Blockchain Commons is an active org (Christopher Allen et al.) with a
  large surrounding "Research" spec corpus (BCR-2020-005 through BCR-2020-012 and
  later), still referenced/updated. The C++/Swift reference libs are stable rather than
  fast-moving — this is a mature, frozen-ish spec, which is actually a point in its
  favor for a from-scratch reimplementation (the wire format isn't going to shift under
  you).
- **Spec documentation quality**: Good. BCR-2020-005 (UR) and BCR-2020-012 (Bytewords)
  are readable, versioned spec documents on GitHub, plus a `developer.blockchaincommons.com`
  reference site (Uniform Resources, Bytewords, Animated QRs pages) that explains the
  fountain-encoder/decoder design (Luby-transform-style mixing of fragments once
  `seqNum > seqLen`, xor-based reconstruction) in prose, with the C++ `ur-encoder.hpp`/
  `ur-decoder.hpp` headers as the executable ground truth. This is enough to do a
  faithful **from-scratch Kotlin implementation of the spec** (not a code fork) with
  reasonable confidence, cross-validated against the C++/Swift/Rust test vectors that
  ship in those repos.

### General-purpose JVM fountain-code libraries (lower-level alternative)

- **OpenRQ** (`openrq-team/OpenRQ`) — pure Java implementation of RaptorQ (IETF RFC
  6330), Apache-2.0 licensed. It is a real, spec-compliant RaptorQ codec, but the
  project has been dormant for years (last substantive activity years back) and RaptorQ
  itself is patent-encumbered by Qualcomm (a real concern for a project that wants to
  avoid entanglements) — Luby Transform / `bc-ur`'s LT-style fountain code is patent-free
  by comparison and is the actual encoding hardware wallets standardized on.
- No other actively-maintained pure-Kotlin or plain-JVM LT-code library turned up in
  search (the one Kotlin "fountain codes" hit was `xmartlabs/fountain`, which is an
  unrelated Android paging library that happens to share the name — false positive to
  rule out explicitly).
- **Verdict on this sub-option**: not attractive. Adopting OpenRQ/RaptorQ means carrying
  a different, heavier FEC scheme than the one the QR-transfer ecosystem actually uses,
  for no compatibility benefit (SteleKit devices only ever talk to each other, so there's
  no interop upside to RaptorQ over LT), plus the patent overhang and a dormant upstream.

### Verdict — fountain-code/UR transport layer

**Recommended: implement the `bc-ur` *spec* natively in Kotlin (commonMain)**, using the
BCR-2020-005/012 documents and the C++/Swift/Rust reference implementations as the
correctness oracle (port their test vectors into unit tests), rather than forking any
existing codebase. Treat `bcur-kotlin` (gorunjinian) as a **reference/cross-check**, and
optionally as a **fork/vendor candidate** if a spike shows its fountain-encoder and CRC32
checksum logic already round-trip clean against the official test vectors — see §5.

## 2. QR generation and scanning library

### ZXing (`com.google.zxing`)

- **Maturity/license/maintenance**: Apache-2.0. Still actively released (releases as
  recent as Nov 2025 found in search). It is the long-standing standard JVM/Android
  barcode library; `zxing-js` (unrelated JS port) is explicitly in maintenance-only mode,
  but that doesn't affect the JVM/Android `zxing/zxing` repo this project would consume.
- **`core` module is pure Java, zero dependencies** — no Android SDK requirement. This
  matters directly for this repo: it means the *same* `com.google.zxing:core` artifact
  can back both `jvmMain` (desktop) QR generation/decoding and, together with
  `zxing:android-core`/CameraX frame analysis, `androidMain` scanning — one dependency,
  two thin platform wrappers.
- **KMP artifacts**: none — ZXing is JVM/Android-only, as expected for a 2007-era Java
  library. **This repo will need `expect`/`actual` wrapping per platform**, exactly as it
  already does for `CameraProvider` (`kmp/src/commonMain/.../platform/sensor/
  CameraProvider.kt` with `AndroidCameraProvider`, `WebcamCameraProvider` (JVM),
  `IOSCameraProvider`, `WebCameraProvider` (wasmJs), and a `NoOpCameraProvider` for
  tests) — the QR encode/decode layer should follow the identical expect/actual pattern
  alongside it.
- **Per-platform actuals**:
  - **JVM (desktop)**: ZXing `core` directly for both encode (`MultiFormatWriter` →
    `BitMatrix` → image) and decode (`MultiFormatReader` over a `BufferedImage`/
    `LuminanceSource`) via the existing `WebcamCameraProvider` frame feed.
  - **Android**: ZXing `core` + `android-core` (or Google's ML Kit Barcode Scanning,
    which search results note is "substantially better and faster than ZXing" on
    Android) fed by CameraX (`androidx.camera:camera-core` is already a dependency, per
    `AndroidCameraProvider.kt`) `ImageAnalysis` frames. MLKit is already used elsewhere
    in this repo (`com.google.mlkit:image-labeling`, `text-recognition`, `genai-prompt`
    are all already declared in `kmp/build.gradle.kts`/`MODULE.bazel`), so MLKit Barcode
    Scanning would be a natural, low-friction addition on Android specifically — while
    still needing ZXing (or a Kotlin-native encoder) for QR *generation*, since MLKit only
    scans.
  - **iOS**: no ZXing port needed at all — **native CoreImage `CIFilter.qrCodeGenerator()`**
    (`CIQRCodeGenerator`) for generation, and **Vision `VNDetectBarcodesRequest`**
    (filtering `VNBarcodeObservation.symbology == .qr`) for scanning, both zero-dependency
    Apple frameworks, callable from Kotlin/Native via cinterop (this repo already has a
    cinterop precedent for `FoundationModelsShim`/`PingShim` in `iosApp/`).
  - **Web (wasmJs)**: the `BarcodeDetector` (Shape Detection API) is not reliably
    available in Safari/iOS-embedded WebViews; a JS-side `jsQR` (decode) + a small QR
    encoder (e.g. `qrcode-generator`/hand-rolled, since encoding a short byte string is
    much simpler than decoding a photographed one) called via Kotlin/JS interop is the
    pragmatic fallback, matching the existing `WebCameraProvider` wasmJs actual.

### Verdict — QR layer

**Recommended.** Adopt ZXing `core` for JVM/Android (Apache-2.0, zero-dep, already the
obvious default), and use native platform APIs for iOS (Vision/CoreImage) and Web
(jsQR + a small encoder), wrapped behind a new `QrCodec` expect/actual pair that sits
next to the existing `CameraProvider` in `platform/sensor/`. This is standard KMP
practice for this exact class of problem (see `code-kmp` skill guidance and the
`kmp-bits.com` / Scanbot SDK write-ups on barcode scanning in Compose Multiplatform,
which converge on the same per-platform-native-API pattern since no uniform KMP
barcode API exists as of today).

## 3. SaaS / managed API

**Not applicable — confirmed, not skipped.** The requirement is explicitly an
air-gapped, no-network, camera-to-screen optical transport (Constraints: "purely
optical... no network transport"; Alternatives Considered explicitly rules out git sync
and file-export+share-sheet for needing network/accounts). Any hosted API — QR
generation-as-a-service, a cloud OCR/decode API, a managed relay — would reintroduce
exactly the network dependency the feature exists to avoid, and would violate the
"No new network dependency" constraint outright. There is no reasonable interpretation
of this requirement where a SaaS component belongs in the data path. (A hosted API could
theoretically play a role in *tooling*, e.g. an internal test-vector service, but that's
speculative and out of scope for this decision.)

## 4. LLM-generated implementation vs. battle-tested library/spec

This is the crux of the build-vs-buy call, and the two layers should be treated
**differently**:

- **The erasure-coding math (fountain encoder/decoder, XOR-chain reconstruction, degree
  distribution, CRC/checksum validation) is exactly the class of code where a
  bespoke/LLM-authored implementation is reckless to ship without a spec to check
  against.** Fountain codes have subtle correctness properties — degree-distribution
  bugs, off-by-one part indexing, incorrect fragment-mixing when `seqNum > seqLen`,
  checksum/CRC mismatches on the reconstructed payload — that manifest as "decodes fine
  in every unit test with synthetic data" and then silently corrupt or fail to converge
  on real photographed frames with realistic loss patterns (motion blur misses, partial
  occlusion, camera autofocus hunting). This is precisely the "looks correct in tests,
  fails in the field" failure mode requirements.md's own framing warns about, and it's
  also the layer with a lossless-round-trip **gate** metric (not just a quality bar) —
  a single-bit reconstruction bug fails the acceptance criterion outright, unlike a UI
  bug which just looks bad.
- **The mitigation is not "don't use an LLM," it's "anchor to an external spec and
  cross-validate against independently-produced test vectors."** `bc-ur`/BCR-2020-005
  provides exactly that anchor: the C++, Swift, and Rust reference implementations all
  ship (or can be made to emit) matching encoded byteword strings and multipart UR
  sequences for the same input. An LLM-assisted Kotlin port is **acceptable and
  recommended** *as long as* the resulting implementation is checked against those
  cross-language test vectors bit-for-bit (encode a known payload in the C++/Rust ref
  impl, assert the Kotlin encoder produces an identical UR string; decode a captured
  reference multipart sequence and assert identical output), not just checked against
  its own round-trip. That converts "bespoke erasure-coding implementation, hope it's
  right" into "spec-conformance-tested port," which is a fundamentally different risk
  profile.
- **Where custom/LLM-generated code is entirely fine, with no special anchoring needed**:
  the Compose UI (full-screen animated QR display, frame-rate/timing loop, progress
  indicator), the CameraProvider-driven capture loop and frame throttling, the
  feature-flag/opt-in UI plumbing, and the platform `expect`/`actual` glue around ZXing/
  Vision/jsQR. Bugs here are visible, low-stakes, and caught by normal UI/manual testing
  — nothing here has fountain-code-style silent-corruption risk.
- **Recommendation**: implement the UR/fountain layer as a from-scratch Kotlin
  reimplementation of BCR-2020-005/012 (per §1), written with LLM assistance if useful,
  but gated behind a cross-language test-vector suite before it's trusted with real user
  data; treat everything else (UI, camera glue, feature flag) as ordinary application
  code with ordinary review/testing standards.

## 5. Fork or adapt

- **`bcur-kotlin` (gorunjinian)** is the one candidate close enough to be worth a
  concrete spike: pure Kotlin Multiplatform, MIT-licensed, already implements `bc-ur`
  fountain encoding and targets Android/JVM/Kotlin-Native. **Action before committing**:
  pull it into a scratch project, run the official Blockchain Commons test vectors
  (from the C++/Swift/Rust repos' test suites) through it, and check (a) correctness,
  (b) whether it has a wasmJs/JS target or would need one added, (c) code quality/test
  coverage given it's a single-maintainer library vendored inside someone else's wallet
  app rather than an independent, widely-used package. If it passes, **forking/vendoring
  it (with attribution, MIT permits this) is a legitimate time-saver** over a full
  from-scratch port — but it still needs the same cross-language test-vector validation
  from §4 before being trusted, whether it's forked or written new. If it fails
  correctness or platform-coverage checks, fall back to the from-scratch Kotlin
  reimplementation of the spec.
- **No other close "QR sneakernet" tool** (a full existing app doing exactly
  markdown-over-animated-QR for a note-taking tool) turned up in search — this
  combination (Logseq markdown export specifically) does not have prior art beyond the
  hardware-wallet PSBT-transfer domain that `bc-ur` itself comes from. Nothing to
  fork at the application level; only the transport-layer library candidate above.
- **For QR generation/scanning itself (§2)**: no forking is warranted — ZXing and the
  native Apple/Web APIs are consumed as ordinary dependencies/platform APIs, not forked.

## Dependency-management fit (Gradle + Bazel dual build)

Checked `kmp/build.gradle.kts` and `MODULE.bazel` to confirm how a new dependency would
actually land here:

- **Gradle**: add to `commonMain`/`jvmMain`/`androidMain` `dependencies { }` blocks as
  usual (e.g. `implementation("com.google.zxing:core:3.5.4")` in a shared source set for
  JVM+Android, since ZXing has no separate JVM/Android artifact split).
- **Bazel**: this repo uses `rules_jvm_external` via `MODULE.bazel`'s `maven.install(...)`
  — every Maven coordinate must be added to the `artifacts = [...]` list there, then
  `bazel run @maven//:pin` regenerates and the resulting
  `rules_jvm_external++maven+maven_install.json` lockfile must be committed (per the
  comment at `MODULE.bazel:135-139`). ZXing `core` is a plain, dependency-free Maven
  Central artifact, so this is a low-risk one-line addition with no transitive-version
  conflicts expected (unlike the AndroidX/Compose pins already fighting each other in
  that file).
- **`bcur-kotlin` (if adopted per §5)**: it is not on Maven Central under a well-known
  group; it would likely need to be resolved via **JitPack** (`https://jitpack.io`,
  already listed in this repo's `MODULE.bazel` `repositories = [...]`) pointing at the
  GitHub repo/tag, the same mechanism this repo already uses for other GitHub-hosted
  Maven artifacts. If it can't resolve cleanly via JitPack for Bazel/Coursier, that's
  itself a signal to prefer the from-scratch spec implementation instead, since it keeps
  the dependency graph simpler and avoids a JitPack single-point-of-failure for a
  security/correctness-sensitive component.
- **No iOS/Web dependency-management concern for the platform-native options** (Vision/
  CoreImage need no package manager entry at all; a `jsQR`-equivalent for wasmJs would
  be an `npm(...)` entry in `wasmJsMain`, mirroring the existing
  `npm("@sqlite.org/sqlite-wasm", ...)` pattern already used in `kmp/build.gradle.kts`).

## Summary table

| Component | Decision | Verdict |
|---|---|---|
| UR/fountain-code transport | From-scratch Kotlin port of BCR-2020-005/012 spec, cross-validated against C++/Swift/Rust reference test vectors; evaluate `bcur-kotlin` as a fork/vendor shortcut first | **Recommended** (build-to-spec, not "from scratch with no anchor") |
| General JVM fountain-code libs (OpenRQ/RaptorQ) | Different codec family, patent overhang, dormant upstream, no interop upside | **Not recommended** |
| QR generation/scanning — JVM & Android | ZXing `core` (+ MLKit Barcode Scanning as an Android-specific option) via new `expect`/`actual` `QrCodec`, mirroring existing `CameraProvider` pattern | **Recommended** |
| QR generation/scanning — iOS | Native CoreImage (`CIQRCodeGenerator`) + Vision (`VNDetectBarcodesRequest`) via cinterop | **Recommended** |
| QR generation/scanning — Web | `jsQR` (decode) + lightweight JS encoder via `npm(...)` in `wasmJsMain` | **Viable** (no better first-party API available today) |
| SaaS/managed API | N/A by design — would violate the air-gap/no-network constraint | **Not applicable** (confirmed) |
| LLM-generated fountain-code math with no spec anchor | Silent-corruption risk on a lossless-round-trip gate metric | **Not recommended** |
| LLM-assisted spec-conformant port, test-vector-gated | Converts risk into an ordinary tested-implementation problem | **Recommended** |
| Custom/LLM code for UI, camera loop, feature flag | Normal application code, no special risk | **Recommended** |
| Fork `bcur-kotlin` outright | Worth a validation spike; adopt if it passes cross-language test vectors and has/gets a wasmJs target | **Viable**, pending spike |
