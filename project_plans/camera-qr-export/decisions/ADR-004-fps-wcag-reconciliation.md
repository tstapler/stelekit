# ADR-004: Reconcile QR frame rate — ≤3 fps WCAG hard ceiling + inset card + denser frames + FEC, not raw 8–12 fps

**Status**: Accepted
**Date**: 2026-07-11
**Feature**: camera-qr-export
**Resolves**: Tension #2 (pitfalls 8–12 fps reliability vs. UX ≤3 fps WCAG ceiling)

## Context

Two research findings are in direct conflict:

- **Pitfalls research**: field implementations converge on **~8–12 fps** display
  rate for reliable scanning; below that, throughput drops and lower rates need
  bigger/denser frames to compensate.
- **UX research**: a full-screen animated high-contrast B/W QR at 8–12 fps is
  *exactly* the WCAG 2.3.1 (flash-safety, Level A) failure case. UX mandates a
  **≤3 fps hard ceiling** to satisfy 2.3.1 by rate alone (no PEAT analysis needed),
  and suggests not using a true 100%-full-screen high-contrast area.

Picking either number in isolation is wrong: 8–12 fps risks harming
photosensitive users and fails an accessibility Level-A criterion; a naive 3 fps
without compensation risks missing the 30–60 s time-to-transfer quality bar.

## Decision

Cap the display rate and **compensate for lost throughput by packing more bytes
per frame**, on three axes simultaneously:

1. **Frame rate ≤ 3 fps hard ceiling** (default **2.5 fps**, i.e. 400 ms/frame).
   This is a compile-time clamp in `QrTransferSettings`, not a soft default. Any
   value above 3 fps is rejected at construction and requires a documented PEAT
   validation to unlock — out of scope for v1.
2. **Inset-card presentation, not true full-screen**. The QR is rendered in a
   bordered card (target ≤ 60% of viewport area) on a static, non-flashing
   background with a persistent status line. This invokes WCAG 2.3.1's
   **small-area exemption** as a second line of defence, and leaves room for the
   "No internet connection used" air-gap assertion and the frames-received count.
3. **Denser frames + stronger FEC to recover throughput at low fps**:
   - Larger QR (higher QR version, up to ~v20–v25) in **byte mode** with EC level
     **M** (tunable L/M/Q), carrying a larger `fragLen` per `FountainChunk`.
   - The BC-UR fountain scheme (ADR-001) emits redundant parts indefinitely, so a
     receiver that misses frames at low fps still converges — redundancy, not
     frame rate, provides robustness.
   - The exact max dense payload per frame is **empirically unknown** and is the
     subject of the max-QR-payload spike (Unresolved Question UQ-1), which sets
     `QrTransferSettings.maxFragmentBytes` before the encoder chunk-size story is
     finalized.

**Accessible transfer mode** (also from UX research): a toggle dropping to
1–2 fps with a manual tap-to-advance button, serving photosensitivity and
motor/switch-access users; this is strictly *below* the ceiling so it is always
safe.

## Consequences

**Positive**
- Categorically WCAG 2.3.1-compliant by rate (≤3 fps) *and* area (inset card) —
  belt and suspenders, no PEAT dependency to ship.
- Density + FEC redundancy preserves the time-to-transfer bar despite the low rate;
  denser frames are exactly what the low rate needs (aligns with pitfalls'
  own "compensate with bigger frames" guidance).
- The air-gap assertion and progress count get screen real estate the inset layout
  frees up.

**Negative / trade-offs**
- Low-vision users want the QR as large as possible, which fights the shrink-the-
  flash-area goal. Documented explicit tradeoff (UX research): the inset card is
  sized as large as the ≤60%-area exemption allows, not larger.
- Denser QR at arm's length may fail to scan; this is the risk UQ-1's spike exists
  to bound. If dense frames prove unscannable, fall back to more frames at the same
  safe rate (slower transfer) rather than raising fps.

**Rejected alternatives**
- *8–12 fps as pitfalls suggests* — rejected: fails WCAG 2.3.1 Level A on a
  large-area high-contrast display; unacceptable accessibility regression.
- *3 fps full-screen with no density compensation* — rejected: risks the 30–60 s
  quality bar and still relies on rate alone (no area exemption safety margin).
