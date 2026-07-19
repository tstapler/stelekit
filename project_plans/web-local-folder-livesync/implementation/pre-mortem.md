# Pre-mortem: web-local-folder-livesync
**Date**: 2026-07-17
**Revision note (2026-07-17)**: Re-reviewed after a fix pass on `plan.md`. This revision's scope was
limited to the two items named by the review request — the OPFS-write-durability crash window
(Epic 1.7) and the reconciliation full-content-read walk (Epic 3.4) — both now resolved and moved to
"Resolved Findings" below. **Important accuracy note**: in the pre-revision version of this document,
those two items were actually labeled P2 and P1 respectively — the *other* original P1 (poll-cadence/
visibility-throttling, row 1 below) was **not** in this review's scope and remained open at that time.
See "Scope discrepancy" at the bottom.
**Revision note (2026-07-17, follow-up)**: The remaining P1 (poll-cadence/visibility-throttling) has
now also been fixed in `plan.md` (Story 5.1.2's revised timer loop, `effectivePollIntervalMs()`,
Epic 5.2's `observerConfirmedActive`, Epic 5.5's new Story 5.5.2 cumulative-cost benchmark) and is
moved to "Resolved Findings" below. All three original findings from this document are now resolved;
0 P1 items remain open.

## Failure Modes

| # | Failure | First Symptom | Prevention | Severity |
|---|---------|--------------|------------|----------|
| 1 | Chromium auto-revokes `readwrite` grants on backgrounded/inactive tabs (`research/pitfalls.md` §1.1), which is exactly the usage pattern of a note-taking app users leave open for hours. The "Reconnect folder" badge (Story 2.3.1) will therefore appear far more often mid-session than the "one click at cold start" framing in requirements.md/research implies, with no copy explaining *why* it's happening. | Users repeatedly see "Reconnect folder" on a tab they never closed and conclude live sync is flaky/broken, or reflexively click through without understanding what they're re-granting; some file "sync keeps disconnecting" bug reports. | Add first-occurrence-in-session copy explaining this is routine browser security behavior, not an app fault (distinct from the generic "Reconnect folder" label). Instrument (client-side `println` counter, per the existing Observability Plan) how often `Granted → PromptNeeded/Denied` transitions occur *after* startup, not just at cold start, and review that signal during dogfooding before wider rollout. | P2 |
| 2 | Task 5.2.2b explicitly permits, as an "acceptable simplification for v1," calling the **full-tree** `pollHostDirectoryOnce` once per `FileSystemObserver` change record instead of a targeted per-path walk. Real burst-change patterns this feature exists to detect (`git pull`, editor atomic-save-via-temp-file-rename, which the plan's own pitfalls doc flags as a Windows quirk generating extra disappear/appear records) can fire dozens to hundreds of records in a burst. | After a `git pull` or bulk external edit, the tab becomes laggy/unresponsive for seconds to tens of seconds as many full-tree walks fire back-to-back — worse than the 10s baseline poll alone, i.e. the "fast path" meant to reduce latency causes the worst observed perf cliff. | Require coalescing/debouncing observer records into one `pollHostDirectoryOnce` call per short window (e.g. one macrotask or ~200ms debounce) before Epic 5.2 is considered done, and add a burst test (N rapid observer records, matching Epic 5.5's 100-of-8,030 burst scenario) asserting the walk count stays bounded, not O(N) records. | P2 |

*(The formerly-#1 finding — the poll baseline running at full cadence regardless of tab visibility or
`FileSystemObserver` health — is resolved; see "Resolved Findings" below.)*

## P1 Items (address before implementation)
None remaining. The sole P1 item (poll-cadence/visibility-throttling) is resolved — see "Resolved
Findings" below.

## Summary
0 P1 items, 2 P2 items, 0 P3 items. All P1 items are resolved. Top remaining risk is P2: Chromium's
background-tab permission auto-revocation surfacing the "Reconnect folder" badge more often than
users expect, without explanatory copy.

---

## Resolved Findings (this revision)

### Resolved — Reconciliation full-content-read walk unbenchmarked and blocking on every reconnect (was P1)
**Original finding**: `runHostReconciliation` did a full-content-read walk (`.text()`/`arrayBuffer()`
on every path) unconditionally on every `reconnectHostDirectory`, never benchmarked at 8,000+-file
scale, and was awaited as a sequential `Main.kt` startup step — risking a perceptible startup hang on
large graphs.

**Fix verified in `plan.md`'s new Epic 3.4** ("Reconciliation cost control — mtime/size pre-filter,
non-blocking session resume, and required large-graph benchmark"):
- **Story 3.4.1** adds a cheap mtime/size pre-filter (reusing `hostModTimes`/`hostFileSizes`) before
  falling back to a content read — steady-state unchanged files are classified `Identical` with zero
  content reads; first-ever reconciliation (no baseline) still does a full content-read walk, exactly
  as before, so no correctness regression is introduced.
- **Story 3.4.2** makes `reconnectHostDirectory`'s call to `runHostReconciliation` non-blocking
  (`scope.launch`, not awaited) so `Main.kt` startup is never held up by reconciliation, while
  `connectHostDirectory`'s one-time opt-in reconciliation **remains** awaited-blocking.
- **Story 3.4.3** adds the previously-missing required benchmark
  (`HostDirectoryPollerBenchmarkTest`/`HostDirectorySyncReconciliationBenchmarkTest`, mirroring Epic
  5.5's fixture) covering both the first-ever full walk and the steady-state pre-filtered walk at
  8,030 files, with an explicit gate: if the steady-state background cost isn't comfortably cheap,
  Story 3.4.2's non-blocking decision must be revisited before Phase 3 is done — not silently shipped.

**Judgment on the connect-path/reconnect-path split**: this is a coherent, purpose-driven split, not
paperwork. `design/ux.md` Surface 8 (the "Connecting to folder… → N files already match / N differ /
…" progress and summary UI) is explicitly scoped to the one-time "Enable live folder sync" click from
Surface 7 — a deliberate, user-initiated action the user is already primed to wait on. Surface 8 says
nothing about covering an unattended, automatic-every-launch reconnect. Keeping `connectHostDirectory`
blocking (with its purpose-built progress UI) while making `reconnectHostDirectory` non-blocking (with
outcomes streaming through the existing `pendingConflicts`/badge mechanisms described in Surface 8's
"Flow after the summary" section, which are not modal-dependent) matches both the UX design's actual
scope and `research/ux.md`'s "no spinners" steady-state trust-signal principle. Verdict: **resolved**.

### Resolved — OPFS-write-durability crash window (originally logged in this document as P2, not P1)
**Original finding**: the unawaited `scope.launch { opfsWriteFile(...) }` inside `writeFile`/
`writeFileBytes` meant a hard crash during that write's flight time could silently lose an edit in
both directions (never reaches OPFS or the host file), and the plan only planned to document this as
an accepted, narrow, rare risk with a "characterization test" that recorded the loss rather than
preventing it.

**Fix verified in `plan.md`'s new Epic 1.7** ("OPFS-write durability fix — await-before-durable +
beforeunload/pagehide flush, SCOPE EXPANSION, Option A"):
- **Task 1.7.1a** adds per-path in-flight `Deferred` tracking (`opfsWriteInFlight: MutableMap<String,
  Deferred<Unit>>`) to `writeFile`/`writeFileBytes`, without changing their synchronous, non-blocking,
  `Boolean`-returning signatures.
- **Task 1.7.1b** makes `HostDirectorySync.scheduleHostWriteThrough` await that path's `Deferred`
  before adding it to `hostWritePending` — an edit can no longer be enqueued for host push before it
  has actually landed in OPFS.
- **Story 1.7.2** adds a `beforeunload`/`pagehide`-triggered best-effort flush/log loop
  (`jsPageHidePromise()`) as platform-wide defense in depth, honestly scoped as best-effort (does not
  block unload, cannot close a true instant hard-kill/OOM) rather than oversold as a complete fix.
- The former "Known Pre-Existing Limitations Not Fixed By This Project" section has been fully removed
  from `plan.md` — confirmed via search, zero remaining references.
- **Task 3.3.1g** (`plan.md` line ~1046) now specifies three tests, the load-bearing one being: *"a
  slow-but-eventually-resolving `opfsWriteFile` test double... not a never-resolving double, which
  cannot meaningfully assert 'data is not lost' since nothing can be awaited to a testable
  completion"* — and explicitly asserts `hostWritePending` **does** contain the path once the delayed
  write resolves, i.e. correct/safe behavior, not a documentation-of-loss assertion. This matches
  exactly what was required: the test now proves the race is closed, not merely observed.

Verdict: **resolved** — the mechanism is sound, the test was rewritten to assert correct behavior
rather than document loss, and the plan is honest about the residual, fundamentally unclosable
hard-kill case rather than overclaiming 100% closure.

### Resolved — Poll baseline never backs off on tab visibility or `FileSystemObserver` health (was P1)
**Original finding**: `Task 5.1.2a`'s timer loop was an unconditional
`while (isActive) { delay(hostPollIntervalMs); pollHostDirectoryOnce(...) }` — no `visibilityState`
gate, and no reduction tied to `FileSystemObserver` health. Story 5.3.1 only added an *additional*
immediate poll on regaining focus; it never throttled or paused the always-on baseline timer while
hidden. `research/ux.md` §4 explicitly recommends "poll aggressively only while visible, back off or
pause when hidden," and per ADR-002/`research/stack.md`'s framing, `FileSystemObserver` is meant to
be an accelerator on top of the poll baseline, not an equally-frequent redundant mechanism once
confirmed active — neither property held.

**Fix verified in `plan.md`'s revised Story 5.1.2 and Epic 5.5**:
- **Task 5.1.2b** adds `isTabHidden` (tracked by a dedicated `scope.launch` loop alternating
  `jsVisibilityHiddenPromise()`/`jsVisibilityVisiblePromise()` awaits — the same interop idiom this
  codebase already uses for `PlatformFileSystem.kt:48-57`'s marker-flush loop and Story 5.3.1's
  existing visibility-regain trigger) and `observerConfirmedActive` (set `true` once Task 5.2.2a's
  `HostChangeObserver` construction + `observeHandle()` succeed, per ADR-002's "fast path" framing),
  plus `effectivePollIntervalMs()` — `hostPollIntervalMs * maxOf(HIDDEN_POLL_BACKOFF_MULTIPLIER,
  OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER)` when either condition holds (default 6x ≈ 60s), `maxOf`
  rather than multiplicative so the two backoff reasons don't compound to an absurd interval.
- **Task 5.1.2a** now sleeps on `effectivePollIntervalMs()`, recomputed fresh every tick, instead of
  the fixed `hostPollIntervalMs` — a visibility or observer-health change takes effect starting the
  very next tick.
- **Story 5.3.1** is explicitly scoped as complementary, not duplicative, in its own text now: it
  still fires one extra immediate poll on visibility regain, while Story 5.1.2 owns the steady-state
  cadence while hidden.
- **Story 5.5.2** (new) closes the "add a benchmark asserting cumulative `getFile()` call volume"
  half of this finding's Prevention column: a virtual-time test simulates a one-hour idle
  backgrounded tab and asserts `pollHostDirectoryOnce` fires ~60 times (widened cadence), not ~360
  times (base cadence); a second test proves the observer-healthy case independently backs off the
  same way; a third proves the combined-backoff case doesn't compound past the single-backoff bound.

**Judgment on the `maxOf`-not-multiplicative design choice**: this is the correct middle ground
between the two failure modes a naive fix risks — (a) leaving the poller at full cadence whenever
either condition alone doesn't hold (the original bug), and (b) stacking both backoffs
multiplicatively into a several-minutes-long interval that would meaningfully weaken the poller's
safety-net role for the case ADR-002 actually worries about (an `errored`-only or silently-broken
observer session on a hidden tab). `maxOf` guarantees the interval only ever backs off as far as the
*single* strongest applicable reason, never further. Verdict: **resolved**.

---

## Scope discrepancy (flagged for the requester — now closed)
This document's Failure Modes table, as originally written, labeled the **reconciliation walk** (now
resolved, see above) and the **poll-cadence/visibility-throttling gap** (now also resolved, see
above) as the two P1 items — not the OPFS-write-durability crash window, which was originally logged
as P2. An earlier revision's review request named "OPFS-write-durability" and "reconciliation walk"
as the two P1s to re-check; both were resolved in `plan.md` at that time (Epic 1.7 and Epic 3.4
respectively). The item that was *actually* labeled P1 alongside the reconciliation walk in the
original document — the always-on poll timer never backing off when the tab is hidden or when
`FileSystemObserver` is healthy — was outside that revision's stated scope and remained open as
`P1 Items #1` afterward. This follow-up pass specifically targeted Epic 5.1/5.3 (poll cadence) and
has now resolved it (Story 5.1.2's `effectivePollIntervalMs()`, Epic 5.5's Story 5.5.2). No open
items remain from this discrepancy.
