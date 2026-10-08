# Implementation Plan: cross-graph-merge-share-target

**Feature**: Block-level, idempotent "Copy pages to..." between graphs (picker, filters, linked pages/assets, dry-run, undo) plus a chosen-graph share/capture target on Android and Desktop (Settings default + per-share override), with iOS/Web via the shared flow and inbox.
**Date**: 2026-10-07
**Status**: Planned. **Only Phase 0 (Spikes 0.1.1-0.1.5) is authorised work right now**; no Phase 1+ story starts before the go/no-go checkpoint (see "Phase 0 checkpoint"). Not ready to implement any story marked GATED until its Phase 0 spike is green. ADR-001..004 stay "Proposed" until their spike results are appended (same gate; ADR-by-spike mapping in the checkpoint section), so plan and ADR statuses agree. Spikes are unrun BY DESIGN: they are the first work, not a defect of the plan.
**ADRs**: ADR-001 (off-graph markdown write, GraphManager stays single-open), ADR-002 (block identity, remap, conflicts, aliases, conflict-removal semantics), ADR-003 (undo scope, linked-page closure), ADR-004 (share inbox keying when no graph exists)

**Vocabulary (one term set, used by plan, UX, ADRs, logs)**: copy results are `new` / `combined` / `unchanged` (plus `conflicts`, a SUBSET of `combined`, and `unreadable`/`failed`); requirements.md's "merged" means `combined`. Share failures are `queued` (internal class `ShareInbox`, UI nouns "queued" / "Queued for <graph>"; the UX phrase "Save for later" is retired). Copies are never queued. The share-only `merge_force_inbox` setting is cut from v1 (decision 3, Repair pass 4), so no `merge_`-prefixed share key exists.

**Final user decisions (Repair pass 4)**: (1) a copy whose target cannot be written off-graph is DISABLED WITH A REASON in the destination chooser; copies never queue to the inbox; results are always definite; iOS/Web copy requires the target to be the active graph (no apply-on-activate for copies) and is delivered as a PULL-STYLE copy (decision 4); a grant lost mid-run fails that page definitively with Retry, never queues. (2) Desktop Esc with typed capture text asks before discarding; Android Back auto-saves to the chosen graph or, on failure, the inbox. (3) `merge_force_inbox` is cut entirely. Share may still use the inbox. (4) **Repair pass 5, final**: iOS and Web get PULL-STYLE COPY in v1. The user opens the DESTINATION graph (so the target is active) and runs "Copy pages from...", choosing a SOURCE graph whose markdown is read read-only (no second DB open). Results are always definite (`new`/`combined`/`unchanged`/`failed`), never queued. Gated on Spike 0.1.5; a source whose files cannot be read is disabled with a reason. The earlier "no destination on iOS/Web, accepted limitation" text is retired everywhere.

Path aliases used below (all exact): `{C}` = `kmp/src/commonMain/kotlin/dev/stapler/stelekit`; `{CT}` = `kmp/src/commonTest/kotlin/dev/stapler/stelekit`; `{BT}` = `kmp/src/businessTest/kotlin/dev/stapler/stelekit`; `{J}` = `kmp/src/jvmMain/kotlin/dev/stapler/stelekit`; `{A}` = `androidApp/src/main/kotlin/dev/stapler/stelekit` (CaptureActivity/CaptureViewModel live here, VERIFIED by `ls`; the pitfalls note that they were not found under `kmp/src` is resolved). Every new businessTest class must be added to `{BT}/AllBusinessTests.kt` (`AllBusinessTestsCompletenessTest` enforces it).

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `MergeId` | Unique id of one copy run (staging dir, manifest, log correlation) | Value class over String |
| `GraphId` | Existing id of a registered graph | Reuse; never raw string for target/source |
| `MergeBlock` | Pure, IO-free block node: `uuid?`, `content`, `properties`, `children` | Converted from `Block` / `ParsedBlock` |
| `MergePage` | Pure page: `name`, `isJournal`, `journalDate?`, `properties`, `blocks: List<MergeBlock>` | Input/output of `mergePage` |
| `MergePolicy` | Parameters for `mergePage`: `blockKey` function, `shortContentMinLength`, `sourceGraphId`, `sourceGraphName` | Key function injectable for tests |
| `MergeOutcome` | Sealed: `New(page)`, `Unchanged`, `Merged(page, added, conflicts)` | Exhaustive `when` |
| `BlockConflict` | Same UUID, different content; incoming kept as flagged sibling | `targetUuid`, `incomingUuid'`, `pageName` |
| `SourceBlockRef` | `src-id::` property value `<sourceGraphId>:<sourceUuid>` | Provenance + idempotent match |
| `UuidRemap` | Map sourceUuid -> uuid' for EVERY inserted block, on every target (active and off-graph), derived from a strong hash (SHA-256, truncated to 128 bits, formatted as a UUID) of `merge:<sourceGraphId>:<sourceUuid>` | Also rewrites `((uuid))` refs. No `isTakenInTarget` oracle (ADR-002 rev. 2). Replaces the weak FNV `generateDeterministic` for merge |
| `StagedPage` | `@Serializable` JSON record of one source page (`MergePage` incl. every block uuid, props, children) | Staging format; markdown is never the transport (ADR-002 rev. 2) |
| `MarkdownSplicer` | Pure: `(originalFileText, newBlocksToInsert) -> newFileText` that edits only inserted ranges; every untouched byte is preserved | Off-graph write path; never re-renders the whole page |
| `RoundTripGuard` | `serialize(parse(file)) == file` check run before any off-graph rewrite; failure -> `NotRoundTrippable` -> queued (share) / failed page kept in staging with Retry (copy). Strictness (exact vs "structure-stable") is settled by Spike 0.1.4 BEFORE the writers are built | Belt-and-braces over the splicer |
| `TargetWriterCapabilities` | Owns every "can I write there" decision: crypto layer, SAF grant, platform FS reach. Reasons: `Encrypted`, `NoGrant`, `SafInboxOnly` (only if Spike 0.1.2 is "no"), `PlatformUnsupported`. There is no `ForcedInbox` reason (cut) | Consumed by `TargetWriterRouter` and by the copy destination chooser (disabled-with-reason); `PageFileResolver` has no policy |
| `PageSelection` | Set of selected page keys (by page uuid) plus filter state, independent of list position | Survives search/filter changes |
| `SelectionFilter` | Journals/pages, date range, namespace, tag | Pure predicate + SQL-bounded query |
| `LinkClosurePolicy` | `Off` / `Depth1(cap)`; default Off, confirm > 200, hard cap 1000 | ADR-003 |
| `DryRunSummary` | Counts: new / combined / unchanged / conflicts / unreadable + assets renamed + bounded conflict detail list. `conflicts` is a SUBSET of `combined`; the "Copy N pages" count is `new + combined` | Counters only, no page bodies |
| `WriteRefused(reason)` | Typed `Left` from the router when `TargetWriterCapabilities` says the target cannot be written (including a grant lost mid-run). Copy: that page fails definitively (listed with Retry). Share: `InboxFallbackAppender` turns it into `Queued(reason)` | Copies never queue |
| `MergePlan` | Result of `PlanMerge`: per-chunk classification, the `DryRunSummary`, and `planFingerprint` | Recomputed at commit |
| `MergeStagingDirectory` | `<appDataDir>/.stele-merge-staging-<MergeId>/` with `.marker` JSON and one markdown file per page | Marker-or-never 7-day sweep |
| `MergeManifest` | Per-run JSON of created files (+hash) and added block UUIDs | Drives undo (ADR-003) |
| `PageSource` | Bounded reader of selected pages from the source (paged, <=100 rows) | Port; `ActiveDbPageSource` |
| `TargetWriter` | Port: `readExisting(page)`, `write(page, mergePage)`, `deletePageFile(page, expectedHash)`, `fileHash(page)`, `removeBlocks(page, uuids, expectedHashes)` (the last three serve Undo); impls `MarkdownTargetWriter`, `ActiveTargetWriter`; one shared contract-test suite runs against both | ADR-001 |
| `TargetWriterRouter` | The ONE router for merge and share. Picks writer per page at write time under `GraphWriteLock` using the readiness predicate (see Task 2.1.2). Share adds an `InboxFallbackAppender` decorator; there is no second router | Active vs off-graph (vs inbox, share only) |
| `GraphLocator` | Resolves `GraphInfo`, path, `StorageLocation` by `GraphId` without activating | Separate class over `GraphManager.graphRegistry` (a `StateFlow`); not implemented inside `GraphManager.kt` |
| `GraphWriteLock` | Per-`GraphId` `Mutex`. Taken by the `switchGraph` init coroutine for the incoming id across factory create + repository-set creation + migrations (through `deferred.complete`), and by the teardown/close of the previous id | Prevents merge/open/teardown races; see Task 2.1.1c/2.1.2 |
| `PageFileResolver` | Pure: page name/journal flag/journal-filename format -> `pages|journals/<name>.md` path. Path only; no write-capability policy | Extracted from `GraphWriter.getPageFilePath` (private, ~line 790: `FileUtils.sanitizeFileName(page.name)`, no journal-date logic, verified) |
| `PageMergeService` | Orchestrator: plan, stage, apply, record, undo | Replaces `GraphMergeService` |
| `CaptureTarget` | Sealed: `ActiveGraph`, `NamedGraph(GraphId)` + resolved availability | Share destination |
| `CaptureTargetSettings` | Wrapper over `Settings`: `capture_default_graph_id`, `capture_last_graph_id`, `capture_remember_last` | Strings only |
| `CaptureTargetResolver` | last-used (if enabled and still valid) -> default -> active graph | Never silently redirects; UI shows result |
| `JournalAppender` | Shared commonMain service: append block to journal of a date in a `CaptureTarget` | Extracted from `CaptureViewModel`/`CaptureController` |
| `ShareInbox` | App-private durable queue of failed SHARES per `GraphId` (never copies) | Visible "queued" state; drained by `ShareInboxDrain` when `GraphManager.activeRepositorySet` becomes non-null for that id AND `awaitPendingMigration()` returned (there is no `GraphActivated` event; grep of `kmp/src/commonMain` finds none) |
| `AppendOutcome` | Sealed: `Appended`, `AlreadyPresent`, `Queued(reason)`, `Failed(error)` | Idempotent via `captureId` |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Cross-graph write architecture | Strategy (`TargetWriter`) + Router; ports and adapters | GoF / Hexagonal | Multi-open GraphManager; queue-only inbox; CRDT | See ADR-001; avoids touching a 1 478-line hot class; inbox breaks one-step metric; CRDT needs shared history |
| Creative alt: "Pull-on-switch" (stage, user switches, apply via active path) | Rejected | - | Chosen A2 push | Hidden two-step mode is today's UX bug; no longer kept even as an apply-on-activate path (Repair pass 4); interrupted copies Resume by re-planning |
| `mergePage` | Pure function (functional core) | type-driven-design | Method on a service / Transaction Script | Property-testable once for 4 platforms (CLAUDE.md) |
| `MergeOutcome`, `AppendOutcome`, `CaptureTarget`, `LinkClosurePolicy` | Sealed interfaces | type-driven-design | Booleans / nullable | Exhaustive handling; illegal states unrepresentable |
| `MergeId`, `SourceBlockRef`, `PageSelection` | Value classes | type-driven-design | raw String | Prevent id confusion across graphs |
| `PageMergeService` | Service Layer (coordinates plan/stage/apply) | PoEAA | Extending `GraphMergeService` snapshot flow | Snapshot is the O(graph) violation |
| Page file path | Extract pure resolver | Refactoring | Duplicate logic in merge | Filename/namespace encoding must match `GraphWriter` |
| Merge transport | Staging directory with marker | existing `RelocationStagingDirectory` convention | In-memory list | 8k-page SLO, process death |
| Undo | Command log / manifest | PoEAA (Unit of Work-like record) | Pre-merge snapshot | Heavy at 8k pages; manifest safe by construction |
| Selection | Value object keyed by page uuid + Specification-style `SelectionFilter` | DDD Specification | Index-based selection | Search/filter must preserve selection |
| Share append | Shared `JournalAppender` service (two adapters) | PoEAA Service Layer | Keep duplicated VM/controller logic | Both entry points already duplicate it |
| Idempotent writes | Deterministic UUID from captureId / source uuid | existing `CaptureWriter` approach | Dedup by timestamp | Survives `onNewIntent` redelivery and repeat merge |

---

## Tech Debt Disposition

| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `{C}/db/GraphManager.kt` (1 478 lines, 51 commits/6 mo) | God object, single-open assumptions everywhere | **Isolate via seam** | `GraphManager.kt` edits are limited to: (1) taking `GraphWriteLock` inside the `switchGraph` init coroutine and around previous-graph teardown (Task 2.1.1c), (2) exposing a tiny `readyGraphId` accessor set next to `_activeRepositorySet` (Task 2.1.2a). `GraphLocator` is a separate class over `graphRegistry`; `ShareInboxDrain` is a separate collector wired in `StelekitAppDependencies`. No multi-open state |
| `{C}/transfer/GraphMergeService.kt` | Whole-graph in-memory snapshot, skip-by-name, string-list results | **Refactor-first** | Stories 2.2-2.4 replace it before any UI; old class becomes a deprecated adapter until Story 3.4.2 deletes it |
| `{A}/CaptureViewModel.kt`, `{J}/capture/CaptureController.kt`, `{C}/capture/CaptureWriter.kt` | Duplicated active-graph-bound append chain | **Refactor-first (small)** | Story 4.1.2 extracts `JournalAppender` before adding the target parameter (adding a parameter to each copy would double the debt) |
| `{C}/db/GraphWriter.kt` `getPageFilePath` (private) | Path logic not reusable; no journal-date-to-filename logic | **Isolate via seam** | Task 1.2.2 extracts `PageFileResolver`; `GraphWriter` delegates (behavior-preserving, existing tests cover); journal mapping is added with a loader round-trip test (Task 1.2.2d) |
| `{C}/ui/GraphContentLeftSidebar.kt` (hidden two-step export/merge state) | UX debt: mode lives in a service across a graph switch | **Refactor-first** | Story 3.4.1 replaces with single "Copy pages to..." entry |

---

## Migration Plan
No new TABLE: provenance is the `src-id::` block property, undo state is JSON files, share inbox is JSON files. Restated: **queries only, no table** - Task 2.4.1d adds bounded read-only queries to `SteleDatabase.sq` for the picker (filtered page list + filtered count). Consequences: (1) no `MigrationRunner.all` entry (no `CREATE TABLE`; `MigrationRunnerSchemaSyncTest` unaffected); (2) SQLDelight regeneration IS required and the regenerated `kmp/src/generated/sqldelight/` must be committed (`./gradlew :kmp:generateCommonMainSteleDatabase` + `rsync` per CLAUDE.md; CI job "SQLDelight generated sources" enforces); (3) read-only queries need NO `RestrictedDatabaseQueries` stub (`@DirectSqlWrite` applies to INSERT/UPDATE/DELETE/UPSERT only), but any write query added by another task must get one; (4) `QueryPlanAuditTest` gets entries for the new queries. If a task unexpectedly adds a table it must also follow the CLAUDE.md migration rule.
- **Reversibility**: purely additive code; the manifest undo reverses data changes per run.
- **Rollback procedure**: revert PR; stale `.stele-merge-staging-*` and `merge-manifests/` are swept by the 7-day marker sweep.

## Observability Plan
- **Local-only metrics (Repair pass 6)**: requirements.md M1-M5 are read from these same `Logger` lines (no network); Story 5.1.3 pins the keys with a contract test.
- **Logs** (`Logger`): `PageMergeService` entry/exit with `mergeId`, source/target `GraphId`, `direction`, counts new/combined/unchanged/conflicted/failed/assets-renamed; each failed page logs `error + pageName`. `JournalAppender` logs target `GraphId`, writer used (`active|markdown|inbox`), `AppendOutcome`.
- **Metrics**: `merge.plan.duration_ms`, `merge.apply.pages_per_s`, `share.append.duration_ms` (any op > 100 ms) via existing telemetry/`Logger` summaries.
- **User-visible**: result dialog (not only snackbar) for merge; share overlay never dismisses on failure, shows queued state, and a persistent pending-inbox indicator.
- **Alerts**: no new alerts required.

## Risk Control
- **Feature flag**: not gated (new UI entry points only), per requirements. Off-graph writer sits behind `TargetWriterRouter`. The `merge_force_inbox` developer toggle is CUT from v1 (user decision, Repair pass 4): no setting, no `ForcedInbox` reason, no UX S16. If Spike 0.1.1 or 0.1.4 fails, the response is a design change (see the spike fallbacks), not a runtime toggle.
- **Lock risk**: the `GraphManager` lock edit (Task 2.1.1c) ships as its own first PR with bounded acquisition and degrade-open behavior, because a deadlock there freezes graph switching for every user, including those who never copy.
- **Rollback**: standard revert via PR; per-run undo (ADR-003).
- **Staged rollout**: Desktop + Android first (Phases 2-4), iOS/Web pull-copy (Epic 4.5) enabled after wasm/iOS compile, Spike 0.1.5 and FileSystem verification (Epic 5.1); it is scheduled after the core Android/Desktop slice (see Scope Cut Line).

## Unresolved Questions
- [ ] Does a markdown file written into a closed graph reconcile on `switchGraph` on a REAL directory with the real `GraphLoader`, watcher and `FileRegistry` (plus a manual Android device pass) without a spurious `DiskConflict`? — GATES Stories 2.3.1 and 4.1.3 — owner: Spike 0.1.1
- [ ] Can iOS (security-scoped bookmark / document-picker URL) and Web (`FileSystemDirectoryHandle` from IndexedDB, or OPFS) read the markdown files of a REGISTERED but INACTIVE graph, read-only, without opening it in `GraphManager`? — GATES Stories 4.5.2 and 4.5.3 per platform and per graph; a negative result disables pull-copy for that platform/graph with a reason, it does not queue anything — owner: Spike 0.1.5, runs in Phase 0 with the other four spikes (Repair pass 6); its answer does not gate the Gate 1 core slice but is a checkpoint input
- [ ] Does SAF `rename`/`moveDocument` give atomic replace? — blocks SAF-target branch of Story 2.3.1 (fallback: SAF targets become copy-disabled `SafInboxOnly`; shares to them are queued; see Scope Cut Line) — owner: Spike 0.1.2, RUN FIRST
- [x] DECIDED (no longer a spike outcome): `id::` emission is a committed task (Story 1.1.4, Task 1.1.4a) and staging is structured JSON (`StagedPage`), because `LogseqPageSerializer` emits only `block.properties` (no `id::`; `LogseqPageSerializer.kt:38-44`, per adversarial review) and `INSERT OR REPLACE` keyed on uuid is `SteleDatabase.sq:335`. Spike 0.1.3 now only *confirms* parser behavior (explicit `id` property used verbatim by `MarkdownPageParser.generateUuid`) and records the collision outcome (throws / replaces / ignores) that sizes the clobber guard in Task 1.2.1c. Gated stories (hard dependency on Story 1.1.4): 1.1.2, 1.1.3, 1.2.1, 2.3.1, 2.3.2, 2.4.2, 4.1.3, 5.1.1.
- [x] DECIDED (Repair pass 6): journal filename mapping. Rules and home are in Story 1.2.2 ("Journal filename rules"): creation uses `JournalUtils.formatDateForJournal(date)` (`YYYY_MM_DD`, VERIFIED `outliner/JournalUtils.kt:32`), discovery reuses any existing stem matching `^(\d{4})[-_](\d{2})[-_](\d{2})$` (VERIFIED regex, `JournalUtils.kt:12`), and a graph whose `config.edn` declares a non-default journal file-name format is `PlatformUnsupported("journal format")` off-graph (no code in `kmp/src/commonMain` reads such a setting today, grep for `file-name-format` finds none, so the guard is a conservative stop, not a feature). Still to be PROVEN, not assumed: Task 1.2.2d (loader round trip) and Spike 0.1.1 (no duplicate journal).
- [ ] `RoundTripGuard` strictness: what fraction of pages in a REAL exported graph satisfy `serialize(parse(file)) == file`? Go/no-go threshold ~95%. If it misses, the guard is relaxed to "structure-stable" BEFORE any writer is built; otherwise most copies fail per page and most shares queue. — GATES Stories 2.3.1, 2.3.2 (off-graph path) and 4.1.3 — owner: Spike 0.1.4 (Task 1.1.4d now only adds the permanent fixture tests)
- [ ] Android long-run host (application scope vs WorkManager) for 8k-page copies — a Phase 0/1 DECISION (see "Phase 0 checkpoint", Repair pass 7), blocks Task 3.3.1c only; DEFAULT: application scope + persisted "interrupted" marker — owner: implementer records or confirms at Phase 1 start, revisit after Epic 5.1 soak
- Resolved by ADRs: alias storage (page property `alias`), conflict marker (flagged sibling, `merge-conflict::`), undo scope (last run), closure default/cap.

## Dependency Visualization
```
Phase 0 Spikes (order: S0.1.2 FIRST, then the rest):
  S0.1.2 (SAF rename, decides Android story) ─┐
  S0.1.1 (REAL-FS reconcile + device pass) ───┤ GATES 2.3.1, 4.1.3
  S0.1.4 (RoundTripGuard pass rate >= ~95%) ──┤ GATES 2.3.1, 2.3.2 off-graph, 4.1.3 (before writers are built)
  S0.1.3 (id:: roundtrip) ────────────────────┘
Phase 2 PR #1 (stand-alone): Task 2.1.1c GraphManager lock edit (bounded, degrade-open, CI stress) ─► everything else in Phase 2
Phase 1: 1.1.1 mergePage ─► 1.1.4 id:: emission + StagedPage + splice (COMMITTED, gates 1.1.2/1.1.3/1.2.1/2.3.x/2.4.2/4.1.3/5.1.1)
         1.2 UuidRemap (SHA-256, always-remap), PageFileResolver (path only)
              │
Phase 2: 2.1.1 GraphLocator+Lock ─► 2.1.2 readiness predicate (in-flight switch) ─► 2.3 TargetWriters+router (GATED S0.1.1/0.1.2, 1.1.4) ─┐
         2.2 StagingDir+Manifest ─────────────────────────────────────────┼─► 2.4 PageMergeService ─► 2.5 Undo
              │                                                            │
Phase 3: 3.1 Selection VM ─► 3.2 Picker UI ─► 3.3 DryRun/Progress/Result ─► 3.4 Entry points + delete old
                                                    ▲ needs 2.4, 2.5
Phase 4: 4.1 CaptureTargetSettings + JournalAppender (needs 1.1, 2.1, 2.3) ─► 4.2 Android overlay (4.2.2 Direct Share = OPTIONAL, post-MVP)
                                                                         └─► 4.3 Desktop popup
         4.4 ShareInbox (needs 2.1.2) ─► 4.2, 4.3
         4.5 iOS/Web pull-copy (needs 2.3.2 ActiveTargetWriter, 2.4, 3.1-3.3, Spike 0.1.5; scheduled AFTER the Android/Desktop core slice; copies never queue)
Phase 5: 5.1 Large-graph/regression/wasm/iOS compile ─► docs
```

---

## Scope Cut Line and derived scope

The original 3-6 week appetite is SUPERSEDED by the 2026-10-08 re-baseline (Gate 1 about 11-17 weeks at the point estimate, about 23 weeks at the +40% edge and 4 h/day; see "Effort estimate"). The task count is not an effort measure (about 141 `##### Task` headings, 93 of them labelled "~5 min", which is a checklist granularity, not a schedule). The schedulable unit is the story; see "Effort estimate" below, which does NOT fit the original 3-6 weeks. The plan states the cut line now rather than discovering it in week 4. Release gates are mirrored in requirements.md.

**Release gates (Repair pass 6)**
- **Gate 0**: Phase 0 spikes 0.1.1-0.1.5 and the go/no-go checkpoint. Only this is authorised now.
- **Gate 1 (core slice, first release)**: the pure merge function (Stories 1.1.x, 1.2.x); Desktop + Android push copy (Epics 2.1-2.4 except 2.4.3 which may trail, Story 2.5.1 Undo, Stories 3.1.1, 3.2.1, 3.3.1, 3.4.1, 3.4.2); the default capture graph (Story 4.1.1, 4.1.2); the share-target graph override (4.1.3, 4.2.1, 4.3.1); a minimal share inbox that queues and counts failed shares (Story 4.4.1 base: enqueue, drain, indicator, atomic persistence) because REQ-17 forbids silent loss, WITH its rescue actions Copy text / Discard / Retry now (Task 4.4.1d) and the persistent Android indicator; `ConflictReviewScreen` (Story 3.3.2), because Gate 1 flags conflicts and must let the user resolve them; Phase 5 tests and docs. (Repair pass 7, 2026-10-08: Story 3.3.2 and Task 4.4.1d moved INTO Gate 1 from Gate 2 so that no Gate 1 surface dead-ends, UX-30.) Undo and the minimal inbox are in Gate 1 because requirements REQ-16/REQ-17 need them, not because the core definition names them (flagged for owner confirmation).
- **Gate 2 (requirement-complete; committed, NOT on the cut list)**: linked pages and assets UI and engine (Story 2.4.3, REQ-5); last-used copy destination (Task 3.1.1d); the `UNASSIGNED` inbox slot (no-graphs-configured shares, ADR-004; 2 h of Story 4.4.1).
- **Gate 1 variant of S2 (picker)**: Gate 1 ships the picker WITHOUT the "Include linked pages" toggle and its assets sub-toggle (hidden, not disabled: there is no explanation a Gate 1 user could act on), and WITHOUT a preselected last-used destination (the Destination control starts empty; helper line "Choose a destination"). Everything else in S2 (search, filters, select-all, counters, Review) is Gate 1. Task 3.2.1 therefore renders the two controls behind a `gate2LinkedPages` flag that is off until Story 2.4.3 lands; no extra hours (the flag is part of the 15 h of 3.2.1). (The Android Back confirmation toast, Task 4.2.1h, is in Gate 1 with the Back auto-save it explains.)
- **Gate 3 (iOS/Web pull-copy, user decision 4, conditional on the demand criterion in requirements.md and Spike 0.1.5)**: Epic 4.5.
- **Optional / cut list**: below.

**iOS/Web pull-copy placement**: Epic 4.5 is scheduled AFTER the core slice (below) and after the Phase 2 mid-point checkpoint, in parallel with Phase 4 where staffing allows. Estimate (superseded by the hour table in "Effort estimate"): 3 stories, about 41 h (7-10 working days at 4-6 h/day, ~10% of the full-scope hours; the earlier "16 tasks, ~1 week, ~15%" figure counted 5-minute checklist items); it reuses `mergePage`, `StagedPage`, `PageMergeService`, `ActiveTargetWriter`, the picker/dry-run/result/undo UI, so new code is the source reader, the capability type, the direction variant and its tests.

**Core slice (Gate 1, must ship)**: see the Gate 1 definition above. Mid-point checkpoint: end of Phase 2 (go/no-go on whether Phase 3-4 fit), in addition to the Phase 0 checkpoint. **Numeric overrun threshold (Repair pass 7)**: at the end of Phase 2, compare actual hours logged for Phases 0-2 with the table's hours for the stories actually built (29 + 46 + 120 = 195 h, less 10 h if Story 2.4.3 is deferred to Gate 2 = 185 h). If actuals exceed the table by more than 25%, STOP and re-plan: re-estimate Phases 3-4 with the measured ratio of actual to estimated hours, report the new Gate 1 finish estimate to the owner, and take the cut-or-extend decision (option (b) of the effort estimate, or a new appetite) before starting Phase 3. At 25% or less over, continue and record the ratio. Actual hours come from the owner's own per-story log (not invented here).

**Cut first, in order (all optional, nothing else depends on them)**: Story 4.2.2 Direct Share shortcuts (UX S14); then, as a DOWN-SCOPING of iOS/Web pull-copy (Epic 4.5, see below), never as a silent drop. REMOVED from this list in Repair pass 6: `ConflictReviewScreen` (3.3.2) and the pending-shares rescue actions "Copy text"/"Discard" (S13), because they back stated requirements ("true conflicts flagged", "never silent loss"); MOVED INTO GATE 1 in Repair pass 7 (they are not cuttable and Gate 1 would dead-end without them). The linked-page closure UI (`Depth1` toggle) is NOT silently cuttable either: REQ-5 puts linked pages/assets in scope, so cutting it needs an amendment to requirements.md; Story 2.4.3 may ship engine-first within Gate 2. Pull-copy down-scoping order: (a) the graph-switcher row entry point (Task 4.5.3e) and the journal date-range filter, (b) the second of the two platforms (ship pull-copy on whichever platform passes Spike 0.1.5 and compiles first), (c) Story 4.5.3 mid-run Retry-failed polish beyond the S6 default. If after (a)-(c) the appetite is still exceeded, the iOS/Web destination chooser reverts to "no source available" with Close; that reversal contradicts the v1 decision and needs the user's explicit call.

**Derived / optional scope (no explicit requirement line; each is justified by an NFR or a requirement it supports)**:

| Item | Status | Derives from |
|---|---|---|
| Interrupted-copy Resume (UX S9), Task 3.3.1c Android application-scope host | Derived | 8 000-page NFR (copies outlive an activity) |
| Conflict review screen (3.3.2), conflict badge | Committed, Gate 1 (moved from Gate 2 in Repair pass 7; NOT cuttable) | "true conflicts flagged"; UX-30 no dead ends |
| Pending-shares indicator and rescue actions Copy text/Discard/Retry now (S13), both Gate 1 (Repair pass 7); the `UNASSIGNED` slot is Gate 2 | Committed | REQ-17 / Observability: "visible failure state rather than silent loss"; UX-30 |
| Story 4.2.2 Direct Share (S14) | Optional / cut line | None |
| Last-used copy destination (UX S3) | Gate 2, Task 3.1.1d | UX S3; small setting `copy_last_destination_graph_id` |
| Staging-dir marker sweep, `MergeManifest` | Derived | Risk Control (undo) + 8 000-page NFR |

**If Spike 0.1.2 (SAF atomic replace) is "no"**: Android share target degrades as follows. SAF-backed graphs are not written off-graph (`SafInboxOnly`): a share to a SAF graph that is not the active one is QUEUED in `ShareInbox` and drained when that graph becomes ready; a share to the active graph is unaffected. Copies to a non-active SAF graph are DISABLED WITH A REASON in the chooser ("Open that graph to copy into it"); copying into the ACTIVE SAF graph still works. The one-step share-to-chosen-graph metric then holds on Desktop and on Android for non-SAF (plain-folder) graphs and for the active graph, and is explicitly NOT met for inactive SAF graphs. Because 0.1.2 runs first, this decision is made before Phase 4 is scheduled; it must be recorded in ADR-001 and in requirements follow-up.

**Share "one step" metric depends on Spike 0.1.4**: the requirement "a share lands in the chosen graph in one step" is only met for the fraction of target files that pass `RoundTripGuard` (or its relaxed "structure-stable" form). Story 4.1.3 reports the measured Spike 0.1.4 pass rate; the metric is declared met only at or above the go/no-go threshold.

---

## Phase 0 checkpoint (go/no-go) and re-plan triggers

**Authorised now**: Spikes 0.1.1, 0.1.2, 0.1.3, 0.1.4, 0.1.5 and appending their results to ADR-001/002. **Not authorised until the checkpoint is recorded**: every Phase 1+ story, the `GraphManager` lock PR (Task 2.1.1c), SQLDelight changes, UI work. (Unrun spikes are the design: this is the evidence-gathering step.)

| Spike | Pass condition (record in the named ADR) | Fail -> consequence |
|---|---|---|
| 0.1.2 SAF atomic replace (run first) | Yes/no recorded with evidence | "No" is not a stop: inactive SAF graphs become `SafInboxOnly` (copy disabled with reason, shares queued); requirements B3 excludes inactive SAF graphs. Flips nothing by itself; required input to ADR-001 |
| 0.1.4 `RoundTripGuard` pass rate | P >= 95% on a REAL graph under the exact guard, or under "structure-stable" after relaxing | **P < 95% exact, relax and re-measure.** **If structure-stable is also < 95%: RE-PLAN** (re-plan trigger R2): off-graph writes are not viable for this graph shape; copy/share require an active target, ADR-001 is Rejected, Epics 2.3.1/4.1.3/4.5 scope and the "choose a non-active graph" half of the feature are redone. Synthetic-only result = UNVERIFIED, checkpoint stays open until a real graph is measured |
| 0.1.1 real-FS reconcile + Android device pass | JVM test green (0 `DiskConflict`, no duplicate journal, `FileRegistry` state correct) AND device pass recorded | **Re-plan trigger R1**: try the `FileRegistry` own-write mark (ADR-001 fallback); if that fails too, off-graph writes are cut, the feature becomes active-target-only, ADR-001 Rejected, Epic 4.5 and Gate 1's non-active destination are re-planned |
| 0.1.3 uuid round trip / collision | Facts recorded | Replace-with-cascade outcome makes the clobber guard (Task 1.2.1c) mandatory and amends ADR-002 before Phase 1 continues; not a stop |
| 0.1.5 iOS/Web read-only source | Per platform and storage kind recorded | Negative: Gate 3 is cut or narrowed for that platform with the S3 "no source available" state (not queued); a user decision that conflicts with the v1 commitment goes back to the owner; not a stop for Gate 1 |

**Go decision** (owner records it as a dated note at the top of this file): GO to Phase 1 iff 0.1.1 and 0.1.4 pass (R1 and R2 not triggered), 0.1.2/0.1.3/0.1.5 are recorded, and the owner has answered the open items in requirements.md (primary persona/frequency, A-DEMAND thresholds; the appetite re-baseline was answered 2026-10-08). NO-GO or RE-PLAN otherwise.

**Product inputs to the Go decision (Repair pass 7; all OWNER, nothing here is measured)**:
- **Pre-build demand probe** (cheap, runs in parallel with the spikes): the owner self-logs for 2 weeks, or does a one-off count over recent history, of (a) cross-graph page copies done by hand and (b) shares made while a non-target graph was active, with the graph switches and taps each took. Result recorded as a dated note next to the Go decision. A result near zero is the cheapest available evidence for the Freeze criterion and should prompt the owner to reconsider before spending Gate 1's roughly 333 h; thresholds are the owner's (OWNER INPUT NEEDED).
- **Falsifiable user-value claim** (requirements.md): today a share to a non-active graph takes N graph switches (N UNMEASURED; the probe measures it); success = 0 switches and 1 tap (Save) with the default destination.
- **Opportunity-cost line** (OWNER INPUT NEEDED): what roughly 3-4 months of the single implementer's time displaces. Not invented here; the owner states it in the Go note.
- **Android long-run host decision** (moved here from Unresolved Questions): application scope versus WorkManager for 8k-page copies is decided at Phase 1 start, not left to Story 3.3.1c. DEFAULT: application scope (SteleKitApplication-owned scope with a CoroutineExceptionHandler) plus a persisted interrupted marker and the S9 notice; switch to WorkManager only if the Epic 5.1 soak shows the process is killed mid-run in practice. Owner: implementer records the choice (or confirms the default) in this file before Task 3.3.1c starts.
- **Wording validation** is scheduled before the Gate 1 release (see validation.md "Wording validation step"), not after Gate 2.

**ADR status flips at the checkpoint**:
| ADR | Becomes Accepted when | Becomes Rejected / re-opened when |
|---|---|---|
| ADR-001 | 0.1.1 green + device pass, 0.1.4 >= 95% on a real graph, 0.1.2 and 0.1.5 recorded | 0.1.1 fails with no working own-write mark (R1), or 0.1.4 structure-stable < 95% (R2) |
| ADR-002 | 0.1.3 recorded (a, b, c as listed in the ADR) | 0.1.3 shows `id::` does not round trip and the merge renderer cannot fix it |
| ADR-003 | Flips with ADR-001 (it relies on both writers' hash-checked removal; no spike tests it alone) | Re-opened if ADR-001 is Rejected (undo then runs only through `ActiveTargetWriter`) |
| ADR-004 | Flips with ADR-001 (same inbox and drain) | Re-opened only if the inbox design changes |

## Sequencing against branch `fix/graph-switch-notes-path` (Repair pass 6)

Branch `fix/graph-switch-notes-path` (11 commits ahead of `main`, VERIFIED by `git log main..HEAD`) edits the same `GraphManager.kt` that Task 2.1.1c edits (`git diff --stat main...HEAD` shows +80/-8 in that file). Order: (1) land that branch on `main` first, (2) rebase/branch the lock PR from the updated `main`, (3) only then start Task 2.1.1c. Phase 0 spikes touch no production code and are not blocked by this. Task 2.1.1c is explicitly **its own PR** (with Task 2.1.1b and `GraphManagerSwitchLockStressTest`): `withTimeout` acquisition, release in `finally`, **degrade-open on timeout** (the graph opens without waiting for a merge), debug-build lock-order guard; nothing that depends on it merges until it is green on all platforms in CI. If the notes-path branch is not merged by the time Phase 1 ends, the lock PR waits rather than resolving a conflict in a 1 478-line hot file.

**Explicit dependency (Repair pass 7)**: the Lock PR (Task 2.1.1c) and therefore ALL of Phase 2 depend on `fix/graph-switch-notes-path` being merged to `main` first. Owner of that merge: the user. Date: none set (OWNER INPUT NEEDED: the user should either merge it, or say it will not merge soon so the lock PR can be based on the branch instead). This is a critical-path item: if it is still unmerged when Phase 1 completes, Phase 2 cannot start on the hot file and the schedule slips by the wait.

## Effort estimate (Repair pass 6)

**Basis and honesty**: hours are the plan author's per-story judgement (tests, review round-trips and CI iteration included), NOT measurements and NOT derived from the "~5 min" task labels; assume about +/-40%. One working day is taken as 4-6 productive hours (5 as midpoint). Each total below was derived by summing this table.

| Story | h | | Story | h |
|---|---|---|---|---|
| 0.1.1 real-FS reconcile + device | 8 | | 3.1.1 selection VM (+3.1.1d last-used 3, +3.1.1e probing state 1) | 12 |
| 0.1.2 SAF atomic replace | 3 | | 3.2.1 picker (+skeleton, large text, RTL, Web keys 3) | 15 |
| 0.1.3 uuid round trip | 2 | | 3.3.1 dry-run/progress/result (+Stop wording, loading 2) | 16 |
| 0.1.4 RoundTripGuard pass rate | 8 | | 3.3.2 conflict review (Gate 1 since Repair pass 7; +a11y, wording, loading 4) | 12 |
| 0.1.5 iOS/Web read-only probe | 8 | | 3.4.1 entry points | 5 |
| **Phase 0 subtotal** | **29** | | 3.4.2 delete `GraphMergeService` | 4 |
| 1.1.1 merge function | 8 | | **Phase 3 subtotal** | **64** |
| 1.1.2 property tests | 6 | | 4.1.1 capture settings + resolver | 6 |
| 1.1.3 converters/rendering | 6 | | 4.1.2 `JournalAppender` extract | 6 |
| 1.1.4 identity + splicer + guard | 14 | | 4.1.3 append to non-active graph | 12 |
| 1.2.1 uuid remap | 6 | | 4.2.1 Android overlay (+Back toast, resolver loading 3) | 19 |
| 1.2.2 `PageFileResolver` (+journal rules, traversal tests 1) | 6 | | 4.3.1 Desktop chooser | 10 |
| **Phase 1 subtotal** | **46** | | 4.4.1 share inbox (base 12 + rescue actions 4 = 16 in Gate 1; + unassigned slot 2 in Gate 2 = 18) | 18 |
| 2.1.1 locator + write lock (own PR) | 14 | | **Phase 4 subtotal (4.1-4.4; excl. optional 4.2.2 = 6)** | **71** |
| 2.1.2 router readiness | 10 | | 4.5.1 direction/gating/quick-add (+4.5.1f 3) | 11 |
| 2.2.1 staging dir | 5 | | 4.5.2 `SourceGraphReader` | 14 |
| 2.2.2 manifest | 4 | | 4.5.3 pull flow (+name-index loading 2) | 16 |
| 2.3.1 `MarkdownTargetWriter` (+traversal/symlink 3) | 17 | | **Epic 4.5 subtotal** | **41** |
| 2.3.2 `ActiveTargetWriter` + router + contract + recopy property | 16 | | 5.1.1 large-graph/resilience | 10 |
| 2.3.3 capabilities | 4 | | 5.1.2 docs | 3 |
| 2.4.1 bounded queries (split, see Task 2.4.1d1-d7) | 16 | | 5.1.3 Bazel check + log-line contract | 5 |
| 2.4.2 plan/apply | 16 | | **Phase 5 subtotal** | **18** |
| 2.4.3 linked pages/assets (Gate 2) | 10 | | | |
| 2.5.1 undo | 8 | | | |
| **Phase 2 subtotal** | **120** | | | |

| Scope | Hours | Days at 6 / 5 / 4 h/day | Weeks (5-day) at 6 / 5 / 4 |
|---|---|---|---|
| Phase 0 only (authorised now) | 29 | 4.8 / 5.8 / 7.2 | 1.0 / 1.2 / 1.4 |
| **Gate 1 core slice incl. all of Phase 0** (29 + 46 + 110 (Phase 2 less 2.4.3) + 61 (Phase 3 less 3.1.1d) + 69 (Phase 4 less the 2 h unassigned slot; includes base inbox 12 + rescue actions 4) + 18) | **333** | 55.5 / 66.6 / 83.3 | 11.1 / 13.3 / 16.7 |
| Gate 1 at the +/-40% band (200 h to 466 h) | 200 to 466 | 33.3 to 77.7 at 6 h/day; 50.0 to 116.6 at 4 h/day | 6.7 to 15.5 at 6 h/day; 10.0 to 23.3 at 4 h/day |
| Gate 2 additions (2.4.3 10 + 3.1.1d 3 + inbox `UNASSIGNED` slot 2) | 15 | | |
| Gate 3 (Epic 4.5) | 41 | | |
| **Full scope** (Gates 1-3; excludes optional 4.2.2 = 6 h) | **389** | 64.8 / 77.8 / 97.2 | 13.0 / 15.6 / 19.4 |

Check: 333 + 15 + 41 = 389. (Gate 1 was 317 and Gate 2 was 31 before Repair pass 7 moved Story 3.3.2 (12 h) and the inbox rescue actions (4 h) into Gate 1: 317 + 16 = 333; 31 - 16 = 15; the full-scope total is unchanged.)

**Staffing assumption (Repair pass 7)**: the hour-to-week conversion assumes ONE implementer working the critical path serially (Phase 0, 1, 2, then 3 and 4), 4-6 productive hours per day, with Android (SAF, device passes) and Desktop work interleaved, not parallel. Parallel agent workers (subagent-driven implementation) can take independent stories off the critical path (for example Story 1.2.1, 2.2.x, 4.1.1/4.1.2, and the Phase 3 screens once the 2.4 interfaces exist) and could shorten calendar time, but they do NOT remove review, CI iteration, or the serial chain 2.1.1c, 2.1.2, 2.3.x, 2.4.2. The table is therefore the single-implementer figure; any speed-up from parallel workers is to be measured at the Phase 2 checkpoint, not assumed.

**Does it fit the original 3-6 weeks (15-30 working days)? No.** The full scope needs about 13-19 weeks. The Gate 1 core slice (333 h after Repair pass 7) needs about 11-17 weeks at the point estimate and about 23 weeks at the +40% edge and 4 h/day; it does not fit the 6-week top end even at the optimistic edge (200 h at 6 h/day is about 6.7 weeks). Only Phase 0 plus a deeper cut fits. **Owner decision made 2026-10-08: option (a), re-baseline; Undo and the minimal inbox stay in Gate 1.** Original options: (a) re-baseline the Large appetite to roughly 11-17 weeks for Gate 1 (it was 11-16 weeks when Gate 1 was 317 h) (and treat Gates 2/3 as follow-on appetites gated by the demand criterion), or (b) cut deeper before Phase 1, for example ship Gate 1 as Desktop-only push copy plus active-graph share override (drops Android off-graph/SAF work, Spike 0.1.2 consequences and most of Story 4.2.1) and add Android later; neither option has been costed beyond this table.

## Phase 0: Spikes (the first and ONLY authorised work before the go/no-go checkpoint; timeboxes equal the Effort estimate hours: 0.1.1 and 0.1.4 are 8 h (1 day) each, 0.1.5 is 8 h (0.5 day per platform, two platforms), 0.1.2 is 3 h, 0.1.3 is 2 h; total 29 h; output = test + note appended to the ADR)

**Run order**: Spike 0.1.2 first (its answer sizes the Android story and the Scope Cut Line), then 0.1.4 (determines guard strictness before any writer exists), 0.1.1, 0.1.3, 0.1.5 (iOS/Web read-only source access; parallelisable with 0.1.3). All five are authorised now; nothing in Phase 1 or later is started until the "Phase 0 checkpoint" decision is recorded. Throw-away spike code (Tasks 0.1.4a, 0.1.5a) is not production code and is replaced in Phase 1.

### Epic 0.1: Verify unverified architecture assumptions
**Goal**: Convert the two architecture-research INFERRED claims and the UUID round-trip gap into executable evidence before building on them.

#### Story 0.1.1: Spike - markdown written into a closed graph reconciles cleanly on a REAL filesystem (1 day)
**As a** developer, **I want** proof, on a real temp directory with the real `GraphLoader`, file watcher and `FileRegistry` (not `FakeFileSystem`), that a file written into a non-active graph is indexed on next open with no `DiskConflict`, **so that** ADR-001's off-graph writer is safe. A `FakeFileSystem` run does not count: the watcher, `FileRegistry` hash/mtime state and SAF behave differently on real disks (pre-mortem failure #1).
**Gates**: Stories 2.3.1 and 4.1.3 do not start until the JVM test below is green AND the manual Android pass is recorded.
**Acceptance Criteria**:
- A page file added to graph A while B is active appears (page + blocks) after `switchGraph(A)`, on a real directory.
  - *Given* graphs `A` and `B` registered from `Files.createTempDirectory` roots, `B` active, real `GraphLoader` + watcher + `FileRegistry` wired as in production, and `A/pages/Spike.md` = `- hello\n  id:: 11111111-1111-1111-1111-111111111111` written directly with `java.nio`, *When* `graphManager.switchGraph(A)` completes and loading settles, *Then* `PageRepository.getPagesByNames(["spike"])` returns 1 page, its single block has uuid `11111111-...`, `GraphLoader.externalFileChanges` emitted 0 `DiskConflict`, and no duplicate journal page exists for today's date.
- A modified existing page (appended block) also reconciles.
  - *Given* A already indexed with `Spike.md` containing 1 block then closed, *When* a second block is appended on disk (real write, new mtime) and A is reopened, *Then* the page has 2 blocks and 0 `DiskConflict`.
- Watcher/FileRegistry state.
  - *Given* the above, *Then* `FileRegistry` holds the new hash/mtime for the file after reconcile and a second reopen performs no re-parse of that file (asserted via loader counter).
- Manual Android device pass (recorded in ADR-001 with date, device, API level, graph kind: plain folder and SAF).
  - *Given* a device with graphs A (inactive) and B (active), *When* a page file is added to A through the SAF/file API the writer will use and A is opened, *Then* the page and block appear with no "page changed on disk" prompt and no second journal.
- Permanent regression: the JVM test is kept (not deleted after the spike) and is extended in Story 5.1.1 as `OffGraphReconcileRegressionTest`.
**Fallback if it fails**: record the failing mechanism; ADR-001 adopts a `FileRegistry` own-write mark consumed at open. There is no "staged, applied on next switch" fallback for copies (user decision 1); if the mark approach also fails, off-graph writes are cut and copies/shares require the target to be the active graph.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/OffGraphWriteReconcileSpikeTest.kt` (new; jvmTest because it needs `java.nio` temp dirs; run via `./gradlew jvmTest --tests '*OffGraphWriteReconcileSpikeTest'`, headless, no display needed), `project_plans/cross-graph-merge-share-target/decisions/ADR-001-off-graph-markdown-write-single-open-graphmanager.md`

##### Task 0.1.1a: Write the reconcile test on a real temp directory with the real loader/watcher/`FileRegistry` (~half day)
- Mirror setup of existing `GraphManager*Test` and `LargeGraphWarmStartCrashTest` (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/`); no production changes.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/OffGraphWriteReconcileSpikeTest.kt`
##### Task 0.1.1b: Run the manual Android device pass (plain folder and SAF), record results (~2 hours)
##### Task 0.1.1c: Record result, append "Spike result" section to ADR-001 (date, JVM output, device notes); update Unresolved Questions and unblock/keep-blocked Stories 2.3.1 and 4.1.3 (~15 min)

#### Story 0.1.2: Spike - SAF atomic replace (3 h)
**As a** developer, **I want** to know if SAF supports temp-file + rename replace, **so that** I pick the write strategy for SAF graphs.
**Acceptance Criteria**:
- A documented yes/no with evidence.
  - *Given* a SAF tree URI of a test graph on an emulator/device, *When* writing `Spike.md.tmp` then renaming over existing `Spike.md`, *Then* either `Spike.md` holds the new content with no leftover `.tmp` (yes) or the call throws/duplicates (`Spike (1).md`) (no, recorded).
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/SafAtomicReplaceSpikeTest.kt` (new, Robolectric where feasible), notes in ADR-001

##### Task 0.1.2a: Read `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` write/rename paths; write spike test or manual device script (~5 min)
##### Task 0.1.2b: Run on emulator (`bazel`/`./gradlew` android config), record outcome; if "no": mark SAF targets `SafInboxOnly` (copy: disabled with reason; share: queued) in ADR-001, Story 2.3.1 and Story 2.3.3, and record the degraded Android story from the Scope Cut Line (~5 min). RUN THIS SPIKE FIRST.

#### Story 0.1.3: Spike - `id::` and UUID round trip, import and collision behavior (2 h)
**As a** developer, **I want** verified UUID behavior, **so that** idempotent remap (ADR-002) rests on facts.
**Acceptance Criteria**:
- Round trip preserves UUID.
  - *Given* `Block(uuid=22222222-2222-2222-2222-222222222222, content="x")` on `Page(name="P")`, *When* `LogseqPageSerializer.serialize` then `MarkdownPageParser` / `OutlinerPipeline` parse it, *Then* the parsed block uuid equals `22222222-...` (or the test documents that `id::` is absent and what must be added).
- Collision behavior is known.
  - *Given* block uuid `U` already in the DB on page `P1`, *When* the repository saves another block with uuid `U` on `P2`, *Then* the test asserts and documents whether it throws, replaces (cascade risk), or ignores.
- `QrImportService.import` UUID handling documented.
**Files**: `{BT}/transfer/MergeUuidRoundTripSpikeTest.kt` (new), `{BT}/AllBusinessTests.kt`, `project_plans/cross-graph-merge-share-target/decisions/ADR-002-merge-identity-and-conflict-model.md`

##### Task 0.1.3a: Test serializer->parser round trip for uuid and `id` in `Block.properties` (~4 min)
##### Task 0.1.3b: Test duplicate-uuid insert via `SqlDelightBlockRepository` (in-memory driver) and read `QrImportService.kt` import path (~5 min)
##### Task 0.1.3c: Append "Spike result" to ADR-002 (collision outcome sizes the clobber guard in Task 1.2.1c; `id::` emission is NOT conditional any more - it is Task 1.1.4a). Cite `QrImportService` at `transfer/qrcode/QrImportService.kt` (~3 min)

#### Story 0.1.4: Spike - `RoundTripGuard` pass rate over a real exported graph (1 day; runs BEFORE any writer is built)
**As a** developer, **I want** the measured fraction of real pages with `serialize(parse(file)) == file`, **so that** the guard's strictness is settled before the writers exist, instead of discovering mid-Phase 1 that most off-graph writes will refuse (pre-mortem failure #2).
**Gates**: Stories 2.3.1, the off-graph path of 2.3.2, and 4.1.3. Story 1.1.4's guard implementation takes its strictness from this result.
**Acceptance Criteria**:
- Measurement over real data.
  - *Given* a real exported graph (the author's own, about 8k pages, passed as a path argument `-Pspike.graphPath=/path/to/graph` / env `SPIKE_GRAPH_PATH`), or, when no path is given, the repo's synthetic generator `kmp/src/commonTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphContent.kt` at XLARGE scale (the same scale `scripts/benchmark-local.sh` uses), *When* every `pages/*.md` and `journals/*.md` file is run through `RoundTripGuard`, *Then* the spike prints total files, pass count, pass percentage, and the first 20 failing files with a one-line diff reason, grouped by cause (line endings, trailing whitespace, property ordering, unsupported construct).
- Go/no-go.
  - *Given* the pass percentage P, *Then*: P >= 95% => keep the exact guard `serialize(parse(file)) == file`. P < 95% => before writers are built, relax the guard to "structure-stable" (`parse(serialize(parse(file)))` equals `parse(file)` block-for-block: uuids, contents, properties, nesting; the splicer still preserves untouched bytes) and re-measure; record both numbers. If structure-stable is still < 95%, STOP and escalate (off-graph writes are not viable for this graph shape; fall back to "copy/share requires the target to be the active graph").
- Synthetic result is not a substitute.
  - *Given* only the synthetic generator was run, *Then* the ADR notes the result as UNVERIFIED against real data and Story 4.1.3's metric claim is withheld until a real graph is measured.
- Permanent regression: the measured fixtures that fail are added as named fixtures to Story 1.1.4's `merge-fixtures/` (with the guard result asserted).
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/merge/RoundTripGuardPassRateSpikeTest.kt` (new; jvmTest because it walks a real directory; skipped with an explicit "no graph supplied" message when neither input is available), notes in ADR-001
##### Task 0.1.4a: Land a throw-away `RoundTripGuard` (exact form) and `MarkdownPageParser` + `LogseqPageSerializer` round trip used only by the spike; Story 1.1.4 later replaces it with the production guard (~half day)
##### Task 0.1.4b: Run on the author's graph and on the synthetic XLARGE graph, record pass %, failure groups and decision in ADR-001 and in Unresolved Questions (~2 hours)
##### Task 0.1.4c: If P < 95%, specify the "structure-stable" predicate in ADR-001 and update Story 1.1.4's guard AC accordingly before any writer task starts (~1 hour)

#### Story 0.1.5: Spike - read an inactive graph's markdown read-only on iOS and Web (0.5 day per platform, 8 h for both; gates Epic 4.5)
**As a** developer, **I want** proof that a registered-but-inactive graph's `pages/` and `journals/` files can be listed and read without activating the graph, **so that** pull-copy (Story 4.5.3) is built only where it can work.
**Acceptance Criteria**:
- Readability per platform.
  - *Given* two registered graphs A (active) and B (inactive) on an iOS simulator/device and in Chrome (wasmJs), *When* a throw-away `PlatformFileSystem` call lists B's `pages/` and `journals/` and reads three files (one 1 MB+ file included) without calling `GraphManager.switchGraph`, *Then* the spike prints listing time, per-file read time, peak heap delta, and whether a user gesture/permission prompt was needed.
- Failure outcomes recorded.
  - *Given* a stale iOS bookmark, a revoked/expired Web handle permission, an OPFS graph, and a Safari/Firefox browser (no `showDirectoryPicker`), *When* the same read runs, *Then* each is recorded as readable, re-grantable (`NoGrant`, with the platform action that re-grants), or unreadable (`PlatformUnsupported`).
- Decision.
  - *Given* the results, *Then* ADR-001 records, per platform and per graph storage kind, which sources are enabled; a negative platform result leaves that platform with the S3 "no source available" state and Story 4.5.3 unbuilt for it (Epic 4.5 is cut for that platform, not queued, not deferred).
**Files**: throw-away spike code only; notes in ADR-001
##### Task 0.1.5a: Throw-away list+read probe over `PlatformFileSystem` for a non-active graph path/handle (~half day)
##### Task 0.1.5b: Run on iOS, Chrome, Safari/Firefox; record outcomes and the re-grant action per case in ADR-001 and Unresolved Questions (~half day)

---

## Phase 1: Pure core (no IO; runs on all four platforms from commonTest)

### Epic 1.1: Block-level merge function
**Goal**: A pure, property-tested `mergePage(existing, incoming, policy)` meeting the success metrics.

#### Story 1.1.1: Merge model and function
**As a** user, **I want** same-named pages combined without losing blocks, **so that** nothing from either graph disappears.
**Acceptance Criteria**:
- Union with target order preserved.
  - *Given* target `Projects` = ( `A`(id a1), `B`(id b1) ) and incoming `Projects` = ( `B`(id b1), `C`(id c1) ), *When* `mergePage`, *Then* result blocks = ( `A`, `B`, `C` ), outcome `Merged(added=1, conflicts=none)`.
- Same content, different UUID dedups under a matched parent.
  - *Given* target ( `Buy milk`(id t1) ) and incoming ( `Buy milk`(id s1) ), *When* `mergePage`, *Then* `Unchanged`.
- Same UUID, different content is a flagged sibling.
  - *Given* target ( `Draft v1`(id x1) ) and incoming ( `Draft v2`(id x1) ), *When* `mergePage`, *Then* result = ( `Draft v1`(x1), `Draft v2`(uuid' ≠ x1, `merge-conflict:: true`, `merge-source:: Work`) ) and `conflicts.size == 1`.
- Edited-after-copy, then re-copied (repair R4; rule in ADR-002 rev. 3).
  - *Given* a previous run inserted `Draft v1` as `uuid'1` with `src-id:: g:S`, the user edited it in the target to `Draft v1 edited`, and the source block `S` is unchanged, *When* the source is copied again, *Then* the target block is untouched, ONE conflict sibling is added with content `Draft v1`, `src-id:: g:S`, `merge-conflict:: true`, and `uuid'' = H("merge-conflict:" + g + ":" + S + ":" + contentHash(normalized incoming))`, `uuid'' != uuid'1`; *When* copied a third time, *Then* `Unchanged` (an existing block under the same parent with `src-id g:S` and equal normalized content is the match). If the source later changes to `Draft v2`, a second conflict sibling with a different `uuid''` is added once and is then also `Unchanged` on repeat.
- Unlabeled target blocks keep their positional uuids (repair R3).
  - *Given* a target page whose blocks have no `id::` (uuids are positional: `MarkdownPageParser.generateUuid` seed `"$pagePath:$parent:$index"`, `MarkdownPageParser.kt:59`), *When* any merge inserts blocks, *Then* placement appends after the last sibling (rule in Task 1.1.1c) so the index, and thus the parsed uuid, of every pre-existing unlabeled block and its descendants is unchanged after re-parse.
- Property union; scalar clash keeps target.
  - *Given* target props `{tags: a, status: open}` and incoming `{tags: b, status: done}`, *When* merged, *Then* props = `{tags: a, b, status: open}` and the `status` clash is recorded in the outcome.
- Short content needs parent context.
  - *Given* target `Parent1`→( `TODO` ) and incoming `Parent2`→( `TODO` ), *When* merged, *Then* both `TODO` blocks survive under their own parents.
- Absent existing page.
  - *Given* `existing = null`, *When* merged, *Then* `New(incoming)` with remapped uuids per ADR-002.
**Files**: `{C}/merge/MergeModel.kt`, `{C}/merge/MergePage.kt`, `{CT}/merge/MergePageExamplesTest.kt`

##### Task 1.1.1a: Define `MergeBlock`, `MergePage`, `MergePolicy`, `MergeOutcome`, `BlockConflict`, `SourceBlockRef` (value classes/sealed) (~5 min)
- Files: `{C}/merge/MergeModel.kt`
##### Task 1.1.1b: Implement block matching (uuid, src-id, normalized content with short-content parent guard), top-down (~5 min)
- Files: `{C}/merge/MergePage.kt`
##### Task 1.1.1c: Implement placement (repair R3: DEFAULT is append after the last sibling under the matched parent, or end of page for top level; use "after nearest preceding matched sibling" ONLY when every sibling after that anchor, and all their descendants, carries an explicit `id::`, so no positional uuid can shift; never reorder target) and property union (set union for `alias`,`tags`; target wins scalar; never merge `id`,`collapsed`) (~5 min)
- Files: `{C}/merge/MergePage.kt`
##### Task 1.1.1d: Implement conflict sibling creation + `New` path using `UuidRemap` stub interface (real remap in 1.2.1). Conflict sibling uuid is `UuidRemap.conflictUuid(sourceGraphId, sourceUuid, normalizedContent)` (SHA-256 of `"merge-conflict:" + g + ":" + S + ":" + contentHash`), NOT the plain `uuid'`, and the sibling carries `src-id` (R4); a block already present under the same parent with equal `src-id` and equal normalized content (original copy or earlier conflict sibling) means `Unchanged` (~4 min)
- Files: `{C}/merge/MergePage.kt`
##### Task 1.1.1e: Example tests for each criterion above (~5 min)
- Files: `{CT}/merge/MergePageExamplesTest.kt`

#### Story 1.1.2: Property tests (acceptance gate) (GATED on Story 1.1.4 - idempotence is only meaningful once uuids survive the stage and disk hops)
**As a** maintainer, **I want** invariants machine-checked, **so that** idempotence and no-loss hold across generated trees.
**Acceptance Criteria**:
- Idempotence, no loss, additive-only, self-merge identity, empty-source identity, uuid uniqueness, and serializer round trip hold.
  - *Given* generated `MergePage` pairs (depth<=4, duplicate/short contents, shared/colliding uuids) from `Arb`, *When* `checkAll(500)` in `runTest`, *Then* `merge(merge(t,s),s) == merge(t,s)`; every target block content appears in order among target siblings; every source block content or its conflict copy is present; `merge(t,t)==Unchanged`; `merge(t,empty)==Unchanged`; no duplicate uuid in result.
- Block-set commutativity when there are no conflicts.
  - *Given* t and s with disjoint uuids and distinct contents, *When* merged both ways, *Then* the multiset of block contents is equal.
**Files**: `{CT}/merge/MergePagePropertyTest.kt`, `{CT}/merge/MergeArbs.kt`

##### Task 1.1.2a: Write `Arb<MergePage>` generators with collision-biased uuid/content pools. Required generator axes (R3/R4): (i) "post-copy edit": derive `t` from `merge(t0, s)` then randomly rewrite the content of a random subset of `src-id`-carrying blocks (edited-after-copy); (ii) "source drift": derive `s2` from `s` by editing a random subset of contents; (iii) "unlabeled": a fraction of target blocks have no explicit uuid (positional). Properties on these axes: `merge(merge(t,s),s) == merge(t,s)`; after edit-then-recopy exactly one conflict sibling per edited matched block and zero on the next repeat; `merge(merge(merge(t,s),s2),s2)` idempotent; no duplicate uuid (conflict `uuid'' != uuid'`); positional uuids of pre-existing unlabeled blocks are unchanged after render + re-parse (~5 min)
##### Task 1.1.2b: Idempotence + no-loss + additive-only properties (~5 min)
##### Task 1.1.2c: Identity/commutativity/uuid-uniqueness properties; parametrize on both key functions (~5 min)

#### Story 1.1.3: Model converters and rendering (GATED on Story 1.1.4)
**As a** developer, **I want** conversion between `Block`/`ParsedBlock` trees and `MergeBlock`, **so that** the pure function serves both DB and disk sides.
**Acceptance Criteria**:
- Round trip.
  - *Given* a `Page` with 3 nested `Block`s, *When* `Block`s -> `MergePage` -> `Block`s -> `LogseqPageSerializer.serialize` -> parse -> `MergePage`, *Then* the tree equals the original (content, props, order, nesting).
- Disk side.
  - *Given* markdown `- a\n\t- b\n  id:: 33333333-3333-3333-3333-333333333333`, *When* parsed via the existing parser into `MergePage`, *Then* block `a` has child `b` and `b.uuid` = `3333...` (per Spike 0.1.3).
**Files**: `{C}/merge/MergeConverters.kt`, `{CT}/merge/MergeConvertersTest.kt`

##### Task 1.1.3a: `Block`/`Page` -> `MergePage` and back using `FractionalIndexing` for positions (~5 min)
##### Task 1.1.3b: `ParsedBlock`/markdown -> `MergePage` via `OutlinerPipeline`/`MarkdownPageParser` (no new parser) (~5 min)
##### Task 1.1.3c: Render ONLY the *new/inserted* `MergeBlock`s to markdown text (via `LogseqPageSerializer` block rendering + the `id::` emission from Task 1.1.4a) for the splicer to insert. A whole existing page is never re-rendered on the off-graph path; whole-page rendering is used only for brand-new pages (no existing file) (~5 min)

#### Story 1.1.4: Lossless identity and splice (COMMITTED; owner of the uuid and no-loss invariants)
**As a** user, **I want** block identity to survive staging and disk, and my existing target file bytes untouched, **so that** repeat copies are idempotent and nothing in the target is dropped.
**Acceptance Criteria**:
- `id::` is emitted.
  - *Given* `Block(uuid=22222222-..., content="x")` with no `id` in `properties`, *When* rendered by the merge renderer and parsed back by `MarkdownPageParser`, *Then* the parsed uuid equals `22222222-...`. (The existing `LogseqPageSerializer` is not changed for ordinary saves unless the existing serializer tests show `id::` emission is already safe; the merge renderer adds `id::` for every block it inserts.)
- Staging carries uuids.
  - *Given* a `MergePage` with 3 blocks with explicit uuids, *When* encoded to `StagedPage` JSON and decoded, *Then* equal (uuids, props, nesting, order).
- Splice preserves bytes.
  - *Given* a real-markdown fixture (CRLF line endings, tab indentation, fenced code block with `- ` lines inside, `collapsed:: true`, `#+BEGIN_QUOTE` block, trailing whitespace, no trailing newline) and one new block, *When* `MarkdownSplicer.splice`, *Then* the output equals the input with only the inserted lines added (verified with a byte-diff assertion that every original byte appears in order).
- Unlabeled-block fixtures (repair R3).
  - *Given* fixtures with NO `id::` on any block (flat list, nested list, mixed labeled/unlabeled), *When* a block is inserted by `MarkdownSplicer` via the Task 1.1.1c placement rule and the file is re-parsed with the same `pagePath` string the DB side uses, *Then* every pre-existing block's parsed uuid is identical before and after (no positional shift), and `readExisting` uuids equal the DB's for the same page (the `pagePath` string passed to the parser is the one `GraphLoader` uses; record it in the `PageFileResolver` KDoc). Fixtures: `unlabeled-flat.md`, `unlabeled-nested.md`, `mixed-labeled.md`. (The sidecar content-hash uuid path, `MarkdownPageParser.kt:51-55`, is not relied on.)
- Round-trip guard.
  - *Given* a fixture where `serialize(parse(file)) != file`, *When* the off-graph writer is asked to write, *Then* it returns `Left(NotRoundTrippable)` and writes nothing (router: queued for share; for a copy, that page fails definitively, is kept in staging and listed with Retry). The guard's strictness (exact vs "structure-stable") is the one Spike 0.1.4 settled; this Story does not start the guard before that result is recorded.
**Files**: `{C}/merge/MergeRenderer.kt` (new), `{C}/merge/StagedPage.kt` (new), `{C}/merge/MarkdownSplicer.kt` (new), `{C}/merge/RoundTripGuard.kt` (new), `{CT}/merge/MarkdownSplicerFixtureTest.kt`, `{CT}/merge/StagedPageTest.kt`, fixtures in `kmp/src/commonTest/resources/merge-fixtures/*.md` (real exported pages, not `Block` trees)
##### Task 1.1.4a: Merge renderer emits `id::` for every inserted block (committed, not conditional) + `src-id::` property (~5 min)
##### Task 1.1.4b: `StagedPage` `@Serializable` JSON codec (kotlinx.serialization already used by the manifest) (~4 min)
##### Task 1.1.4c: `MarkdownSplicer`: locate insertion offset from the parsed block tree's source line spans (if `MarkdownPageParser` does not expose spans, add a line-based top-level/indent scanner here, with tests) and insert only the new lines, preserving the file's detected line ending and indent style; page-property union only inserts missing `key:: value` lines (an existing `alias`/`tags` line is never rewritten; the skipped extension is reported in the outcome) (~5 min)
##### Task 1.1.4d: Production `RoundTripGuard` at the strictness Spike 0.1.4 settled + fixture tests (including the failing real files Spike 0.1.4 collected). Strictness is NOT decided here any more (~5 min)

### Epic 1.2: Identity remap and path resolution

#### Story 1.2.1: Deterministic UUID remap with ref rewrite (GATED on Story 1.1.4)
**As a** user, **I want** block references to survive a copy and a repeat copy to find the same blocks, **so that** merges are idempotent.
**Acceptance Criteria**:
- Deterministic.
  - *Given* sourceGraphId `g-work` and source uuid `S1`, *When* remapped twice, *Then* identical `uuid'` and `src-id:: g-work:S1`.
- Always remapped (no target oracle).
  - *Given* any source uuid (free or colliding in some target), *When* remapped, *Then* `uuid' != S` and no target lookup is made (`UuidRemap.compute(sourceGraphId, blocks)` takes no `isTakenInTarget`). Property: no two distinct `(sourceGraphId, S)` pairs in a generated set of 10 000 map to the same `uuid'`.
- Strong hash.
  - *Given* the derivation, *Then* it is SHA-256 (via okio `ByteString.sha256()`, commonMain-safe; verify okio is on the commonMain classpath - it backs `FileSystem`) truncated to 128 bits and formatted as a UUID; it does NOT use `UuidGenerator.generateDeterministic` (FNV-1a 64-bit x2, non-cryptographic).
- Anti-clobber guard.
  - *Given* `uuid'` already present in the target DB on a DIFFERENT page (forced collision via a test-injected derivation), *When* `ActiveTargetWriter` applies the page, *Then* the page fails with `WriteFailed("uuid collision")` before any `INSERT OR REPLACE` (`SteleDatabase.sq:335` would otherwise silently replace the other page's block); and *Given* the off-graph writer, *Then* it refuses to insert a block whose `uuid'` already appears in the same target file under a different `src-id`.
- Refs rewritten.
  - *Given* incoming block `see ((S2)) and {{embed ((S2))}}` and `((OUT))` where OUT is not selected, *When* remapped, *Then* content = `see ((uuid'(S2))) and {{embed ((uuid'(S2)))}}` and `((OUT))` is unchanged.
**Files**: `{C}/merge/UuidRemap.kt`, `{CT}/merge/UuidRemapTest.kt`

##### Task 1.2.1a: `UuidRemap.compute(sourceGraphId, blocks)` using the SHA-256 derivation; always remap; plus `conflictUuid(sourceGraphId, sourceUuid, normalizedContent)` (ADR-002 rev. 3, distinct seed prefix `merge-conflict:`; property: never equals `uuid'` for the same `(g, S)`) (~4 min)
##### Task 1.2.1b: Regex ref/embed rewriter + tests incl. property "rewrite is idempotent" (~5 min)
##### Task 1.2.1c: Clobber guard in `ActiveTargetWriter` (DB lookup by `uuid'` before write) and in `MarkdownTargetWriter` (same-file check); size it from Spike 0.1.3's recorded collision outcome; regression tests (~5 min)

#### Story 1.2.2: Extract `PageFileResolver`
**As a** developer, **I want** one place that maps a page to its file path, **so that** merge and `GraphWriter` agree on filenames.
**Acceptance Criteria**:
- Behavior-preserving.
  - *Given* `Page(name="a/b", isJournal=false)` and graph path `/g`, *When* resolved, *Then* the result equals what `GraphWriter.getPageFilePath` returned before the refactor (existing `GraphWriter*Test` still green); journal `Oct 7th, 2026` resolves under `/g/journals/`.
- Journal filenames round-trip.
  - *Given* a journal page for 2026-10-07, *When* `PageFileResolver` resolves it and `GraphLoader` (via `JournalUtils.parseJournalDate` on the file stem) parses the resulting file name back, *Then* the date is 2026-10-07 and the loader's page identity equals the one `ensureTodayJournal` creates (no second journal for the same date).
- Path only. `PageFileResolver` carries no write-capability predicate; crypto/SAF/platform checks live in `TargetWriterCapabilities` (Story 2.3.3).
- **Journal filename rules (DECIDED, Repair pass 6; home = `PageFileResolver`, rules recorded in its KDoc).** (1) Folder is `journals/` for `isJournal`, else `pages/`. (2) Creation stem = `JournalUtils.formatDateForJournal(date)` = `YYYY_MM_DD` (VERIFIED `JournalUtils.kt:32`), file `journals/<stem>.md`. (3) Discovery: before creating, `resolveJournal(date, existingStems)` checks the target's `journals/` listing for ANY stem matching `^(\d{4})[-_](\d{2})[-_](\d{2})$` that parses to the same date (both `_` and `-` separators) and reuses that exact file name, so an existing `2026-10-07.md` is appended to, never duplicated as `2026_10_07.md`. (4) If the target graph's `config.edn` declares a non-default journal file-name format, off-graph journal writes return `Left(WriteRefused(PlatformUnsupported("journal format")))` (share: queued; copy: journal page fails with reason); no code reads that setting today (grep of `kmp/src/commonMain` for `file-name-format` finds none), so the check is a conservative stop and its detection cost is Task 1.2.2a. (5) Page names with namespaces follow the existing `FileUtils.sanitizeFileName` output unchanged (behavior-preserving AC above). Acceptance tests: property over generated dates (stem round-trips through `JournalUtils.parseJournalDate`), existing-`-`-separator reuse, and the loader round trip of Task 1.2.2d. No acceptance criterion elsewhere in this plan hard-codes a journal file name.
- **Path containment (traversal and symlink), Task 1.2.2e.** Every path `PageFileResolver` returns for a page name, namespace or journal date is checked to be inside `<graphRoot>/pages` or `<graphRoot>/journals`. *Given* page names `../../etc/passwd`, `a/../../b`, `..%2f..`, a name that is only dots, an absolute path, a name with NUL or backslash, and a namespace `x/../y`, *When* resolved, *Then* each either sanitises to a file name that lies inside the folder or returns `Left(InvalidPageName)`, never a path outside the graph root (property test with `Arb` strings, plus the named cases). *Given* a `pages/` directory entry or file that is a symlink pointing outside the graph root (JVM `Files.createSymbolicLink` in a real temp dir; Android/iOS/Web: skipped where symlinks do not exist, asserted by capability flag), *When* `MarkdownTargetWriter` reads or writes it, *Then* the write is refused with `WriteRefused(PathOutsideGraph)` (canonical path outside the root) and nothing outside the root is created or changed. The same containment check guards `MergeStagingDirectory` file names, `SourceGraphReader` (pull) listings, `AssetCopier` destinations and manifest `deletePageFile` targets.
**Files**: `{C}/db/PageFileResolver.kt`, `{C}/db/GraphWriter.kt`, `{CT}/db/PageFileResolverTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/PathContainmentSymlinkTest.kt` (new, real temp dir)

##### Task 1.2.2a: Read `GraphWriter.getPageFilePath` (line ~790) and journal naming; extract pure function (~5 min)
##### Task 1.2.2b: Make `GraphWriter` delegate; run existing GraphWriter tests (`bazel test //kmp:business_tests` or `./gradlew jvmTest --tests '*GraphWriter*'`) (~4 min)
##### Task 1.2.2c: Resolver tests incl. namespace and journal cases (~4 min)
##### Task 1.2.2e: Path-containment tests and `Left(InvalidPageName)` / `PathOutsideGraph` handling per the AC above, incl. the JVM symlink test; wire the check into `MarkdownTargetWriter`, `MergeStagingDirectory`, `AssetCopier`, `MarkdownSourceGraphReader` (~1 h for the resolver, the other call sites are covered by Tasks 2.3.1e, 2.2.1c, 2.4.3b, 4.5.2e)
##### Task 1.2.2d: Journal-filename resolver round-trip test: for a generated set of dates, `resolve(journal(date))` -> file stem -> `JournalUtils.parseJournalDate`/loader name handling -> same date; plus an integration check that an off-graph-created journal file reconciles to the same page as `ensureTodayJournal` on open. First step: read how `GraphLoader` and the capture path name journal files (`GraphWriter.getPageFilePath` ~line 790 has no journal-date logic) and record the mapping in the resolver KDoc (~5 min)

---

## Phase 2: Services, transport, writers

**Fresh-reviewer re-check at Phase 2 start (Repair pass 7)**: the architecture-review leftovers R1 (`ActiveTargetWriter` `id::` emission, Tasks 1.1.4a and 2.3.2) and R3 (positional-uuid placement shift, Task 1.1.1c) were repaired in the plan text but never independently re-reviewed after Repair pass 6. Before Story 2.3.2 starts, a fresh reviewer (one who has not read the repair log) re-checks those two against the code and the Phase 1 test results; findings go to the Repair log.

### Epic 2.1: GraphManager seam

**Sequencing (Repair pass 6)**: land branch `fix/graph-switch-notes-path` (it also edits `GraphManager.kt`) on `main` FIRST; then branch the lock PR from the updated `main`. See "Sequencing against branch" above.

**PR ordering (pre-mortem P1-3)**: Task 2.1.1c, the only edit to the hot `GraphManager.kt` `switchGraph` path, ships as its OWN FIRST PR (own PR, `withTimeout` acquisition and degrade-open on timeout are mandatory parts of it, not follow-ups) (with Tasks 2.1.1b and the stress test below), before any merge code. Nothing that depends on it merges until that PR is green on all platforms in CI. `GraphLocator` (2.1.1a) and everything else follow in later PRs.

#### Story 2.1.1: `GraphLocator` and `GraphWriteLock`
**As a** developer, **I want** to resolve any registered graph without activating it and to serialize merge vs open, **so that** off-graph writes cannot race `switchGraph`.
**Acceptance Criteria**:
- Locate without activating.
  - *Given* graphs A (active) and B registered, *When* `locator.locate(B)`, *Then* returns `GraphInfo(path=B path)` and `activeRepositorySet` still belongs to A (no switch, no DB opened).
- Unknown id.
  - *Given* id `gone`, *When* located, *Then* `Left(DomainError...NotFound)`.
- Lock serializes open.
  - *Given* a merge holding `GraphWriteLock(B)`, *When* `switchGraph(B)` starts, *Then* the init COROUTINE (not the synchronous part of `switchGraph`) suspends on the lock until the merge page batch releases, then proceeds.
- Lock serializes teardown.
  - *Given* a merge page batch holding `GraphWriteLock(A)` through `ActiveTargetWriter` while A is active, *When* `switchGraph(B)` starts, *Then* closing A's factory (the `factoryToClose?.close()` step in the init coroutine) waits for the batch to release, so the batch never sees a closed driver / `ClosedSendChannelException`.
**Verified code facts (GraphManager.kt)**: `switchGraph` (line 804) is a non-suspend `fun`; in order it (1) cancels the previous `activeGraphJobs` entry, (2) calls `tearDownActiveGraphResources()` (line 508) which sets `_activeRepositorySet.value = null` synchronously, (3) launches the init coroutine on `PlatformDispatcher.IO` (closes the previous factory, creates the factory + `createRepositorySet`, sets `_activeRepositorySet.value = repoSet` at ~line 904, runs UuidMigration/FilePathRootMigration, completes `_pendingMigration` in `finally`), and (4) only AFTER launching, synchronously sets `registry.activeGraphId = id` (line 964). So "registry says B active" and "B's `RepositorySet` exists" are different moments; a `Mutex` cannot be taken in the synchronous part. `RepositorySet` (`repository/RepositoryFactory.kt:47`) has no `graphId` field. The initial `loadDirectory` is driven by `GraphLoader` outside `switchGraph`, so the lock covers open + migrations, not the file scan; the loader's own reconcile handles files written before it runs.
**Files**: `{C}/db/GraphLocator.kt`, `{C}/db/GraphWriteLock.kt`, `{C}/db/GraphManager.kt` (lock acquisition only), `{BT}/db/GraphLocatorTest.kt`, `{BT}/db/GraphWriteLockTest.kt`, `{BT}/db/GraphManagerSwitchLockStressTest.kt`

##### Task 2.1.1b: `GraphWriteLock` (per-id `Mutex` map, `withLock(id){}`, plus `withTimeout` variant and the debug lock-order guard) (~4 min). Ships in the Task 2.1.1c PR.
##### Task 2.1.1a: `GraphLocator` interface + impl as a SEPARATE class taking `graphRegistry: StateFlow<GraphRegistry>` (no activation, no code added to `GraphManager.kt`) (~5 min). Separate later PR.
##### Task 2.1.1c (SEPARATE FIRST PR, see Epic 2.1 and the hardening block below): In `GraphManager.switchGraph`'s init coroutine, take `GraphWriteLock(id)` before the first read/creation for the incoming id and hold it through `createRepositorySet` + migrations (until just before `deferred.complete`); also take the lock around the previous-graph `factoryToClose?.close()` step, keyed by the graph that OWNS the factory being closed, not by a re-read of the registry: `switchGraph` captures `previousId` and `factoryToClose` together, synchronously, before launching the init coroutine (same moment as the `activeGraphJobs` cancel), and passes both into the coroutine as immutable values. Under rapid B-then-C switches the second call's captured pair is (B, B's factory) or, if B's init has not yet published a factory, (A, A's factory) still held by the in-flight state; the factory field must be handed off per switch so each factory is closed exactly once under its own owner's lock. Test: `switchGraph(B)` then `switchGraph(C)` immediately, with a merge batch holding `lock(A)`; A's factory close waits on `lock(A)` (not `lock(B)`), and no factory is closed twice or under the wrong id. `forceReinit` and relocation paths go through the same coroutine and therefore the same lock. Minimal edit, no new state beyond the lock map; tests for both ACs above (~5 min)
- **Task 2.1.1c hardening (own first PR; pre-mortem P1-3)**:
  - *Bounded acquisition*: the init coroutine acquires `lock(id)` via `withTimeout(LOCK_ACQUIRE_TIMEOUT)` (default 10 s, injectable for tests). It releases in `finally` (use `withLock`, or `lock()`/`try`/`finally unlock()`), including when migration throws or the coroutine is cancelled.
  - *Degrade open*: on timeout, log `graph.switch.lock_timeout` (with graph id and the holder's label if known) and open the graph WITHOUT waiting for the merge: proceed with factory creation, repository set and migrations, and tell the holder to abort its batch (the router's retryable `Left` path, Story 2.1.2). The open path is never blocked by a merge; a timed-out merge page fails definitively and is retried via Retry.
  - *Lock-order assertion in debug builds*: a `GraphWriteLockOrderGuard` active in production DEBUG builds (not only in tests) tracks locks held per coroutine context and throws `IllegalStateException` on a second graph lock acquisition while one is held, or on `awaitPendingMigration()` while holding any graph lock. Release builds compile it to a no-op.
  - *CI stress test* (`GraphManagerSwitchLockStressTest`, businessTest, runs on every platform in CI, each case under `withTimeout`): (1) 50 rapid B-then-C-then-B switches; (2) slow fake `DriverFactory` (delay in create); (3) paused migration (fake migration suspends) with a router write waiting; (4) throwing migration (assert lock released, `_pendingMigration` completed, next switch succeeds); (5) a merge batch holding `lock(A)` while `switchGraph(B)` is called (assert degrade-open after the timeout). Failure of any case blocks the PR.
- **LOCK ORDER (normative, repair N1)**: (1) No coroutine ever holds `GraphWriteLock(X)` while acquiring `GraphWriteLock(Y)`. In the init coroutine the previous-graph close (`lock(previousId) { factoryToClose?.close() }`) is a complete, released critical section BEFORE `lock(id) { create repo set + migrations }` begins; the two never nest. A merge/router batch holds at most one graph lock at a time (it targets one graph per page batch). (2) No coroutine may `await` `_pendingMigration`/`awaitPendingMigration()` while holding any `GraphWriteLock`, because the init coroutine completes the deferred in its `finally` (`GraphManager.kt:958`) and needs `lock(id)` to get there. Test: two concurrent `switchGraph(B)` then `switchGraph(C)` with a slow fake `DriverFactory` complete without deadlock (`withTimeout`), and a lock-order assertion helper in `GraphWriteLockTest` fails if a second lock is acquired while one is held on the same coroutine.
- **Readiness-before-migration ordering (repair, arch re-review)**: `_activeRepositorySet.value = repoSet` is set at ~line 904, BEFORE the UuidMigration/FilePathRootMigration block (~910-931). So `readyGraphId == B` (set in the same statement, Task 2.1.2a) is true while migrations are still running. This is safe ONLY because the init coroutine still holds `lock(B)` through the migrations and releases it just before `deferred.complete`; every consumer that writes (router, `ShareInboxDrain`) must take `lock(B)` AFTER observing `readyGraphId == B`, so it blocks until migrations finish. Add a comment at the set site and a test: a router write attempted when `readyGraphId == B` but migrations are paused (fake migration that suspends) does not proceed until the migration completes.
- **Previous-graph scope cancel is outside any lock (repair)**: `switchGraph` cancels the previous `activeGraphJobs` scope synchronously (`GraphManager.kt:825`, `activeGraphJobs.remove(it)?.cancel()`) BEFORE the init coroutine takes any lock. `lock(A)` therefore cannot protect an `ActiveTargetWriter` batch from scope cancellation of A's `graphScope`, only from the factory close. Whether `DatabaseWriteActor` lives in that scope is not verified (it is passed `scope = graphScope` via `createRepositorySet`). The retryable-error path in `ActiveTargetWriter` (closed channel OR `CancellationException` from the actor, see Task 2.3.2a) is the actual safety net; keep the Story 2.1.2 injected-close test.

#### Story 2.1.2: Router readiness predicate (in-flight switch)
**As a** developer, **I want** the router's "is the target active and usable" decision to be correct while a switch is in flight, **so that** a merge/share never writes through a missing `RepositorySet` or races init.
**Acceptance Criteria**:
- Predicate.
  - *Given* any moment, *Then* `isActiveAndReady(target)` = `activeRepositorySet != null` AND that set belongs to `target`; registry `activeGraphId` alone is never used.
- Registry says active, init unfinished.
  - *Given* `switchGraph(B)` just returned (registry `activeGraphId == B`, `activeRepositorySet == null`), *When* the router is asked to write to B, *Then* it awaits `awaitPendingMigration()` OUTSIDE any `GraphWriteLock` (exists: `GraphManager.kt:974`, `suspend fun awaitPendingMigration(): RepositorySet?`; returns null if init failed -> router falls back to `MarkdownTargetWriter` under the lock, or to the inbox for share), THEN takes `lock(B)` and re-checks `readyGraphId == B`; if it still holds, uses `ActiveTargetWriter`; if it changed (a later `switchGraph(C)` replaced `_pendingMigration`, `GraphManager.kt:845`), it releases the lock and re-decides, at most `MAX_ROUTER_ATTEMPTS = 3` times, then returns a retryable `Left` (merge keeps the page in staging; share goes to the inbox). Awaiting while holding `lock(B)` deadlocks: the init coroutine needs `lock(B)` before it completes the deferred (`GraphManager.kt:958`).
- Router called before init finishes (deadlock regression, repair N1).
  - *Given* `switchGraph(B)` returned and a fake slow `DriverFactory` keeps init unfinished, *When* the router writes to B on another coroutine, *Then* the router is suspended on the await with NO lock held (asserted: `GraphWriteLock(B)` is acquirable by the init coroutine), init completes, the router then takes `lock(B)` and writes via `ActiveTargetWriter`, and the whole test finishes under `withTimeout`. Variant: `switchGraph(C)` is called while the router awaits B; the router re-checks, sees `readyGraphId != B`, and falls back to `MarkdownTargetWriter` for B (B is now inactive) without ever returning C's repository set.
- Switch in flight, inactive to active.
  - *Given* a merge writing page 3 of 10 off-graph to B, *When* `switchGraph(B)` is called mid-run, *Then* the init coroutine blocks on `GraphWriteLock(B)` until page 3 completes, pages 4-10 go through `ActiveTargetWriter`, and a re-run is all `Unchanged`.
- Switch in flight, active to inactive.
  - *Given* a merge writing through `ActiveTargetWriter` to A, *When* `switchGraph(B)` is called mid-run, *Then* teardown of A waits for the current page batch, later pages route to `MarkdownTargetWriter`, and no `ClosedSendChannelException`/closed-driver error surfaces; additionally, if `ActiveTargetWriter` still observes a closed channel it returns a retryable error and the router re-decides (test with an injected close).
**Files**: `{C}/merge/TargetWriterRouter.kt`, `{C}/db/GraphManager.kt` (only the tiny `readyGraphId` accessor below), `{BT}/merge/TargetWriterRouterInFlightSwitchTest.kt`
##### Task 2.1.2a: Add a single atomic pair accessor `readyGraph: StateFlow<ReadyGraph?>` where `data class ReadyGraph(val id: GraphId, val repoSet: RepositorySet)`, replacing the separate `readyGraphId` + `_activeRepositorySet` reads for routing (`readyGraphId` may remain as a derived `map { it?.id }`). It is assigned in the same statements that set `_activeRepositorySet` (~line 904 set, ~line 514 null). Teardown at ~line 514 is NOT under `GraphWriteLock`, so the router must read the pair once, and under `lock(target)` must use only that captured `repoSet` (never re-read `_activeRepositorySet`); if the captured pair is null or its id != target at the in-lock re-check, release and retry (bounded by `MAX_ROUTER_ATTEMPTS`). A write against a repo set torn down mid-batch surfaces as the retryable `Left` already specified. Test: teardown at ~514 racing a router write never yields a (id, set) mismatch (~4 min)
##### Task 2.1.2b: Router algorithm (replaces "decide under lock"): loop up to `MAX_ROUTER_ATTEMPTS`: (1) outside any lock, if `readyGraphId != target` and the registry says `target` is active (or a switch is in flight), `awaitPendingMigration()`; (2) `lock(target).withLock { if (readyGraphId == target) activeWriter.write(...) else if (target not ready) markdownWriter.write(...) }`; a `readyGraphId` that flipped between (1) and (2) is detected by the re-check inside the lock and retried from (1). Never await inside the lock; follow the Task 2.1.1c lock order (~5 min)
##### Task 2.1.2c: In-flight-switch tests (both directions, plus the router-before-init-finishes and switch-during-await tests above) using a fake slow `DriverFactory`; add a `merge.apply.lock_wait_ms` metric around lock acquisition (~5 min)

### Epic 2.2: Staging transport and manifest

#### Story 2.2.1: `MergeStagingDirectory`
**As a** user, **I want** big copies to survive graph switches and process death with bounded memory, **so that** 8k-page copies do not OOM.
**Acceptance Criteria**:
- Layout and marker.
  - *Given* `MergeId m1`, *When* `create(sourceId, targetId)`, *Then* `.stele-merge-staging-m1/.marker` JSON has `mergeId, sourceGraphId, targetGraphId, startedAtEpochMs` and pages are written as one file each.
- Sweep rule.
  - *Given* dirs: one with marker 8 days old, one with marker 1 day old, one without marker, *When* `sweep(now)`, *Then* only the 8-day dir is deleted.
- Bounded read.
  - *Given* 5 000 staged files, *When* iterating `readAll()`, *Then* at most 1 page is held (sequence/flow) — asserted by a counting fake.
**Files**: `{C}/merge/MergeStagingDirectory.kt`, `{BT}/merge/MergeStagingDirectoryTest.kt`

##### Task 2.2.1a: Implement over `FileSystem` (okio), reusing `RelocationStagingDirectory.kt` conventions (read it first) (~5 min)
##### Task 2.2.1b: Sweep + startup hook wiring where the relocation sweep is invoked (grep `sweep(`) (~4 min)
##### Task 2.2.1c: Tests with `FakeFileSystem` (~4 min)

#### Story 2.2.2: `MergeManifest` store
**As a** developer, **I want** a crash-safe per-run record, **so that** undo and "interrupted" detection work.
**Acceptance Criteria**:
- Record and reload.
  - *Given* a run that created `pages/New.md` (hash H1) and added blocks ( `u1`, `u2` ) to `Projects`, *When* the manifest is flushed and reloaded, *Then* entries equal.
- Interrupted marker.
  - *Given* a manifest with `status=InProgress` found at startup, *When* `MergeManifestStore.findInterrupted()`, *Then* returns that run for the "last copy was interrupted" notice.
**Files**: `{C}/merge/MergeManifest.kt`, `{BT}/merge/MergeManifestTest.kt`

##### Task 2.2.2a: `@Serializable` manifest + append-only flush per page (~5 min)
##### Task 2.2.2b: Store (list/load/delete/expire 7 days) + tests (~5 min)

### Epic 2.3: Target writers (GATED: S0.1.1 REAL-FS green + device pass and S0.1.4 pass-rate decision for the markdown writer, S0.1.2 for the SAF branch, Story 1.1.4 for both writers; S0.1.3 sizes the clobber guard)

#### Story 2.3.1: `MarkdownTargetWriter` (off-graph) (GATED on Story 1.1.4, Spike 0.1.1 real-filesystem run green, Spike 0.1.4 go decision)
**As a** user, **I want** to copy into a graph I'm not viewing, **so that** I don't switch graphs.
**Acceptance Criteria**:
- Merge into existing file by splice, never re-render.
  - *Given* target `B/pages/Projects.md` = `- A\n- B`, *When* writing merged page adding `C`, *Then* file = `- A\n- B\n- C\n  id:: <uuid'(C)>\n  src-id:: <g>:<S>` and only that file changed; the original bytes are a prefix-preserving subsequence of the result (only inserted lines added).
- Real-markdown fixtures.
  - *Given* each fixture in `kmp/src/commonTest/resources/merge-fixtures/` (CRLF, tabs, fenced code with `- ` lines, `collapsed::`, org-style `#+BEGIN_` blocks, no trailing newline) plus one new block, *When* written through `MarkdownTargetWriter` on a `FakeFileSystem`, *Then* every original byte is preserved in order; fixtures failing `RoundTripGuard` return `Left(NotRoundTrippable)` and the file is untouched.
- No-op when identical.
  - *Given* the splice result is byte-identical to disk (all blocks already present by `src-id`), *When* written, *Then* file mtime unchanged (`FakeFileSystem` records 0 writes).
- Atomic replace.
  - *Given* a write failing mid-way, *When* it throws, *Then* the original file content is intact and no stray `.tmp` remains.
- Refusals.
  - *Given* an encrypted graph or SAF target without a verified grant (or SAF per Spike 0.1.2 "no"), *When* `write`, *Then* it returns `Left(WriteRefused(reason))` and writes nothing. The writer never refers to the inbox. The caller decides: a COPY marks that page failed (definitive, listed with Retry; the destination chooser already disables such targets, so reaching this means the capability changed mid-run, for example a grant lost); a SHARE (`InboxFallbackAppender`) queues it.
- Grant lost mid-run (copy).
  - *Given* a copy to SAF graph B that had a verified grant when the chooser enabled it, and the grant is revoked after page 3 of 10, *When* page 4 is written, *Then* page 4 and later pages fail with `WriteRefused(NoGrant)`, pages 1-3 stay committed, the result dialog lists the failed pages with the reason and "Retry failed", nothing is queued, and `Retry failed` after re-granting succeeds.
**Files**: `{C}/merge/TargetWriter.kt`, `{C}/merge/MarkdownTargetWriter.kt`, `{BT}/merge/MarkdownTargetWriterTest.kt`

##### Task 2.3.1a: `TargetWriter` port defined up front with `readExisting`, `write`, `deletePageFile(page, expectedHash)`, `fileHash(page)`, `removeBlocks(page, uuids, expectedContentHashes)`; all return `Either<DomainError, ...>` (~4 min)
##### Task 2.3.1b: `readExisting` via `PageFileResolver` + parser -> `MergePage` (read-only model used for matching; also runs `RoundTripGuard`) (~5 min)
##### Task 2.3.1c: `write` = `MarkdownSplicer.splice(originalText, newBlocks)` then temp+rename (or direct per Spike 0.1.2), byte-identical short-circuit, `FileRegistry` pre-mark per ADR-001 spike outcome. Brand-new page (no existing file) is the only case rendered whole (~5 min)
##### Task 2.3.1e: Containment tests at the writer: page names with `..`, absolute paths, namespaces escaping the folder, and a symlinked page file pointing outside the graph root (real temp dir, JVM) all end in `Left(InvalidPageName)` / `WriteRefused(PathOutsideGraph)` with zero files created or changed outside the root; uses the resolver check of Task 1.2.2e (~3 h, counted in Story 2.3.1's 17 h)
##### Task 2.3.1d: Capability checks consumed from `TargetWriterCapabilities` (Story 2.3.3), `deletePageFile`/`fileHash`/`removeBlocks` (splice-out of exactly the manifest's uuids, hash-checked) and tests (~5 min)

#### Story 2.3.2: `ActiveTargetWriter`, unified `TargetWriterRouter`, shared contract test (GATED on Story 1.1.4)
**As a** user, **I want** the same merge to work when the target is the open graph, **so that** DB and editor stay consistent.
**Acceptance Criteria**:
- Contract parity (shared suite).
  - *Given* one `TargetWriterContractTest` abstract suite (cases: new page, merge into existing, repeat -> Unchanged, conflict sibling, remapped uuid and `src-id` values, `deletePageFile`/`removeBlocks` with matching and mismatching hash) and the same `StagedPage` input, *When* run against `MarkdownTargetWriter` (FakeFileSystem) and `ActiveTargetWriter` (in-memory repositories), *Then* both produce identical `MergeOutcome`, identical final block uuids/contents/properties, and identical `MergeResult` counts. The comparison is on BLOCK DATA, not file bytes (the active path re-renders the whole page, see ADR-001 rev. 3). For `ActiveTargetWriter` the "final blocks" are obtained by re-parsing the file `GraphWriter.savePage` wrote (via `MarkdownPageParser` with the same `pagePath` the loader uses) AND by reading the DB, and both must equal the `MarkdownTargetWriter` result; a DB-only comparison does not satisfy this AC (repair R1).
- `id::` on the active path (repair R1).
  - *Given* `ActiveTargetWriter` inserts a block with `uuid'` into a page, *Then* it sets `properties["id"] = uuid'` (and `src-id`) on every inserted `Block` before `DatabaseWriteActor.saveBlock`/`GraphWriter.savePage`, because `LogseqPageSerializer` emits only `block.properties` (`LogseqPageSerializer.kt:38-44`) and `MarkdownPageParser.generateUuid` uses `properties["id"]` verbatim (`MarkdownPageParser.kt:46-49`) but otherwise derives a positional uuid (`:59`). *When* the file is reloaded and re-parsed, *Then* the block's uuid is still `uuid'` and `((uuid'))` rewrites in other copied blocks resolve (test: copy A with `((B))`, reload target from disk, assert the ref target exists).
- One router.
  - *Given* merge and share both call `TargetWriterRouter` (Story 4.1.3 adds only an `InboxFallbackAppender` decorator for share), *Then* a grep for a second class that chooses between active and off-graph writers finds none.
- Active write uses actor/GraphWriter.
  - *Given* target = active graph, *When* applying `Merged(added=1)`, *Then* the block is saved via `DatabaseWriteActor.saveBlock` / `saveBlocksDiff` (no direct `SteleDatabaseQueries` write) and `GraphWriter.savePage` writes the file with watcher self-write suppression (0 `DiskConflict` emitted).
- Router decides at write time using the Story 2.1.2 algorithm: await readiness OUTSIDE the lock (`awaitPendingMigration()` when the registry says active but init is unfinished), then take `GraphWriteLock(target)`, re-check `readyGraphId == target`, and retry (bounded) if it changed; the awaiting is never done while holding the lock (deadlock, see Task 2.1.1c lock order). In-flight switch and router-before-init tests live in Story 2.1.2.
- Page being edited.
  - *Given* the target page has pending debounced edits, *When* merge applies, *Then* it goes through `BlockStateManager`-safe path or is deferred and reported, never clobbered.
- End-to-end identity on the real active path (pre-mortem P2-4).
  - *Given* a real in-memory `GraphManager` with the target active, the real `ActiveTargetWriter`, `GraphWriter.savePage` and `GraphLoader` (no fakes for the write/parse hop), *When* a generated page is copied, then a random subset of copied blocks is edited through the editor path (`BlockStateManager` -> `DatabaseWriteActor` -> `GraphWriter`), saved, the file re-parsed with the loader's `pagePath`, and the same source copied again, *Then* the second copy adds zero new blocks other than exactly one conflict sibling per edited matched block, a third copy adds nothing (all `Unchanged`), no `src-id`/`id::` line is dropped or reordered by the editor save, and `readExisting`'s uuids equal the DB's for the same page (DB-vs-file `pagePath` seed equality, tested against `GraphLoader`, not only the parser). Property test, `checkAll(50)` (heavier than the pure ones; real IO).
**Files**: `{C}/merge/ActiveTargetWriter.kt`, `{C}/merge/TargetWriterRouter.kt`, `{BT}/merge/TargetWriterRouterTest.kt`, `{BT}/merge/CopyEditRecopyPropertyTest.kt` (new, register in `AllBusinessTests`)

##### Task 2.3.2a: `ActiveTargetWriter` (`Either`, `withContext(DB)` inside actor, wraps exceptions as `WriteFailed`; implements `deletePageFile`/`fileHash`/`removeBlocks` through `GraphWriter`/`DatabaseWriteActor`; closed-channel or actor-scope `CancellationException` -> retryable error; sets `properties["id"] = uuid'` on every inserted block (R1)) (~5 min)
##### Task 2.3.2b: `TargetWriterRouter` with `GraphWriteLock` and the Story 2.1.2 await-outside-lock/re-check algorithm (single router for merge and share) (~4 min)
##### Task 2.3.2c: Edited-page guard (~5 min)
##### Task 2.3.2d: `TargetWriterContractTest` abstract suite + the two concrete subclasses; the abstract suite's "read back" hook for the active subclass re-parses the written file (R1) and also compares against the DB; add the contract cases "target contains unlabeled blocks" (R3) and "src-id-matched block edited in target, then re-copied" (R4) (~5 min)
##### Task 2.3.2e: `CopyEditRecopyPropertyTest` per the end-to-end AC above (real `ActiveTargetWriter`, editor save, loader re-parse, re-copy), including the `pagePath` seed equality check; fails CI if `src-id` handling regresses (~5 min). Decide in this task whether `src-id` is shown or hidden in the UI (record in ADR-002).

#### Story 2.3.3: `TargetWriterCapabilities` (write-capability policy)
**As a** developer, **I want** one place that answers "can this target be written off-graph", **so that** the router and the picker agree and `PageFileResolver` stays a pure path function.
**Acceptance Criteria**:
- *Given* a graph with a `CryptoLayer`, *When* `TargetWriterCapabilities.canWriteOffGraph(graph)`, *Then* false with reason `Encrypted`; SAF target without verified grant -> `NoGrant`; SAF per Spike 0.1.2 "no atomic replace" -> `SafInboxOnly`; platform FS unable to address the path (iOS/Web) -> `PlatformUnsupported`. There is no `ForcedInbox` reason.
- Reasons are a sealed type rendered by the copy destination chooser as DISABLED WITH A REASON (never hidden, never selectable). The active graph is always writable (via `ActiveTargetWriter`), so a non-writable-off-graph reason applies only to INACTIVE targets. Copies to a disabled target are impossible from the UI; if one slips through (capability changes mid-run), the router returns `WriteRefused(reason)` and `PageMergeService` fails that page definitively (Story 2.3.1 AC), never queuing.
- Shares are different: a share to a non-writable inactive target is queued by `InboxFallbackAppender` (Story 4.1.3) and drained when that graph is ready.
- Reasons carry user text and an optional action ("Re-grant access" where the platform supports it, otherwise "Open that graph to copy into it"), consumed by UX S3.
**Files**: `{C}/merge/TargetWriterCapabilities.kt`, `{BT}/merge/TargetWriterCapabilitiesTest.kt`
##### Task 2.3.3a: Sealed `WriteCapability`/reason types + implementation over `PlatformFileSystem` and `CryptoLayer` (~5 min)
##### Task 2.3.3b: Tests per reason (~4 min)

### Epic 2.4: PageMergeService

#### Story 2.4.1: Bounded source reading and selection queries
**As a** user, **I want** to list/search/filter pages without loading the graph, **so that** the picker works on 8 000 pages.
**Acceptance Criteria**:
- Paged, filtered, searchable.
  - *Given* 8 030 pages, *When* the picker queries `SelectionFilter(journals=false, namespace="work/")` with search "road", *Then* each repository call returns <= 100 rows and the total result count is available via a count query, not a full list.
- Query plan.
  - *Given* any new `.sq` query added, *When* `QueryPlanAuditTest` runs, *Then* it passes. This story DOES add `.sq` queries (the filtered and count queries of Tasks 2.4.1d1-d7) plus matching `PageRepository` methods, so it follows the CLAUDE.md SQLDelight rules: regenerate and commit `kmp/src/generated/sqldelight/`; no new table, so no `MigrationRunner` entry; read-only queries, so no `RestrictedDatabaseQueries` stub; each new query is added to `QueryPlanAuditTest`.
**Files**: `{C}/merge/PageSource.kt`, `{C}/merge/ActiveDbPageSource.kt`, `{C}/merge/SelectionFilter.kt`, `{BT}/merge/ActiveDbPageSourceTest.kt`

##### Task 2.4.1a: `SelectionFilter` value type (journal flag, date range, namespace prefix, tag) + a pure predicate used ONLY for the in-memory fake repository and as the test oracle for the SQL; tests in `{CT}/merge/SelectionFilterTest.kt` (~5 min)
##### Task 2.4.1d (RE-SIZED, Repair pass 6: about 9.5 h, split into sub-tasks d1-d7; the original text below is the specification and every sub-task implements a slice of it)
- d1: read the schema (`pages` columns, existing indexes, `journal_date`, lowercase name column) and decide namespace/name matching and the journal-date source; write the decision as a comment in the `.sq` (~1 h)
- d2: add `selectPagesFilteredPaginated` and `countPagesFiltered` to `SteleDatabase.sq` (~2 h)
- d3: `PageRepository.getPagesFiltered` / `countPagesFiltered` on the interface, SqlDelight implementation (`asDbFlowList`/suspend + `withContext(PlatformDispatcher.DB)`) and in-memory implementation (~2 h)
- d4: tag filter as the bounded second pass over page ids (IN lists <=500) against page properties (~1.5 h)
- d5: search text via `SearchRepository` (FTS) intersected by id chunk (~1.5 h)
- d6: `./gradlew :kmp:generateCommonMainSteleDatabase`, `rsync` into `kmp/src/generated/sqldelight/`, commit regenerated sources (CI "SQLDelight generated sources" job) (~0.5 h)
- d7: `QueryPlanAuditTest` entries (index used, no full-table scan) and the oracle test against `SelectionFilter` (~1 h)
Original specification: add to `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq` read-only queries `selectPagesFilteredPaginated(isJournal?, dateFrom?, dateTo?, namePrefix?, nameLike?, limit, offset)` and `countPagesFiltered(<same filters>)` (namespace = `name LIKE 'prefix/%'` on an indexed lowercase name column if one exists, else the existing `getPageNameEntries` projection narrowed in a bounded pass; journal date from `journal_date`/name per schema - read the schema first). Tag filter: second bounded pass over only the page ids returned by the first (IN lists <=500) against page properties; never a full scan. Surface as `PageRepository.getPagesFiltered(filter, limit, offset)` and `countPagesFiltered(filter)` on the interface + SqlDelight and in-memory implementations, using `asDbFlowList`/suspend + `withContext(PlatformDispatcher.DB)` per CLAUDE.md. Search text routes to the existing `SearchRepository` (FTS), intersected by id chunk. Steps: edit `.sq` -> `./gradlew :kmp:generateCommonMainSteleDatabase` -> `rsync -a kmp/build/generated/sqldelight/code/SteleDatabase/commonMain/ kmp/src/generated/sqldelight/` -> commit regenerated sources. No `RestrictedDatabaseQueries` stub (read-only); no `MigrationRunner.all` entry (no table). Add both queries to `QueryPlanAuditTest` (must use an index, no full-table scan) (~5 min x2)
##### Task 2.4.1b: `PageSource` port + `ActiveDbPageSource` using `getPagesFiltered`/`countPagesFiltered` and `getBlocksForPage` per page (~5 min)
##### Task 2.4.1c: Bounded-batch assertion test modeled on `LargeGraphWarmStartCrashTest`: filter/search change on 8 030 pages issues O(1) bounded queries (<=100 rows each, count via `countPagesFiltered`), not ~81 paged scans (~5 min)

#### Story 2.4.2: Plan (dry run) and apply (GATED on Story 1.1.4 and Story 1.2.1)
**As a** user, **I want** an accurate preview and then an idempotent commit, **so that** I trust the copy.
**Acceptance Criteria**:
- Dry-run classification.
  - *Given* selection of 30 pages where target has 10 identical, 5 overlapping, and 15 absent, *When* `plan`, *Then* `DryRunSummary(new=15, combined=5, unchanged=10, conflicts=n, unreadable=0)` and no file in target changed.
- Memory bounded.
  - *Given* 8 000 selected pages, *When* `plan`, *Then* only counters + <=50 conflict details are retained (assert via serialized size of `MergePlan` < 256 KB) and reads are <=100-row chunks / IN lists <=500.
- Idempotent apply.
  - *Given* a completed apply, *When* applied again, *Then* summary = `new=0, combined=0, unchanged=N` and no target file written.
- Stale plan.
  - (Counting rule) `conflicts` is a subset of `combined`; the commit button count is `new + combined`.
  - *Given* a plan computed, then target page `X` changed before commit, *When* `apply(plan)`, *Then* returns `Left(PlanStale)` with a recomputed summary and writes nothing.
- Partial failure continues.
  - *Given* page 3 of 10 throws `FileSystemError`, *When* applying, *Then* the other 9 are committed, page 3 is listed under failed, staging is kept, `retryFailed()` succeeds after the fault clears.
- Cancel.
  - *Given* a cancel after 1 200 of 4 000 pages, *When* cancelled, *Then* committed pages stay, the result states "Stopped after 1,200 of 4,000", and a re-run converges.
**Files**: `{C}/merge/PageMergeService.kt`, `{C}/merge/MergePlan.kt`, `{BT}/merge/PageMergeServiceTest.kt`

##### Task 2.4.2a: `MergePlan`/`DryRunSummary`/`MergeResult` sealed result types (typed failures, not string lists) (~5 min)
##### Task 2.4.2b: `plan()` streaming per chunk: source page -> `readExisting` -> `mergePage` -> counters (~5 min)
##### Task 2.4.2c: `stage()` spill selected pages as `StagedPage` JSON (one `<n>.json` per page; carries every block uuid) to `MergeStagingDirectory`; markdown is never the staging format (~4 min)
##### Task 2.4.2d: `apply()` writes per page through `TargetWriterRouter` (apply() itself takes no lock: `GraphWriteLock` is a non-reentrant `Mutex`, and only the router and the `switchGraph` init coroutine ever acquire it, so a second acquisition by apply() would self-deadlock), manifest flush, `Logger` counts, `PlanStale` fingerprint check (~5 min)
##### Task 2.4.2e: Service owns `CoroutineScope(SupervisorJob()+Default)` + `CoroutineExceptionHandler`; exposes progress `StateFlow`; cancel/retryFailed (~5 min)
##### Task 2.4.2f: Tests for each criterion; register in `AllBusinessTests` (~5 min)
##### (Task 2.4.2g REMOVED, Repair pass 4: no apply-on-activate for copies. "Resume" of an interrupted copy re-opens the dry run and re-plans against a target that is writable now (UX S9); it needs no deferred-apply trigger. A copy whose target is not writable is not startable.)
##### Task 2.4.2h: `apply()` maps `Left(WriteRefused(reason))` and retryable-exhausted `Left` from the router to a per-page `failed` entry (reason text, kept in staging, included in `retryFailed()`), never to a queue, so every `MergeResult` is definite: each selected page is exactly one of new / combined / unchanged / failed (~4 min)

#### Story 2.4.3: Linked pages and assets
**As a** user, **I want** optional inclusion of linked pages and assets, **so that** copied pages aren't dangling, without pulling in the whole graph.
**Acceptance Criteria**:
- Default off, depth 1.
  - *Given* page `P` linking `[[Q]]` and `Q` linking `[[R]]`, *When* closure = `Depth1`, *Then* selection adds `Q` only, not `R`; with `Off` adds nothing.
- Cap and confirm.
  - *Given* closure resolving to 1 340 pages, *When* computed, *Then* result flags `requiresConfirmation` (>200) and is truncated at 1 000 with a "N more not included" count.
- Assets.
  - *Given* block `![x](../assets/a.png)` and target already has `assets/a.png` with different bytes, *When* applied, *Then* the asset is copied as `a-<hash8>.png`, the link rewritten, `assetsRenamed=1`; identical bytes -> no copy; missing source asset -> warning, page still copied.
**Files**: `{C}/merge/LinkClosure.kt`, `{C}/merge/AssetCopier.kt`, `{BT}/merge/LinkClosureTest.kt`, `{BT}/merge/AssetCopierTest.kt`

##### Task 2.4.3a: `LinkClosurePolicy` + `LinkClosure.expand` using `getPagesByNames(chunk<=500)` and visited set (~5 min)
##### Task 2.4.3b: `AssetCopier` (hash dedupe, rename, link rewrite; reuse `BulkCopyVerifier` patterns) (~5 min)
##### Task 2.4.3c: Wire into `plan()`/`apply()` and tests (~5 min)

### Epic 2.5: Undo

#### Story 2.5.1: Undo last run
**As a** user, **I want** to undo a wrong copy, **so that** a mistake isn't permanent.
**Acceptance Criteria**:
- Safe removal.
  - *Given* manifest with created `pages/New.md` (hash H1) and added blocks ( `u1`, `u2` ) on `Projects`, *When* `undo(mergeId)` and nothing was edited, *Then* `New.md` is deleted and `u1`, `u2` removed; pre-existing target blocks remain.
- Edited-since is preserved.
  - *Given* `u2` content edited after the copy, *When* undone, *Then* `u2` stays and is reported "1 block changed since, left in place".
- Expiry.
  - *Given* a manifest older than 7 days, *When* swept, *Then* undo is unavailable.
**Files**: `{C}/merge/MergeUndo.kt`, `{BT}/merge/MergeUndoTest.kt`

##### Task 2.5.1a: `MergeUndo.undo` via the `TargetWriter` port's `removeBlocks(page, uuids, expectedContentHashes)`, `fileHash(page)`, `deletePageFile(page, expectedHash)` (defined in Task 2.3.1a; covered by the shared contract suite) - no overloading of `write` with deletion semantics (~5 min)
##### Task 2.5.1b: Tests incl. through-router (target active at undo time) (~5 min)

---

## Phase 3: Copy UI (commonMain Compose: Desktop, Android, Web, iOS)

### Epic 3.1: Selection state

#### Story 3.1.1: `CopyPagesViewModel`/state holder
**As a** user, **I want** selection that survives searching and filtering, **so that** I can build a set across queries.
**Acceptance Criteria**:
- Selection keyed by page uuid.
  - *Given* 3 pages selected, *When* the search text changes so those rows disappear, *Then* the counter still says "3 selected" and they remain selected on clearing the search.
- Select all matching.
  - *Given* a filter matching 213 pages, *When* "Select all 213 matching" is used, *Then* selection = those 213 only; a separate "All pages" action requires confirmation.
- Same-graph disabled.
  - *Given* source = active graph A, *When* choosing destination, *Then* A is disabled with reason "current graph".
- Unwritable destinations are disabled, never queued.
  - *Given* registered inactive graphs `enc` (encrypted), `saf` (grant lost) and `plain` (writable), *When* the destination chooser opens, *Then* `enc` and `saf` are listed DISABLED with their `TargetWriterCapabilities` reason text ("Can't write here: ..."), `plain` is selectable, the Review button cannot be enabled with a disabled destination, and no code path from the picker enqueues a copy into `ShareInbox`.
  - *Given* iOS/wasmJs, where inactive graphs cannot be written off-graph, *When* the push-direction chooser ("Copy pages to...") opens, *Then* every inactive graph is disabled with `PlatformUnsupported` text "Can't copy into <graph> from here on this device. Open <graph>, then use Copy pages from..." with a link action that switches to the pull direction; the pull-direction chooser ("Copy pages from...", Story 4.5.3) is the iOS/Web path and always targets the ACTIVE graph, so a destination always exists.
**Files**: `{C}/ui/screens/copy/CopyPagesState.kt`, `{C}/ui/screens/copy/CopyPagesViewModel.kt`, `{BT}/ui/CopyPagesViewModelTest.kt`

##### Task 3.1.1a: `PageSelection` value class + reducer functions (pure, commonTest `{CT}/ui/PageSelectionTest.kt`) (~5 min)
##### Task 3.1.1b: ViewModel owning its scope (no `rememberCoroutineScope`), paging via `ActiveDbPageSource`, debounce search (~5 min)
##### Task 3.1.1c: Destination list from `GraphLocator`, disabled-reason logic + tests (~4 min)
##### Task 3.1.1d (Gate 2, Repair pass 6): last-used copy destination. Setting `copy_last_destination_graph_id` (string, in the same `Settings` wrapper style as `CaptureTargetSettings`) written on a successful dry-run confirm; read when the destination chooser opens and preselected ONLY if that graph is registered, not the current graph, and not disabled by `TargetWriterCapabilities`; otherwise nothing is preselected. Tests: preselect when valid, no preselect when removed/disabled/equal to source, never changes `capture_*` keys (~3 h)
##### Task 3.1.1e (Repair pass 6): destination availability probing state. The chooser renders each graph row immediately in a "Checking..." state (spinner plus text, not colour only) while `TargetWriterCapabilities` / grant checks run per graph (async, each bounded by a 3 s timeout, results arrive independently); a timed-out probe becomes a disabled row "Couldn't check <graph>: <reason>" with Retry; Review stays disabled until the chosen row has a definitive answer; announced via polite live region. Robolectric test with a fake slow probe (~1 h)

### Epic 3.2: Picker UI

#### Story 3.2.1: Picker screen
**As a** user, **I want** a searchable multi-select list with filter chips, **so that** I can copy a chosen subset.
**Acceptance Criteria**:
- Accessibility semantics.
  - *Given* a row "Roadmap, 14 blocks", *When* TalkBack/semantics tree inspected, *Then* it is `toggleable(role=Checkbox)` with merged text "Roadmap, 14 blocks, checked"; the count line is `liveRegion=Polite` ("213 results, 12 selected").
- Filters.
  - *Given* chips Journals/Pages, date range, namespace, tag, *When* "Journals" + range Oct 2026 selected, *Then* only journals in range are listed.
- Keyboard (desktop).
  - *Given* focus in the list, *When* Space / Ctrl+A / Esc, *Then* toggles row / selects all filtered / cancels; Enter only activates when the primary button is focused.
- Esc / Back with a selection (consistency review C1; UX S2).
  - *Given* 0 pages selected, *When* Esc (desktop) or Back (Android), *Then* the picker closes immediately.
  - *Given* N >= 1 pages selected, *When* Esc or Back, *Then* a dialog asks "Discard selection of N pages?" with "Discard" and "Keep editing"; Discard closes and clears, Keep editing returns focus to where it was; nothing is written either way. (The capture overlay's Esc/Back rules differ on purpose, see Stories 4.2.1 and 4.3.1: capture text is user-typed content and is saved or asked about.)
- Reuse components: `FilterChip`, `Checkbox` per `AllPagesScreen.kt`; 48dp targets.
**Files**: `{C}/ui/screens/copy/CopyPagesScreen.kt`, `{C}/ui/screens/copy/PageSelectionRow.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyPagesScreenTest.kt` (Robolectric, per CLAUDE.md), `{BT}/ui/` none

##### Task 3.2.1a: Screen scaffold: search field, filter chips row, lazy list (virtualized), counter live region (~5 min)
##### Task 3.2.1b: Row component with semantics and linked-pages/assets toggles showing live "adds N pages" delta (~5 min)
##### Task 3.2.1c: Keyboard handling modeled on `SearchDialog.kt` (~4 min)
##### Task 3.2.1d: Robolectric UI test for semantics and selection persistence (~5 min)
##### Task 3.2.1e: Discard-selection confirmation on Esc/Back when selection > 0 (state in `CopyPagesViewModel`, dialog in `CopyPagesScreen`), with Robolectric test (~4 min)
##### Task 3.2.1f (Repair pass 6): loading, skeleton and accessibility states (about 3 h). (1) Initial list on an 8 000-page graph: skeleton rows (5 placeholder rows, no layout jump) for the first page; header line "Loading pages..." (polite live region); the count line shows "Counting..." until `countPagesFiltered` returns; subsequent 100-row pages append with a footer spinner and never block scrolling; search/filter changes keep the previous rows dimmed with "Updating..." (selection untouched) until the new first page arrives. (2) Failure while loading: the existing inline banner with Retry/Close. (3) Large text: rows grow in height (no truncation of the page name at 200% font scale; name wraps to 2 lines then ellipsises with full text in the semantics node); the count/selected line wraps; 48dp minimum targets hold. Robolectric test at `fontScale = 2.0f`. (4) RTL: layout mirrors (`LayoutDirection.Rtl`), checkbox leading edge flips, keyboard Left/Right semantics follow layout direction; Robolectric test with RTL and an Arabic/Hebrew page name. (5) Web keyboard (Pull and push on wasmJs): Tab order search -> chips -> select-all -> list -> options -> destination -> Review; list uses roving focus (arrow keys move, Space toggles, Home/End jump); visible focus ring; no browser-shortcut collisions (Ctrl+A is intercepted only while the list has focus; Esc closes only if no dialog is open); documented in the screen KDoc and checked in the manual Web checklist (validation.md). Loading skeleton honours reduced-motion (no shimmer animation required)

### Epic 3.3: Dry-run, progress, result, conflicts

#### Story 3.3.1: Dry-run dialog, progress, result dialog
**As a** user, **I want** to see exactly what will happen, watch progress, and get a retry/undo result, **so that** I'm never unsure of my data.
**Acceptance Criteria**:
- Dry-run wording and counts.
  - *Given* plan `new=3, combined=5, unchanged=20`, *When* shown, *Then* lines read "3 new - will be created", "5 already exist - blocks will be combined (nothing removed)", "20 unchanged - nothing to do", reassurance "Nothing in the destination is deleted or overwritten. The source graph is not changed.", and the button reads "Copy 8 pages".
- Nothing to do.
  - *Given* `new=0, combined=0`, *When* shown, *Then* commit disabled with "Nothing to copy - destination already has all selected content".
- Progress and stop (wording changed from "Cancel" to "Stop", Repair pass 6).
  - *Given* a running copy, *When* shown, *Then* determinate progress with `progressBarRangeInfo` and a keyboard/TalkBack reachable **Stop** button (label "Stop"; helper text "Pages already copied stay copied."); pressing it stops after the page in flight, the result dialog title reads "Stopped after 1,200 of 4,000", the body states the partial write ("1,200 pages were copied and kept. 2,800 were not copied."), and offers Continue, Undo this copy, Done. The word "Cancel" is not used for this action anywhere (it implies nothing happened). Completion announced.
- Graph switch during a run (Repair pass 7).
  - *Given* a push copy to Work graph is running and the user switches the active graph (any graph, including the destination or the source), *Then* the run is NOT blocked and the destination does not change (the router re-routes later pages internally, Task 2.4.2d), and the user is told: the progress dialog (or, when the switch dismissed it, a persistent snackbar) shows "Copy to Work graph continues in the background" until it finishes, then the normal result dialog or snackbar appears; the notice is a polite live region. *Given* a PULL copy (iOS/Web, Gate 3), whose later pages need the destination to stay active, *When* the user tries to switch graphs mid-run, *Then* a confirm appears: "A copy into <graph> is running. Stop it and switch?" with "Stop and switch" / "Keep copying"; "Stop and switch" behaves like Stop (partial result, Undo available). Robolectric test for the push notice and the pull confirm.
- Dry-run loading.
  - *Given* a plan running over many chunks, *When* the dry-run dialog opens, *Then* it shows "Checking N of M pages..." with determinate progress (chunk counter), live counters, and a working Back that cancels the PLAN (nothing was written, so "Cancel"/Back wording is correct there); the commit button is disabled until the plan completes.
- Result dialog (not just snackbar).
  - *Given* result `new 3, combined 5, unchanged 20, conflicts 2 (inside combined), failed 1`, *When* shown, *Then* it lists categories (text "3 new", "5 combined", "20 unchanged", "2 conflicts kept (both versions)", "1 failed"), expandable failed list with "Retry failed", "Review 2 conflicts", "Undo this copy".
- Stale plan.
  - *Given* `PlanStale`, *When* commit pressed, *Then* "Things changed - review again" with the new summary.
- Interrupted copy.
  - *Given* an `InProgress` manifest at launch, *When* app starts, *Then* a notice offers "Resume" (idempotent re-run).
**Files**: `{C}/ui/screens/copy/DryRunDialog.kt`, `{C}/ui/screens/copy/CopyProgressDialog.kt` (pattern: `StorageMoveProgressDialog.kt`), `{C}/ui/screens/copy/CopyResultDialog.kt`, `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/CopyDialogsTest.kt`

##### Task 3.3.1a: `DryRunDialog` (counts, assets renamed, linked-page delta, confirm >200) (~5 min)
##### Task 3.3.1b: `CopyProgressDialog` with the **Stop** button (label, helper text and partial-write result wording per the AC; never "Cancel") + semantics (~5 min)
##### Task 3.3.1c: Android run host: launch apply in application scope (`{A}/SteleKitApplication.kt` owned scope with `CoroutineExceptionHandler`), persist interrupted marker (~5 min)
##### Task 3.3.1d: `CopyResultDialog` with retry/undo/conflicts actions (~5 min)
##### Task 3.3.1e: Robolectric tests for wording, disabled commit, result actions (~5 min)

#### Story 3.3.2: Review conflicts (Gate 1 since Repair pass 7, COMMITTED, not on the cut list: it backs the requirement "true conflicts flagged" and Gate 1 already flags conflicts)
**As a** user, **I want** to find flagged conflict blocks after a copy, **so that** I can resolve them in my own time.
**Acceptance Criteria**:
- List and jump.
  - *Given* 2 blocks with `merge-conflict:: true` in graph B, *When* "Review conflicts" opens, *Then* a bounded list shows each with page name and the neighboring original block; tapping navigates to the page.
- Loading.
  - *Given* the property search is running, *When* the screen opens, *Then* 3 skeleton rows and "Loading conflicts..." (polite live region) show until the first bounded page returns; a failed load shows the banner with Retry/Close; the empty result shows "No conflicts to review." with Close.
- Resolve (wording changed, Repair pass 6, ADR-002 rev. 4).
  - *Given* a conflict block, *When* user taps **"Mark resolved"** (formerly "Keep both": both blocks were already kept, so the action only dismisses the flag), *Then* the `merge-conflict` property is removed (via `DatabaseWriteActor`), the block keeps its `src-id`, and a repeat copy of the same source content is `Unchanged` (durable).
  - *Given* a conflict block, *When* user taps **"Remove this block"** (renamed from "Remove copy" in Repair pass 7 so it does not collide with "Undo this copy", which undoes a whole run), *Then* a confirmation reads "Remove this block? It will come back, flagged again, if you copy this page from <source graph> again. To keep it from coming back, choose Mark resolved instead." with "Remove this block" / "Mark resolved instead" / "Cancel"; confirming deletes only the flagged block and shows a 10 s snackbar with Undo that repeats the "may return on the next copy" sentence. Test: remove, then re-copy, asserts the sibling returns flagged exactly once; mark resolved, then re-copy, asserts nothing is added.
- Accessibility.
  - *Given* a row, *Then* it is one merged semantics node "Conflict on page Roadmap: original 'Draft v1', copied 'Draft v2' from Personal" with custom actions (`semantics.customActions`) "Mark resolved", "Remove this block", "Open page" whose labels include the page name for TalkBack/switch users; the remaining-count line is a polite live region; after an action focus moves to the next row (or the empty-state text); at 200% font scale rows wrap rather than truncate; RTL mirrors layout; Web: Tab reaches each row's three buttons in order, Enter/Space activates, visible focus ring.
**Files**: `{C}/ui/screens/copy/ConflictReviewScreen.kt`, `{C}/repository/` use existing property-search APIs (verify first; no new unbounded query), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/ui/ConflictReviewTest.kt`

##### Task 3.3.2a: Find existing property-search repository API with `sg`; add bounded query only if absent (follow `.sq` + RestrictedDatabaseQueries rules if needed) (~5 min)
##### Task 3.3.2b: Screen modeled on `JournalMergeReviewScreen.kt` look and feel (~5 min)
##### Task 3.3.2c: Keep/remove actions through `DatabaseWriteActor` + test (~5 min)

### Epic 3.4: Entry points and removal of the old flow

#### Story 3.4.1: "Copy pages to..." entry points
**As a** user, **I want** one verb in the sidebar and page menu, **so that** there is no hidden export-then-switch mode.
**Acceptance Criteria**:
- Sidebar and page context.
  - *Given* the left sidebar, *When* the user opens it, *Then* one "Copy pages to..." action replaces "export pages for merge" / "Merge captured pages"; page overflow menu offers "Copy this page to..." preselecting that page.
- Same flow on all four platforms (shared Compose); wasmJs and iOS compile.
**Files**: `{C}/ui/GraphContentLeftSidebar.kt`, `{C}/ui/components/Sidebar.kt`, `{C}/ui/App.kt`, `{C}/ui/StelekitAppDependencies.kt`

##### Task 3.4.1a: Wire `PageMergeService` into `StelekitAppDependencies` (~4 min)
##### Task 3.4.1b: Replace sidebar callbacks (lines ~87-88, 185, 195) and route in `App.kt` (~5 min)
##### Task 3.4.1c: Page overflow "Copy this page to..." (~4 min)

#### Story 3.4.2: Delete `GraphMergeService`
**As a** maintainer, **I want** the old snapshot flow gone, **so that** the O(graph) violation can't return.
**Acceptance Criteria**:
- *Given* all callers migrated, *When* building all targets, *Then* no reference to `GraphMergeService`/`pendingPageCount` remains and old behavior tests are ported: old test "skips existing name" becomes "combines blocks of same-named page".
**Files**: `{C}/transfer/GraphMergeService.kt` (delete), `{BT}/transfer/GraphMergeServiceTest.kt` (port/delete), `{BT}/AllBusinessTests.kt`

##### Task 3.4.2a: Port test cases to `PageMergeServiceTest` (~5 min)
##### Task 3.4.2b: Delete class + references; compile all targets (`bazel build //kmp:desktop_app`, wasm compile) (~5 min)

---

## Phase 4: Share target

### Epic 4.1: Shared capture target plumbing

#### Story 4.1.1: `CaptureTargetSettings` and resolver
**As a** user, **I want** a default capture graph and remembered last choice, **so that** sharing is zero-tap when I want it.
**Acceptance Criteria**:
- Resolution order.
  - *Given* default=`work`, remember_last=true, last=`personal`, *When* resolve, *Then* `NamedGraph(personal)`; with remember_last=false -> `work`; with last graph deleted -> `work`; with both deleted -> `ActiveGraph`.
- Override doesn't change the default.
  - *Given* default=`work`, *When* a share overrides to `personal`, *Then* `capture_default_graph_id` is still `work` and `capture_last_graph_id`=`personal`.
- Labeling: Settings copy distinguishes "Default capture graph" and "Remember last used".
**Files**: `{C}/capture/CaptureTargetSettings.kt`, `{C}/capture/CaptureTargetResolver.kt`, `{CT}/capture/CaptureTargetResolverTest.kt`, `{C}/ui/components/settings/GeneralSettings.kt`

##### Task 4.1.1a: Settings wrapper (strings; pattern `QrTransferSettings` + test like `QrTransferSettingsTest`) (~4 min)
##### Task 4.1.1b: Pure resolver + property/example tests for fallback chain (~5 min)
##### Task 4.1.1c: Settings UI section (default graph picker, remember toggle) (~5 min)

#### Story 4.1.2: Extract `JournalAppender` (Refactor-first)
**As a** developer, **I want** one append-to-journal service, **so that** Android and Desktop share logic and idempotence.
**Acceptance Criteria**:
- Behavior preserved for the active graph.
  - *Given* active graph and text `hello`, `captureId=c1`, *When* `append`, *Then* today's journal gains one block `hello` with deterministic uuid(c1) via the existing chain (`ensureTodayJournal` -> `saveBlock` -> `GraphWriter.savePage`); existing `CaptureWriter*Test`/capture tests still pass.
- Idempotent replay.
  - *Given* the same `captureId=c1` appended twice, *When* second call, *Then* `AlreadyPresent`, still one block.
**Files**: `{C}/capture/JournalAppender.kt`, `{C}/capture/CaptureWriter.kt`, `{A}/CaptureViewModel.kt`, `{J}/capture/CaptureController.kt`, `{BT}/capture/JournalAppenderTest.kt`

##### Task 4.1.2a: Read `CaptureViewModel.kt` (~313-354) and `CaptureWriter.kt`; define `JournalAppender` + `AppendOutcome` (~5 min)
##### Task 4.1.2b: Active-graph implementation delegating to `CaptureWriter.writeCapture` (~4 min)
##### Task 4.1.2c: Switch `CaptureViewModel` and `CaptureController` to it; run existing capture tests (~5 min)

#### Story 4.1.3: Append to a chosen non-active graph (GATED: Story 1.1.4, Spike 0.1.1 real-filesystem run green + device pass, Spike 0.1.4 go decision)
**As a** user, **I want** a share to land in the chosen graph without switching, **so that** capture is one step.
**Acceptance Criteria**:
- Off-graph append.
  - *Given* target `NamedGraph(work)` inactive with the journal file for date D (path obtained from `PageFileResolver.resolveJournal(D, existingStems)`, rules in Story 1.2.2; the test computes D's path through the resolver, no literal file name) containing `- existing`, *When* `append("hello", captureId=c1)`, *Then* the file = `- existing\n- hello\n  id:: <uuid(c1)>` via `mergePage` (one-block page), outcome `Appended`; repeat gives `AlreadyPresent`. A second case uses an existing `-`-separated stem to prove reuse rather than a duplicate file.
- Missing journal file created with the target's filename format.
  - *Given* no journal file for the date, *When* appended, *Then* the file named by `PageFileResolver` (creation stem `YYYY_MM_DD` per the Story 1.2.2 rules, proven by Task 1.2.2d and Spike 0.1.1, not assumed) is created, and on next open of the target the loader reconciles it to the same page `ensureTodayJournal` would create (no duplicate journal).
- Off-graph payload scope (explicit behavior, from the adversarial review).
  - *Given* a share to a non-active graph, *Then* (DECIDED and confirmed, Repair pass 6) plain text is appended; link suggestions and stub-page creation (active-graph features in `CaptureViewModel.performSave`, which need that graph's open DB) are disabled with the visible note "Link suggestions aren't available when saving to a graph that isn't open" in the S10 overlay; an image share copies the image into the TARGET graph's `assets/` via `AssetCopier` (not a `cacheDir` path that can be evicted), with the same containment check as Task 1.2.2e, or queues in the inbox with the image copied to app-private storage at enqueue time. Test: image share to an inactive graph lands under `<target>/assets/` and the block links to it; link-suggestion UI is hidden, not silently empty.
- Failure fallback.
  - *Given* SAF grant revoked, *When* appended, *Then* `Queued("permission")` and a `ShareInbox` item exists; text not lost. (Queuing applies to SHARES only; copies never queue, Story 2.3.3.)
- Target becomes active → active chain used (router).
- "One step" metric is conditional on Spike 0.1.4 (consistency review C5).
  - *Given* the pass percentage P that Spike 0.1.4 measured on a real graph, *When* this Story is declared done, *Then* the PR description reports P and the guard form used (exact or structure-stable), the metric "a share lands in the chosen graph in one step" is claimed only for P >= 95%, and the fraction `1 - P` of targets that will queue instead is stated. An integration test over the Spike 0.1.4 fixtures asserts that every fixture that passes the guard lands in one step (`Appended`) and every fixture that fails is `Queued("not-round-trippable")`, never silently written.
**Files**: `{C}/capture/JournalAppender.kt` (single class; no `OffGraphJournalAppender` hierarchy), `{C}/capture/InboxFallbackAppender.kt`, `{BT}/capture/JournalAppenderOffGraphTest.kt`

##### Task 4.1.3a: Extend `JournalAppender` to call the one `TargetWriterRouter` (Story 2.3.2) with a one-block `MergePage` (same idempotence path as merge) (~5 min)
##### Task 4.1.3b: `InboxFallbackAppender` decorator: on `Left(capability/permission/NotRoundTrippable)` from the router, enqueue into `ShareInbox` and return `Queued(reason)`. No second router, no second lock/readiness logic (~4 min)
##### Task 4.1.3c: Tests for the three criteria (~5 min)

### Epic 4.2: Android overlay

#### Story 4.2.1: Destination row in `CaptureActivity` (GATED 4.1.3)
**As a** user, **I want** to see and change the destination graph in the share sheet, **so that** I can file things correctly.
**Acceptance Criteria**:
- Destination row replaces the hard-coded "Today's Journal" label.
  - *Given* default `work`, last `personal`, remember on, *When* a text share opens the overlay, *Then* the row reads "Saving to Personal graph - Today's Journal", has `contentDescription` "Saving to Personal graph. Double-tap to change", and tapping opens a menu of graphs (inline chips if <=4) with typed text preserved.
- Selecting a graph updates `capture_last_graph_id` only; Save appends there.
  - *Given* user picks `work`, *When* Save, *Then* block lands in work's journal and the row shows confirmation "Added to Work graph".
- TalkBack auto-finish timer pauses while the menu is open.
- Unavailable target.
  - *Given* target graph path missing/permission lost, *When* overlay opens, *Then* destination row shows an error reason with actions "Save to <fallback>", "Retry" and "Queue for later", shared text stays visible (replace `NoGraphPlaceholderContent` path for this case), and Save is never silent-lossy ("Queued for <graph>" state if the inbox is used). Also in scope here (consistency review N6, DECIDED in ADR-004): the "no graphs configured" placeholder queues the text on Close into the `ShareInbox.UNASSIGNED` slot (`share-inbox/_unassigned/`), which is re-keyed once to the first graph created; the overlay says "Saved. It will be added to the first graph you create."; a redelivered share shows "Already added".
- Resolver loading (Repair pass 6).
  - *Given* the overlay opens before the target is resolved/probed (grant check, registry read), *Then* the destination row shows "Saving to... (checking)" with a spinner and text, Save is enabled only for the already-known fallback or disabled with "Checking destination..." for at most 2 s, then the row resolves or shows the unavailable state; typed text is never blocked; the row is a polite live region.
- No active graph and a named target works (gate on `activeRepositorySet` removed for named targets).
- Process-death/redelivery safe: payload persisted before UI, handled flag in `savedInstanceState`, `onNewIntent` handled, write runs in application scope.
- Back with unsaved text AUTO-SAVES (user decision 2; consistency review C1; UX S10).
  - *Given* typed or shared text in the overlay and a resolved destination, *When* the user presses Back, *Then* the text is appended to the shown destination (same `captureId`, so redelivery is idempotent) and the overlay finishes with the "Added to <graph>" confirmation; *Given* that append fails or the destination is unwritable, *Then* the text is queued in `ShareInbox` for that graph, the activity finishes, and a notification-style snackbar/toast reads "Couldn't save to <graph>. Queued for <graph>." with the S13 indicator visible; the text is never lost and never silently dropped. *Given* the text is empty or whitespace, Back just closes.
- Back auto-save confirmation (Repair pass 6, mitigates a silent misfile).
  - *Given* Back auto-saved, *Then* the system toast/snackbar shown after the overlay finishes names the destination graph: "Saved to Personal graph" with an **Undo** action (removes just-added block via the existing capture undo, within the toast window) and a **Change** action (re-opens the overlay with the text and the destination menu so the user can move it; implemented as undo + reopen, cost accepted as cheap because both primitives exist). If the toast host is unavailable after `finish()` (activity gone), a minimal `Toast` with the graph name is shown, Undo/Change being best-effort and documented as such. FALLBACK (Repair pass 7), because the toast is a best-effort safety net, not a guarantee: every Back auto-save is also recorded in a small recent-captures list (last 5, captureId plus graph plus time, app-private) surfaced as a one-line notice at the next app start ("Last share saved to Personal graph" with Undo and Change while that block is still the last one added), and a Back auto-save that routed through the inbox is covered by the persistent queued badge (Task 4.4.1d). The wrong-graph risk is therefore bounded by visibility at the next launch, not only by the toast. The `last_graph` setting is updated only on a successful save, not on Undo.
  - *Given* TalkBack, *Then* the toast text is announced including the graph name.
**Files**: `{A}/CaptureActivity.kt`, `{A}/CaptureViewModel.kt`, `androidApp/src/test/` Robolectric test `CaptureActivityTargetTest.kt` (path: `androidApp/src/test/kotlin/dev/stapler/stelekit/CaptureActivityTargetTest.kt`; the directory EXISTS, VERIFIED by `ls` in Repair pass 6: it already holds `CaptureActivityTest.kt`, `CaptureViewModelTest.kt`, `CaptureShareTextTest.kt`; extend those rather than starting a new harness)

##### Task 4.2.1a: ViewModel: resolved target state, `selectTarget`, availability check (persisted grants) (~5 min)
##### Task 4.2.1b: Destination row composable + menu with a11y semantics (~5 min)
##### Task 4.2.1c: Pause auto-finish timer when menu open (extend existing logic ~lines 321-357) (~3 min)
##### Task 4.2.1d: Unavailable-target state preserving text (~5 min)
##### Task 4.2.1e: Application-scope write + `onNewIntent`/handled-flag/payload persistence (~5 min)
##### Task 4.2.1f: Robolectric tests for row, override, unavailable state (~5 min)
##### Task 4.2.1h (Repair pass 6): Back-save confirmation toast with graph name, Undo and Change actions; resolver "checking" state; Robolectric tests for both (~3 h, inside the 19 h of Story 4.2.1)
##### Task 4.2.1g: Back handler: auto-save to the shown destination, fall back to `ShareInbox` + "Queued for <graph>" message on failure; Robolectric tests for success, failure-queues, empty-text (~5 min)

#### Story 4.2.2: Direct Share shortcuts (OPTIONAL / CUTTABLE, post-MVP; excluded from the appetite; nothing else depends on it)
**As a** user, **I want** each graph in the system share sheet, **so that** I can pick the graph before the overlay.
**Acceptance Criteria**:
- *Given* graphs work/personal, *When* sharing text from another app, *Then* the chooser lists "Work graph" and "Personal graph" shortcuts that open `CaptureActivity` with extra `target_graph_id`, which overrides the resolver for that share only.
**Files**: `{A}/widget/` or new `{A}/ShareShortcutPublisher.kt`, `androidApp/src/main/AndroidManifest.xml` (share-target meta-data + `shortcuts.xml`)
##### Task 4.2.2a: Publish `ShortcutInfoCompat` per registered graph on graph registry change (~5 min)
##### Task 4.2.2b: Manifest `<meta-data android:name="android.service.chooser.chooser_target_service">`/share-target XML + intent extra handling (~5 min)

### Epic 4.3: Desktop quick capture

#### Story 4.3.1: Graph chooser in `CaptureController`
**As a** desktop user, **I want** to pick the capture graph in the popup, **so that** hotkey captures go to the right graph.
**Overlap with open issue #266 (VERIFIED with `gh issue view 266`, state OPEN, "feat: OS-level quick capture (context menu / Share extension) for Desktop platforms")**: #266 is about NEW OS-level capture entry points (Services menu, file-manager "Send to", Windows context menu) that would post through a local API (#232) into today's journal. This story only adds a graph chooser to the EXISTING `CaptureController` popup, so it does not implement #266. The overlap is the destination: any #266 entry point should resolve its graph through `CaptureTargetResolver` + `JournalAppender` built here, so #266 must not grow a second "which graph" path. No code dependency in either direction; add a comment on #266 when this story lands (owner action, not done here).
**Acceptance Criteria**:
- State and success message.
  - *Given* `CapturePopupState.Shown(text="idea", targetGraphId=work)`, *When* saved, *Then* appender receives `NamedGraph(work)`, toast "Saved to Work graph's journal", last-used updated, default unchanged.
- Keyboard.
  - *Given* popup focused, *When* Alt+G, *Then* the graph menu opens; Tab reaches it; selection does not discard typed text.
- Failure keeps popup open with queued/error state.
- Esc with typed text ASKS before discarding (user decision 2; consistency review C1; UX S11).
  - *Given* the popup with empty or whitespace-only text, *When* Esc, *Then* it closes immediately. *Given* non-empty text, *When* Esc, *Then* a confirmation "Discard this note?" with "Discard" and "Keep editing" appears; Discard closes and saves nothing, Keep editing returns focus to the text field with text intact. Esc never auto-saves on desktop (differs from Android Back by design: a hotkey popup is dismissed reflexively, and a wrong auto-save into a graph is harder to notice than a prompt). The threshold is any non-whitespace text (the earlier "longer than one keystroke" assumption in the UX draft is dropped).
**Files**: `{J}/capture/CaptureController.kt`, `{J}/capture/CapturePopupState.kt`, popup composable (find with Glob `{J}/capture/*Popup*`), `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/capture/CaptureControllerTargetTest.kt` (state logic only; avoid display-dependent UI per `scripts/jvm-display-check.sh`)

##### Task 4.3.1a: Add `targetGraphId` to `Shown`, resolve via `CaptureTargetResolver` (~4 min)
##### Task 4.3.1b: `performSave` via `JournalAppender` with `CaptureTarget` (~4 min)
##### Task 4.3.1c: Popup chooser UI + Alt+G (~5 min)
##### Task 4.3.1d: Controller state tests (businessTest if no display needed) (~5 min)
##### Task 4.3.1e: Esc handling: empty text closes; non-empty shows the "Discard this note?" state in `CapturePopupState` (new `ConfirmDiscard` state so it is testable without a renderer); tests for both branches and Keep editing (~4 min)

### Epic 4.4: Share inbox

#### Story 4.4.1: `ShareInbox` and drain
**As a** user, **I want** failed shares kept and visibly queued, **so that** nothing is silently lost.
**Acceptance Criteria**:
- Durable and idempotent.
  - *Given* `enqueue(graph=work, text="x", captureId=c9)` then process restart, *When* graph `work` becomes ready (`readyGraphId == work`, i.e. `activeRepositorySet` non-null for `work` and `awaitPendingMigration()` has returned), *Then* `x` is appended through the router/active path once; draining twice does not duplicate (deterministic uuid(c9)).
- Drain does not race the initial load: the drain waits for the readiness signal above (and the loader's idle state if exposed) rather than firing on the registry's `activeGraphId` change.
- Visible.
  - *Given* 2 queued items, *When* UI observes `pendingCount`, *Then* a persistent indicator shows "2 shares queued for Work graph". On Android the indicator is a sidebar/drawer badge on the graph switcher plus Settings > Capture plus an app-start notice (Task 4.4.1d), not Settings alone.
- Shared images copied to app-private storage at enqueue time (temporary URI grant dies with the activity).
**Files**: `{C}/capture/ShareInbox.kt`, `{C}/capture/ShareInboxDrain.kt`, `{C}/ui/StelekitAppDependencies.kt` (wiring only), `{BT}/capture/ShareInboxTest.kt`. `GraphManager.kt` is NOT in this list (no `GraphActivated` symbol exists; the drain is an external collector).

##### Task 4.4.1a: JSON-file inbox per `GraphId` over `FileSystem`, plus the `UNASSIGNED` slot (ADR-004). **Crash-safety spec (Repair pass 6)**: one file per item `share-inbox/<graphKey>/<captureId>.json`; envelope `{ "v": 1, "captureId", "graphKey", "createdAtEpochMs", "payload": {...}, "sha256": "<hex of canonical payload bytes>" }`; write = serialize to `<name>.json.tmp` in the SAME directory, `fsync`/flush where the `FileSystem` supports it, then atomic rename over the final name (on a FileSystem without atomic rename, write `.json.tmp` and treat a leftover `.tmp` as incomplete); read verifies `v` (unknown future version -> item kept untouched and shown as "needs a newer app version", never deleted), parses, and verifies `sha256`; a failed checksum or unparseable file is MOVED to `share-inbox/_quarantine/` (never deleted) and surfaced in the S13 indicator as "1 share couldn't be read" with Copy text if the payload text is recoverable. Startup sweep removes only `.tmp` files older than 1 h and never touches `.json`. Image payloads are copied to `share-inbox/<graphKey>/<captureId>.img` with the same tmp+rename+hash before the `.json` that references them is renamed into place (so a crash leaves an orphan image, never a dangling reference). Re-keying (ADR-004) is a directory rename and is idempotent after a crash. Tests (businessTest, `FakeFileSystem` with injected failure after each step): crash after tmp write, after rename, mid-image; corrupted checksum; truncated JSON; future version; double drain; re-key crash; all end with the text recoverable and no duplicate append (~5 min label, ~4 h real, inside Story 4.4.1's 12 h base)
##### Task 4.4.1b: `ShareInboxDrain` collector over `GraphManager.activeRepositorySet` / `readyGraphId` (Task 2.1.2a) wired in `StelekitAppDependencies`; waits on `awaitPendingMigration()` before draining (~4 min)
##### Task 4.4.1c: Pending indicator state + tests (~5 min)
##### Task 4.4.1d (Gate 1 since Repair pass 7, COMMITTED, not on the cut list; Repair pass 6): rescue actions on queued items, backing REQ-17 ("nothing lost silently"): per item **Copy text** (to clipboard; works even when the target graph no longer exists) and **Discard** (confirm showing the first 80 characters; only after the user has had the chance to Copy text), plus Retry now. Semantics: each item row is one merged node "Queued share for Work graph: 'meeting notes...', Today 09:14" with `customActions` Copy text / Discard / Retry now (labels include graph name); the panel count is a polite live region; focus moves to the next item after Discard; 200% font scale wraps; RTL mirrors; Web keyboard Tab/Enter. Chip host decision (revised Repair pass 7): Desktop near the graph switcher; Android shows a persistent queued badge (count plus text, not colour only) on the graph-switcher entry in the sidebar/drawer whenever the inbox has items, in addition to Settings > Capture and the overlay's own queued row, and an app-start notice (a snackbar once per cold start: "2 shares are queued for Work graph" with a View action) so a share to a graph that never drains cannot go unnoticed. Robolectric tests for actions, semantics, the badge and the app-start notice (~4 h for the rescue actions, counted in the Gate 1 inbox figure; the badge and notice reuse the Task 4.4.1c indicator state and sit inside Story 4.4.1's 12 h base)

### Epic 4.5: iOS and Web pull-style copy (v1, decision 4; scheduled after the core Android/Desktop slice)

Why pull: iOS/Web cannot address an inactive graph's files for WRITING, and the push source is always the active graph's DB (`ActiveDbPageSource`), so push has no destination there. Pull flips the direction: the user opens the DESTINATION graph (the target is the active graph, written by `ActiveTargetWriter`, the same code as desktop active-target copies) and reads pages from a chosen SOURCE graph's markdown, read-only. Copies never queue; every result is definite.

#### Story 4.5.1: iOS/Web copy direction, source capability gating and quick-add target (REWRITTEN, Repair pass 5: pull-style copy replaces the "no destination" limitation)
**As an** iOS/Web user, **I want** to pull pages from another graph into the graph I have open, **so that** I can copy between graphs on a platform that cannot write into a closed graph.
**Acceptance Criteria**:
- Direction variant.
  - *Given* a `CopyDirection` { `Push` (source = active, choose destination; Android/Desktop and any platform whose `TargetWriterCapabilities` allows it), `Pull` (destination = active, choose source) }, *When* the copy flow opens on iOS/wasmJs, *Then* `Pull` is the only direction offered; on Android/Desktop only `Push` is exposed in v1 (the code path is shared; exposing `Pull` there is a follow-up, not planned). The picker, filters, linked-page options, dry-run, progress, result and undo screens are the same composables, parameterized by direction (titles read "Copy pages from <source>").
- Source capability gating (`SourceReadCapabilities`, a sibling of `TargetWriterCapabilities`; sealed reasons `Encrypted`, `NoGrant`, `FolderMissing`, `PlatformUnsupported`, `UnreadableIo`).
  - *Given* a registered inactive graph, *When* the source chooser opens, *Then* it is selectable only if `SourceReadCapabilities.canReadOffGraph(graph)` is true (per Spike 0.1.5 for that platform and storage kind); otherwise it is listed DISABLED WITH ITS REASON and, where the platform can re-grant (`NoGrant`), a "Re-select folder" action. The active graph is disabled as "current graph". Spike 0.1.5 negative for a platform => every source on it shows the `PlatformUnsupported` reason and the screen ends in "no source available" with Close.
- No queue, ever.
  - *Given* a pull run, *Then* the target is the active graph, results are `new`/`combined`/`unchanged`/`failed` (and `unreadable` for a source file that cannot be parsed), no `ShareInbox` entry and no `PendingApplyOnActivate` exist, and no pending indicator is shown for copies.
- Quick-add target (consistency review C7). See Task 4.5.1e: either iOS/Web has an in-app quick-add entry point, in which case it resolves its graph through `CaptureTargetResolver` and appends through `JournalAppender` (a failed or unaddressable non-active target is QUEUED in `ShareInbox`, because this is a share-type write, and drained when that graph is ready), or it has none, in which case the Settings "Used by quick add" wording (UX S12) is replaced by "Default capture graph is used by sharing and quick capture on Android and Desktop" and no iOS/Web wiring is built.
- Settings default graph exists on all platforms; the inbox is ready for a future share extension/PWA `share_target` (documented, not built).
- Compile gates.
  - *Given* CI, *When* wasmJs and iOS targets compile, *Then* no `java.*` usage in new commonMain code and tests (`./gradlew :kmp:compileTestKotlinWasmJs -PenableJs=true`, `:kmp:compileTestKotlinIosSimulatorArm64`).
**Files**: `{C}/merge/CopyDirection.kt`, `{C}/merge/SourceReadCapabilities.kt`, `{BT}/merge/SourceReadCapabilitiesTest.kt`, `{BT}/merge/IosWebCopyGatingTest.kt`, `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/merge/MergePageWasmSmokeTest.kt`

##### Task 4.5.1a: `CopyDirection` + `SourceReadCapabilities` over `PlatformFileSystem`/`CryptoLayer`, per-platform mapping from Spike 0.1.5 (~5 min)
##### Task 4.5.1b: Source-chooser rendering of disabled reasons and the re-select action + wasm smoke test of `mergePage` (~5 min)
##### Task 4.5.1c: Document the pull-copy decision, the Spike 0.1.5 outcome and the deferred external entry points (share extension, PWA `share_target`) in ADR-001 consequences (~3 min)
##### Task 4.5.1d: (replaces the removed apply-on-activate test) `IosWebCopyGatingTest`: on iOS/wasm capability fakes only `Pull` is offered, push destinations are disabled with `PlatformUnsupported`, an unreadable source is disabled with its reason, and no staging directory or `ShareInbox` entry is created by a disabled attempt (~4 min)
##### Task 4.5.1e: Determine (Glob/grep under `iosMain`, `wasmJsMain`, `commonMain/ui`) whether iOS or Web has a quick-add/capture entry point. If yes, wire it to `CaptureTargetResolver` + `JournalAppender` (~5 min); if no, change UX S12's note and drop this AC. Record the finding in this Story before Phase 4.5 is scheduled (~3 min)

##### Task 4.5.1f (Repair pass 6): iOS/Web quick-add wiring, executed only if Task 4.5.1e finds an entry point: resolve the destination via `CaptureTargetResolver`, append via `JournalAppender`; an unaddressable non-active target is QUEUED in `ShareInbox` (a share-type write); S12's "Used by quick add" note is shown only then, else the Android/Desktop-only note. Tests: resolver + appender with iOS/wasm capability fakes (~3 h, inside Story 4.5.1's 11 h). If 4.5.1e finds none, this task is deleted and Story 4.5.1 drops to 8 h

#### Story 4.5.2: `SourceGraphReader` - read-only markdown scan of an inactive graph (GATED on Spike 0.1.5, Story 1.1.4)
**As a** developer, **I want** a bounded, read-only reader that turns a source graph's markdown into `StagedPage`s, **so that** pull-copy needs no second database and reuses the merge core.
**Acceptance Criteria**:
- Port and implementation.
  - *Given* the port `SourceGraphReader { listEntries(graph, afterName, limit): Either<ReadError, List<SourceEntry>>; readPage(graph, entry): Either<ReadError, StagedPage> }` and its `commonMain` implementation `MarkdownSourceGraphReader` over `PlatformFileSystem` (iOS: security-scoped bookmark URL; Web: persisted `FileSystemDirectoryHandle` or OPFS directory), *When* used, *Then* it only lists and reads (no write, no `DriverFactory`/`SteleDatabase` open for the source, no `GraphManager.addGraph`/`switchGraph`, no `FileRegistry` or watcher registration), consistent with ADR-001's single-open `GraphManager`.
- Same identity as the push path.
  - *Given* a source file, *When* parsed with `MarkdownPageParser` (explicit `id::` used verbatim; otherwise the same derived uuid `GraphLoader` would assign for that file, verified by Task 4.5.2d) and converted to `MergePage` then `StagedPage` JSON, *Then* the result equals the `StagedPage` produced by the push path for the same page loaded into a DB, so `mergePage`, `UuidRemap` (always-remap, SHA-256) and `src-id` behave identically and a pull then a push of the same page are idempotent in the same way (`unchanged` on repeat).
- Bounded memory (CLAUDE.md "Graph-scale reads must be paginated, projected, or chunked").
  - *Given* a source graph of 8 000+ pages, *Then* `listEntries` is a PROJECTION (name, kind journal/page, file size, mtime; no content) delivered in pages of <= 100 and held as a sorted name index of at most the entry projection (the `PageNameIndex` pattern), never page bodies; `readPage` reads and parses ONE file at a time; the dry run and apply loops process chunks of <= 50 pages, each spilled to `MergeStagingDirectory` as `<n>.json` and released before the next chunk; a single file over 2 MB (configurable) returns `ReadError.TooLarge` and the page is reported `unreadable` (not loaded); directory enumeration is not materialized as a full file list of contents. No unbounded read is added to any repository interface.
- Filters available without a DB.
  - *Given* the source has no queryable DB, *Then* the pull picker supports name search (prefix/substring over the name index), kind (journals/pages) and journal date range (decoded from the filename via `JournalUtils`), and "Select all N matching" over those. Tag/property/backlink filters are NOT offered in pull and are shown disabled with "Not available when copying from a graph that isn't open". Page row subtitles show size and mtime instead of block counts (block count appears in the dry run after parse).
- Failure states are per-source and per-page.
  - *Given* a folder grant that expired or a stale bookmark mid-listing, *Then* `ReadError.NoGrant` returns to the chooser with that source disabled and the "Re-select folder" action, the selection is kept; a missing folder => `FolderMissing`; a file that disappears or fails to parse between listing and read => that page `unreadable` with the reason, the rest continue; no failure yields a queued or partial silent result.
**Files**: `{C}/merge/SourceGraphReader.kt`, `{C}/merge/MarkdownSourceGraphReader.kt`, `{CT}/merge/MarkdownSourceGraphReaderParseTest.kt`, `{BT}/merge/SourceReaderParityTest.kt`

##### Task 4.5.2a: `SourceGraphReader` port, `SourceEntry`, sealed `ReadError` (~4 min)
##### Task 4.5.2b: `MarkdownSourceGraphReader.listEntries` projection, paging cursor, name/kind/date decode (~5 min)
##### Task 4.5.2c: `readPage` with size cap, parse -> `MergePage` -> `StagedPage` (~5 min)
##### Task 4.5.2d: `SourceReaderParityTest` (businessTest): the same fixture files, loaded once through the real `GraphLoader` into an in-memory repo and read once through `MarkdownSourceGraphReader`, give equal `StagedPage`s (uuids, props, nesting, order) (~5 min)
##### Task 4.5.2e: `MarkdownSourceGraphReaderParseTest` + property test (commonTest): CRLF, tabs, fenced code with `- ` lines, `collapsed::`, org-style blocks, explicit and absent `id::`, journal filename decoding, over-cap file, non-UTF8/garbage file => `unreadable` not crash; `Arb`-generated `MergePage`s survive render -> read -> equal (~5 min)

#### Story 4.5.3: Pull-copy flow, picker and entry points (GATED on Stories 4.5.1, 4.5.2, 2.3.2, 2.4.1-2.4.2, 3.1-3.3)
**As an** iOS/Web user, **I want** "Copy pages from..." in the graph I have open, **so that** I can choose pages from another graph, preview the result and apply it with a definite outcome.
**Acceptance Criteria**:
- Same flow, direction variant.
  - *Given* destination D active and a readable source S chosen, *When* the user searches/filters S's name index, selects pages, and taps Review, *Then* `PageMergeService` runs with `source = SourceGraphReader(S)` (instead of `ActiveDbPageSource`) and `target = TargetWriterRouter` which always resolves to `ActiveTargetWriter(D)`; the dry run classifies each selected page against D's DB (`new`/`combined`/`unchanged`/`conflicts`/`unreadable`) without writing; Apply writes through `ActiveTargetWriter` under `GraphWriteLock(D)` exactly as an active-target copy does; progress, result, Retry failed and Undo (ADR-003, `removeBlocks`/`deletePageFile` on D) are the shared implementations.
- Definite results; no queue.
  - *Given* a source page that cannot be read or parsed, a grant lost mid-run, or the user switching away from D mid-run (later pages would no longer have an active target), *Then* the affected pages end `failed`/`unreadable` with a reason and "Retry failed" (re-opening D if it was switched away from), never queued, never deferred; completed pages stay and remain undoable.
- Entry points (UX S1).
  - *Given* the iOS/Web build, *Then* "Copy pages from..." appears (1) in the sidebar where Android/Desktop show "Copy pages to...", (2) in the command palette, and (3) in the graph switcher row overflow as "Copy pages from <graph> to <current graph>" (preselects the source; cuttable, first item in the cut list for this epic). It opens S2 with the source chooser (S3 pull variant) first.
- Preconditions and exits.
  - *Given* fewer than 2 registered graphs, *Then* the existing "You need a second graph to copy pages." state; *given* no readable source, the "no source available" state lists each source with its reason and offers "Re-select folder" (where re-grantable), "Add a graph", and Close.
- Limits that are explicit, not silent.
  - *Given* pull in v1, *Then* linked-asset copying and the linked-page-closure (`Depth1`) toggle are disabled in the pull direction with "Not available when copying from a graph that isn't open", because both need a source-side reference index; selected pages are copied with their `[[links]]`/`((refs))` unchanged text-wise (same as push without closure).
**Files**: `{C}/ui/screens/copy/CopyPagesState.kt` (+ `direction`), `{C}/ui/screens/copy/PullSourceChooser.kt`, `{C}/merge/PullPageSource.kt` (adapts `SourceGraphReader` to the `PageSource` port), `{BT}/merge/PullCopyFlowTest.kt`, `{BT}/ui/PullCopyViewModelTest.kt`

##### Task 4.5.3a: `PullPageSource` adapter implementing the same `PageSource` port as `ActiveDbPageSource`, plus chunked staging driver (<= 50) (~5 min)
##### Task 4.5.3b: `direction` in `CopyPagesState`/`CopyPagesViewModel`; name-index paging, search, kind and date filters over `SourceEntry` (~5 min)
##### Task 4.5.3c: Source chooser (S3 pull variant) with disabled reasons, re-select action, "no source available" state (~5 min)
##### Task 4.5.3d: Parameterize picker/dry-run/result titles and the disabled-filter notes by direction (~4 min)
##### Task 4.5.3e: Entry points: sidebar, command palette, graph-switcher row overflow (cuttable) (~5 min)
##### Task 4.5.3f: `PullCopyFlowTest` and `PullCopyViewModelTest` (businessTest), registered in `AllBusinessTests` (~5 min)
##### Task 4.5.3g (Repair pass 6): pull name-index loading state: after a source is chosen, the picker shows skeleton rows and "Reading <graph>... N files found" (determinate once the listing total is known, indeterminate before), the listing streams in pages of <= 100 and the list is usable (search over what has loaded, with "Still reading..." note) before the index completes; a Stop/Back during listing cancels cleanly; failure -> per-source `ReadError` banner with Retry/"Re-select folder"/Close. Robolectric test with a slow fake reader (~2 h, inside Story 4.5.3's 16 h)

---

## Phase 5: Hardening

### Epic 5.1: Scale, regression, docs

#### Story 5.1.1: Large-graph and resilience tests
**As a** maintainer, **I want** the 8 000-page SLO enforced by tests, **so that** the old OOM pattern can't return.
**Acceptance Criteria**:
- 8 030-page copy.
  - *Given* a synthetic 8 030-page source (existing generator used by `LargeGraphWarmStartCrashTest`), *When* a full copy runs with a recording default uncaught-exception handler, *Then* no uncaught Throwable, every repository read <=100 rows / IN <=500, peak staged-in-memory pages <= 1, and a second run is all `Unchanged`.
- Closed DB.
  - *Given* the source graph switches during picker use, *When* collectors run on a closed driver, *Then* they yield `Left(ReadFailed)` not a crash (extends `UpgradeResilienceTest` pattern).
- Watcher bursts: N off-graph file writes produce at most one reconcile on next open (assert via loader counter).
- Permanent reconcile regression (from Spike 0.1.1): the real-temp-directory test is kept and extended to write through the production `MarkdownTargetWriter` (not a raw file write) into a closed graph, then reopen: 0 `DiskConflict`, one page, no duplicate journal.
- Copy -> edit -> re-copy property test (Task 2.3.2e) is part of the Phase 5 CI gate and stays green on the final branch.
- Guard pass-rate regression: the Spike 0.1.4 fixture set (including the real failing files) is asserted in CI at the recorded guard strictness.
**Files**: `{BT}/merge/LargeGraphMergeTest.kt`, `{BT}/merge/MergeResilienceTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/OffGraphReconcileRegressionTest.kt`, `{BT}/AllBusinessTests.kt`

##### Task 5.1.1a: Large-graph merge test (~5 min)
##### Task 5.1.1b: Resilience tests (closed DB, scope exception handler) (~5 min)
##### Task 5.1.1c: Run `bazel test //kmp:business_tests` and `scripts/jvm-display-check.sh -- bazel test //kmp:jvm_tests ...` per CLAUDE.md; capture output (~5 min)

#### Story 5.1.2: Docs and CLAUDE.md
**As a** maintainer, **I want** the new invariants written down, **so that** future changes keep them.
**Acceptance Criteria**:
- CLAUDE.md gains a short section: "Cross-graph writes: GraphManager stays single-open; use `TargetWriterRouter`/`GraphWriteLock`; never write markdown of the active graph behind `GraphWriter`".
  - *Given* the PR, *When* reviewed, *Then* the section exists and cites `merge/TargetWriterRouter.kt`.
- `kmp/TESTING_README.md` mentions the property tests.
**Files**: `CLAUDE.md`, `kmp/TESTING_README.md`

##### Task 5.1.2a: Add CLAUDE.md section and testing note (~4 min)
##### Task 5.1.2b: Final `make`/CI-equivalent run: `bazel test //... --config=ci` (wrapped in `timeout 30m`); `bazel shutdown` after (~5 min)

#### Story 5.1.3: Bazel registration check and metrics log-line contract (Repair pass 6)
**As a** maintainer, **I want** new sources and tests to be built by Bazel and the metric log lines pinned, **so that** CI builds what exists and the local-only metrics (requirements M1-M5) keep working.
**Acceptance Criteria**:
- Bazel registration (VERIFIED facts: `kmp/src/commonMain/kotlin/BUILD.bazel` `kt_srcs` uses `glob(["**/*.kt"])` with explicit excludes, and `kmp/src/businessTest/kotlin/BUILD.bazel` uses `glob(["**/*.kt"])`; so new `.kt` files under `{C}` and `{BT}` need NO BUILD edit).
  - *Given* the new packages `{C}/merge`, `{C}/capture` additions, `{BT}/merge`, `{BT}/capture`, *When* `bazel build //kmp:desktop_app` and `bazel test //kmp:business_tests` run, *Then* the new sources compile and `AllBusinessTests` runs the new classes (completeness test green).
  - *Given* new files under `{CT}`, `kmp/src/jvmTest`, `kmp/src/androidUnitTest`, `androidApp/src/test`, `kmp/src/wasmJsTest`, *Then* the owning `BUILD.bazel` (`kmp/src/commonTest/kotlin/BUILD.bazel`, `kmp/src/jvmTest/kotlin/BUILD.bazel`, `kmp/src/androidUnitTest/kotlin/BUILD.bazel`, `androidApp/BUILD.bazel`) is checked by actually running the target and confirming the new test appears in the run; any glob gap or `exclude` that hides a new file is fixed in the same PR. New test resources (`kmp/src/commonTest/resources/merge-fixtures/*.md`) are confirmed packaged by the resource target. Wasm/iOS test sources are Gradle-only (compile gate), stated explicitly.
  - Generated SQLDelight sources: the committed `kmp/src/generated/sqldelight/` update (Task 2.4.1d6) is the Bazel input.
- Metrics log contract.
  - *Given* the S15 lines, *When* a copy run, an undo, a share (active, markdown, inbox) complete, *Then* a test with the recording `Logger` asserts the exact key set: `PageMergeService mergeId, source, target, direction, new, combined, unchanged, conflicted, failed, assetsRenamed`; `MergeUndo mergeId, target, removedPages, removedBlocks, leftInPlace`; `JournalAppender target, writer, override, outcome`; and that no page body or shared text appears. A documented one-line grep over `~/.stelekit/logs/stelekit-<date>.log` reproduces M1-M3 (command recorded in the story; no script is shipped).
**Files**: `{BT}/merge/MetricsLogContractTest.kt` (register in `AllBusinessTests`), the `BUILD.bazel` files above only if a gap is found
##### Task 5.1.3a: Run each affected Bazel target, confirm new test classes appear in the output, fix gaps (~2 h)
##### Task 5.1.3b: Log-line contract test and recorded grep command (~3 h)

---

## Repair log

Repair pass on 2026-10-07 against `architecture-review.md` and `adversarial-review.md`. Code facts re-verified in `GraphManager.kt` (line numbers as of this branch): `switchGraph` at 804 is non-suspend; `tearDownActiveGraphResources` (508) nulls `_activeRepositorySet` synchronously; init coroutine sets `_activeRepositorySet` (~904) and completes `_pendingMigration` in `finally`; registry `activeGraphId` is set at 964 AFTER the init coroutine is launched; `awaitPendingMigration()` exists at 974; `RepositorySet` (`repository/RepositoryFactory.kt:47`) has no graph id; no `GraphActivated` symbol exists in `kmp/src/commonMain` (grep); `GraphWriter.getPageFilePath` (~790) has no journal-date logic; `JournalUtils` matches `YYYY[-_]MM[-_]DD`.

| Finding | Change made |
|---|---|
| A1 / arch #1 (lock vs `switchGraph`) | Story 2.1.1 now records verified code facts, takes the lock inside the init coroutine (open + repo set + migrations) and around previous-graph close (Task 2.1.1c). New Story 2.1.2 with Tasks 2.1.2a-c: predicate = `activeRepositorySet` non-null AND belongs to target (adds tiny `readyGraphId` accessor because `RepositorySet` has no id); registry-active-but-unfinished -> `awaitPendingMigration()`; in-flight-switch tests in both directions; lock-wait metric. `forceReinit`/relocation covered. Glossary `GraphWriteLock`/`TargetWriterRouter` updated. |
| arch #2 / B2 (UUID oracle, identity) | Always-remap on every target (no `isTakenInTarget`); SHA-256-128 derivation instead of FNV `generateDeterministic` (Story 1.2.1, Task 1.2.1a); clobber guard vs `INSERT OR REPLACE` (Task 1.2.1c); staging is `StagedPage` JSON (Task 1.1.4b, 2.4.2c); `id::` emission committed (Task 1.1.4a); dangling "Story 1.1.4" created; Stories 1.1.2, 1.1.3, 1.2.1, 2.3.1, 2.3.2, 2.4.2, 4.1.3 marked GATED on it; contract-parity AC + `TargetWriterContractTest` (Task 2.3.2d). Unresolved Questions and dependency graph updated. |
| B1 (re-serialize drops content) | Off-graph write is `MarkdownSplicer` (insert-only), whole-page render only for brand-new pages (Tasks 1.1.3c, 1.1.4c, 2.3.1c); `RoundTripGuard` -> `NotRoundTrippable` -> inbox/failed-page (Task 1.1.4d); real-markdown fixture tests (Story 1.1.4 AC, Story 2.3.1 AC). |
| arch concern: GraphManager debt | `GraphLocator` is a separate class (Task 2.1.1a); drain is `ShareInboxDrain` wired in `StelekitAppDependencies`, `GraphManager.kt` removed from Story 4.4.1 files; `GraphActivated` references removed (Glossary, Story 4.4.1, ADR-001); drain waits on readiness. Tech Debt row rewritten. |
| arch concern: port too narrow for Undo | `TargetWriter` has `deletePageFile`, `fileHash`, `removeBlocks` (Task 2.3.1a); Undo uses them (Task 2.5.1a); shared contract suite covers them. |
| arch concern: two routers | One `TargetWriterRouter`; share adds `InboxFallbackAppender` decorator (Tasks 4.1.3a/b, Story 2.3.2 AC). `OffGraphJournalAppender` removed. |
| arch concern: resolver vs policy | `PageFileResolver` is path-only; `supportsOffGraphWrite` AC removed; new Story 2.3.3 `TargetWriterCapabilities` (Phase 2); Task 4.5.1a reduced to platform mapping. |
| arch concern / adv: picker queries | Explicit Task 2.4.1d: bounded filtered-list + count queries in `SteleDatabase.sq`, repository methods, SQLDelight regeneration + rsync, `QueryPlanAuditTest`; read-only so no `RestrictedDatabaseQueries` stub and no `MigrationRunner` entry; Migration Plan restated as "queries only, no table". |
| arch concern / adv: journal filename | Story 1.2.2 AC + Task 1.2.2d round-trip test through `JournalUtils`/loader; Story 4.1.3 AC no longer hard-codes the filename; Unresolved Question retained with owner. |
| adv: iOS/Web has no copy flow | Apply-on-activate fallback AC in Story 4.5.1, Task 2.4.2g (trigger), Task 4.5.1d (test). |
| adv: teardown not covered by lock | Folded into A1 (Task 2.1.1c, Story 2.1.2 active-to-inactive AC). |
| adv: Android off-graph share payload | Explicit off-graph scope AC in Story 4.1.3 (text only; suggestions disabled; images copied to target `assets/` or inbox). |
| adv: scope vs appetite | Story 4.2.2 (Direct Share) and `merge_force_inbox` marked OPTIONAL/CUTTABLE, off the critical path. |
| Minors | `QrImportService` path corrected (Task 0.1.3c); Story 1.1.4 reference fixed. |

### Repair pass 2 (2026-10-07, against the 'Re-review' sections)

Code facts re-verified by grep/read in `GraphManager.kt`: `_pendingMigration` replaced at 845; previous-scope cancel at 825; `_activeRepositorySet.value = repoSet` at 904; `deferred.complete(Unit)` in `finally` at 958; `awaitPendingMigration()` at 974; `LogseqPageSerializer` is `object` in `db/LogseqPageSerializer.kt:15`; `MarkdownPageParser.generateUuid` uses `properties["id"]` (46-49), else sidecar hash (51-55), else positional seed `"$pagePath:${parentUuid ?: "root"}:$blockIndex"` (59).

| Finding | Change made |
|---|---|
| N1 (BLOCKER, router/init deadlock) | Story 2.1.2 predicate AC and Task 2.1.2b respecified: await readiness OUTSIDE the lock, then `lock(target)` and re-check `readyGraphId == target`, bounded retry (`MAX_ROUTER_ATTEMPTS = 3`), retryable `Left` after that. New AC "router called before init finishes" (+ switch-during-await variant) and Task 2.1.2c extended. Task 2.1.1c now states the normative lock order (no nested graph locks; previous-graph close and incoming open are separate critical sections; never await the deferred under a lock) with a two-switch no-deadlock test and a lock-order assertion. Story 2.3.2 router AC, Task 2.3.2b and ADR-001 rev. 3 aligned. |
| Arch concern: `_pendingMigration` replaced per switch | Covered by the re-check in the N1 algorithm and the switch-during-await test. |
| Arch concern: `readyGraphId` set before migrations | Task 2.1.1c states it is safe only because init holds `lock(B)` through migrations; consumers must take the lock after observing readiness; test with a paused fake migration. |
| Arch concern: scope cancel at 825 precedes any lock | Task 2.1.1c states it; `ActiveTargetWriter` retryable path now also covers actor-scope `CancellationException` (Task 2.3.2a); injected-close test retained. Whether `DatabaseWriteActor` lives in the cancelled scope remains unverified. |
| R1 (active path has no `id::`) | Story 2.3.2 new AC + Task 2.3.2a: `ActiveTargetWriter` sets `properties["id"] = uuid'` on every inserted block; contract-parity AC and Task 2.3.2d re-parse the written file (not DB-only). |
| R2 (active path not byte-preserving) | ADR-001 rev. 3 records it as accepted editor behavior, `RoundTripGuard` not applied; parity AC compares block data, not bytes. |
| R3 (positional uuid shift) | Task 1.1.1c: default append after last sibling, anchor placement only when all later siblings/descendants have explicit `id::`; Story 1.1.1 AC; Story 1.1.4 unlabeled fixtures (flat, nested, mixed) asserting unchanged parsed uuids and same `pagePath` string as the DB side; generator axis (iii) in Task 1.1.2a; contract case in Task 2.3.2d. |
| R4 (edit-after-copy) | ADR-002 rev. 3 rule: conflict sibling uuid = SHA-256 of `merge-conflict:<g>:<S>:<contentHash>`, carries `src-id`; `Unchanged` if any block under the matched parent has equal `src-id` and equal normalized content. Story 1.1.1 AC, Task 1.1.1d, Task 1.2.1a (`conflictUuid`), Task 1.1.2a generators (post-copy edit, source drift) with idempotence/one-conflict-per-edit properties, contract case in Task 2.3.2d. |

### Repair pass 3 (2026-10-07, against 'Re-review 2' in `architecture-review.md`)

| Finding | Change made |
|---|---|
| C-1 (apply() lock vs router lock) | Task 2.4.2d reworded to "per page through `TargetWriterRouter`"; states `GraphWriteLock` is non-reentrant and only the router and the `switchGraph` init coroutine acquire it. |
| C-2 (`readyGraphId` vs `_activeRepositorySet` separate reads; unlocked teardown ~514) | Task 2.1.2a now specifies a single `ReadyGraph(id, repoSet)` pair accessor; router reads it once, uses only the captured set under `lock(target)`, retries on null/mismatch; teardown-race test. |
| C-3 (previous-graph close lock keyed wrongly under rapid switches) | Task 2.1.1c captures `previousId` + `factoryToClose` synchronously in `switchGraph`, keys the close lock by the factory's owner, with a B-then-C test. |

### Repair pass 4 (2026-10-07, against `consistency-review.md`, `pre-mortem.md` and the user's final decisions)

| Finding / decision | Change made |
|---|---|
| User decision 1; Consistency B1 | Copies to targets that cannot be written off-graph are DISABLED WITH A REASON (Story 2.3.3, 3.1.1 AC); copies never queue; results are definite. Story 2.3.1 "Refusals" AC rewritten to `Left(WriteRefused(reason))` with no inbox reference, plus a "grant lost mid-run" AC (page fails definitively with Retry). New Task 2.4.2h maps refusals to failed pages. Story 4.5.1 rewritten with no apply-on-activate; Task 2.4.2g removed; Task 4.5.1d now tests disabled-with-reason; `PendingApplyOnActivate.kt` removed from Files. Glossary adds `WriteRefused`. ADR-001 updated ("inbox applies to SHARES only"). |
| User decision 3; Consistency B2 | `merge_force_inbox` cut: removed from Glossary `TargetWriterCapabilities`, Story 2.3.3 (`ForcedInbox` reason), Risk Control, UX S16, UX surface table, validation rows. No `merge_` share key remains (C3 rename moot). |
| User decision 2; Consistency C1 | Desktop Esc with typed text asks "Discard this note?" (Story 4.3.1 AC + Task 4.3.1e); Android Back auto-saves, failure queues (Story 4.2.1 AC + Task 4.2.1g); picker Esc/Back with selection asks (Story 3.2.1 AC + Task 3.2.1e). UX S2/S10/S11 and Open questions updated. |
| Pre-mortem P1-1 | Spike 0.1.1 rewritten: real temp directory, real `GraphLoader`/watcher/`FileRegistry`, manual Android device pass, kept as permanent regression (Story 5.1.1). Stories 2.3.1 and 4.1.3 gated on it. |
| Pre-mortem P1-2; Consistency C5 | New Spike 0.1.4 (RoundTripGuard pass rate over a real exported graph or the `SyntheticGraphContent.kt` generator; go/no-go ~95%; relax to "structure-stable" BEFORE writers if missed). Gates Stories 2.3.1, off-graph 2.3.2, 4.1.3; Task 1.1.4d no longer decides strictness. Story 4.1.3 AC ties the share "one step" metric to the measured rate. |
| Pre-mortem P1-3 | Task 2.1.1c is its own first PR; `withTimeout` acquisition, release in `finally`, degrade-open on timeout, debug-build lock-order assertion, CI `GraphManagerSwitchLockStressTest` (rapid switches, slow driver, paused migration, throwing migration, held-lock timeout). |
| Pre-mortem P2-4 | New Story 2.3.2 AC + Task 2.3.2e: `CopyEditRecopyPropertyTest` on the real active path, including `pagePath` seed equality against `GraphLoader`; kept in the Story 5.1.1 CI gate. |
| Pre-mortem P2-5 | New "Scope Cut Line and derived scope" section: core slice, ordered cut list (Direct Share 4.2.2 first), mid-point checkpoint, Spike 0.1.2 runs FIRST, and what the share target degrades to if SAF is "no". |
| Consistency C2/C3 | One term set stated at the top of the plan: copy `new`/`combined`/`unchanged` (`conflicts` is a subset of `combined`), share `queued`. Plan Observability, Story 3.3.1 AC, UX S6/S10/S11/S13/S14 and UX-25/UX-29 aligned; "Save for later" retired for "Queue for later". |
| Consistency C4 | Derived/optional table in the Scope Cut Line section. |
| Consistency C6 | ADR-002 decision 3 rewritten to the SHA-256 derivation only; history moved to a Revisions note. |
| Consistency C7 | New Task 4.5.1e: confirm whether iOS/Web has a quick-add entry point; wire it to `CaptureTargetResolver` + `JournalAppender` or drop the AC and UX S12 wording. |
| Consistency N6 | Story 4.2.1 AC now includes "Queue for later", the no-graph placeholder queue and "Already added". N1-N5, N7 left as listed in the review (see below). |
| Consistency N8 | Plan status reworded to match ADR "Proposed" status (gated); Epic 4.5 task order fixed (c before d). |
| validation.md | Rows for apply-on-activate, `ForcedInbox` and `PendingApplyOnActivateTest` removed or replaced; new rows for Spikes 0.1.1 (real FS) and 0.1.4, lock stress test, copy-edit-recopy property test, copy-refusal and grant-lost tests, Esc/Back tests. |

### Not resolved here
- Repair pass 2: the append-by-default placement (R3) means a merged block may land far from its source neighbors; accepted trade-off, not user-tested. Deleting a conflict sibling re-creates it on the next copy (ADR-002 rev. 3). Whether `DatabaseWriteActor` runs in the scope cancelled at `GraphManager.kt:825` is unverified. The stale-source-uuid case where the active writer's DB already holds unlabeled positional blocks that shift after an active-path `savePage` re-render was not analyzed beyond R3's append rule.
- `RoundTripGuard` strictness is a measured unknown (Unresolved Questions); if many real files fail, most off-graph writes go to the inbox.
- Whether `MarkdownPageParser` exposes per-block source line spans (Task 1.1.4c has a fallback scanner); okio on the commonMain classpath for SHA-256 (Story 1.2.1 says verify).
- Exact column names/indexes for the filtered page queries (Task 2.4.1d must read the schema first).
- Not addressed (outside the four blockers/listed concerns; Bazel registration and `androidApp/src/test` existence CLOSED in Repair pass 6, Story 5.1.3 and Story 4.2.1): Story 3.4.1 sidebar line numbers, `CaptureId` value class, Glossary "replaces vs deprecated adapter" wording, stale-ness of the Phase 4.5/5 staged-rollout sentence.

- Repair pass 4: (a) iOS/Web copy limitation: RESOLVED in Repair pass 5 (pull-style copy, Epic 4.5; the earlier "accepted limitation" is retired). (b) UX nits N1-N5 and N7 (pending-shares panel actions, conflict badge and Remove-copy undo, UX-listed gaps, last-used copy destination setting, >200 confirmation location, chip host) are still unplanned tasks; they sit under the Scope Cut Line as derived/optional. (c) The 95% Spike 0.1.4 threshold and 10 s lock timeout are initial values, not measured. (d) Graph-less `ShareInbox` holding slot (Story 4.2.1) is a new small design point not yet in an ADR.

### Repair pass 5 (2026-10-07, user decision: iOS/Web get PULL-STYLE COPY in v1)

| Item | Change |
|---|---|
| Decision 4 | Recorded at the top of the plan: on iOS/Web the user opens the destination graph and pulls pages from a chosen source graph by reading its markdown read-only; results always definite; never queued. Earlier decisions intact (copies never queue; unwritable push targets disabled with reason; `merge_force_inbox` cut). |
| Story 4.5.1 | Rewritten: `CopyDirection` {Push, Pull}, `SourceReadCapabilities` (sealed reasons), disabled-with-reason sources, no-queue AC, `IosWebCopyGatingTest` retargeted. "Accepted limitation" text removed. |
| New Story 4.5.2 | `SourceGraphReader` / `MarkdownSourceGraphReader`: read-only, no second DB open, projection listing (<= 100/page), one-file-at-a-time parse, chunks <= 50 spilled as `StagedPage` JSON, 2 MB per-file cap, filters limited to name/kind/journal date; parity with the push-path `StagedPage`. |
| New Story 4.5.3 | Pull flow reusing picker/dry-run/result/undo, `PullPageSource` into `PageMergeService`, `ActiveTargetWriter` target, entry points (sidebar, command palette, graph-switcher row), failure states and exits, explicit v1 limits (no assets, no `Depth1`, no tag/property filters). |
| New Spike 0.1.5 | Gates 4.5.2/4.5.3 per platform and storage kind; negative result disables that source (or platform) with a reason. Added to Unresolved Questions and run order. |
| Story 3.1.1 AC | iOS/Web push chooser now points to Pull instead of ending in "no destination available". |
| Scope Cut Line | Placement after the core slice, ~16 tasks / ~1 week (~15% of Large appetite), ordered down-scoping (graph-switcher entry and date range, then second platform, then Retry polish); full reversal needs the user's call. |
| Staged rollout, dependency graph, Not resolved here | Updated to match. UX (S1, S3, S4, S6, UX-41, UX-43..47, open questions) and validation (REQ-11 rows, UX rows, known gaps) updated in their own files. |

### Not resolved here (Repair pass 5)
- Spike 0.1.5 is unrun: whether iOS bookmarks and Web handles/OPFS can list and read an inactive registered graph is UNVERIFIED. Safari/Firefox (no `showDirectoryPicker`) are expected to be `PlatformUnsupported` for user-picked folders.
- Source uuids for blocks without `id::`: pull relies on the parser deriving the same uuid `GraphLoader` would; Task 4.5.2d verifies this, and a mismatch would break idempotence between pull and push of the same page.
- Pull omits assets, the linked-page closure and tag/property/backlink filters in v1 (no source-side index); whether users accept that is untested.
- The 2 MB per-file cap, chunk size 50 and page size 100 are initial values, not measured on a device. Pull on Android/Desktop is not exposed (code path shared; follow-up).

### Repair pass 6 (triad)

Source: three fresh triad reviewers (product, UX, engineering), all "needs-work", no hard blockers. Everything below is document-only; no code was touched, no user research or measurement was invented. Items needing an owner decision are marked OWNER.

**Verification of earlier engineering findings (read in this pass)**: C-1 is present in Task 2.4.2d (apply() "per page through `TargetWriterRouter`", states `GraphWriteLock` is a non-reentrant `Mutex` and only the router and the `switchGraph` init coroutine acquire it). C-2 is present in Task 2.1.2a (single `ReadyGraph(id, repoSet)` pair accessor, router reads it once and uses only the captured set under `lock(target)`, retries on null/mismatch). C-3 is present in Task 2.1.1c (`previousId` + `factoryToClose` captured synchronously and keyed by the factory's owner, B-then-C test). The Repair pass 3 patches are in the plan text; the triad engineering reviewer's "re-check" is closed.

| Triad finding | Change made |
|---|---|
| PRODUCT: requirements.md out of sync with plan scope | requirements.md rewritten: undo, share inbox, SAF fallback, iOS/Web pull-copy, Direct Share optional, `merge_force_inbox` cut are now stated; out-of-scope list corrected |
| PRODUCT: "one step" vs "one tap" | Defined in requirements B3: zero graph switches and one tap (Save) after the sheet opens with the default destination; tied to Spike 0.1.4 (>= 95%) and Spike 0.1.2 |
| PRODUCT: no outcome metrics / baseline / source | Metrics M1-M5 with baseline (0 / n/a), source = existing `Logger` summary lines (S15), no network; Story 5.1.3 pins the log-line format; ux.md S15 gained `direction`, `override`, undo fields |
| PRODUCT: demand assumption unnamed | A-DEMAND named with PROPOSED expand/freeze thresholds (OWNER to confirm); limitation stated: only owner installs are observable |
| PRODUCT: persona/frequency evidence | Marked "UNVERIFIED — needs owner input" (OWNER); nothing fabricated |
| PRODUCT: core slice undefined | Gates 0-3 defined in this plan ("Scope Cut Line") and requirements.md; Gate 1 = merge fn + Desktop/Android copy + default capture graph + share-target override (+ Undo and minimal inbox, flagged OWNER because REQ-16/17 need them) |
| UX: loading/skeleton states | Task 3.2.1f (picker 8k pages), Task 3.1.1e (destination probing), Story 3.3.2 Loading AC (conflict list), Story 4.2.1 Resolver-loading AC, Task 4.5.3g (pull name index), Story 3.3.1 Dry-run loading AC |
| UX: S5 "Cancel" | Renamed "Stop" with partial-write wording (Story 3.3.1 AC, Task 3.3.1b; ux.md S5) |
| UX: S8 "Keep both" vs "Remove copy"; re-create surprise | "Mark resolved" (durable) vs "Remove copy" (returns on next copy, said before and after); model kept, justified in ADR-002 rev. 4 (tombstone rejected: needs persistence not justified before demand evidence) |
| UX: a11y S8/S13, large text, RTL, Web keyboard | Story 3.3.2 Accessibility AC, Task 4.4.1d, Task 3.2.1f items 3-5 |
| UX: Back auto-save toast | Story 4.2.1 AC + Task 4.2.1h: names the graph, Undo and Change (cheap: both primitives exist) |
| UX: no-graphs inbox keying | ADR-004 (new): `UNASSIGNED` slot re-keyed once to the first graph; Story 4.2.1 and Task 4.4.1a updated |
| UX: S8 and rescue actions on cut list | Moved to Gate 2 (committed): Story 3.3.2, Task 4.4.1d; removed from cut list and derived table |
| UX: last-used copy destination, iOS/Web quick-add tasks | Task 3.1.1d (Gate 2), Task 4.5.1f (conditional on 4.5.1e) |
| UX: no end-to-end journey table | ux.md "End-to-end journeys and failure branches" |
| ENG: re-size Task 2.4.1d | Split d1-d7 (about 9.5 h) |
| ENG: hour estimates and fit | "Effort estimate" table: 389 h full, 317 h Gate 1, 29 h Phase 0; honest conclusion: does NOT fit 3-6 weeks (OWNER decision: re-baseline or cut deeper) |
| ENG: sequencing vs `fix/graph-switch-notes-path`, lock own PR | "Sequencing against branch" section (VERIFIED the branch edits `GraphManager.kt`, +80/-8): merge it first, then the lock PR; lock PR is its own PR with `withTimeout` + degrade-open |
| ENG: journal filename mapping | Decided in Story 1.2.2 "Journal filename rules"; hard-coded `journals/2026_10_07.md` removed from Story 4.1.3 ACs; Unresolved Question closed |
| ENG: path traversal/symlink tests | Task 1.2.2e, Task 2.3.1e, `PathContainmentSymlinkTest`, containment applied to staging, assets, pull reader, manifest deletes |
| ENG: inbox crash safety | Task 4.4.1a spec: tmp+rename in same dir, version field, SHA-256, quarantine, startup sweep rules, crash-injection tests |
| ENG: Bazel registration, `androidApp/src/test` | Story 5.1.3: `kt_srcs` and business-test targets use `glob(["**/*.kt"])` (VERIFIED) so no BUILD edit for those; other test BUILDs verified by running; `androidApp/src/test` EXISTS (VERIFIED `ls`, holds Capture tests); the old "Not addressed" note is closed |
| ENG: image / link-suggestion behaviour off-graph | Confirmed in Story 4.1.3: text only, link suggestions hidden with a note, images copied to the target's `assets/` (or inbox) |
| ENG: issue #266 overlap | Story 4.3.1 note (VERIFIED `gh issue view 266`): different scope, shared destination resolver, owner to comment on #266 |
| BLOCKER: spikes unrun | Made explicit: Phase 0 (0.1.1-0.1.5) is the only authorised work; "Phase 0 checkpoint" section with pass conditions, re-plan triggers R1 (0.1.1 fails with no working own-write mark) and R2 (0.1.4 structure-stable < 95%), go decision, ADR flip table; ADR-001/002/003 carry their acceptance rules and stay Proposed |

### Not resolved here (Repair pass 6)
- OWNER decisions: appetite re-baseline vs deeper cut; primary persona and frequency; A-DEMAND thresholds; whether Undo and the minimal inbox belong in Gate 1 (this plan says yes, because REQ-16/17 need them).
- All hour figures are author judgement (about +/-40%), unmeasured. The 95% guard threshold, 10 s lock timeout, 3 s probe timeout, 2 MB read cap are still initial values.
- Mental-model wording ("Copy pages to...", "combined", "Mark resolved") remains untested with users; no usability session is planned or scheduled (not invented here).
- ADR-003 has no spike of its own; ADR-004 is untested on a device.
- Pull on iOS/Web remains UNVERIFIED until Spike 0.1.5 runs.

### Repair pass 7 (triad round 2)

Source: three fresh triad reviewers (product, UX, engineering), round 2, all needs-work with no blockers. Document-only; no code, no invented measurements. Items needing the owner are marked OWNER INPUT NEEDED.

| Triad finding | Change made |
|---|---|
| STALE: 3-6 weeks in requirements.md Appetite and plan "Scope Cut Line" | Both reworded as superseded history; current text is the 2026-10-08 re-baseline |
| STALE: Story 2.4.1 AC said no .sq change | AC rewritten: the story adds filtered/count queries (Tasks 2.4.1d1-d7), regenerates sources, no new table, read-only, QueryPlanAuditTest |
| Phase 0 timeboxes vs hour table | Phase 0 header and spike titles now match the table (0.1.1 8 h, 0.1.2 3 h, 0.1.3 2 h, 0.1.4 8 h, 0.1.5 8 h) |
| UX-30 / gate boundaries | Story 3.3.2 (S8) and Task 4.4.1d (S13 rescue actions) moved INTO Gate 1; Gate 2 now = linked pages/assets, last-used destination, UNASSIGNED slot |
| Effort re-total | Gate 1 317 -> 333 h (11.1 / 13.3 / 16.7 weeks at 6 / 5 / 4 h/day); band 200-466 h (23.3 weeks at +40% and 4 h/day); Gate 2 31 -> 15 h; full scope unchanged at 389 h (check line in the table) |
| Gate 1 variant of S2 | Defined in "Scope Cut Line": linked-pages/assets controls hidden and last-used destination absent until Gate 2 |
| UX: Android queued indicator too hidden | Task 4.4.1d: sidebar/drawer badge plus Settings > Capture plus overlay row plus app-start notice |
| UX: Back toast best-effort | Story 4.2.1: stated best-effort; fallback = recent-captures notice at next app start plus queued badge |
| UX: vocabulary | "identical" -> "unchanged" in dry-run wording (plan, ux.md S4, UX-06); "Remove copy" -> "Remove this block" (plan, ux.md S8, UX-19, ADR-002 if referenced) |
| UX: mid-run graph switch silent | Story 3.3.1 AC: push copy continues with a "continues in the background" notice; pull copy asks "Stop it and switch?" |
| UX: wording untested | Entry-subtitle mitigation ADOPTED; wording-validation step with owner/trigger added to validation.md; M4 undo-rate wired as a UX-review trigger (ux.md, validation.md) |
| PRODUCT: no pre-build demand test, no falsifiable claim, no opportunity cost | Phase 0 checkpoint: 2-week owner self-log probe, one-line claim (also in requirements.md), opportunity-cost line (OWNER INPUT NEEDED) |
| ENG: no numeric overrun threshold | End-of-Phase-2 checkpoint: more than 25% over the table hours => stop and re-plan |
| ENG: single-implementer assumption | Stated in "Effort estimate" with what parallel agent workers change |
| ENG: Lock PR external dependency | "Sequencing" section: fix/graph-switch-notes-path must merge to main first; owner = user, no date (OWNER INPUT NEEDED) |
| ENG: Android long-run host | Moved to the Phase 0 checkpoint as a Phase 1 decision with a default (application scope plus interrupted marker) |
| ENG: R1/R3 not re-reviewed | Fresh-reviewer re-check note at the start of Phase 2 |

OWNER INPUT NEEDED after this pass: persona/frequency and A-DEMAND thresholds; opportunity-cost line; merge date for fix/graph-switch-notes-path; run the 2-week demand probe; confirm Gate 1 at 333 h now includes conflict review and rescue actions.
