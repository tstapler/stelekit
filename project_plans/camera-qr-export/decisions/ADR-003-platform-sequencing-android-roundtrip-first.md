# ADR-003: Platform sequencing — Android round-trip is the v1 gate; Desktop/iOS send-only in v1; Desktop/iOS receive deferred

**Status**: Accepted
**Date**: 2026-07-11
**Feature**: camera-qr-export
**Resolves**: requirements.md Open Question "which platform pairing should be proven first?" and Tension #1 (appetite vs. any↔any scope)

## Context

The rabbit-hole "any↔any = 6 send/receive combinations" is **asymmetric**:

| Side | Platform readiness | Risk |
|------|-------------------|------|
| **Send** (full-screen QR render, no camera) | Pure Compose Canvas, `commonMain`, works everywhere | Low on all 4 |
| **Receive** (continuous camera capture) | **Android only** has a working camera today; JVM=`NoOpCameraProvider`, iOS=stub, WASM=`NoOpCameraProvider` | **3 of 4 are greenfield platform builds** |

Building continuous camera capture from scratch on JVM (new webcam dependency —
JavaCV/webcam-capture), iOS (AVFoundation cinterop + `PrivacyInfo.xcprivacy` +
`NSCameraUsageDescription`), and WASM (`getUserMedia` + autoplay-policy gotchas)
is each a substantial platform work item. Taken literally as "all 6 combos in v1,"
this blows the Large (3–6 week) appetite. requirements.md is explicit: if scope
doesn't fit the appetite, **cut scope, do not move the deadline.**

Note also: requirements.md Users/Consumers names only **Desktop, Android, iOS** —
Web is not listed (see ADR-005).

## Decision

Sequence by risk. v1 delivers a **complete, dogfoodable round trip on Android**
plus **send-only** on the two zero-camera-risk desktop/mobile platforms:

**v1 (ships within the appetite):**
1. Layers 1 & 2 (protocol codec + QR codec) — platform-agnostic, all targets.
2. **Android send + Android receive** — the full round-trip fidelity gate
   (success metric: byte-identical Logseq markdown A→B).
3. **Desktop (JVM) send** — encode-only; full-screen QR render needs no camera.
4. **iOS send** — encode-only; needs no camera.

**Explicitly deferred to follow-on phases (named, not silently dropped):**
5. **Desktop receive** — requires a new JVM webcam dependency; own phase.
6. **iOS receive** — requires AVFoundation `ImageAnalysis`-equivalent cinterop +
   App Store privacy manifest; own phase.
7. **Web/WASM send and receive** — out of scope entirely (ADR-005).

The v1 acceptance gate is: an Android device can send to another Android device
AND a Desktop-or-iOS-sent stream can be received by an Android device. That last
combination validates that the send side is genuinely platform-independent while
only requiring one working receiver.

## Consequences

**Positive**
- Fits the appetite: one hard receiver (Android, which already has CameraX) plus
  three cheap senders, instead of four hard receivers.
- Delivers a real, demoable air-gap transfer (the success metric) in v1.
- The send side being cross-platform from day 1 proves the wire format is not
  Android-specific, de-risking later Desktop/iOS receive work.

**Negative / trade-offs**
- A Desktop user cannot *receive* into their desktop in v1 (they can send from it,
  and receive on their phone). Documented limitation; the primary JTBD ("get this
  page onto my *other device* right now") is satisfied phone-side.
- Two follow-on phases carry real cost. Accepted and scoped as Phase 4 (Desktop
  receive) and a post-appetite phase (iOS receive) rather than hidden.

**Rejected alternatives**
- *All 6 combos flat in v1* — rejected: blows the appetite; three greenfield camera
  stacks.
- *iOS-first round trip* — rejected: iOS camera is the least-exercised path in this
  repo and needs App Store privacy plumbing; highest-risk first is wrong when a
  lower-risk full round trip (Android) exists.
- *Desktop-first round trip* — rejected: desktop has no webcam library at all today;
  a stub-to-working webcam build is strictly more work than using Android's existing
  CameraX.
