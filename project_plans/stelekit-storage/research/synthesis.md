# Research Synthesis: SteleKit Storage Architecture

**Date**: 2026-04-13
**Sources**: findings-stack.md, findings-features.md, findings-architecture.md, findings-pitfalls.md

---

## Decision Required

Design a storage architecture for SteleKit that makes SQLite the authoritative CRDT state, uses markdown files as a human-readable serialization format, and supports persistent undo/redo, stable block identity (without embedding IDs in markdown), and semantically-aware git-based multi-device sync.

---

## Context

SteleKit currently treats SQLite as a disposable cache: every file re-parse runs `DELETE all blocks → INSERT all blocks`. This causes data loss on blank-file re-parses, FK violations when page writes fail, no stable block identity (UUIDs are content-derived and change on every edit), no persistent undo, and no merge semantics on git sync (last-write-wins). The architecture must be redesigned so sync is a merge operation, not a replace operation.

**Constraints driving the design:**
- No `id::` or `^anchor` metadata in markdown files — SteleKit files must be clean to external readers
- Best-effort Logseq compatibility — sidecar files acceptable; embedded block properties are not
- Git-based async sync (not real-time collaboration)
- Single user per machine; two or more machines sharing a git repo
- KMP (JVM + Android + iOS); SQLDelight 2.3.2
- Existing data must migrate; no clean-slate option

---

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| **A: Operation Log + Sidecar (Recommended)** | Append-only `operations` table + `.stelekit/` per-page UUID sidecar; markdown files are clean exports | Most complex to build; most capable; aligns with all requirements |
| **B: Position-only UUID + Diff Merge (no op log)** | Change UUID seed to drop content; implement diff-based merge (SKIP/UPDATE/INSERT/DELETE); no history | Fast to implement; no undo across restarts; no conflict forking; partial fix only |
| **C: Embed IDs in Markdown (`id::`)** | Follow Logseq classic: embed UUID as block child property in the `.md` file | Simplest; explicitly ruled out by requirement ("no embedded IDs") |
| **D: cr-sqlite** | Load native SQLite CRDT extension | Not viable on Android (extension loading disabled); incompatible with SQLDelight |
| **E: KMP CRDT library (synk or c-crdtlib)** | Use `CharlieTap/synk` or `concordant/c-crdtlib` as the CRDT layer | Maturity unverified; requires spike before adopting; may not integrate with SQLDelight |

---

## Dominant Trade-off

**Capability vs. implementation complexity.**

All capable options (operation log, CRDT library) require significant new infrastructure. The quick fix (Option B, position-only UUIDs + diff merge) delivers stable identity and better merge but still lacks persistent undo and forked-block conflict representation — which are explicit success criteria.

The architecture cannot meet the requirements incrementally from Option B up to Option A without a second full rewrite of the merge layer. Options B and A share the same UUID scheme change; the additional cost of Option A over B is primarily: (1) the operation log table, (2) the undo/redo machinery, and (3) the forked-block representation. These are separable concerns that can be built in phases.

The dominant tension is: **shipping something useful fast (Option B) vs. building the right foundation once (Option A)**. For a single-developer personal project with no tight deadline, Option A is correct. The phased delivery plan resolves this tension.

---

## Recommendation

**Choose**: Option A — Operation Log + Sidecar (phased delivery)

**Because**:
- Stable block identity without file pollution is only achievable via a sidecar — confirmed by surveying all comparable tools (Logseq DB, Roam, Notion all sacrifice file portability to achieve clean files; SteleKit's sidecar approach is the correct compromise)
- Persistent undo across restarts is a differentiating requirement no comparable tool provides; it requires an operation log (a simple diff-merge state never enables this)
- Block-level forked conflict detection (both versions preserved, user resolves) is more sophisticated than what any comparable tool provides; it requires both the operation log and a `fork_group_id` column in the blocks table
- The `left_uuid` linked list already provides RGA-style ordering; adding `session_id` + Lamport `seq` to the operation log completes the CRDT semantics without adopting an external library

**Accept these costs**:
- Significant implementation complexity: new `operations` table, `logical_clock` table, `DatabaseWriteActor` extension for batch/transactional ops, sidecar read/write on import/export, conflict-marker detection pre-import, undo pointer management
- Requires a one-shot UUID migration (atomic, cannot be gradual) from content-derived to position-derived UUIDs
- Compaction strategy deferred to after confirmed bidirectional sync — unbounded log growth in early versions is acceptable for personal-scale use

**Reject these alternatives**:
- **Option B (no op log)**: Rejected because it cannot deliver persistent undo or forked-block conflicts — two of six success criteria. Building B then upgrading to A requires rewriting the same merge layer twice.
- **Option C (embed `id::`)**: Explicitly ruled out by requirements. Logseq DB version confirms the community backlash when file readability is degraded.
- **Option D (cr-sqlite)**: Not viable on Android. No maintained KMP wrapper. Incompatible with SQLDelight managed driver.
- **Option E (KMP CRDT library)**: `CharlieTap/synk` and `concordant/c-crdtlib` exist but their production maturity is unverified (see Open Questions). A manual RGA implementation (~200 lines) tied to the existing `left_uuid` structure is lower risk for the first version. Library adoption can be revisited after a spike.

---

## Architecture Decisions Required (ADRs)

The following ADRs must be written before implementation begins. Each decision is load-bearing; changing it mid-implementation would require significant rework.

| ADR | Decision | Status |
|-----|----------|--------|
| ADR-001 | Block UUID scheme: position-only hash vs. creation-time random UUID vs. UUID v7 | To write |
| ADR-002 | Sidecar file format: per-page NDJSON vs. graph-level manifest vs. YAML front matter | To write |
| ADR-003 | Operation log schema: columns, payload format (JSON blob vs. normalized), compaction strategy | To write |
| ADR-004 | Merge algorithm: how git-sync triggers reconciliation; forked-block representation | To write |
| ADR-005 | Undo/redo model: undo barrier at sync boundary; BATCH_OPERATION wrapper for find-replace | To write |
| ADR-006 | UUID migration: one-shot migration script approach, FK cascade strategy | To write |

---

## Phased Delivery Plan

The architecture is deliverable in three phases. Each phase is independently shippable and improves on the previous.

### Phase 1 — Stable Identity + Diff Merge (Option B)

**Delivers**: Success criteria 4 (stable block identity), 5 (clean markdown), 6 (no-op re-import)

1. Change `generateUuid` seed: drop `content`, keep `filePath + parentUuid + siblingIndex`
2. One-shot SQLite migration: re-derive all UUIDs, update FK references (backlinks, properties)
3. Replace `DELETE all + INSERT all` with diff-based merge: SKIP unchanged, UPDATE changed, INSERT new, DELETE removed
4. Add conflict-marker detection before import (hard error)
5. Ensure `.db`, `.db-wal`, `.db-shm` are in `.gitignore`

**Does NOT deliver**: Undo/redo (criteria 1), git sync merge (criteria 2), find-replace (criteria 3)

### Phase 2 — Operation Log + Undo

**Delivers**: Success criteria 1 (undo across restarts), progress toward criteria 2

1. Add `operations` table + `logical_clock` table to SQLDelight schema
2. Add `session_id` to DB metadata; generate per-device session ID on first run
3. Extend `DatabaseWriteActor` to write ops to log as side-effect of all mutations
4. Implement `UndoManager`: load last N ops for current session; invert and re-apply
5. Mark sync events as undo barriers in the op log

**Does NOT deliver**: Full git-sync merge (criteria 2), find-replace batch undo (criteria 3)

### Phase 3 — Sidecar + Git Sync Merge

**Delivers**: Success criteria 2 (git sync), 3 (find-replace)

1. Implement `.stelekit/pages/<slug>.meta.json` NDJSON sidecar read/write
2. On import: reconcile file block tree against sidecar UUID map against op log
3. Fork-detection: when same UUID has diverging op history, create `fork_group_id` rows
4. Conflict UI: side-by-side forked block display until user resolves
5. `BATCH_OPERATION` wrapper in op log for find-replace atomicity

---

## Open Questions Before Committing to Phase 1

- [ ] **UUID scheme** — Should Phase 1 use position-derived hash (same derivation, minus content) or switch to random UUID on first stable parse? Position-derived gives deterministic IDs from file content; random UUIDs need the sidecar from day one. Blocks decision on ADR-001.
- [ ] **Migration atomicity** — The content-to-position UUID migration must update all FK references in one transaction. Verify that SQLDelight's migration system can run a Kotlin-side migration (not just DDL), or whether a manual migration step is needed. Blocks ADR-006.
- [ ] **KMP CRDT library spike** — `CharlieTap/synk` and `concordant/c-crdtlib` could replace the manual RGA implementation in Phase 3. A 1–2 hour spike (read source, check SQLDelight integration, check Android build) should happen before Phase 3 planning. Does NOT block Phase 1 or 2.
- [ ] **Sidecar format for git diff quality** — NDJSON (one block per line) vs. JSON array vs. UUID-per-line plain text. Need to verify that NDJSON diffs cleanly in `git diff` with standard tooling. Blocks ADR-002.

If the UUID scheme question (first open question) is resolved before Phase 1 begins, Phase 1 can proceed without blocking on any other question. The sidecar and CRDT library questions block Phase 3 only.

---

## Key Risk Register

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Concurrent insert interleaving at same `left_uuid` | Medium (only on concurrent multi-device edits) | Data ordering corruption | Total order for concurrent inserts: `(session_id, seq)` tiebreaker |
| Git conflict markers ingested as block content | High (any conflicted git pull) | Silent data loss | Hard error before import if conflict markers detected |
| UUID migration breaks backlinks | High (all existing data affected) | All cross-page links broken | Single-transaction migration; test on copy before running on production graph |
| `.db` file committed to git | Low (user must explicitly `git add`) | Binary conflict, history bloat | `.gitignore` entry; guard on DB open detecting git-tracked status |
| Undo stack invalidated by post-sync remote ops | Medium (multi-device use) | Confusing undo behavior | Sync barrier in op log; ops before barrier are non-undoable |
| KMP CRDT library not production-ready | Medium | Phase 3 delay | Fallback to manual ~200-line RGA; spike before Phase 3 |

---

## Sources

- [findings-stack.md](findings-stack.md) — SQLite event sourcing patterns, KMP CRDT libraries, SQLDelight 2.x patterns, cr-sqlite assessment
- [findings-features.md](findings-features.md) — Logseq, Obsidian, Roam, Notion, Bear block identity and sync models; local-first software principles
- [findings-architecture.md](findings-architecture.md) — Kleppmann 2021 move algorithm, fractional indexing, ProseMirror transaction model, sidecar formats, op log schema design
- [findings-pitfalls.md](findings-pitfalls.md) — CRDT correctness issues, SQLite event log performance, git sync markdown conflicts, undo history storage pitfalls
