# ADR-006: `FrameTransport` naming — distinct from the pre-existing `SyncTransport`

**Status**: Accepted
**Date**: 2026-07-11
**Feature**: camera-qr-export
**Resolves**: Architecture-review naming-collision concern (a bare `transfer.Transport` risks confusion with the accepted `SyncTransport` abstraction)

## Context

This feature's Phase-3 plan originally named its pluggable send/receive seam
`Transport` / `TransportSender` / `TransportReceiver` (in package `transfer/`).

The repo **already has** an accepted abstraction named `SyncTransport`
(`docs/adr/ADR-016-sync-transport-abstraction.md`): a transport-neutral interface
for **file sync** — `connect`, `listRemote`, `pullFiles`, `pushFiles`,
`remoteVersion`, `hasRemoteChanges`, with `SyncEndpoint` variants (`GitRemote`,
`LanPeer`, `WebRtcPeer`, `BastionServer`) and its own planned future
`WebRtcTransport`.

A bare `transfer.Transport` sitting next to `SyncTransport` is a real
confusion/collision hazard: both are "transports," both name a future
**WebRTC** implementation, yet they operate at completely different layers and
solve unrelated problems. Left unrenamed, a reader (or a future implementer)
could conflate the two and, worse, duplicate WebRTC effort or wire the wrong
abstraction into the wrong caller.

## Decision

Rename this feature's seam to **`FrameTransport`** (`FrameTransportSender` /
`FrameTransportReceiver`), with the v1 QR implementation named
`QrFrameTransport`. The name makes the abstraction's unit — an opaque **frame** —
explicit and keeps it lexically distinct from `SyncTransport`.

The two abstractions are deliberately kept separate, with **no shared code**:

| Axis | `FrameTransport` (this feature) | `SyncTransport` (ADR-016) |
|---|---|---|
| Unit of work | Opaque `ByteArray` **frame** | Files + a versioned logical commit |
| State | **Stateless**, one-shot per transfer | **Stateful**, versioned, resumable |
| Network | **Offline / air-gapped** (the whole point) | **Networked** (git/LAN/bastion/WebRTC) |
| Direction | One-directional broadcast (no back-channel) | Bidirectional pull/push with version tokens |
| Layer | Sits under the fountain/chunk protocol | Sits above file storage, below sync coordinator |
| Error surface | `Either<QrTransferError, …>` over frames | `Either<DomainError, …>` over files/versions |
| "Future WebRTC" | A *frame* transport (send opaque bytes over a data channel) — unrelated to sync | A *sync* transport (`WebRtcTransport`, `WebRtcPeer` endpoint) for file sync |

The interface KDoc for `FrameTransport` carries a one-line pointer to this ADR so
the distinction is discoverable at the definition site.

## Consequences

**Positive**
- No naming collision with `SyncTransport`; the two are impossible to conflate
  at a glance.
- The eventual WebRTC work is unambiguous: a `FrameTransport` WebRTC data-channel
  is a *different thing* from `SyncTransport`'s `WebRtcTransport`, and this ADR
  records that up front so the effort is not accidentally duplicated or merged.
- `FrameTransport` names its unit (a frame), reinforcing the requirements.md
  constraint that the layer beneath it operates on opaque byte frames.

**Negative / trade-offs**
- Two "transport" concepts still coexist in the codebase. Accepted: they serve
  genuinely different jobs at different layers, and this ADR plus the distinct
  names keep them separated.

**Rejected alternatives**
- *Keep the bare `Transport` name* — rejected: collides with `SyncTransport`;
  invites duplicated WebRTC effort and wrong-abstraction wiring.
- *Fold QR framing into `SyncTransport`* — rejected: `SyncTransport`'s surface
  (`listRemote`/`pullFiles`/versioned commits) is meaningless for a stateless,
  one-directional, offline optical frame stream; forcing the fit would produce
  nonsensical method stubs (the exact anti-pattern ADR-016 itself rejects).
