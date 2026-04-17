# Architecture Research Findings: SteleKit Storage

**Dimension**: Architecture — event sourcing, CRDT merge, sidecar formats, fractional indexing, tree CRDTs
**Date**: 2026-04-13
**Model**: claude-sonnet-4-6 (knowledge cutoff August 2025, no live web access)
**Confidence scale**: HIGH = well-established, well-documented; MEDIUM = widely discussed but details may vary; LOW = reconstruction from partial knowledge

---

## 1. Local-First Software and CRDTs

**Confidence: HIGH**

### The Paper

Martin Kleppmann, Adam Wiggins, Peter van Hardenberg, and Mark McGranaghan. "Local-First Software: You Own Your Data, in Spite of the Cloud." Proceedings of Onward! 2019, ACM SPLASH. (October 2019). Published under the Ink & Switch research lab.

### Seven Ideals for Local-First Software

1. **No spinners** — the app works instantly without waiting for a network round-trip.
2. **Your work is not trapped on one device** — data syncs across devices owned by the user.
3. **The network is optional** — the app functions fully offline; sync happens opportunistically.
4. **Seamless collaboration with your colleagues** — real-time or near-real-time collaboration is possible when online.
5. **The long now** — data is still accessible decades later, independent of any vendor's server uptime.
6. **Security and privacy by default** — data lives locally and is synced end-to-end.
7. **You retain ultimate ownership and control** — the user can fork, export, or delete their data at any time.

### CRDT Types Suited to Document Editing

**RGA (Replicated Growable Array)** — each element has a unique identifier (timestamp, node-id pair). Insertions are totally ordered by this identifier. Used in Atom's Teletype. Confidence: HIGH.

**LOGOOT / LSEQ** — position-based sequence CRDTs that assign fractional identifiers to each element. Concurrent insertions at the same logical position produce different identifiers that sort deterministically.

**Automerge** — a JSON CRDT library by Kleppmann et al. implementing RGA for text, lists, and maps. Supports moves, deletions, and conflict representation. Confidence: HIGH on existence and design; MEDIUM on exact API surface.

**Yjs** — high-performance CRDT framework by Kevin Jahns using "YATA" (Yet Another Transformation Approach). More memory-efficient than RGA for large documents. Used in ProseMirror y-collab, TipTap, BlockSuite/Affine. Confidence: HIGH.

**Map CRDTs (LWW-Map, OR-Map)** — for key-value metadata. An OR-Map (Observed-Remove Map) supports concurrent add/remove. Block properties map well to an OR-Map.

### Implication for SteleKit

For async git-based sync (not real-time collaboration), a full RGA or Yjs deployment is overkill. A lightweight event-sourcing approach tracking operations (insert, update, move, delete) and replaying during merge achieves the same practical outcome with far less implementation complexity.

---

## 2. Sidecar File Formats

**Confidence: HIGH** for Obsidian and Logseq; MEDIUM for Zettlr and iA Writer specifics.

### How Existing Tools Handle Metadata

**Obsidian** stores vault-level metadata in `.obsidian/` and per-note metadata in YAML front matter. Maintains a SQLite cache inside `.obsidian/` treated as disposable (regenerated from markdown). Does not use per-file sidecar files.

**Logseq** embeds block identity directly in content as `id::` properties. Makes block UUIDs portable and stable but pollutes markdown. Explicitly not acceptable for SteleKit.

**Dendron** (deprecated) used a hierarchy file plus per-note YAML front matter with `id:` fields, and maintained a separate SQLite cache — the closest analog to SteleKit's need.

### Tradeoffs

| Approach | Pros | Cons |
|---|---|---|
| **Per-file sidecar** (`.stelekit/page.meta.json`) | Portable with page; diffs cleanly in git; naturally partitioned | Double the file count; rename must update both |
| **Single manifest file** | One file in git; easy atomic update | Grows unbounded; git merge conflicts; whole-vault lock on every write |
| **SQLite-only** | Fastest; rich query; no file proliferation | Binary conflicts badly in git; not human-readable |
| **YAML front matter in-file** | Self-contained; git diffs cleanly | Pollutes markdown; block-level IDs require per-block annotation |

### Recommendation for SteleKit

A `.stelekit/` subdirectory within the graph root with one JSON file per page, storing the block position-to-UUID mapping. Diffs readably in git, partitioned by page (no merge conflict bottleneck), regenerable from scratch if missing.

---

## 3. Fractional Indexing / Ordered-List CRDTs

**Confidence: HIGH** for core concepts; MEDIUM for exact algorithmic details of LSEQ and Fugue.

### The Problem with Integer Positions

Concurrent insertions at the same position have no deterministic winner without coordination. SteleKit's current `left_uuid` linked list avoids renumbering but makes concurrent insertions at the same position ambiguous.

### Logoot (2009)

Weiss, Urso, Molli. "Logoot: A Scalable Optimistic Replication Algorithm for Collaborative Editing on P2P Networks." ICDCS 2009.

Each element gets a position identifier: a tuple of (integer, site-id) pairs. Comparison is lexicographic. Concurrent insertions at the same position produce different identifiers via unique site IDs. Limitation: identifiers grow unbounded with insertions at the same location.

### LSEQ (2013)

Nedelec, Molli, Mostefaoui, Desmontils. "LSEQ: an Adaptive Structure for Sequences in Distributed Collaborative Editing." DocEng 2013.

Improves Logoot with adaptive allocation: identifier length grows logarithmically under typical patterns rather than linearly.

### Fugue (2023)

Weidner, Gentle, Kleppmann. "Fugue: Beating Flocks of Seagulls." arXiv preprint, 2023.

Avoids **interleaving anomalies** via a tree structure where concurrent insertions at the same position become siblings in a right-child chain, guaranteeing non-interleaving. Implemented in the Diamond Types library.

### Fractional Indexing (simpler alternative)

Each item has a rank string (`"a0"`, `"a1"`, `"a2"`). Inserting between `"a1"` and `"a2"` gives a midpoint like `"a1V"`. Strings compared lexicographically. Concurrent insertions with the same neighbors get the same string; tiebreaker (site ID suffix) resolves it. Used in Figma's multiplayer (David Greenspan, ~2020).

### Summary for SteleKit

For single-user git-sync, fractional indexing is sufficient and far simpler than a full sequence CRDT. The `left_uuid` linked list is equivalent in power but harder to merge. Switching to fractional index strings stored in the sidecar gives O(1) position lookup and git-diffable metadata.

---

## 4. Event Sourcing for Document Editors

**Confidence: HIGH** for ProseMirror and CodeMirror 6; MEDIUM for Tiptap internals.

### ProseMirror

Author: Marijn Haverbeke. Core model is a pure functional state machine:

- `EditorState` — immutable snapshot of the document
- `Transaction` — description of a state change applied to produce a new state
- `Step` — atomic invertible transformation (`ReplaceStep`, `AddMarkStep`, `RemoveMarkStep`)
- `Mapping` — tracks how position offsets shift as steps are applied, enabling rebase

Undo via `prosemirror-history`: done/undone stacks of `InvertedStep` + position mapping. Handles **rebasing**: inverted steps are remapped through new steps' position mappings. Step format is JSON-serializable — persistence across restarts requires writing steps to disk, which ProseMirror doesn't do by default.

### CodeMirror 6

Same conceptual model. `ChangeSet` is the actual text-change payload: sorted array of `(from, to, inserted)` tuples. Composable and invertible. A change set for a small edit is tiny even on a large document. Undo stores `ChangeSet` objects on the undo stack.

### What a Step/Transaction Looks Like for SteleKit

```json
{
  "op_id": "uuid-v7",
  "timestamp": 1712345678901,
  "session_id": "machine-A-session-1",
  "page_id": "page-uuid",
  "op_type": "UPDATE_CONTENT",
  "block_id": "block-uuid",
  "payload": {
    "before": "old content text",
    "after": "new content text"
  }
}
```

The `before` field enables inversion (undo). The `session_id` enables grouping and merge filtering.

### Implementing Undo from an Operation Log

1. **Undo a single op**: fetch most recent op for current session, apply its inverse (swap before/after for UPDATE; for INSERT emit DELETE; for DELETE emit INSERT with saved content).
2. **Redo**: after undo, mark undone op for replay via `undo_seq` or `undo_of` foreign key.
3. **Cross-restart undo**: ops are in SQLite; on app start, load last N ops for the current session.
4. **Undo boundary at merge**: limit undo to local ops (local `session_id`). Remote ops imported during git merge are non-undoable — same policy as git commit history.

The existing 500ms debounce groups keystrokes into single undo cells, equivalent to ProseMirror's `closeHistory` cell boundary.

---

## 5. Tree CRDT Merge Algorithm

**Confidence: HIGH** for the problem statement and Kleppmann 2021; MEDIUM for practical specifics.

### The Tree Merge Problem

Four operation types: Insert, Update, Move, Delete.

**Delete-move conflict**: machine A deletes block B while machine B moves block B to a new parent. After merge, is block B present (moved) or absent (deleted)?

**Move-move cycle**: machine A moves block X under Y, machine B moves Y under X. Creates an ancestor cycle that must be detected and resolved.

### Kleppmann 2021 — "A Highly-Available Move Operation for Replicated Trees"

Kleppmann, Tarr, Frazee, Wiggins. IEEE Transactions on Parallel and Distributed Systems, 2021.

Each move operation logged as `(timestamp, node, old_parent, new_parent)`. When merging:
1. Apply all operations in **timestamp order** (Lamport or hybrid logical clock).
2. If applying a move would create a **cycle**, the operation is **ignored**.
3. The "undo-move-redo" (UMR) technique handles out-of-order delivery: virtually undo later operations, insert the earlier one, redo later operations. O(n) in concurrent operations.

Key property: highly available — every replica applies local operations without coordination; replicas converge when they receive the same operation set regardless of delivery order.

Limitation: addresses tree structure (parent-child) but not sibling ordering. Sibling order requires a separate sequence CRDT or fractional indexing.

### Practical Merge for SteleKit (Git-Based Async Sync)

1. **Identify divergence point** — find the git common ancestor commit. Parse all three versions (base, ours, theirs) into block trees using sidecar UUID map.
2. **Compute operation sets** — derive operations from base→ours and base→theirs.
3. **Classify conflicts**:

| Operation pair | Conflict type | Resolution |
|---|---|---|
| Both updated content | Content fork | Preserve both; user resolves |
| A deleted, B updated | Delete-update | Keep updated version; mark for review |
| A deleted, B moved | Delete-move | Keep moved version; mark for review |
| A moved, B moved to different targets | Move-move | Apply in timestamp order; cycle check |
| A inserted, B inserted at same position | Ordering conflict | Tiebreak by (timestamp, site-id) |
| Only one side modified | No conflict | Apply directly |

4. **Apply non-conflicting operations** directly.
5. **Represent conflicts** — for conflicting blocks create a forked block record: two rows with the same `fork_group_id` distinguished by `fork_source` ("local" vs "remote"). UI renders side-by-side until user resolves.

### The `left_uuid` Linked List Under Merge

If machine A inserts block X with `left_uuid = Z` and machine B inserts block Y with `left_uuid = Z`, both have the same left neighbor. Resolution: order concurrent insertions by `(timestamp, machine_id)`. Switching to **fractional index strings** in the sidecar simplifies this: merge takes the union of both machines' index assignments and sorts; collisions resolved by site-ID tiebreaker suffix.

---

## Proposed Schemas

### Operation Log Table

```sql
CREATE TABLE ops (
    op_id       TEXT PRIMARY KEY,  -- UUID v7 (time-sortable)
    created_at  INTEGER NOT NULL,  -- Unix ms
    session_id  TEXT NOT NULL,     -- machine + session identifier
    page_id     TEXT NOT NULL,
    op_type     TEXT NOT NULL CHECK (op_type IN (
                    'INSERT_BLOCK','UPDATE_CONTENT','DELETE_BLOCK',
                    'MOVE_BLOCK','SET_PROPERTY')),
    block_id    TEXT NOT NULL,
    payload     TEXT NOT NULL,     -- JSON: before/after state, new_parent_id, etc.
    is_local    INTEGER NOT NULL DEFAULT 1,  -- 0 = imported from remote during merge
    undo_seq    INTEGER            -- position in local undo stack (NULL = not undoable)
);

CREATE INDEX ops_page_session ON ops(page_id, session_id, created_at);
CREATE INDEX ops_block        ON ops(block_id, created_at);
```

UUID v7 for `op_id` gives free temporal ordering and efficient range scans without a separate index on `created_at`.

### Sidecar File Layout

```
.stelekit/
  pages/
    <page-slug>.meta.json    -- per-page block UUID map + fractional indices
  graph.meta.json            -- graph UUID, schema version
```

Each `<page-slug>.meta.json`:

```json
{
  "schema_version": 1,
  "page_id": "uuid",
  "blocks": [
    {
      "block_id": "uuid",
      "position": "a0V",
      "parent_id": null,
      "content_hash": "sha256-short"
    }
  ]
}
```

The `content_hash` field detects whether the markdown file's block at this position has changed since the last sidecar write, enabling the "re-import is a no-op for unchanged content" requirement.

Use NDJSON (one block per line) instead of a JSON array for cleaner git diffs.

---

## Open Questions

1. **UUID v7 in KMP**: `java.util.UUID` generates v4 only. A lightweight KMP UUID v7 implementation is needed for time-ordered op IDs.
2. **Sidecar format for clean git diffs**: NDJSON (one block per line) diffs more cleanly than a JSON array when a single block's metadata changes.
3. **Op log compaction**: Fold ops older than N days into a snapshot at each sync boundary; discard raw ops after snapshot is confirmed by all peers.
4. **Cycle detection cost**: Kleppmann 2021 UMR checks for ancestor cycles on every move. For typical outliner depth (10-50 levels) trivial; add a depth limit for pathological cases.
5. **Logseq compatibility of sidecar**: Logseq will ignore `.stelekit/` directory silently. If user opens vault in Logseq, it will regenerate `id::` properties. Acceptable under "best-effort compatibility" but should be documented.

---

## Key References

| Item | Authors | Year | Venue | Confidence |
|---|---|---|---|---|
| "Local-First Software" | Kleppmann, Wiggins, van Hardenberg, McGranaghan | 2019 | Onward! / SPLASH | HIGH |
| "A Highly-Available Move Operation for Replicated Trees" | Kleppmann, Tarr, Frazee, Wiggins | 2021 | IEEE TPDS | HIGH |
| "Logoot: A Scalable Optimistic Replication Algorithm" | Weiss, Urso, Molli | 2009 | ICDCS | HIGH |
| "LSEQ: an Adaptive Structure for Sequences" | Nedelec, Molli, Mostefaoui, Desmontils | 2013 | DocEng | MEDIUM |
| "Fugue: Beating Flocks of Seagulls" | Weidner, Gentle, Kleppmann | 2023 | arXiv preprint | MEDIUM |
| Automerge library | Kleppmann et al. | 2017–2023 | GitHub/npm | HIGH |
| Yjs library | Kevin Jahns | 2015–2023 | GitHub | HIGH |
| ProseMirror | Marijn Haverbeke | 2015–2023 | prosemirror.net | HIGH |
| CodeMirror 6 | Marijn Haverbeke | 2021–2023 | codemirror.net | HIGH |
| Fractional indexing | David Greenspan | 2020 | Figma engineering blog | MEDIUM |
