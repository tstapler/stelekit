# Requirements: camera-qr-export

**Date**: 2026-07-11
**Type**: feature addition (pluggable transport framework + cross-platform UI)
**Complexity**: 4 — high-stakes / cross-cutting

**Scope note (added mid-research)**: the user has since asked for a **pluggable transport** design — QR/camera is the first transport, not the only one. WebRTC (local-network/direct-peer), Bluetooth, and audio (acoustic/ultrasonic modem) are named as candidate future transports. This changes the architecture shape (Phase 3 must design a `Transport` plugin abstraction) but does not change v1 delivery scope: QR/camera remains the only *implemented* transport for v1, sized to the existing Large (3–6 week) appetite. Building out WebRTC/Bluetooth/audio transports now would blow the appetite — see updated Rabbit Holes and Open Questions below.

## Problem Statement
SteleKit users have no way to move a page (or set of pages) between two SteleKit devices when there is no shared network, no shared cloud/git remote, and no Bluetooth pairing available or wanted — e.g. an explicitly air-gapped transfer, or two phones that have never been paired. There is currently no purely optical (camera-to-screen) transport in the app.

## Baseline
Today, moving a page between devices requires: (a) the existing git sync feature, which needs a shared git remote and network connectivity, or (b) manual file export followed by a network- or account-based share mechanism (AirDrop, cloud drive, USB, messaging apps). Both require a network, a paired transport, or a shared account. There is no zero-trust, no-network option.

## Users / Consumers
SteleKit end users on Desktop (JVM), Android, and iOS who want to transfer one or more pages to another SteleKit install without a network path between the two devices — e.g. air-gapped or trust-sensitive transfers, or ad hoc "beam this page to your phone" sharing.

## Success Metrics
- **Round-trip fidelity (gate)**: a page (or set of pages) exported on device A and scanned on device B reconstructs byte-identical (or lossless) Logseq markdown — verified by automated round-trip test fixtures plus a manual cross-device demo. (v1 implementation scope, per plan.md, cuts this to single-page transfer only — see plan.md's "v1 scope — explicit cuts" section. Multi-page/graph-set transfer is deferred to a follow-on; the single-page flow run once per page satisfies this gate for v1.)
- **Time-to-transfer (quality bar)**: a typical page (~2KB markdown) completes encode → display → scan → decode within roughly 30–60s under normal indoor lighting.
- **Product/adoption signal (added during plan.md's pre-mortem P1 fix — not purely technical)**: the two metrics above are fidelity/performance gates, not evidence anyone actually uses the feature. plan.md's "v1 Definition of Done" defines the adoption-side success signal: sustained real-world dogfooding (weeks of actual, non-demo use) backed by a local usage counter, plus an explicit 4-week kill-criteria time-box if real usage doesn't materialize. A reader of this document alone should not conclude success is purely technical — see plan.md's "v1 Definition of Done" section for the full adoption/kill-criteria definition.

## Appetite
Large (3–6 weeks)
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

## Constraints
- Solo-developer project; no fixed external deadline.
- Must reuse the existing cross-platform `CameraProvider` abstraction (`kmp/src/commonMain/.../platform/sensor/CameraProvider.kt`) rather than introducing a second camera API surface — Android, iOS, JVM/desktop, and WASM/JS implementations already exist (`AndroidCameraProvider`, `IOSCameraProvider`, `WebcamCameraProvider`, `WebCameraProvider`). **(Superseded, per plan.md ADR-002: Phase 2 research found `CameraProvider` is single-shot-photo-only with no continuous-frame API, and 3 of 4 platform implementations are non-functional stubs — reusing it as literally stated is infeasible for continuous QR scanning. plan.md introduces a deliberate, ISP-justified parallel `CameraFrameSource` interface instead of extending `CameraProvider`, reusing only its permission/availability patterns. This constraint is knowingly overridden, not silently violated.)**
- No new external network dependency — the whole point is that this works with zero network path between devices.

## Non-functional Requirements
- **Performance SLO**: 30–60s typical transfer time for a single page; degrades gracefully (more frames, not failure) for larger payloads.
- **Scalability**: protocol must be chunk-based so it scales from one page to a full graph export without a redesign, even though v1 UI targets one/few pages.
- **Security classification**: internal/user-data — content is the user's own notes; the feature's value proposition *is* the air-gap (no network transport, no cloud intermediary), so the encode/decode path itself must not silently fall back to any networked transport.
- **Data residency**: not applicable — no network transmission involved.

## Scope
### In Scope
- A **transport-agnostic wire protocol**: page markdown → chunked, forward-error-corrected (fountain-code-style) encoding into bounded-size frames. The chunk/FEC/reassembly layer must not assume QR or any other specific physical medium — it operates on opaque byte frames.
- A **`Transport` plugin interface** (send + receive sides) that the chunk layer sits on top of, so a transport is "implement two functions: emit a frame, receive a frame" — this is the extension point for WebRTC, Bluetooth, and audio transports later, without touching the protocol/reassembly layer.
- **One concrete transport implementation for v1: QR/camera.** Encoder renders chunks as an animated QR frame sequence, displayed full-screen. Decoder consumes the existing `CameraProvider` live feed, decodes QR frames (capture/dedup, tolerant of missed/out-of-order frames), and hands reassembled bytes to the protocol layer.
- Reassembled markdown is parsed back into SteleKit's page/block model via the existing outliner pipeline.
- Cross-platform send AND receive on Android, Desktop (JVM), and iOS, built on the existing `CameraProvider` abstraction for the QR transport. **(v1 scope cut, per plan.md ADR-003: v1 ships Android as the only receiver — Desktop and iOS send in v1, but their receive-side camera streaming is deferred to follow-on Epics 4.3/4.4, since only Android has a working continuous-camera path today. See plan.md's "v1 scope — explicit cuts" section.)**
- A feature-flagged, explicitly opt-in UI entry point ("Export via camera" / "Import via camera") on both ends, structured so the UI's transport selection is a plugin list of one (QR) rather than a hardcoded QR-only screen.

### Out of Scope
Not hard-restricted by the user — evaluated openly rather than pre-cut. The following are the highest-risk scope expansions and are flagged in **Rabbit Holes** below for Phase 3 planning to explicitly size against the Large appetite; if they don't fit, Phase 3 should cut them first rather than slip the timeline:
- Real-time bidirectional live sync / conflict merge (as opposed to a one-directional export-then-import per session — running the flow twice, once each direction, is in scope; automatic two-way merge is not assumed).
- Embedded images/attachments inside the optically-transferred payload (large binary payloads multiply frame count sharply).
- **Implementing** any transport beyond QR/camera (WebRTC, Bluetooth, audio) — these are explicitly deferred to future work. Only the *interface* they will plug into is in scope for v1.

## Rabbit Holes
- **Camera decode reliability**: real-world lighting, motion blur, screen glare, and display refresh vs. camera frame-rate mismatches can all corrupt frame capture — this is why forward error correction (fountain codes / rateless codes), not just chunking, is called out as in-scope rather than a stretch goal.
- **Payload density vs. frame count trade-off**: raw base64-of-markdown wastes QR capacity; an efficient binary encoding (and possibly compression) is likely necessary to keep frame counts — and thus transfer time — reasonable for anything beyond a trivially short page.
- **"Any ↔ any" platform scope**: Android, Desktop, and iOS all need both send and receive paths working, which is 6 send/receive combinations to validate, not 2 — Phase 3 should sequence which pairing is proven first.
- **Attachments and bidirectional sync** (see Out of Scope) — both sound like natural extensions but could each independently consume the remaining appetite; treat as explicitly separate epics if pursued.
- **Full-graph export at scale**: the protocol must not architecturally block scaling beyond one page, but a full 8,000-page graph transfer is not a v1 deliverable — avoid over-building for that case at the expense of the one/few-page path.
- **Speculative transport abstraction**: designing a `Transport` interface against zero non-QR implementations risks guessing wrong about what WebRTC/Bluetooth/audio actually need (e.g. audio transports are likely much lower-bandwidth and may need a different framing than QR's frame-per-image model, WebRTC is bidirectional/stateful in a way QR's one-way display isn't). Phase 3 should keep the interface minimal (frame-in/frame-out, not feature-rich) and validate it can plausibly fit a second transport on paper, without building that second transport now.

## Alternatives Considered
- **Existing git sync**: already in the codebase, but requires a shared remote and network connectivity — does not satisfy the air-gap requirement.
- **File export + share sheet (AirDrop/cloud/USB)**: works today but requires either a network path, a shared account, or physical media — not purely optical/offline.
- **Prior art to mine in Phase 2 research**: animated-QR / fountain-code transfer protocols used by air-gapped hardware wallets (e.g. Blockchain Commons UR, Specter Desktop ↔ Sparrow PSBT transfer, Keystone/ColdCard-style QR flows) are the closest existing designs for "chunked data over animated QR with lossy camera capture" and should be the primary literature-review target.

## Feasibility Risks
- Camera capture reliability under non-ideal real-world conditions (addressed via FEC — see Rabbit Holes).
- iOS build/signing and camera permission flow have less day-to-day exercise in this codebase than Android/Desktop, even though `IOSCameraProvider` already exists — extra validation time should be budgeted there.
- QR frame rate vs. camera capture frame rate synchronization (display refresh, exposure/motion blur at speed) is an empirical tuning problem, not a design-time-solvable one — plan for a hardware-in-the-loop testing pass, not just unit tests.

## Observability Requirements
Standard structured logging of transfer attempts (start/end, frame counts sent vs. frames successfully decoded, retries, final success/failure) sufficient to debug a failed transfer after the fact. No new alerting/oncall infrastructure needed — this is a user-initiated, foreground-only feature with no background service.

## Risk Control
Ship behind a feature flag; dogfood internally across all three platforms before enabling broadly. The flag also gates the UI entry points themselves, so the feature has zero exposure until explicitly enabled per the flag rollout plan.

## Open Questions
- What FEC/fountain-code scheme should the protocol adopt (custom minimal implementation vs. adapting an existing rateless-code design like Blockchain Commons' `bc-ur`)? → Phase 2 research.
- What is the maximum practical QR payload size (version/error-correction level) that still scans reliably from a phone screen displayed at arm's length? → Phase 2 research + empirical spike.
- Should the wire format be SteleKit-specific or interoperable with an existing open animated-QR standard (for potential reuse with other tools)? → Phase 3 planning decision, informed by Phase 2 research.
- Sequencing: which platform pairing (e.g. Desktop→Android) should be proven first, before extending to the full any↔any matrix? → Phase 3 planning.
- What should the minimal `Transport` plugin interface look like so QR/camera is a clean implementation of it without over-fitting to QR's specific frame-per-image model, given WebRTC/Bluetooth/audio are named future transports but none are being built in v1? → Phase 3 planning.
