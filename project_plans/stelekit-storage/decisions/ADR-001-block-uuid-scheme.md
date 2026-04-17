# ADR-001: Block UUID Scheme

**Status**: Proposed
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

## Context

SteleKit currently derives block UUIDs from `hash(filePath + parentUuid + siblingIndex + content)`. The inclusion of `content` means that editing any block produces a new UUID, breaking version history and making undo impossible. The system has no persistent identity for blocks beyond the current parse.

Two credible alternatives exist:

1. **Position-only hash** — keep the derivation function but drop `content` from the seed. UUID is deterministic from `filePath + parentUuid + siblingIndex`. A block can be re-derived from the file at any time without a sidecar. A block moved to a new position gets a new UUID (position change = identity change).

2. **Random UUID on first stable parse + sidecar** — assign a random UUID (v4 or v7) once on first stable import, persist it in a sidecar file. UUID is completely stable across content and position changes. Requires the sidecar from day one.

The Phase 3 sidecar (ADR-002) enables UUID survival across position changes. The question is which scheme is correct for Phase 1, before the sidecar exists.

The constraint driving urgency: the existing data migration (ADR-006) must happen before any other phase ships. The migration strategy depends on which scheme Phase 1 adopts.

## Decision

Phase 1 uses **position-only hash** (option 1): drop `content` from the UUID seed; keep `filePath + parentUuid + siblingIndex`.

Phase 3 upgrades to sidecar-backed UUID recovery: when a sidecar is present, content-hash lookup overrides position-derived UUID, allowing a block moved to a new position to retain its identity.

Random UUID assignment is not introduced until Phase 3, and only as an internal implementation detail of `SidecarManager` for blocks that have never been seen before.

## Rationale

**Position-only hash is derivable from the file without external state.** This property is critical for Phase 1: if the sidecar does not yet exist (all Phase 1 users), the system can still recover all UUIDs by re-parsing the file. A random UUID scheme would be irrecoverable without the sidecar — a Phase 1 DB loss would produce a new set of UUIDs on next open, breaking all backlinks.

**Content is the wrong identity dimension.** A block is the same logical entity when its prose is edited. Position within its parent (sibling index) is a more stable identity anchor than content. The existing design was a pragmatic choice that is now a correctness defect.

**Position-only gives "good enough" stability for Phase 1.** The most common edit patterns (append text, fix typo, reorder siblings) either preserve position or produce predictable UUID changes. Only cross-parent moves produce false "delete old + insert new" events, and those are rare in personal notes.

**Deferring random UUID to Phase 3 avoids a two-migration problem.** Introducing random UUIDs in Phase 1 would require a second migration in Phase 3 to reconcile Phase 1 random UUIDs against Phase 3 sidecar UUIDs. The two-step path (position-only → sidecar-backed) is simpler.

## Consequences

### Positive
- Phase 1 migration is fully self-contained: all UUIDs re-derivable from files, no sidecar needed
- UUID scheme is deterministic and testable without mocking random number generators
- Backlinks survive content edits (the most common edit type)
- `generateUuid` implementation stays simple: two lines of change to `GraphLoader.kt`

### Negative (accepted costs)
- Blocks moved to a new sibling index get a new UUID. A move-block operation in Phase 1 appears in the op log as DELETE_BLOCK + INSERT_BLOCK rather than MOVE_BLOCK. This is acceptable until Phase 3.
- Two distinct blocks at the same position under the same parent produce identical UUIDs. This was already a risk with the content seed (empty bullets at the same depth), but position-only sharpens it. Mitigation: the `UNIQUE` constraint on `blocks.uuid` causes a graceful insert failure rather than silent collision.

### Risks
- If a user has many files with identical tree structures (e.g., templated journal entries with empty bullet at position 0, parent null), UUID collisions across pages are possible. Mitigation: `filePath` is included in the seed, so inter-page collisions are structurally impossible.

## Alternatives Rejected

- **Keep content in seed**: Rejected because it is the root cause of the identity instability problem. Every content edit produces a new UUID, making undo and version history impossible to implement correctly.
- **Random UUID on first parse (no sidecar, Phase 1)**: Rejected because it makes DB loss irrecoverable in Phase 1. Without the sidecar to persist the random UUID, a re-parse would generate entirely new UUIDs, breaking all backlinks. The sidecar is the right place to persist random UUIDs, and the sidecar is a Phase 3 deliverable.
- **UUID v7 (time-ordered random)**: Attractive for op-log `op_id` (already used there), but wrong for block identity. Block identity should be derivable from file content; time-ordered randomness is not. UUID v7 is correct for the operation log's `op_id` column.
