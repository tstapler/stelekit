# Adversarial Review: app-owned-storage-clone

**Date**: 2026-09-12
**Verdict**: CONCERNS

## Iteration 5 disposition of the iteration-4 Blocker

**The iteration-4 Blocker (`switchGraph()` fire-and-forget racing `onGraphLocationDetermined`/the
"graph remains editable" claim) is genuinely fixed, verified against the real `GraphManager.kt`
contract, not just the plan's prose.**

- **`awaitPendingMigration()`'s real contract, re-verified**: `GraphManager.kt:816-819` —
  `suspend fun awaitPendingMigration(): RepositorySet? { _pendingMigration.await(); return
  _activeRepositorySet.value }`. `_pendingMigration` (`GraphManager.kt:115`, a plain `var`, not a
  map) is reassigned **synchronously**, before `switchGraph()` returns, at `GraphManager.kt:705` —
  so a caller that calls `switchGraph(id, forceReinit = true)` immediately followed by
  `awaitPendingMigration()` is guaranteed to await the deferred that specific `switchGraph()` call
  created, not a stale one. On the failure path, `tearDownActiveGraphResources()` nulls
  `_activeRepositorySet` before the async block runs (`GraphManager.kt:427`), and the async
  block's `catch (e: Exception)` (`GraphManager.kt:798-799`) swallows the error without reaching
  line 764's `_activeRepositorySet.value = repoSet` assignment — so `awaitPendingMigration()`
  returns `null` on a genuine reopen failure, exactly as the plan and repair claim. This is a
  correct, non-fabricated characterization of a real API — not a case of "confidently cites a
  nonexistent or misdescribed API."
- **The `ReopenFailed` split is complete, with a single call site and no stragglers.** Grepped
  every `forceReinit` and `switchGraph`/`awaitPendingMigration` occurrence in plan.md: the *only*
  place `switchGraph(graphId, forceReinit = true)` is ever called is Task 3.1.5e's shared
  `finally` block in `GraphRelocationCoordinator`, and every prose reference to it (plan.md:964,
  1006, 1015, 1053, 1077, and the Trade-off Table at plan.md:101) pairs it with an immediate
  `awaitPendingMigration()` suspend, consistently describing the two as "one inseparable step."
  `DomainError.StorageError.ReopenFailed(graphId: String)` is a genuine new sibling leaf (Story
  1.1.2, plan.md:269), `StorageMoveUiState.ReopenFailed` is defined as a structural sibling of
  `Failed`, not a case of it (Task 3.1.5a, plan.md:1094-1100), the coordinator branches on
  `awaitPendingMigration()`'s nullability before running `onGraphLocationDetermined` or emitting a
  terminal state (Task 3.1.5e, plan.md:1143-1146), a dedicated `businessTest` (Task 3.1.5m,
  plan.md:1178-1190) drives a reopen that fails *after* an otherwise-successful copy+verify and
  asserts `onGraphLocationDetermined` is never called, and the UI layer gets a dedicated,
  non-`Failed` rendering (Task 3.4.3c, plan.md:1385-1390) with no Retry and no "untouched"
  reassurance (plan.md:1366-1376). Tasks 3.1.5h/j/l's "delayed-dispatcher fake" requirement
  (plan.md:1158-1162) is a real strengthening — it correctly targets the exact race class this
  iteration fixes (asserting "editable" because `awaitPendingMigration()` *returned*, not because
  `switchGraph` was merely *called*).
- Two lower-level Stories (3.2.1 "job... re-scheduled after `switchGraph()` completes,"
  plan.md:1206-1207; 3.3.2 "poll loop is suspended until `switchGraph()` completes,"
  plan.md:1278-1279) gloss the reopen-and-confirm pair as just "`switchGraph()` completes." This is
  imprecise wording, not a functional gap: both call sites are `quiesceStrategy.release()`, which
  the coordinator only invokes at step 8, itself sequenced after step 6's
  `switchGraph()`+`awaitPendingMigration()` pair (Task 3.1.5f). Listed as a Minor below, not a
  blocker.

No blocker remains from this specific defect. Given this is **iteration 5 of 5** (max repair
rounds for this loop), this closes the loop's Blocker-driven repair cycle — there is no iteration
6. The item below is a newly surfaced Concern, not a Blocker, so it does not require another
repair round; it is recorded for whoever picks up implementation.

## Blockers

_(none)_

## Concerns

- [ ] **NEW — the coordinator's reopen call runs off the UI dispatcher, calling the same
  unsynchronized `GraphManager` mutable state a same-graph UI action could touch concurrently.**
  Task 3.1.5c states the coordinator "already runs off the UI dispatcher" (plan.md:1121) when it
  calls `tearDownActiveGraphResources()`/`switchGraph(forceReinit = true)`. Read directly from
  `GraphManager.kt`: `activeGraphJobs` (`GraphManager.kt:118`) is a plain `mutableMapOf`, not a
  concurrent map; `_pendingMigration` (`GraphManager.kt:115`) is a plain `var`; `currentFactory` is
  a plain field — none are guarded by a mutex, and `switchGraph()` itself is a non-`suspend`
  function with no locking around any of these mutations. The pre-existing code comment at
  `GraphManager.kt:691-693` documents an implicit assumption that `switchGraph()` is "called
  synchronously from the Compose UI dispatcher" — i.e., serialized by virtue of always running on
  the (single-threaded) UI thread. The coordinator, calling the same function from a background
  coroutine while a relocate is in flight, breaks that assumption: if the UI independently calls
  `switchGraph()` for any graph (e.g., a normal graph-switch action, or a startup `LaunchedEffect`
  refiring) at the same moment the coordinator's `finally` block calls
  `switchGraph(graphId, forceReinit = true)`, the two calls race on `activeGraphJobs`,
  `_pendingMigration`, and `currentFactory` with no synchronization — able to overwrite
  `_pendingMigration` with the wrong deferred (so `awaitPendingMigration()` returns for the wrong
  reason), silently clobber `_graphRegistry.activeGraphId` back to the relocated graph
  (`GraphManager.kt:807`) even if the user had already switched away, or lose track of a
  `CoroutineScope` in `activeGraphJobs`. This wasn't flagged in iterations 1-4 because the
  fire-and-forget defect masked it — fixing the await race surfaces a second-order hazard on the
  same shared, unsynchronized state. Likely mitigated in practice if the relocate progress dialog
  (Story 3.4.3) is a genuinely blocking modal that prevents sidebar/graph-switch interaction during
  `Quiescing`/`Copying`/`Verifying`, but plan.md never states this explicitly, and `MoveInProgressFlag`
  (Story 1.3.2) is checked only by `GraphFileWatcher`'s poll loop (Task 1.3.2b) — no task gates the
  UI's own graph-switching actions on it. — **Recommendation**: either (a) state and enforce that
  the relocate progress surface blocks all other graph-switching UI for its duration (and add a
  test), or (b) have the coordinator's reopen call go through a dispatcher-confined queue/mutex
  shared with ordinary `switchGraph()` callers rather than calling the plain function directly from
  a background coroutine.
- [x] **RESOLVED** — Story 3.1.5 previously never specified what API closes the driver at step 3.
  Task 3.1.5c now widens the real, verified `GraphManager.tearDownActiveGraphResources()`
  (`GraphManager.kt:421-431`, confirmed `private fun` prior to this change) to `internal`, and the
  coordinator awaits `factoryToClose?.close()` itself before copying (plan.md:1055-1060) — a
  concrete, awaitable seam as the prior review recommended. Note: `tearDownActiveGraphResources()`
  does more than close the driver — it also calls `_activeGitSyncService.value?.shutdown()`, nulls
  `_activeVaultCredentialStore`, and nulls the UI-facing `_activeRepositorySet` StateFlow
  (`GraphManager.kt:421-430`) — see next Concern on the UI/git-sync side effects of reusing it
  mid-relocate. This does not reopen the original Concern (the close-side ambiguity itself is gone);
  it's a distinct, narrower follow-on.
- [ ] **`tearDownActiveGraphResources()` does more than "close the driver," and its other effects
  during a multi-file copy are unaddressed.** Read directly: the function also calls
  `_activeGitSyncService.value?.shutdown()` and nulls `_activeRepositorySet`, a `StateFlow` whose own
  comment says it exists "so Compose flow collectors see null and stop querying" — i.e., every UI
  surface reading `activeRepositorySet` (not just a hypothetical progress dialog) will observe "no
  active graph" for the *entire* duration of `BulkCopyVerifier.copyAndVerify` + the final rename, not
  briefly. For an 8,000+ file graph this could be a real span of time. The plan doesn't state whether
  `StorageMoveUiState`'s `Copying`/`Verifying` states are meant to gate/mask every such UI surface, or
  only present a modal on top of an app that has gone temporarily blank underneath. Separately,
  `_activeGitSyncService.shutdown()` runs while the `GraphMoveQuiesceStrategy`'s Android
  implementation may still be holding `GitWorktreeLocks.lockFor(shadowKey)` (released only at step 8,
  after reopen) — the plan doesn't confirm `GitSyncService.shutdown()` is safe to call while that lock
  is held by a different party than the sync service's own internal state. Unaddressed by this repair
  pass (Story 3.1.5's repair this iteration was scoped to the reopen-await gap, not this).
  — **Recommendation**: state explicitly (and test) what `activeRepositorySet` observers see/do during
  the closed-driver region, and confirm (or add a note ruling out) any interaction between
  `GitSyncService.shutdown()` and the still-held quiesce lock.
- [x] **RESOLVED** — Cancellation was previously unimplemented/untested. Task 3.1.5l
  (plan.md:1096-1099 / 1173-1177) covers both cancellation during the closed-driver region (reopen
  still runs via `NonCancellable`) and cancellation during quiesce (flag-clear only, no reopen),
  matching the acceptance criteria's *Given*/*When*/*Then* blocks at plan.md:1063-1085.
- [ ] **`StorageLocationResolver`'s Web derivation criterion is ambiguous between a live in-session
  connection and the durable persisted link record, risking a wrong value being permanently locked
  in.** Unaddressed by this repair pass (Story 3.1.5 doesn't touch `StorageLocationResolver`).
  Carried forward verbatim from iteration 2/3 — see Task 1.1.4c, plan.md:391-392, and
  `HostDirectorySync.kt:192`/`:808-825`, `GraphInfo.kt:26-29`.
- [ ] **No cancellation affordance for an in-flight move — still open.** Story 3.4.3
  (plan.md:1147-1161 / 1352-1391) still offers "Retry"/"Cancel" only in the terminal `Failed` state
  (and no button at all in `ReopenFailed`); no story lets a user abort a running
  `Quiescing`/`Copying`/`Verifying` operation. Unaddressed by this repair pass (distinct from the
  coroutine-cancellation Concern, which is about internal flag-clearing/reopen, not a user-facing
  abort control).
- [ ] **No guard against double-invocation of relocate for the same graph — still open.**
  `DomainError.StorageError.SourceInFlight` (plan.md:60/266) still exists only as an unused leaf;
  Story 3.1.5's step 1 ("set `MoveInProgressFlag`", plan.md:951) is still an unconditional set, not a
  check-and-set, and neither Story 3.2.2 nor 3.3.3 disables the trigger button. Unaddressed.
- [ ] **No validation for a no-op move (source == destination) — still open.** No task in Phase 3
  adds a source==destination short-circuit; `ValidateMoveIsPossible` still appears only in prior
  research, not as a plan.md task. Unaddressed.
- [ ] **`navigator.storage.persist()` mitigation from research is still dropped.** Task 3.1.1d
  still only adds `navigator.storage.estimate()` for the space pre-flight; `persist()` appears
  nowhere in plan.md. Unaddressed.
- [ ] **Inconsistent "location of record" semantics for Link across platforms — still open, and now
  more concretely contradictory.** Android's Story 4.2.1 is explicit: choosing Link leaves
  `storage_locations` unchanged, "Link never repoints the location of record — only Relocate does"
  (plan.md:1260 region). Web's Story 4.1.2 (`unlinkHostDirectory`, plan.md:1249-1275 region) states
  that unlinking "updates" the row to `kind = AppOwned`, which only makes sense if establishing the
  Link (Story 4.1.1, plan.md:1229-1246 region) had already repointed `storage_locations` away from
  `AppOwned` in the first place — the opposite of Android's stated rule. Unaddressed.

## Minors

- Stories 3.2.1 and 3.3.2 gloss the coordinator's reopen-and-confirm pair as "after `switchGraph()`
  completes" (plan.md:1206-1207, 1278-1279) rather than "after `switchGraph()` +
  `awaitPendingMigration()`." Functionally correct (both call sites are `quiesceStrategy.release()`,
  which the coordinator only invokes after the real reopen-and-confirm at step 8), but the prose
  invites a future editor to reintroduce the exact race this iteration fixed if these two Stories are
  ever revised in isolation from Story 3.1.5.
- Story 2.2.1e's Android-only zip-export remains an unconfirmed scope addition relative to
  requirements.md's original "In Scope" section (unaddressed by this repair pass, carried forward
  from iteration 1).
- Task 3.1.1d's space pre-flight check still doesn't state whether it accounts for the transient
  doubling of on-disk usage — sharper given Story 3.1.2's staging directory (copy goes source →
  staging → final, so at peak, source + staging + not-yet-deleted old copy can coexist) — still
  likely negligible for markdown-sized graphs, but unstated.
- No task addresses two independent relocations running concurrently for two *different* graphs
  (low-likelihood for a single-user app, not worth blocking on) — carried forward from iteration 1.
