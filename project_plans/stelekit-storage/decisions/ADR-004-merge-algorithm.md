# ADR-004: Merge Algorithm

**Status**: Proposed
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

## Context

SteleKit needs a merge strategy for two distinct scenarios:

**Scenario A (Phase 1) — File watcher re-parse**: The local machine edits a markdown file externally (e.g., in another editor), or a git pull updates a file. The file's current state must be reconciled against the DB state without data loss.

**Scenario B (Phase 3) — Git sync merge**: Two machines edit the same page concurrently, then sync via git. The local machine's DB state (with its op log) must be reconciled against the incoming file state (with its sidecar UUID map) from the remote machine.

Current state: SteleKit uses DELETE-all + INSERT-all, which produces data loss on blank-file re-parse and cannot express "this block was moved" vs. "this block was deleted and a new one inserted".

Key properties the merge algorithm must preserve:
1. **Identity continuity** — a block with a stable UUID should retain that UUID through content and position changes where possible
2. **No silent overwrite** — concurrent edits to the same block must produce a conflict marker, not silent last-write-wins
3. **Correct ordering** — the `left_uuid` linked-list ordering in `blocks` must remain consistent after a merge
4. **Idempotency** — re-running the merge on an unchanged file must produce zero DB writes

## Decision

**Phase 1**: Position-based diff merge (SKIP / UPDATE / INSERT / DELETE).

**Phase 3**: Tree reconciliation with forked-block representation, using sidecar UUID map for identity recovery and the Kleppmann (2021) move algorithm for ordering correctness.

### Phase 1 Algorithm (DiffMerge)

Match parsed blocks against DB blocks by UUID (position-derived, stable per ADR-001). For each parsed block:
- UUID exists in DB and `content_hash` matches: **SKIP** (no write)
- UUID exists in DB and `content_hash` differs: **UPDATE** (write updated content, log `UPDATE_BLOCK`)
- UUID not in DB: **INSERT** (log `INSERT_BLOCK`)

For each DB block whose UUID is not in the parsed block list: **DELETE** (log `DELETE_BLOCK`).

This replaces the entire `deleteBlocksByPageUuid` + `insertBlock` cycle.

### Phase 3 Algorithm (TreeReconciler)

On detecting an external file change after a git pull:

1. Read the sidecar UUID map (`hash → UUID`) if present
2. Parse the file to produce a `List<Block>` with UUIDs assigned via sidecar lookup (content hash match) then position derivation (fallback)
3. Load the current DB state for the page
4. Determine the operation: for each block:
   - **Same UUID, same hash**: SKIP
   - **Same UUID, different hash, no local op log entry since last SYNC_BARRIER**: UPDATE (remote edit, no local conflict)
   - **Same UUID, different hash, local op log entry exists since last SYNC_BARRIER**: FORK — create a second copy of the block with a new UUID, set `fork_group_id` on both copies
   - **New UUID (in sidecar/parsed, not in DB)**: INSERT
   - **DB UUID absent from parsed**: DELETE (unless it has a local op log entry since SYNC_BARRIER — in that case, FORK)
5. Resolve ordering: apply the Kleppmann move algorithm to maintain `left_uuid` consistency when blocks are reordered across concurrent edits
6. Insert `SYNC_BARRIER` after merge completes

### Kleppmann (2021) Move Algorithm (Phase 3 ordering)

When a block is moved concurrently on two machines (machine A moves block X before block Y; machine B moves block X before block Z), naive last-write-wins ordering produces undefined results. The Kleppmann 2021 move operation for trees (from "Moving Elements in List CRDTs") provides a deterministic merge:

- Each move carries a `(session_id, seq)` pair
- When two moves conflict, the one with the larger Lamport timestamp wins
- The `left_uuid` linked list is updated to reflect the winning order
- No data is lost: both moves are recorded in the op log; only the ordering outcome is computed

For SteleKit's use case, this is sufficient without adopting an external CRDT library. The `(session_id, seq)` tiebreaker already exists in the `operations` table schema (ADR-003).

## Rationale

**Phase 1 position-based diff is correct for the common case.** In personal notes, the dominant edit patterns are: append a new block, edit existing content, indent/outdent. These all either preserve position (content edit) or produce a predictable new position (append). Only cross-parent moves produce false DELETE+INSERT pairs, and those are sufficiently rare to be acceptable in Phase 1.

**Content-hash comparison is the right skip condition.** Comparing the full content string for equality is more expensive than comparing a precomputed hash. The `content_hash` column already exists in the schema; the Phase 1 diff reads it for O(1) comparison per block.

**Forked-block representation over last-write-wins.** The requirement explicitly states "both versions preserved, user resolves". Last-write-wins loses one user's work. The fork representation uses a `fork_group_id` to link the two diverging versions in the UI, letting the user choose or merge manually. This is more sophisticated than any comparable tool provides but is required by the success criteria.

**Kleppmann 2021 over fractional indexing.** Fractional indexing (Lseq, Logoot) assigns fractional positions between existing elements. It solves ordering without coordination but produces unbounded position strings over time. The `left_uuid` linked list is already implemented in SteleKit's schema; extending it with a Lamport-based conflict resolution rule (move wins by timestamp) is lower-complexity than switching to fractional indexing and requires no schema change to the `blocks` table.

## Consequences

### Positive
- Phase 1 delivers no-op re-import (success criterion 6) and identity stability (criterion 4) without the full CRDT machinery
- Phase 3 delivers conflict detection without silent data loss (success criterion 2)
- Kleppmann move algorithm integrates with the existing `left_uuid` structure and `(session_id, seq)` from the op log — no new data model required
- `SYNC_BARRIER` clearly separates "local" from "remote" op history, making fork detection condition precise

### Negative (accepted costs)
- Block moves in Phase 1 produce DELETE_BLOCK + INSERT_BLOCK pairs in the op log. Undo of a Phase 1 move requires undoing the insert and re-inserting in the old position — not incorrect, but not as elegant as a single MOVE_BLOCK op.
- Phase 3 `TreeReconciler` is the most complex class in the codebase. Full correctness requires careful handling of all edge cases in the fork detection condition.
- The Kleppmann move algorithm adds computational cost at merge time. For personal-scale use, this is negligible.

### Risks
- Incorrect fork detection condition (too aggressive) creates spurious forks on every git pull. Mitigation: the condition requires both (a) a hash mismatch and (b) a local op log entry since the last SYNC_BARRIER. On first sync (no local ops), condition (b) is false, so no false forks are generated.
- `left_uuid` consistency after merge requires careful topological ordering of INSERT/UPDATE/DELETE operations. A block cannot set `left_uuid = X` if block X has not yet been inserted in the same merge transaction. Mitigation: `TreeReconciler` processes INSERTs in document order (parents before children, left siblings before right siblings).

## Alternatives Rejected

- **Last-write-wins on git pull**: Explicitly rejected by success criterion 2. Loses one user's work silently.
- **Three-way git merge**: Git's built-in three-way merge on markdown files produces line-level conflicts but has no block-level semantics. It cannot distinguish "block moved" from "block deleted and new block added at same position". The result is merge conflicts in the markdown file content, which the conflict-marker detector (Task 1.2.3) treats as a hard error.
- **cr-sqlite (CRDTs at the SQLite layer)**: Rejected in the synthesis. Not viable on Android. See research synthesis for full evaluation.
- **External CRDT library (synk, c-crdtlib)**: Deferred pending a spike. The manual `left_uuid` + Lamport implementation is lower-risk for Phase 3. Library adoption can be revisited after the spike.
- **Operational Transform**: OT requires a central server for convergence guarantees. SteleKit uses git-based async sync with no central server. OT is architecturally incompatible.
