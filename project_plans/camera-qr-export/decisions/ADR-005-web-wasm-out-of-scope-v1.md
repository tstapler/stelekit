# ADR-005: Web/WASM is out of scope for camera-qr-export v1

**Status**: Accepted
**Date**: 2026-07-11
**Feature**: camera-qr-export
**Resolves**: Tension #3 (Web/WASM scope)

## Context

SteleKit targets Desktop/JVM, Android, iOS, and Web/WASM. But for *this* feature:

- requirements.md **Users/Consumers** names only *"Desktop (JVM), Android, and
  iOS"* — **Web is not listed.**
- Two independent research agents (Stack, Pitfalls) flag Web/WASM as the **least
  mature camera leg**: `getUserMedia` needs a `<video>`+`<canvas>` sampling loop;
  `video.play()` is blocked outside a user-gesture handler (autoplay policy);
  `qrcode-kotlin`'s wasmJs artifact is unconfirmed; QR decode falls back to the
  dormant `jsQR`. `WebCameraProvider` is a `NoOpCameraProvider` today.

## Decision

**Web/WASM is out of scope for v1 — neither send nor receive.** The protocol
codec (Layer 1) and QR codec (Layer 2) remain pure `commonMain` and therefore
*compile* for wasmJs, but no `wasmJsMain` UI, camera, or renderer wiring is built,
and the feature flag hides all entry points on Web.

## Consequences

**Positive**
- Removes the single least-mature, highest-uncertainty platform from the appetite.
- Honours requirements.md's own Users/Consumers scoping instead of over-reaching.
- Layers 1/2 staying pure-`commonMain` means Web can be added later with zero
  protocol rework — only `wasmJsMain` glue.

**Negative / trade-offs**
- Web SteleKit users get no QR transfer in v1. Acceptable: not a named consumer.

**Rejected alternatives**
- *Include Web send only* — rejected: even encode-only pulls in the unconfirmed
  `qrcode-kotlin` wasmJs artifact and a Web UI surface for a non-named consumer;
  cost without a requirement.
- *Silently omit Web without a record* — rejected: the task requires the scope
  call to be explicit; this ADR is that record.
