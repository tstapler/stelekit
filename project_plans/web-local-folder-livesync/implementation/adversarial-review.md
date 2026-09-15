# Adversarial Review: web-local-folder-livesync

**Date**: 2026-07-17 (re-review of the 2026-07-17 fix pass against the original review; second pass
re-reviewing the resolution of the sole remaining blocker)
**Verdict**: CONCERNS

**Scope of this pass**: re-reviewed only the 6 previously-BLOCKED items and the 2 Concerns the fix
pass claims to have addressed (`GitWriteLock.kt` modification, permission-revocation silent
degradation). All other Concerns/Minors below are carried over unchanged from the original review
and were **not** re-examined in this pass.

**Scope of the follow-up pass** (this update): re-reviewed only the sole remaining blocker
(`hostWritePending`/OPFS-write-durability) against the fix pass's updated `plan.md` (new "Known
Pre-Existing Limitations Not Fixed By This Project" section, updated Pattern Decisions rows for
"Write-through queue (`hostWritePending`) durability" and the new "Residual gap" row, rewritten
Story 3.3.1/Task 3.3.1g) and `requirements.md` (new Feasibility Risks bullet). The Concerns/Minors
below remain carried over unreviewed, unchanged.

## Blockers

None remain. See "Resolved in this fix pass" below for the disposition of the prior sole blocker.

## Resolved in this fix pass (verified, not just text-checked)

- [x] **`hostWritePending` durability / OPFS-write-durability window — accepted as a documented,
  honestly-tested pre-existing risk (Option B: scope out, don't fix the root cause).** This is not a
  bug fix — the underlying unawaited-OPFS-write race is still present in the codebase — but the
  original objection was that the plan *hid* the gap behind an overstated "fully reconstructable"
  claim and a test that begged the question by pre-seeding the edit as already durable. Both of
  those specific complaints are now addressed:
  - **The claim is no longer overstated.** The Pattern Decisions "Write-through queue
    (`hostWritePending`) durability" row now says "Bounded, not absolute" and points to a new
    "Residual gap" row directly beneath it; the new "Known Pre-Existing Limitations Not Fixed By
    This Project" section in `plan.md` and a new Feasibility Risks bullet in `requirements.md` spell
    out the exact mechanism (crash inside `writeFile`/`writeFileBytes`'s unawaited
    `scope.launch { opfsWriteFile(...) }`, `PlatformFileSystem.kt:267-305`) and its consequence
    (silent, zero-record loss, `runHostReconciliation` reports `Identical`) in the same terms as the
    original finding. Verified against the actual current source: `writeFile`/`writeFileBytes`/
    `applyRemoteContent` do fire-and-forget `scope.launch { opfsWriteFile(...) }`/
    `opfsWriteFileBytes(...)` with no await, and the `init` block's visibility-hidden
    "belt-and-suspenders flush" calls only `scheduleMarkerWrite()` (the `.stele-dirty-set.json`
    marker), never a flush of in-flight content writes — exactly as the plan now describes.
  - **The test now demonstrates the real bad-path behavior, not a rosier one.** Task 3.3.1g's second
    test explicitly rejects the pre-seeded-`cache` fixture the original review objected to: it
    injects a delayed/never-resolving `opfsWriteFile` test double so the edit genuinely never lands
    before `reconnectHostDirectory` runs, then asserts `hostWritePending` does **not** contain the
    path, `cache` does not contain the edit, and `runHostReconciliation` classifies the path as
    `Identical` — i.e. it proves the loss happens silently, rather than asserting the gap is closed.
    This is precisely option (c) from the original finding's own recommendation ("at minimum, add a
    test that models a crash before the OPFS write lands ... so this residual risk is measured
    rather than assumed away").
  - **The risk is proportionate to being left unfixed.** The bug is confirmed pre-existing and
    platform-wide (every `wasmJs` `writeFile`/`writeFileBytes` call, not just this feature's
    host-sync path) — this project's own code only inherits the exposure by relying on `cache` as
    ground truth for reconciliation; it does not introduce or worsen the race. The plan also narrows
    the window versus today's behavior (unconditional reconciliation on every reconnect now recovers
    the common case — an edit whose OPFS write *did* land before the crash — which today's code does
    not attempt at all on `reconnectHostDirectory`). Fixing the root cause would mean changing
    `writeFile`/`writeFileBytes`'s synchronous/non-blocking contract for every wasmJs user or adding
    new flush infrastructure to `PlatformFileSystem` — both explicitly out of scope for a plan whose
    own Pattern Decisions table already rejects blocking host writes for the same UI-stall reason,
    and correctly identified as belonging to a separate, focused follow-up project rather than a
    rider on an already-Large-appetite plan.
  - **Net**: this is a legitimate scope decision, not prose papering over the same gap. The
    remaining exposure is real and worth tracking as a follow-up, but it no longer blocks this plan:
    the plan is honest about what is and isn't fixed, and the test proves the actual current failure
    mode instead of a fixture that assumes it away.

- [x] **OPFS storage-pressure eviction.** `navigator.storage.persist()` is now wired end-to-end:
  interop (`requestStoragePersistence()`, Story 1.5.6/Task 1.5.6a) is called best-effort,
  fire-and-forget from both `connectHostDirectory`'s and `reconnectHostDirectory`'s success paths
  (Epic 2.4/Task 2.4.1a). Recovery is the corollary of Blocker 3's fix, not a separate heuristic: an
  evicted/emptied `cache` reclassifies every host file as `HostOnlyNew` on the next unconditional
  reconciliation and re-imports it (Pattern Decisions "OPFS eviction recovery" row) — this is a
  coherent application of the reconciliation mechanism, not a rationalization, and matches the
  original finding's own recommended remediation.
- [x] **`reconnectHostDirectory` reconciliation gap.** Story 2.2.1/Task 2.2.1a now routes the
  `"granted"` branch of `reconnectHostDirectory` through `runHostReconciliation` before starting the
  Phase 4/5 loops — verified this is a real behavioral change (not just a doc update) via the
  Dependency Visualization's reordering note (Epic 2.1 → Phase 3 → remaining Phase 2 epics) and the
  dedicated parity test, Task 3.3.1f, which drives the same fixture through `reconnectHostDirectory`
  instead of `connectHostDirectory` and asserts identical `onHostConflict` behavior. Genuinely closes
  the gap: ordinary session resumption no longer skips reconciliation.
- [x] **Paranoid-mode (`.md.stek`) unhandled.** `classifyReconciliationBytes` (Task 1.4.1e) is a real
  bytes-aware sibling of `classifyReconciliation`, comparing via `contentEquals` and sharing decision
  logic through a private `classifyByEquality` helper (not a duplicated four-way branch that could
  drift). `CacheAccess` gained `getBytes`/`setBytes`/`removeBytes`/`writeOpfsMirrorBytes` (Task
  1.6.1a), and both the reconciliation walk (Task 3.2.1a: branches on `.md.stek` suffix, reads raw
  bytes via `arrayBuffer()` instead of `.text()`) and the poller (Task 5.1.1b: same branch, updates
  `bytesCache` via `cacheAccess.setBytes`, never `cache`) now handle encrypted content without ever
  decoding it as UTF-8. Dedicated tests exist for both paths (Task 3.3.1e, and Story 5.1.1's second
  acceptance criterion). This is a complete three-leg fix (write-through already had bytes handling;
  reconciliation and poller now do too), not just a type added and left unwired.
- [x] **Stale-rename heuristic.** The content-hash-match auto-delete is dropped entirely (Story
  7.1.2, explicitly superseding the original draft). A `HostOnlyNew` path from an interrupted rename
  is now imported as an ordinary new page with only a non-destructive
  `println("[SteleKit] reconciliation: possible stale-rename duplicate...")` log line (Task
  7.1.2a) — no deletion call is made anywhere in this path. The replacement is genuinely safe: worst
  case is two visible, user-cleanable duplicate files, not data loss, and Task 7.2.1b's test
  explicitly covers the coincidental-content-match (non-rename) case to prove the dropped heuristic
  cannot destroy an unrelated legitimate page.
- [x] **O(graph) poller benchmark.** Epic 5.5 is scoped as a required Phase 5 deliverable (not an
  optional follow-up — stated explicitly and reflected in the Dependency Visualization). Story 5.5.1
  specifies an 8,000+-file mocked fixture (matching `LargeGraphWarmStartCrashTest`'s scale), a
  steady-state (no-change) tick-cost benchmark with a concrete wall-clock upper bound and a
  zero-content-read call-count assertion, and a burst-change (100-of-8,030) benchmark asserting
  exactly 100 content reads occur. Task 5.5.1d requires the measured numbers to confirm or revise the
  10s poll-interval default before Phase 5 is considered done — this closes Unresolved Question #2
  with a real gate instead of leaving it to ad hoc tuning.

## Resolved Concerns (the 2 in scope for this pass)

- [x] **`GitWriteLock.kt` modification.** The plan's approach changed from "extract and delegate" to
  "duplicate": `WebLock.kt` is a new, standalone file (Epic 1.1) independently implementing the same
  acquire-now/release-later idiom. Story 1.1.1's second acceptance criterion and Task 1.1.1b require
  a diff-emptiness check on `GitWriteLock.kt` as part of the story's own verification, and the
  Pattern Decisions "Cross-tab coordination" row explicitly records why extraction was rejected
  (avoidable regression risk to a sibling, uninvolved feature, for a ~50-line utility). This
  genuinely eliminates the blast radius the original concern flagged, at the accepted cost of
  ~50 duplicated lines.
- [x] **Permission-revocation silent degradation.** Story 4.4.1's second acceptance criterion and
  Task 4.4.1a now handle `NotAllowedError`-shaped failures (and, defensively, any other thrown
  error) by re-querying `queryHandlePermission` inside the catch block and mapping the result to
  `PromptNeeded`/`Denied` — not leaving `hostAccessStateFlow` at `Granted`. This directly closes the
  gap: a user whose permission was silently revoked now gets the "Reconnect folder"/"Grant access"
  affordance (Story 2.3.1's badge) instead of an indistinguishable "N changes syncing" state. Task
  4.5.1d adds dedicated test coverage for this mapping. Genuinely resolved.

## Concerns (not reviewed this pass — carried over verbatim from the original review)

- [ ] **No proactive `queryPermission()` check before a write-through batch.** `research/pitfalls.md`
  §1.1 explicitly calls this out as required ("not just once at startup"); the plan only reacts to a
  thrown error from the write call itself (Task 4.4.1a), which is weaker (a batch of writes can
  partially succeed on a soon-to-be-revoked grant with no early warning).
- [ ] **`PlatformFileSystem` is accreting into a god-object.** This plan adds roughly 15 new
  fields/methods (`hostDirHandle`, `hostGraphOpfsPath`, `hostWritePending`, `hostWriteInFlight`,
  `hostWriteDirtyDuringFlush`, `hostModTimes`, `hostFileSizes`, `hostContentHashes`,
  `hostChangeObserver`, `hostPollJob`, `_hostAccessStateFlow`, `_hostWritePendingCountFlow`,
  `onHostConflict`, `onHostWriteFailed`, plus reconciliation/rename logic) onto a class that already
  owns git-write-back's `dirtySet`/marker machinery. This makes the class harder to unit-test in true
  isolation and creates exactly the kind of coupling between two features requirements.md wants kept
  "structurally independent" — at the class level, not just the data-structure level the plan
  explicitly addressed (Pattern Decisions table, "Write-through vs. git dirty-set" row only guards
  the map instances, not the surrounding class).
- [ ] **Cross-tab `cache` consistency has an unstated staleness bound.** A tab that loses the
  per-poll-tick lock (Epic 6.2) does not refresh its own in-memory `cache`/`hostModTimes` from the
  winning tab's result — each tab polls the host directory independently. The plan asserts "a losing
  tab sees the winner's result on its own next tick" but does not state or test the resulting
  worst-case staleness window (up to ~2x the poll interval, ~20s at the proposed default) for a
  losing tab's UI.
- [ ] **Both open cadence questions (poll interval, `FileSystemObserver` `"errored"`-record recovery)
  are deferred to implementation-time spikes** for a feature whose core correctness argument depends
  on the poll baseline being fast/cheap enough at scale — this compounds the O(graph)-per-tick
  blocker above rather than being an independent, low-stakes unknown.

## Minors (not reviewed this pass — carried over verbatim from the original review)

- Task 4.2.1c explicitly allows the post-write `hostModTimes` update to be "a no-op stub until Phase
  5 lands" — a sequencing ambiguity between Phase 4 and Phase 5 that should be called out as a real
  ordering dependency rather than an aside.
- The Risk Control section's "OPFS remains the source of truth either way" framing is in tension with
  `research/pitfalls.md` §1.3's finding that OPFS should be treated as disposable once a host
  directory is attached — worth reconciling the language even independent of the eviction blocker
  above.
- No filtering discussed for the reconciliation/poller directory walk (e.g. `.git`, editor swap
  files, OS metadata files like `.DS_Store`) if the picked folder isn't graph-root-clean — could
  pollute `cache`/`hostWritePending` with non-graph noise.
