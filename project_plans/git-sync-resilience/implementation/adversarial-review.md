# Adversarial Review: git-sync-resilience

**Date**: 2026-09-23
**Verdict**: CONCERNS

## Blockers

Both iteration-1 blockers verified resolved in iteration 2 (re-checked against the current
`plan.md`/`requirements.md`, not accepted on the fix pass's claim alone):

- **`ForegroundServiceStartNotAllowedException` handling** — resolved. Story 3.1.2's AC now
  states `doWork()` "does **not** fail the operation solely because promotion was denied," and
  Task 3.1.2e wraps `setForeground(getForegroundInfo())` in `try { ... } catch (e:
  ForegroundServiceStartNotAllowedException)`, logging at `warn` and falling through to the same
  `AndroidGitRepository.clone()` delegation Task 3.1.2c runs on the success path — the transfer is
  never aborted solely because promotion was denied. Degradation is surfaced via a widened
  `GitTransportRetryState.Attempting(progress: CloneProgress, foregroundPromoted: Boolean =
  true)`, added consistently in both the Domain Glossary and Story 4.1.2's AC/Task 4.1.2a. Checked
  whether this field addition breaks any exhaustive/positional consumer elsewhere in the plan:
  Kotlin `when` exhaustiveness over a sealed type is determined by subtype coverage, not by a
  data class's field list, so Story 4.1.2's `onStateChange` wiring, Story 4.1.3's
  `CloneProgressRow`/`when`-driven rendering, and Story 6.1.1's `Attempting → Retrying → Idle`
  sequence assertion are all unaffected by the new defaulted parameter — no other `when` in the
  plan needs a corresponding update. (Story 4.1.3's AC does not add a distinct visual treatment
  for `foregroundPromoted = false` specifically, but that's a UX-completeness question, not a
  crash/silent-breakage risk, and is out of scope for this narrow re-check.) The regression test
  (Task 3.1.2f) is concrete enough to implement: Robolectric/`androidUnitTest`,
  `TestListenableWorkerBuilder`-built `GitCloneWorker`, `setForeground()` "mocked/shadowed" to
  throw the exception, asserting `doWork()` still invokes the clone delegate and returns a
  terminal `Result` rather than propagating the exception. ("Mocked/shadowed" leaves the exact
  override mechanism to implementation time, but names the builder, the target method, the
  exception, and the assertion — enough to implement without further research.)
- **`requirements.md` Success Metrics still stating the infeasible literal wording** — resolved.
  The bullet at `requirements.md` (Success Metrics, "A clone/fetch/push interrupted partway
  through...") now states shallow-clone-then-progressive-deepen semantics directly, cites
  `project_plans/git-sync-resilience/decisions/ADR-001-resume-means-shallow-then-deepen.md` by
  path, and is explicit about what does/doesn't avoid re-transfer ("a retry after failing partway
  through the *initial shallow clone* still re-transfers that shallow step from zero... only a
  failure *after* the shallow step has already succeeded, during a subsequent deepen
  (`unshallow()`) attempt, avoids re-transferring the already-fetched shallow history"). This
  tracks ADR-001's own Consequences section verbatim in substance rather than loosening it to
  something trivially true.

## Concerns

- [ ] **Disk-full mid-transfer is unhandled and interacts badly with the new partial-directory cleanup.** `DomainError.StorageError.InsufficientSpace` already exists in this codebase (`DomainError.kt:188`, used by the storage-relocation feature) but `classifyGitFailure`'s taxonomy (Task 1.1.1b) never accounts for `IOException`/ENOSPC from a git transfer. A disk-full `IOException` isn't a `TransportException`-wrapped `SocketException`, so it falls through to the generic `catch(Exception)` path or the fail-closed "everything else → Permanent" bucket — meaning Story 2.1.3's `beforeRetry` cleanup (the only automatic cleanup this plan introduces for a partial shallow-clone directory) never fires, since that cleanup only runs ahead of a *retried* attempt, and a Permanent classification means no retry happens at all. Net effect: a disk-full clone leaves a partially-written directory on an already-full disk with no automatic cleanup and a raw Java exception string instead of a recognizable error. — **Recommendation**: add an explicit IOException/ENOSPC branch to `classifyGitFailure` and route it through (or alongside) the existing `StorageError.InsufficientSpace` type, and clean up the partial directory even on this specific non-retried permanent failure.

- [ ] **Story 2.1.4 (`unshallow()`/deepen capability) ships ~6 tasks of backend code with zero callers in v1.** The Domain Glossary itself says: "Backend capability only in this plan (Story 2.1.4) — no new settings-screen UI entry point, which requirements.md does not ask for." Story 2.1.5 deliberately fails closed (`ShallowHistoryInsufficient`) rather than calling `unshallow()`, and the plan's own Unresolved Questions confirm auto-unshallow-on-merge is deferred pending product input. This is speculative "build it because a future story might call it" work, not something any Success Metric or In-Scope bullet in `requirements.md` asks for. — **Recommendation**: cut Story 2.1.4 from this project and defer it to whichever future project adds the UI entry point that would actually call it, unless a concrete v1 caller is identified.

- [ ] **Phase 5 (`GitWorktreeLocks`/periodic-fetch race fix) is not named in `requirements.md`'s In-Scope section.** It's well-justified — `pitfalls.md` §3.4 shows this project's own changes (longer-held retries, foreground survival) widen a pre-existing narrow race into a real one — but it's still scope introduced during research/planning judgment, not authorized by the requirements doc's explicit Scope list. — **Recommendation**: flag this to the product owner explicitly (one line: "adding X because Y's own changes make it necessary") rather than letting it ride silently as part of a "Large" appetite budget.

- [ ] **Story 6.1.2's fault-injection mechanism is deferred to implementation time, not committed to in the plan.** Task 6.1.2a: "pick one and keep it small" (referring to a fault-injection seam) — no concrete choice among the "several viable seams" JGit's pluggable-transport API offers. `pitfalls.md` §5.2 itself calls mid-transfer fault injection "a genuinely hard problem... no existing scaffold," which is exactly the situation where a plan should commit to a mechanism, not defer the research question into a 5-minute task estimate. — **Recommendation**: name the concrete seam now (e.g., a `TransportConfigCallback` wrapping the object-negotiation input stream to throw after N bytes/objects) so this doesn't become an open research question discovered mid-implementation.

- [ ] **Story 2.1.5's shallow/merge-base detection only catches "no merge base found," not "wrong merge base found."** Task 2.1.5b's check is "if no merge base can be found within the shallow history... return `ShallowHistoryInsufficient`." But the git documentation `pitfalls.md` §2.3 itself cites warns that shallow history can make merge-base computation "cannot be counted on to work as expected" — implying JGit's `RevWalk` could return a spurious-but-non-null ancestor rather than cleanly reporting absence. That case would not trip the Task 2.1.5b guard and could still let `MergeStrategy.RECURSIVE` run against a wrong merge base — the exact correctness risk this story exists to close. — **Recommendation**: Task 2.1.5d's regression test should explicitly attempt to construct a "wrong-but-present" merge base case (not just the "absent" case), or the plan should document why that case is provably unreachable given JGit's specific shallow-boundary `RevWalk` behavior.

- [ ] **`runGitTransportOpWithRetry` uses one shared `Schedule` for clone/fetch/push/unshallow.** Architecturally fine — the function takes `schedule` as a parameter, so per-operation policies are possible without redesign — but the plan doesn't differentiate them despite push having different idempotency characteristics (§1.3 of pitfalls.md) than clone. Not a bug, just an unstated one-size-fits-all product choice. — **Recommendation**: note in the plan (or accept explicitly) that clone/fetch/push/unshallow share one retry policy for v1, so a future per-operation-tuning request isn't mistaken for an architecture change.

- [ ] **ADR-002's "429 doesn't compound with internal retry" claim relies on the classifier's fail-closed default, not an explicit rule.** Task 1.1.1b's taxonomy has explicit branches for transient network causes, auth/404, and cancellation — a 429-shaped exception isn't named, so it falls into "everything else → Permanent" by default. That happens to produce the right answer, but ADR-002's non-compounding claim is structurally load-bearing on this default continuing to apply. — **Recommendation**: Task 1.1.1d's classifier test table should include an explicit 429-shaped case asserting `Permanent`, so ADR-002's claim is verified by a test rather than inferred from a fallback rule.

## Minors

- Retry-vs-stale-negotiation (remote ref moves between a failed attempt and its retry) is implicitly safe — JGit performs fresh `have`/`want` negotiation on every `clone()`/`fetch()` call, and Story 2.1.3 deletes-and-restarts the target directory on clone retry — but the plan never states this reasoning explicitly anywhere. One sentence in Epic 1.2's Goal would save a future reader from re-deriving it.
- Two retry/resume attempts racing for the same graph (an automatic retry mid-backoff + a manual "Try again" tap) appears handled by construction — Story 4.1.3 only shows "Try again" in the terminal `Exhausted` state, and Phase 5's shared unique-work name would serialize any actual concurrent `GitCloneWorker` enqueue — but this reasoning is never stated explicitly.
- Cancel racing the retry loop's in-flight `delay()` relies on standard coroutine cancellation semantics; not explicitly tested at that exact narrow timing window beyond Task 4.1.4d's directory-preservation check.
- Verified clean, no action needed: the plan introduces no new third-party dependency and matches `build-vs-buy.md`'s recommendations exactly (`arrow-resilience` `Schedule`, `CoroutineWorker.setForeground()`, no resumable-transfer library) — confirmed by direct comparison, not assumed. The `git_config` migration is correctly forward-only/additive per this repo's own `MigrationRunner` convention. Phase 1's fix for the reported bug ("Clone failed: Software caused connection abort" misrouted as `AuthFailed`) is concrete and directly tested (Story 1.1.1's AC reproduces the exact bug scenario), and the missing `.setTimeout()` root cause is directly addressed (Task 1.1.2) — confirmed against the current `GitOperationSupport.kt` (line 55's unconditional `TransportException → onAuthFailed`, line 139/161's `testRemoteViaLsRemote`-only timeout) and `WorkManagerSyncScheduler.kt` (lines 129, 166's `Result.retry()`), both matching the plan's stated baseline exactly.
