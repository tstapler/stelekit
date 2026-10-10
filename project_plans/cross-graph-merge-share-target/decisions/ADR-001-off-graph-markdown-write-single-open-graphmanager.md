# ADR-001: Write to non-active graphs via pre-marked markdown, keep GraphManager single-open

**Status**: Proposed (gated by Spike 0.1.1 on a real filesystem plus device pass, Spike 0.1.2 SAF atomic replace, and Spike 0.1.4 RoundTripGuard pass rate)
**Date**: 2026-10-07

**Acceptance rule (Repair pass 6)**: stays Proposed until the Phase 0 checkpoint (plan.md "Phase 0 checkpoint"). It flips to Accepted only when ALL of: Spike 0.1.1 JVM real-filesystem test green AND Android device pass recorded here; Spike 0.1.4 measured pass rate recorded on a REAL graph (not only the synthetic generator) at 95% or more under the exact or relaxed structure-stable guard; Spike 0.1.2 answer (yes or no) recorded; Spike 0.1.5 result recorded per platform (a negative result does not block Acceptance, it narrows the iOS/Web consequence to "no source available" and is written into Revision 5). A Spike 0.1.1 failure with no working `FileRegistry` own-write mark, or a Spike 0.1.4 miss on the structure-stable form, sends this ADR to Rejected and the plan to re-plan (copy/share require an active target).

## Context
`GraphManager` holds one `RepositorySet` (`db/GraphManager.kt:99`); `switchGraph` closes the previous DB.
Merge (push from the active graph) and share-to-chosen-graph both write to a graph that is usually not open.
Options (research/architecture.md section 1-2): A1 multi-open GraphManager, A2 direct markdown write + lazy
reindex, A3 queued inbox, A4 staging directory (transport).

## Decision
- Keep `GraphManager` single-open. Add a `GraphLocator` (separate class over `graphRegistry`; resolves
  `GraphInfo`/path/StorageLocation by id without activating) and a per-graph-id write `Mutex` (`GraphWriteLock`).
  `GraphManager.kt` edits are limited to lock acquisition and a tiny `readyGraphId` accessor.
- **Revision 2 (repair)**: `switchGraph` (`GraphManager.kt:804`) is a non-suspend `fun`: it synchronously nulls
  `_activeRepositorySet` (via `tearDownActiveGraphResources`), launches an init coroutine that later creates the
  `RepositorySet`, and sets registry `activeGraphId` after launching. A Mutex therefore cannot guard the flip.
  The lock is taken INSIDE the init coroutine for the incoming id (factory + repository set + migrations) and
  around the previous graph's factory close. The router predicate is "`activeRepositorySet` non-null AND it
  belongs to the target graph" (never registry `activeGraphId` alone); if the registry says the target is
  active but init is unfinished, the router awaits `awaitPendingMigration()` (`GraphManager.kt:974`).
- Non-active target: `MarkdownTargetWriter` parses the target page file only to MATCH blocks, then
  **splices only the new blocks into the original file bytes** (`MarkdownSplicer`); it never re-renders the whole
  page through `LogseqPageSerializer`. A `RoundTripGuard` (`serialize(parse(file)) == file`) must hold before any
  rewrite, otherwise the write is refused (inbox for share; failed page kept in staging for merge). Write is
  temp file + rename where the FileSystem supports it; the graph reconciles through the normal
  `GraphLoader.loadDirectory` on next open. Active target: `ActiveTargetWriter` through
  `DatabaseWriteActor` + `GraphWriter` (existing self-write suppression). ONE `TargetWriterRouter` serves merge
  and share, picking per page at write time under the lock; both writers pass one shared contract-test suite.
  The `TargetWriter` port includes `deletePageFile`, `fileHash`, `removeBlocks` for Undo.
- **Revision 3 (repair N1, R1, R2)**:
  - Lock protocol: the router never awaits `awaitPendingMigration()` while holding a `GraphWriteLock`
    (the init coroutine needs `lock(B)` before it completes the deferred, `GraphManager.kt:958`). It awaits
    readiness first, then takes `lock(target)` and re-checks `readyGraphId == target`, retrying a bounded number of
    times (`_pendingMigration` is replaced on every `switchGraph`, `GraphManager.kt:845`). Lock order: never hold
    two graph locks; the init coroutine's previous-graph close and incoming-graph open are separate, non-nested
    critical sections. `readyGraphId` flips at ~line 904, before migrations; safe only because init holds
    `lock(B)` through them. The previous graph's scope is cancelled (line 825) before any lock, so the active
    writer's retryable-error path, not the lock, covers that window.
  - Active path is NOT byte-preserving and `RoundTripGuard` does not apply: `GraphWriter.savePage` re-renders the
    whole page through `LogseqPageSerializer`, as it does for every editor save. A merge into an active page the
    user never edited can therefore normalize its file (CRLF, trailing whitespace, parser-normalized constructs).
    This is accepted existing editor behavior; the contract-parity check compares block data (uuids, contents,
    properties), not file bytes.
  - `ActiveTargetWriter` sets `properties["id"] = uuid'` on each inserted block so the serializer emits `id::`;
    otherwise the reload derives a positional uuid and `((uuid'))` rewrites dangle.
- Platforms where off-graph write is unavailable (iOS/Web, capability policy in `TargetWriterCapabilities`):
  **Revision 4 (user decision)**: there is NO apply-on-activate. A copy to a target that cannot be written
  off-graph is disabled with a reason in the destination chooser; copies never queue and never wait for the
  target to open. iOS/Web copy therefore requires a writable target.
  **Revision 5 (user decision)**: iOS/Web get PULL-STYLE copy in v1. The user opens the destination graph (the
  active target, written by `ActiveTargetWriter`) and pulls pages from a chosen source graph by reading its
  markdown read-only through `SourceGraphReader` (no second DB open, bounded and chunked). Results are always
  definite, never queued. Gated on Spike 0.1.5 (can the inactive graph's files be listed and read?); a source
  that cannot be read is disabled with a reason. Spike 0.1.5 results are recorded here when run.
- Staging directory (`MergeStagingDirectory`) is the transport for merge, replacing `GraphMergeService`'s
  in-memory snapshot. Share (one block) does not stage. Staging format is structured JSON (`StagedPage`) that
  carries block UUIDs, not markdown (see ADR-002 rev. 2).
- Failure fallback for SHARES only (copies never use the inbox; their results are always definite): `ShareInbox` (app-private JSON) drained by an external `ShareInboxDrain`
  collector when the target graph is ready (`activeRepositorySet` non-null for it and `awaitPendingMigration()`
  returned; there is no `GraphActivated` event), with a visible "queued for <graph>" state.
- Encrypted graphs (`.md.stek`, `CryptoLayer`) and SAF targets without a verified write grant are NOT written
  off-graph. For a SHARE the inbox path is used (queued, drained when the graph is ready). For a COPY the
  destination is disabled with a reason in the chooser; if the capability changes mid-run (grant lost) that
  page fails definitively with Retry (router returns `WriteRefused(reason)`), never queued.
- **Revision 4 (spike hardening)**: Spike 0.1.1 must run on a real temp directory with the real `GraphLoader`,
  watcher and `FileRegistry` plus a manual Android device pass (a `FakeFileSystem` run does not count), and
  stays as a permanent regression test. Spike 0.1.4 measures the `RoundTripGuard` pass rate on a real exported
  graph (go/no-go ~95%); on a miss the guard is relaxed to "structure-stable" before any writer is built. The
  `GraphManager` lock edit ships as its own first PR with `withTimeout` acquisition, release in `finally`, and
  degrade-open (open the graph without waiting for the merge) on timeout. The `merge_force_inbox` setting is
  cut from v1.

## Rejected
- A1 multi-open: highest blast radius on a 1 478-line, high-churn class; second 8-connection pool on Android;
  target watcher/actor race. A3 as primary: breaks "lands in chosen graph in one step". Side-open of a throwaway
  RepositorySet: needs a DriverFactory second-driver guarantee not verified and still leaves the file in a state the watcher did not see.

## Consequences
No new DB surface (`@DirectSqlWrite` untouched on the off-graph path). Dry-run for non-active targets reads
disk, not DB. If Spike 0.1.1 shows a spurious `DiskConflict` on switch, fix by writing a `FileRegistry`
own-write mark record consumed at open; if that also fails, off-graph writes are cut and copy/share require the
target to be the active graph. There is no "staged, applied on next switch" mode for copies.

## Spike result (Story 0.1.1, JVM part, 2026-10-09)
Test: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/OffGraphWriteReconcileSpikeTest.kt` (commit 004f9f8299). Real temp dirs, real `PlatformFileSystem`/`GraphManager`/`GraphLoader`/watcher.
- New page file in an inactive graph reconciles after `switchGraph`: PASS (1 page, uuid `11111111-...`, 0 `ExternalFileChange`, no duplicate journal).
- Appended block reconciles on reopen: PASS (2 blocks, 0 events). Second reopen: PASS, 0 re-reads (the skip is DB-based, `page.updatedAt >= mtime`).
- `FileRegistry` holds a hash after reconcile: FAIL by design. `scanDirectory` records mtime only; hashes are lazy. The criterion wording is wrong, not a safety bug; the test is left red until the criterion is relaxed.
- Fallback note: `FileRegistry` is in-memory per `GraphLoader`, so an "own-write mark consumed at open" would need persistence (DB or disk). Not needed while the primary mechanism passes.
- Android device pass (Task 0.1.1b): NOT RUN. This ADR stays Proposed until it is recorded. Spikes 0.1.2 and 0.1.5 are also not run (no device).

## Spike result (Story 0.1.4, 2026-10-09; R2 triggered, then resolved by owner decision)
Test: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/merge/RoundTripGuardPassRateSpikeTest.kt` (commits 84c7975349, 2663f4cd75). Real graph: author's, 11013 files (`pages/` + `journals/`), read-only.
- First measurement: exact `serialize(parse(f)) == f` 11.96%; structure-stable 83.27%. Both below 95%, so re-plan trigger R2 fired.
- Owner decision: measure a splice-based guard and fix the serializer asymmetries. Fixes in commit 92c8472734 (task-marker re-spacing, lone `-` parsed as empty bullet, fenced-code indent inflation).
- After fixes: exact 12.01%; structure-stable 96.00% (10572); **splice-based 99.68% (10978/11013)** (root-append alone 99.71%, child-append alone 99.96%).
- Splice-based guard: parse the original with one new last root block and one new last child spliced in; untouched lines byte-identical; all pre-existing blocks unchanged in uuid/content/properties/nesting/order. A 0-byte file means "write just the new block".
- The 35 remaining failures are non-outline pages where a fence or heading swallows an appended bullet (32 root, 3 child). Refusal to inbox/stay-staged on those is the guard working as intended.
- Decision: the exact re-serialize guard is not viable (headings, prose, indent style and trailing whitespace are rewritten); the production `RoundTripGuard` is the splice-based predicate. Story 1.1.4 and Task 2.3.1 must adopt it. R2 is not triggered. Synthetic XLARGE (UNVERIFIED against real data): exact 27.46%, stable 100%, splice 100%.
- Caveat: child-append is a last-child insert so positional uuids on pages without `id::` stay stable; first-child insert would shift sibling uuids.
