# ADR-001: Wire format — BC-UR-spec-informed fountain math + SteleKit-minimal binary frame

**Status**: Accepted
**Date**: 2026-07-11
**Feature**: camera-qr-export
**Deciders**: solo developer

## Context

The QR transfer needs a wire format for chunked, forward-error-corrected page
markdown. requirements.md Open Question: *"Should the wire format be
SteleKit-specific or interoperable with an existing open standard?"* Build-vs-buy
research recommends implementing the Blockchain Commons UR spec (BCR-2020-005/012)
primarily for its **correctness-testing** benefit — cross-language reference test
vectors (C++/Swift/Rust) are the only credible oracle for the fountain-code math,
which is exactly the class of code where a bespoke/LLM-authored implementation
silently mis-converges on real photographed frames.

Two things are conflated in "adopt BC-UR":
1. The **fountain-code algorithm** — Luby Transform, the specific degree
   distribution, the deterministic Xoshiro256 RNG seeded per-transfer, and the
   part-mixing/XOR reconstruction. This is the risky math.
2. The **outer envelope** — BC-UR wraps the CBOR payload in Bytewords + a
   `ur:...` URI text scheme designed for interop with hardware wallets. This
   envelope roughly **doubles** payload size (Bytewords is a 2-char-per-byte text
   encoding) and buys SteleKit nothing, because SteleKit devices only ever talk
   to other SteleKit devices — there is no wallet on the other end.

## Decision

**Adopt the BC-UR fountain-code algorithm faithfully** (item 1) so that the
official BC-UR reference test vectors validate our encoder/decoder bit-for-bit.
**Reject the BC-UR outer envelope** (item 2) in favour of a SteleKit-minimal
fixed-width binary frame header carried as raw bytes inside each QR code (byte
mode QR, not alphanumeric/Bytewords).

Wire frame layout (`FountainChunk` serialized form, serialized by `ChunkFrameCodec`), all multi-byte fields big-endian:

```
Offset Size  Field
0      1     magic       0x53 ('S')                 — reject non-SteleKit QR fast
1      1     version     0x01                       — protocol version byte (ADR mandate)
2      4     transferId  TransferId (uint32)        — session id; reject 2nd concurrent sender
6      4     payloadLen  total original byte count  — bound before allocating (OOM guard)
10     4     payloadCrc  CRC32 of the ORIGINAL      — whole-payload proof gate
              markdown bytes
14     4     chunkIndex  fountain part sequence      — Xoshiro seed index (unbounded); `ChunkIndex` newtype
18     2     fragLen     this fragment byte length
20     n     fragment    fountain-mixed part bytes
20+n   4     chunkCrc    CRC32 of bytes[0 .. 20+n)  — per-chunk admission check
```

Header is 20 bytes fixed + 4-byte trailing CRC = 24 bytes overhead per frame.
`fragment` is a fountain-code part produced by the BC-UR LT algorithm over the
(optionally Deflate-compressed) original markdown bytes.

The fountain algorithm, degree distribution, RNG, and part-mixing match BCR-2020-012
exactly so that `FountainCodecVectorTest` can replay the published reference vectors.

## Consequences

**Positive**
- The single highest-risk component (fountain math) is validated against an
  independent cross-language oracle, not self-consistency.
- ~50% denser payload than full BC-UR (no Bytewords text expansion) — directly
  helps the fps/WCAG tradeoff (ADR-004) by packing more data per frame.
- Explicit `version` byte from frame 0 handles v1-phone→v2-phone drift
  (pitfalls research: format drift = silent total failure).
- `magic` byte lets `QrScanner` reject a stray non-SteleKit QR in O(1) and drive
  the UX "That's not a SteleKit transfer code" state.

**Negative / trade-offs**
- No interop with hardware wallets or other BC-UR tools. Accepted: not a goal
  (requirements.md — SteleKit devices only talk to each other).
- We own the frame header format forever; mitigated by the version byte.
- Must port BC-UR test vectors into Kotlin unit tests (one-time cost, Story 1.1.4).

**Rejected alternatives**
- *Full BC-UR (Bytewords + CBOR + ur: URI)* — rejected: doubles payload size for
  interop we will never use; alphanumeric/Bytewords QR mode is less dense than
  byte mode for this data.
- *CBOR frame header* — rejected: fixed-width binary is smaller and needs no CBOR
  dependency on the critical QR-density path; CBOR's self-description is wasted
  when both ends share the struct definition.
- *Purely SteleKit-invented fountain scheme* — rejected: forfeits the test-vector
  oracle, which build-vs-buy research flags as reckless for erasure-coding math.
