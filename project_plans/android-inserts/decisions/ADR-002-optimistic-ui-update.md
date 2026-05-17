# ADR-002: Optimistic UI Update Pattern for Structural Block Operations

**Status**: Accepted  
**Date**: 2026-05-16

## Context

Structural block operations — `splitBlock`, `indentBlock`, `outdentBlock`, `mergeBlock` — follow this sequence:

1. Compute the new block tree in memory.
2. Persist changes to the database (`DatabaseWriteActor`).
3. Move the cursor / focus to the new block (`requestEditBlock`).

Step 3 is currently gated behind the `await` of step 2. On Android, the database write involves SAF Binder IPC (addressed by ADR-001), making the round-trip 1–2 seconds. The user types a character, presses Enter to split the block, and waits over a second before the cursor moves to the new line — a perceived freeze.

Three strategies were considered:

| Strategy | Description | Rejected reason |
|---|---|---|
| **Synchronous wait (status quo)** | Await DB before updating UI | 1–2s lag is unacceptable UX |
| **Fire-and-forget** | Update UI immediately; never check DB result | Data loss or silent corruption if write fails |
| **Optimistic update with rollback** | Update UI immediately; rollback + notify on DB failure | Chosen — balances UX and correctness |

DB writes succeed in >99.9% of normal operation. The rare failure cases (disk full, SAF permission revoked) require user notification regardless of UI strategy.

## Decision

For `splitBlock`, `indentBlock`, `outdentBlock`, and `mergeBlock`, the cursor/focus update (`requestEditBlock`) is moved **before** the database write `await`. The DB write is still awaited; on failure the following happens:

1. A `SnackbarMessage` error is emitted to the UI layer.
2. The block tree is reloaded from the database (authoritative state), reverting the optimistic UI change.

The optimistic state is the in-memory block tree that was already computed to derive the write payload — no extra speculative computation is required.

## Consequences

**Positive**:
- Cursor moves to the new block immediately on user action; perceived latency drops from 1–2s to imperceptible.
- No change to data integrity guarantees: every write is still awaited and failures are surfaced.
- Pattern is composable — the same rollback mechanism (`reloadFromDb` + snackbar) can be reused for future structural operations.

**Negative/Risks**:
- On DB failure the user sees: a brief visual flicker (cursor in new position → cursor snaps back after reload), plus a snackbar error. This is a rare but non-zero regression versus the previous experience where nothing moved until success.
- The optimistic state and the written state must be derived from the same computation; divergence would require extra reconciliation logic.

**Mitigation**:
- DB failure rate is extremely low under normal conditions; the snackbar path is an exceptional code path, not a performance-sensitive one.
- Reloading from the authoritative DB state (rather than attempting partial rollback) keeps the rollback logic simple and correct regardless of operation complexity.
- Unit tests must cover the failure path: assert snackbar emission and that block tree state matches DB after rollback.
