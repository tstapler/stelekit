# Requirements: cross-graph-merge-share-target

**Date**: 2026-10-07 (synced with plan.md at Repair pass 6)
**Type**: feature addition
**Complexity**: 3 — system design

## Problem Statement
Copying pages between graphs is all-or-nothing and lossy. `GraphMergeService` snapshots every page of the source graph and, on merge, skips any page whose name already exists in the target, so content that differs between two same-named pages is dropped rather than combined. Separately, content shared into the app (Android `CaptureActivity` SEND filters, desktop `CaptureController`) always lands in the single active graph, with no way to choose a destination.

Evidence status: the merge half is verifiable in code (`GraphMergeService` skips by name). The share half is a convenience gap, not a loss. **Neither half has frequency or pain evidence** (no user quotes, no counts of how often anyone holds multiple graphs or hits this). Both are developer-observed gaps. See "Demand assumption" below.

## Baseline
- Copy: user taps "export pages for merge" (captures the whole active graph in memory), switches graphs, taps "Merge captured pages". Existing same-named pages are untouched; there is no selection, preview, or content merge.
- Share: text/HTML/image shared to SteleKit is written to today's journal in whichever graph is active; the user must switch graphs first (exact tap count of that detour: UNMEASURED).
- Adoption baseline for every metric below is 0: the feature does not exist yet.

## Users / Consumers
SteleKit users with multiple graphs (e.g. work/personal, or recovering from a split graph) on Android, Desktop, Web and iOS.

**Persona and frequency: UNVERIFIED — needs owner input.** No primary persona, no ranking between the "merge curator" (copies pages between graphs) and the "quick-capture sharer" (shares text to a chosen graph), and no population estimate exist. Candidate primary persona to confirm or replace: the maintainer's own work/personal two-graph setup. The owner must state (a) which persona is primary, (b) roughly how often they copy between graphs or share to a non-active graph today. Until then the plan treats demand as an assumption (next section), not a fact.

## Demand assumption (named, with a kill/expand criterion)
**A-DEMAND (UNVERIFIED)**: enough users hold 2+ graphs AND copy pages between them or share into a non-active graph often enough to justify a Large (multi-million-token) build touching the hot `GraphManager` class.

Falsification is local-only (no network), so only installs the owner controls are observable; external-user adoption is unobservable except via GitHub issues. Criterion (thresholds are PROPOSED placeholders for the owner to confirm or change before the core slice ships, not measurements):
- **Expand** (build the Gate 2 and Gate 3 items below): within 4 weeks of Gate 1 shipping, the owner's own installs log at least 4 completed copy runs OR at least 10 shares routed to a non-active graph (metrics M1/M2 below).
- **Freeze** (keep Gate 1, build nothing further, do not delete): 0 copy runs and 0 non-active-graph shares in those 4 weeks.
- Between the two: owner judgement at the 4-week review.

**Falsifiable user-value claim**: today a share to a non-active graph takes N graph switches plus the share taps (N and the tap count are UNMEASURED; the owner records them in the probe below); success = 0 graph switches and 1 tap (Save) with the default destination (B3). Copying pages between graphs today takes a whole-graph export plus a switch plus a merge that skips same-named pages; success = a chosen subset, a dry-run count and a block-level union.

**Pre-build demand probe (Phase 0 checkpoint input, OWNER task)**: before the Go decision, the owner self-logs for 2 weeks (or does a one-off count of recent history) every cross-graph page copy and every share made while a non-target graph was active, noting the switches and taps each took. This is a cheap, existing-data test of A-DEMAND that can run in parallel with the spikes; it can lower or raise the Go confidence but its thresholds are the owner's to set (no number is invented here).

## Success Metrics
Behavioral outcomes (testable now; each maps to validation.md):
- B1. Merging a page that exists in both graphs yields a page containing the union of blocks (no block from either side lost); repeating the same merge adds zero duplicate blocks (idempotent). (REQ-1/2)
- B2. User can copy a chosen subset (not the whole graph) and sees a dry-run count of new / combined / unchanged pages before commit. (REQ-3/6)
- B3. **"One step" is defined as: zero graph switches and, for a share with the default destination, one tap (Save) after the share sheet opens; changing graph adds two taps.** This is the wording used by validation (UX-03) and replaces the earlier ambiguous "one step". It is CONDITIONAL on Spike 0.1.4: the claim "a share lands in the chosen graph in one tap" is made only for target files that pass `RoundTripGuard`; it is declared met only if the measured pass rate on a real graph is at least 95% (or the relaxed "structure-stable" form reaches it). The fraction `1 - P` queues instead and is stated in the PR. On Android it also needs Spike 0.1.2 (SAF atomic replace); if "no", inactive SAF graphs are explicitly outside B3.

Outcome metrics (local-only; source = existing `Logger` summary lines, see Observability):

| ID | Metric | Baseline | Measurement source | Target |
|----|--------|----------|--------------------|--------|
| M1 | Completed copy runs per 4 weeks on owner installs | 0 (feature absent) | `PageMergeService` summary log line (S15) | Owner-set; expand/freeze thresholds above |
| M2 | Shares routed to a non-active graph per 4 weeks | 0 (impossible today) | `JournalAppender` log line, `target` != active, `override=true` or `writer=markdown` | Owner-set; same thresholds |
| M3 | Share success mix: `Appended` vs `Queued` vs `Failed` | n/a | `JournalAppender` log line `outcome=` | `Queued` share of non-active shares below 5%, which is the 95% guard threshold restated |
| M4 | Undo rate: undone copies / completed copies | n/a | `MergeUndo` log line vs summary line | No target; a rate above 25% is a signal the dry run is not predicting the result (PROPOSED trigger for a UX review, not a pass/fail) |
| M5 | Conflict-sibling rate: `conflicted` / `combined` | n/a | summary line | No target; informational, informs whether ADR-002's flagged-sibling model is too noisy |

These are aggregated by grepping the existing log files (Desktop writes `~/.stelekit/logs/stelekit-<date>.log`, VERIFIED present; Android via `adb logcat`/log export, UNVERIFIED path). No new telemetry pipeline, no network (REQ-18). Plan Story 5.1.3 pins the log-line format with a test because the logs ARE the measurement source.

## Appetite
Effort is expressed in tokens (Large band: 3-15M tokens across all agents, including review and verify), not in human time. The original 3-6 week appetite and the 2026-10-08 hour-based re-baseline are retired history: the owner's 2026-10-08 position is that human-time estimates are wrong for LLM-executed work.

**Proposed budget (derived from plan.md "Effort estimate", bands INFERRED, recalibrate after the first implementer run)**:

| Scope | low (x1.5) | likely (x1.75) | high (x2) |
|---|---|---|---|
| Gate 1 core slice incl. Phase 0 (Undo, conflict review, minimal inbox with rescue actions) | 7.5M | 8.8M | 10.1M |
| Full scope, Gates 1-3 | 8.6M | 10.0M | 11.4M |

Proposed: **10M tokens for Gate 1** and **12M for Gates 1-3**; Gates 2 and 3 are follow-on spends gated by the A-DEMAND criterion. Planning already cost about 3.5M subagent tokens (VERIFIED from task notifications) and is outside this budget. **OWNER CONFIRMATION NEEDED: budget in tokens.** The owner's 2026-10-08 "re-baseline to 11-16 weeks" answer was given against human-time numbers that are now retired, so it does not carry over. The owner confirmed the Gate 1 scope (Undo, minimal inbox, ConflictReview, rescue actions stay in Gate 1). Phase 0 spikes (about 0.5M raw, 0.8M-1.1M verified) still run first and end in a go/no-go checkpoint. Overrun rule: at the end of Phase 2, stop and re-plan if tokens spent exceed the table value for the stories done by more than 25%.

**Opportunity cost (OWNER INPUT NEEDED, not invented here)**: Gate 1 consumes roughly 8.8M tokens of agent spend (likely case) plus the owner's review attention. Which SteleKit work that displaces (open issues, roadmap items, the release-please backlog) and why this ranks above it is not recorded anywhere; the owner must write that one-line comparison before the Phase 0 go decision.

## Release gates and cut line (committed here, mirrors plan.md "Scope Cut Line")
- **Phase 0 (authorised first and alone)**: spikes 0.1.1–0.1.5. Nothing else is started before the go/no-go checkpoint.
- **Gate 1 — core slice, first release**: block-level merge function (pure, property-tested); Desktop + Android copy (picker, dry-run, progress, result) writing to a non-active or active graph; Settings default capture graph; share-target graph override (Android overlay destination row, Desktop quick-capture chooser). Included because stated requirements and UX-30 (no dead ends) need them: Undo (REQ-16); a minimal share inbox that queues a failed share visibly (REQ-17: never silent loss) WITH its rescue actions "Copy text" and "Discard" (moved in from Gate 2 on 2026-10-08: a queued share for a deleted graph must not be a dead end); and the conflict review screen (moved in from Gate 2 on 2026-10-08: Gate 1 flags conflicts, so it must also let the user resolve them). Gate 1 uses a reduced picker (S2): the "Include linked pages" control and the last-used destination are hidden/absent until Gate 2.
- **Gate 2 — requirement-complete (committed, ships after Gate 1, NOT on the cut list)**: linked pages and assets (REQ-5; the "Include linked pages" and assets controls), last-used copy destination, and the `UNASSIGNED` inbox slot (no-graphs-configured shares). (The Android Back confirmation toast ships in Gate 1 with the Back auto-save; the conflict review screen and queued-share rescue actions are Gate 1.)
- **Gate 3 — platform expansion (user decision 4: v1, but after Gates 1 and 2 and conditional on the demand criterion)**: iOS/Web pull-style copy ("Copy pages from..."), gated on Spike 0.1.5 per platform.
- **Optional / cut list (cut in this order, nothing depends on them)**: Android Direct Share shortcuts; the graph-switcher-row entry point and journal date-range filter in pull; the second of iOS/Web; Retry polish in pull. Cutting the linked-page closure UI would change REQ-5 and needs an explicit amendment to this file, so it is NOT a silent cut.
- Reverting iOS/Web pull-copy entirely contradicts the v1 decision and needs the owner's explicit call.

## Constraints
- `GraphManager` keeps only one graph's `RepositorySet` open at a time (see `GraphMergeService` KDoc), so source read and target write cannot share a call today. Resolved by ADR-001 (keep single-open; write non-active targets as markdown through `TargetWriterRouter`).
- Repository rules in CLAUDE.md apply: Arrow `Either`, writes via `DatabaseWriteActor`, `@DirectSqlWrite` gating, bounded graph-scale reads, `MigrationRunner` entry for any new table (none planned), regenerated SQLDelight sources (read-only queries planned).
- Shared logic in `commonMain`/`commonTest` (property-based tests for the merge function).
- Sequencing: the `GraphManager` lock edit lands as its own PR after branch `fix/graph-switch-notes-path` (which also edits `GraphManager.kt`) is merged to `main` (plan.md Epic 2.1). Owner of that merge: the user; no date set (OWNER INPUT NEEDED).
- Execution assumption: parallel worker agents in waves with a verification multiplier; the critical path is the wave chain plus wall-clock blockers (plan.md "Effort estimate").

## Non-functional Requirements
- **Performance SLO**: merge of an 8 000-page graph must not load the full graph in memory at once or cause GC thrash/OOM on Android (chunked, bounded reads).
- **Scalability**: graphs up to ~8 000 pages.
- **Security classification**: internal (personal notes); no network.
- **Data residency**: no special requirements (local only).
- **Path safety**: any path resolved for an off-graph write must stay inside the target graph root (no `..`, namespace separators or symlinks escaping it); enforced and tested (plan Task 1.2.2e).
- **Crash safety**: durable local state (share inbox, merge manifest, staging) survives process death without corruption (atomic write plus version and checksum; plan Task 4.4.1a).

## Scope
### In Scope
- Block-level merge: blocks match by UUID, then by identical content; properties union; true conflicts flagged; nothing removed from the target.
- Selection UI: page picker with search; filters (journals vs. pages, date range, namespace, tag); optional inclusion of linked pages and assets; dry-run summary (new / combined / unchanged); loading, empty and error states for each.
- Undo of the last copy run (7 days, manifest based, ADR-003).
- Share target: default capture graph in Settings plus per-share override in the Android `CaptureActivity` overlay (remembers last used); equivalent graph choice for desktop quick capture. A failed share is queued in an app-private share inbox and shown as "queued" (never silent loss); the inbox also holds shares made when no graph is configured (ADR-004).
- Android SAF-backed graphs: written off-graph only if Spike 0.1.2 shows atomic replace works; otherwise inactive SAF graphs are copy-disabled with a reason and shares to them are queued.
- Platforms: Android, Desktop (JVM): push copy ("Copy pages to..."). Web (WASM) and iOS: PULL-style copy ("Copy pages from...") because they cannot write to a closed graph; Gate 3. Share-target graph choice where a share/capture entry point exists (Android, Desktop; iOS/Web quick-add only if Task 4.5.1e finds an entry point).
- Writing to a non-active graph without forcing the user to switch (ADR-001).

### Out of Scope
- Multi-device/network sync between graphs; git-level merge (`JournalMergeReview` is separate).
- Deleting or moving pages out of the source graph.
- A new iOS share extension and a Web PWA `share_target` (deferred; the share inbox is built so they can be added later).
- OS-level Desktop quick capture beyond the existing `CaptureController` popup (open issue #266 overlaps and is a separate effort; see plan Story 4.3.1 note).
- The `merge_force_inbox` developer setting (cut from v1, user decision 3).
- Copies never queue: an unwritable copy destination is disabled with a reason, not deferred.

## Rabbit Holes
- Writing into a graph whose DB is not open (single-open graph constraint).
- Block identity across graphs: UUIDs may differ for the same content; fuzzy matching can wrongly merge or duplicate.
- Reordering/parent-child placement of merged blocks in an outline tree.
- Linked-page/asset closure explosion (transitive links pull in the whole graph).
- Asset file copy and filename collisions across graphs.
- File-on-disk vs. DB consistency (GraphWriter, file watcher reacting to merge writes).

## Alternatives Considered
- Append source blocks under a divider (rejected: duplicates on repeat merges).
- Ask per page for merge/keep/replace (rejected as the default; may return as conflict resolution for flagged conflicts).
- Default-graph-only or overlay-only share target (rejected: user chose both).
- Multi-open `GraphManager`; queue-only inbox as the primary path (rejected, ADR-001).

## Feasibility Risks
- Cross-graph markdown write may produce a spurious `DiskConflict` on next open (Spike 0.1.1), may fail `RoundTripGuard` on many real files (Spike 0.1.4), and SAF may lack atomic replace (Spike 0.1.2). All unrun; ADRs stay Proposed until they pass.
- iOS/Web cannot write a closed graph; pull-copy depends on reading an inactive graph read-only (Spike 0.1.5, unrun).
- Appetite risk: see Appetite.

## Observability Requirements
Log merge and share-target outcomes via `Logger` (counts of imported/merged/skipped/conflicted/failed pages, destination graph id, `override`, `direction`, undo events); surface failures as snackbar/dialog and, for share target, a visible failure state rather than silent loss. The log lines are also the local-only source for metrics M1–M5 (no network, no new pipeline).

## Risk Control
Merge is additive-only (never deletes target content) and gated by the dry-run confirmation. Manifest-based undo for the last run (ADR-003; no full pre-merge snapshot). No feature flag beyond the new UI entry points. The lock edit in `GraphManager` ships as its own PR with bounded acquisition and degrade-open behavior.

## Open Questions
- Owner input needed: primary persona, today's frequency of cross-graph copy/share (the 2-week demand probe above produces this), the expand/freeze thresholds (A-DEMAND), and the opportunity-cost line (Appetite).
- Token budget: OWNER CONFIRMATION NEEDED (see Appetite); the opportunity-cost comparison is still OWNER INPUT NEEDED.
- Resolved in planning (see ADRs): how to write to a non-active graph (ADR-001); what counts as the same block and how conflicts are kept (ADR-002); staging directory for large selections (plan Epic 2.2); inbox keying when no graph exists (ADR-004).
- Still open: cheap paths to a share target on iOS and Web (deferred, Out of Scope); whether iOS/Web has a quick-add entry point (Task 4.5.1e).
