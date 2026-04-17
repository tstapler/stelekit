# Research Findings: Pitfalls
## SteleKit Storage Architecture

**Date**: 2026-04-13  
**Method**: Training knowledge only (cutoff August 2025). No live web searches were performed.  
**Scope**: CRDT correctness issues, SQLite event log performance, git sync markdown conflicts, undo history storage.

---

## Confidence Legend

| Symbol | Meaning |
|--------|---------|
| HIGH   | Well-documented, multiple independent sources in training data, stable domain knowledge |
| MED    | General consensus but implementation details vary; verify before committing |
| LOW    | Inferred from related work; limited direct sources in training data |

All URLs listed are **unverified** — sourced from training data memory and may be stale, moved, or incorrect. Do not treat them as live references without independent verification.

---

## 1. CRDT Correctness Pitfalls

**Confidence: HIGH** (core CRDT correctness is well-studied academic territory)

### 1.1 Interleaving Anomalies in List CRDTs

The central correctness problem in text/list CRDTs is **concurrent insertion interleaving**: when two peers independently insert characters or blocks at the same logical position, the merge can interleave their contributions in ways that neither peer intended.

**The specific failure**: Peer A inserts blocks [X, Y] between anchor B1 and B2. Peer B simultaneously inserts blocks [P, Q] at the same position. A naive Logoot/LSEQ merge may produce [X, P, Y, Q] or [P, X, Q, Y] rather than either [X, Y, P, Q] or [P, Q, X, Y]. Both [X, Y] and [P, Q] are semantically coherent as sequences; the interleaved forms are not.

**State of the art**: The Fugue algorithm (Weidner et al., 2022/2023) was specifically designed to guarantee non-interleaving. RGA (Replicated Growable Array) with a per-site tie-breaking comparator gives interleaving-free merges for two concurrent inserts but can still interleave under three-way concurrent edits depending on tie-break ordering. YATA (used in Yjs) and its successor guarantee non-interleaving under their insert-right semantics.

**For SteleKit**: If using a `left_uuid` linked-list ordering (as the requirements describe), concurrent insertions at the same `left_uuid` anchor will collide. A total order over concurrent inserts at the same anchor must be defined (typically by peer ID + Lamport clock). Without it, the merge result is nondeterministic across peers — they may converge to different orderings, violating the CRDT convergence guarantee.

**Unverified URLs (training memory)**:
- https://www.inkandswitch.com/peritext/ (rich text CRDT by Ink & Switch)
- https://arxiv.org/abs/2305.00583 (Fugue paper, Weidner et al.)

---

### 1.2 Tombstone Accumulation and Bloat

CRDTs that use soft-delete (tombstoning) never physically remove deleted nodes — the tombstoned node must be retained to preserve insertion ordering for any future insertion that anchored itself to the deleted node.

**The specific failure**: After 1 year of note-taking with frequent block reorganization (delete + re-insert at new location), the block graph accumulates tombstones that may dwarf the live content. An outliner with 10,000 live blocks could have 50,000+ tombstone entries if the user reorganizes frequently. These tombstones must be traversed during merge operations.

**Compaction strategies (and their failure modes)**:
- **Snapshot + GC**: Periodically collapse all peer states into a snapshot and discard tombstones only referenced by other tombstones. Safe only if all peers have acknowledged the snapshot. If a long-offline device reconnects after GC, it may have insertions referencing GC'd tombstones, causing undefined merge behavior. This is the most common CRDT GC failure mode in the wild.
- **Causal GC with vector clocks**: GC a tombstone only once all peers' vector clocks exceed the tombstone's timestamp. Correct but requires knowing the full peer set — breaks down with open/unknown peer sets.
- **No GC (append-only forever)**: Simple and safe. For a personal single-user app this is tolerable; 10 years of heavy editing could reach tens of MB of tombstone data in the operation log.

**For SteleKit**: Since scope excludes real-time collaboration and limits to personal multi-device use with a known peer set (desktop + mobile), causal GC with a 2-peer vector clock is feasible. But GC must be coordinated — never run GC unilaterally on one device.

---

### 1.3 Lamport / Vector Clock Skew

**Confidence: HIGH**

CRDT state machines depend on monotonically increasing logical clocks. Common violations:

- **System clock rollback**: If the device clock is set backwards (NTP correction, VM snapshot restore, timezone change), wall-clock timestamps used as Lamport clocks can regress. A regressed timestamp makes a "newer" event sort before an "older" one, causing permanent incorrect merge order.
- **Import-time clock collisions**: When importing a markdown file as "new content," assigning the current Lamport clock to all imported blocks means they all get the same timestamp. Any concurrent edit that races the import will have an ambiguous ordering relative to the entire imported batch.
- **For SteleKit specifically**: The current UUID scheme (`hash(filePath + parentUuid + siblingIndex + content)`) embeds no temporal information. The transition to a CRDT-based scheme must introduce a reliable Lamport counter stored in SQLite and incremented atomically on every write. Relying on SQLite `rowid` as a Lamport clock is tempting and mostly works, but breaks if rows are ever deleted and VACUUM is run (rowid is not reused by default, but verify the behavior under the SQLDelight version in use).

**Mitigation**: Store a single-row `logical_clock` table in SQLite. Increment it in the same transaction as every operation log entry. Never use wall-clock time as the primary ordering key.

---

### 1.4 Move Operation Correctness

**Confidence: MED**

List CRDTs handle insert and delete well. Move (reorder a block to a different parent/position) is significantly harder:

- A naive move implemented as delete + insert creates a tombstone at the old position and a new live node at the new position — losing the block's history and UUID continuity (violating SteleKit success criterion 4).
- True CRDT move semantics require a dedicated Move operation type with causal dependencies that can be applied in any order. Kleppmann's 2022 work on CRDT move describes an algorithm that handles concurrent moves to the same parent without creating duplicate entries, but it is significantly more complex than insert/delete.
- **Cycle detection**: A concurrent Move(A → child of B) and Move(B → child of A) creates a cycle. CRDT move algorithms must detect and resolve cycles deterministically. The standard resolution is to accept only the causally earlier move and discard the later one.

**For SteleKit**: If move is implemented as "update the block's parent_uuid and left_uuid in the operation log," the reconciliation layer must handle the concurrent-move cycle case. If move is implemented as "soft-delete old position + new-insert at new position reusing the same UUID," the GC story becomes more complicated because the tombstone and the live node share a UUID.

---

## 2. SQLite Event Log Performance Pitfalls

**Confidence: HIGH** (SQLite internals are well-documented; event sourcing patterns widely discussed)

### 2.1 Unbounded Table Growth Without Compaction

An append-only operation log table with no compaction strategy will grow linearly with edits. For a power user editing 100 blocks/day over 5 years, that is ~180,000 rows at minimum — more with undo/redo cycles.

**Performance impact**:
- `SELECT * FROM ops WHERE block_uuid = ?` on an unindexed table degrades to O(n) full scans. Index by `(block_uuid, sequence_number)` from day one.
- SQLite query planner can use at most one index per table scan. A log table with compound queries (e.g., "all ops for page X after sequence Y") needs a covering index `(page_id, sequence_number)` — not just individual column indexes.
- WAL mode writes the log sequentially but the WAL file itself grows until a checkpoint is triggered. An aggressive write workload (paste of 500 blocks) can grow the WAL to tens of MB before the next checkpoint, causing a sudden multi-second pause when the checkpoint fires.

**Recommended mitigations**:
- Define a `snapshot` table that captures the current materialized state at a sequence number. Queries for "current state" read snapshot + ops after snapshot, not the full log.
- Trigger snapshots on: graph close, idle timer, every N operations (e.g., N=500).
- Run WAL checkpoints explicitly after snapshot creation, not on SQLite's automatic schedule (`PRAGMA wal_autocheckpoint`).

---

### 2.2 Write Amplification from Fine-Grained Operations

**Confidence: HIGH**

If every keypress in a text block is logged as a separate `UPDATE_CONTENT` operation, a user typing a 200-character sentence produces 200 operation log rows. This causes:

- Rapid table growth (far exceeding the block count estimates above)
- Undo that replays character-by-character, which is incorrect UX (users expect word-level or chunk-level undo)
- Checkpoint pressure: 200 WAL frames for a single semantic edit

**Standard solutions**:
- **Debounced coalescing**: The existing 500ms debounce in `BlockStateManager` collapses typing bursts into a single logical edit. Ensure the operation log records the coalesced form, not intermediate keystrokes. Never log raw keystroke events to the operation log.
- **Content diff on commit**: When the debounce fires, compute a diff between old and new content and store only the diff (or just the full new content). For a local-first app without real-time collaboration, storing full new content per operation is simpler and costs only a few hundred bytes per edit — acceptable at scale.

---

### 2.3 SQLite Locking and the DatabaseWriteActor Pattern

**Confidence: HIGH**

The existing `DatabaseWriteActor` serializes writes through a coroutine channel. This is the correct pattern. Known pitfalls when extending it to include an operation log:

- **Read-your-writes**: After the actor commits an operation, any coroutine that immediately reads the resulting materialized state may read stale data if the read uses a separate connection in WAL mode. WAL readers see a snapshot of the DB at the time their read transaction started — if the write committed after the read began, the reader misses it. Ensure post-write reads use a new transaction.
- **Batching in the actor**: If the actor processes one operation per message, a paste of 500 blocks sends 500 messages. The actor's overhead (channel receive, transaction open/commit) per message becomes significant. Add batch-write support (accept `List<Operation>`) and wrap in a single transaction to amortize overhead.
- **FK constraint ordering**: The existing FK violation bug (savePage before saveBlocks) must be preserved in operation log playback. When replaying operations, ensure parent page creation operations always precede child block operations within the same transaction. A topological sort of page/block operations is safer than relying on insertion order.

---

### 2.4 Compaction and Snapshot Atomicity

**Confidence: MED**

A snapshot is only useful if it is atomically consistent with the operation log sequence number it claims to represent. Failure modes:

- **Snapshot written, crash before marking old ops purgeable**: On restart, the system sees both the snapshot and the ops it already covers. If it replays ops on top of the snapshot, state is double-applied. The snapshot table must store the exact `max_sequence_number` it covers, and op replay must start at `max_sequence_number + 1`.
- **Snapshot written, log truncated, then restore from backup**: If the backup captured the truncated log but not the snapshot (or an older snapshot), the user loses history. Never delete ops in the same transaction that creates the snapshot; keep old ops for one additional backup cycle before purging.
- **iOS/Android SQLite VACUUM behavior**: On mobile, SQLite `VACUUM` rewrites the entire DB file. Running VACUUM during compaction while a background sync is reading will contend for the exclusive lock. On Android with WAL mode, VACUUM requires an exclusive lock, briefly blocking all reads. Schedule VACUUM only during explicit user-initiated maintenance, not automatically.

---

## 3. Git Sync Markdown Conflict Pitfalls

**Confidence: HIGH** for general git behavior; **MED** for markdown-specific merge heuristics

### 3.1 Line-Based Diff Cannot Detect Block-Level Intent

Git's default merge strategy (`ort`/`recursive`) uses a three-way line diff. Markdown outliner files are particularly hostile to this:

- An outliner block is typically a single long line (the entire block content). A block move appears to git as a line deletion at the old position and a line insertion at the new position — git has no concept of "this is the same block, just moved."
- Two peers independently editing the same line (one fixes a typo, one adds a tag) produce a genuine git conflict on that line even though a block-aware merge could resolve it.
- Block reordering conflicts are especially bad: if Peer A reorders [1,2,3] to [2,1,3] and Peer B reorders to [1,3,2], git's line diff produces an unsolvable conflict even though the intent is clear at the semantic level.

**For SteleKit**: The sidecar file approach (`.stelekit/` directory with block UUID metadata) mitigates this partially — the sidecar lets re-import identify which UUID corresponds to which line after a git merge. But the sidecar itself will conflict with git if both peers modified it concurrently. The sidecar must be designed to minimize conflicts (e.g., one UUID-mapping entry per line, so only changed lines conflict).

---

### 3.2 Sidecar File Merge Conflicts

**Confidence: MED**

If the sidecar file maps line numbers to block UUIDs, it will conflict every time the line count changes. Common failure patterns:

- **Line-number based sidecar**: Peer A inserts a block at line 5, Peer B inserts at line 7. The sidecar now has conflicting entries for lines 5–7 on both sides. The main `.md` file may auto-merge; the sidecar will conflict.
- **Content-hash based sidecar**: Map block content (first 40 chars or full hash) to UUID. More stable against reordering but breaks when a block is edited — the hash changes and the old UUID cannot be found.
- **Recommended layout**: Use a separate `.stelekit/<page-name>.uuids` file with one `uuid content_hash` pair per line. One entry per block means conflicts are localized to changed blocks. This is the most git-friendly layout of the realistic options. Example line format: `a3f9c1b2 <sha1-of-block-content>`.

---

### 3.3 Merge Commit Timing and the "Dual Write" Window

**Confidence: MED**

When a user pulls from git after a concurrent edit, the sequence is:
1. Git merges the `.md` files (auto-merge or conflict markers).
2. SteleKit's file watcher fires on the changed file.
3. SteleKit re-imports the merged markdown.
4. SteleKit reconciles the imported content against the operation log.

The window between steps 1 and 4 is a "dual write" hazard: if the user edits a block during re-import (possible if the file watcher fires asynchronously), the import and the edit race. The existing B3 guard in `StelekitViewModel` (suppress external file changes while dirty blocks exist) mitigates but does not eliminate this — the guard is checked at import start, not held as a lock throughout import.

**Mitigation**: During re-import triggered by a file-watcher event, take an application-level exclusive edit lock for the affected page. Refuse block edits until reconciliation completes. The lock duration should be bounded by import time (typically < 200ms for a single page).

---

### 3.4 Binary SQLite File in Git

**Confidence: HIGH**

If the SQLite database file is tracked in git (common mistake when the `.db` is in the repo root or not `.gitignore`'d):
- Git treats `.db` as binary. Any concurrent modification produces an unresolvable binary conflict: git can only choose one side.
- Even without conflicts, the `.db` file changes on every write, causing massive git history bloat (SQLite page size is 4KB; a single row insert may dirty 2–3 pages = 8–12KB of change per commit).
- WAL files (`.db-wal`, `.db-shm`) must never be committed. A committed `.db-wal` file alongside a committed `.db` puts the DB in an intermediate state on the receiving machine — it will attempt to apply the WAL on open, potentially corrupting state.

**For SteleKit**: The `.db` file must be in `.gitignore`. Only `.md` files and sidecar UUID files should be tracked. This is already implicit in the requirements ("markdown files are the sharing format; SQLite is the local state") but must be enforced with a `.gitignore` entry and a guard that refuses to open a DB that arrived via git pull.

---

### 3.5 Git Conflict Marker Injection Into Content

**Confidence: HIGH**

When git cannot auto-merge a markdown file, it injects conflict markers (`<<<<<<<`, `=======`, `>>>>>>>`) directly into the file. SteleKit's markdown parser will encounter these on the next import. Failure modes:

- The parser likely does not handle conflict markers — they will be ingested as block content, corrupting the graph.
- A block whose content is `<<<<<<< HEAD` will get a UUID and be written to the DB, then potentially synced back to disk without the conflict markers if `GraphWriter` serializes from DB state — silently discarding the conflict.

**Required handling**: Before re-importing any `.md` file, scan for git conflict markers. If found, present the file to the user in a conflict resolution UI rather than attempting auto-import. Attempting to parse and import a conflicted file must be a hard error, not a silent failure.

---

## 4. Undo History Storage Pitfalls

**Confidence: HIGH** for general patterns; **MED** for SQLite-specific implementations

### 4.1 Undo Stack Invalidation After Sync/Merge

**Confidence: HIGH**

The canonical undo problem in synced systems: after a git pull that merges remote changes, the local undo stack may reference operations that are no longer coherent with the current state.

**Example**:
1. Local: User types "foo" → op #100 records `UPDATE_CONTENT(block_A, "foo")`.
2. Remote sync: Remote peer deleted `block_A` entirely. After merge, `block_A` no longer exists.
3. User presses Ctrl-Z: undo tries to revert op #100. But the block does not exist.

**Options**:
- **Prune undo stack at sync boundary**: All ops before the sync point become non-undoable. Simple but loses cross-restart undo for any session that involved a sync.
- **Causal undo (selective undo)**: Undo op #100 even if subsequent ops (including remote ops) have occurred — requires the operation log to be a causal graph, not a linear sequence. Correct but complex.
- **Conflict-surfacing undo**: Attempt the undo; if the target block does not exist, surface a user-visible warning ("Cannot undo: this block was deleted on another device"). Do not silently no-op.

**For SteleKit**: Given the personal single-user multi-device scope, the pragmatic choice is to mark a sync event in the operation log as an undo barrier — operations before the barrier cannot be undone (the barrier is visible in the UI as "Synced from git"). This is what most IDEs (VS Code, IntelliJ) do for external file changes.

---

### 4.2 Undo History Size and Bounded Storage

**Confidence: HIGH**

Without an upper bound, the operation log grows indefinitely. But a hard truncation (delete ops older than N days) invalidates the undo stack for those ops.

**Common bounds**:
- **Count-based**: Keep last 1,000 undoable operations per page. Simple but arbitrary; a heavy session on a single page could fill 1,000 ops in minutes.
- **Time-based**: Keep last 30–90 days of ops. Provides cross-restart undo for the useful window. Operations older than the window transition to "non-undoable history."
- **Size-based**: Keep ops until the operation log exceeds N MB (e.g., 50–100 MB). Purge oldest first. Requires compaction to reclaim space.

**For SteleKit**: A time-based + size cap combination is most practical:
- Retain ops for 90 days (cross-restart undo for recent sessions).
- Hard cap at 100 MB total operation log size; if exceeded before 90 days, emit a warning and purge oldest ops.
- Ops older than 90 days are compacted into the snapshot table and purged from the log.

---

### 4.3 Redo Invalidation After New Edit

**Confidence: HIGH**

Standard undo/redo: after undoing N operations, if the user makes a new edit, the redo stack is discarded. In a persistent log, this means all ops after the current undo pointer must be either deleted or marked non-redoable.

Failure modes in a persistent log:
- **Redo after external sync**: If ops are appended to the log by a sync/merge operation after the user undid local ops, the redo stack is in an undefined state. The sync ops are not "redoable" (they came from outside), but they now sit between the undo pointer and the pre-undo ops.
- **Solution**: Tag operations with origin (`LOCAL` | `SYNC`). The redo stack only contains `LOCAL` ops. `SYNC` ops cannot be undone or redone — they are permanent (but visible in the operation history view).

---

### 4.4 Operation Log and Rollback Atomicity

**Confidence: HIGH**

Undo must be atomic with the log update. If undo writes the reverted state to the DB but crashes before recording the undo operation in the log, on restart the state appears as if the undo never happened — but the user's mental model is that it did.

**Required pattern**: Undo must be a single SQLite transaction that:
1. Writes the reverted block content.
2. Appends an `UNDO(op_id=N)` entry to the operation log.
3. Updates the undo pointer.

If any step fails, the entire transaction rolls back and the undo is not applied.

**For SteleKit's `DatabaseWriteActor`**: Ensure the actor supports multi-step transactions (i.e., can accept a `Transaction { ... }` lambda that executes atomically). The current design (one operation per message) may not support this without extension.

---

### 4.5 Cross-Page Undo and Referential Integrity

**Confidence: MED**

Find-and-replace across the graph (success criterion 3) produces a batch of `UPDATE_CONTENT` operations across multiple pages. Undoing this batch should revert all affected blocks, not just the most recent one.

**Failure mode**: If find-and-replace produces N individual operations (one per block) and they are independently undoable, the user must press Ctrl-Z N times to fully revert the rename. This is incorrect UX and also risks partial revert (e.g., user undoes 3 of 50 substitutions and stops).

**Required**: A `BATCH_OPERATION` wrapper in the operation log that groups N ops under a single undo unit. A single Ctrl-Z undoes the entire batch atomically.

**Implementation note**: The batch wrapper can reference the constituent op IDs. The undo machinery checks: "is this op part of a batch? If so, undo all ops in the batch together."

---

## 5. Cross-Cutting Pitfalls

### 5.1 Schema Migration with Live Operation Log

**Confidence: MED**

If the operation log schema changes (e.g., adding a new operation type, adding a field to existing operations), previously recorded operations in the old format must be migrated or the replay logic must handle both formats. This is the "evolvability" problem in event sourcing.

**Mitigations**:
- Never delete operation type codes; only add new ones (forward compatibility).
- Store operation payloads as JSON rather than normalized columns — makes schema evolution easier at the cost of query performance.
- Version the operation log schema explicitly (`schema_version` in the DB metadata table) and include a migration path for each version bump.

---

### 5.2 The "Dual Truth" Problem During UUID Migration

**Confidence: HIGH**

The requirements note that migrating from the current `hash(filePath + parentUuid + siblingIndex + content)` UUID scheme to a position-only scheme will break UUID continuity for all existing blocks. During the migration window:

- The old DB has blocks under old UUIDs.
- Re-import of existing files generates new UUIDs under the new scheme.
- Saved bookmarks, backlinks, or UI state referencing old UUIDs break.

**Required**: The migration must run as a one-shot database migration (SQLDelight migration script) that re-derives new UUIDs from existing blocks and updates all FK references (backlinks, properties, etc.) in a single transaction. There is no safe "gradual" UUID migration — it must be atomic or the DB is in a broken state.

---

## Summary: Highest-Priority Pitfalls for SteleKit

| # | Pitfall | Risk | Mitigations Required |
|---|---------|------|---------------------|
| 1 | Concurrent insert interleaving at same `left_uuid` | Data corruption | Total order for concurrent inserts (peer ID + Lamport clock tie-break) |
| 2 | Git conflict markers ingested as block content | Silent data loss | Conflict marker detection before import; hard-error on conflicted files |
| 3 | Undo stack invalidated by post-sync state | UX failure / confusing behavior | Sync barrier in op log; ops before sync are non-undoable |
| 4 | `.db` file accidentally committed to git | Severe data corruption on pull | `.gitignore` enforcement + guard on DB open |
| 5 | Undo not atomic with op log update | Corruption on crash | Single-transaction undo in `DatabaseWriteActor` |
| 6 | WAL checkpoint pause during heavy paste | Performance regression | Explicit checkpoint after batch ops, not automatic |
| 7 | GC of tombstones while offline peer has pending ops | Permanent merge failure | Causal GC only; coordinate across known peer set |
| 8 | UUID migration creates "dual truth" | Broken backlinks | Single-transaction migration script; no gradual migration |
