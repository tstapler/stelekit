# Architecture Review: llm-tag-download-stall
**Date**: 2026-07-29
**Verdict**: CONCERNS (prior blocker resolved; 5 concerns and 5 nitpicks from the prior pass carried forward, not re-evaluated this pass)

## Constitution Violations
N/A — `docs/adr/ADR-000-architecture-constitution.md` does not exist in this repo

## Blockers

None. The prior blocker (Epic 4/Story 4.4b/Task 4.4.4's NFR-3 violation — ~120s/~20s of
required real wall-clock sleep) is resolved. Verified against the current
`project_plans/llm-tag-download-stall/implementation/plan.md`:

1. **Injectable dispatcher + deadline/interval overrides exist and default to production
   values.** Task 4.1.1 (plan.md:522-573) widens `TagSuggestionViewModel`'s constructor with
   `dispatcher: CoroutineDispatcher = Dispatchers.Default`, `pollDeadlineMs: Long =
   TagAvailabilityPoller.DEFAULT_POLL_DEADLINE_MS`, `pollIntervalMs: Long =
   TagAvailabilityPoller.DEFAULT_POLL_INTERVAL_MS`, `pollEscalationThresholdMs: Long =
   TagAvailabilityPoller.CAPTION_ESCALATION_THRESHOLD_MS`, all optional with production
   defaults. The plan states `App.kt`'s sole construction site
   (`TagSuggestionViewModel(tagEngine, onPropose = viewModel::proposeLlmSuggestion)`) needs
   zero changes to keep compiling, and the Domain Glossary (plan.md:48-49) reiterates "No
   production call site (`App.kt`) passes a non-default value."

2. **Task 4.4.4's test now uses those overrides, and its assertion is unchanged in
   substance.** The rewritten test (plan.md:862-883) builds `StandardTestDispatcher(testScheduler)`
   shared with `runTest`'s own scheduler, constructs the VM with `pollDeadlineMs = 200L`,
   `pollIntervalMs = 50L`, calls `requestSuggestions(...)`, then `advanceUntilIdle()`, and
   asserts `assertFalse(vm.isSuggestionJobActiveForTest)`. This completes in milliseconds of
   both real and virtual time — no real sleep. The assertion is logically equivalent to what
   AC5 requires: the poll loop is bounded and self-terminates at its own deadline (not
   indefinite), demonstrated at a scaled-down deadline rather than the literal 120000ms —
   which is the correct virtual-time idiom (mirrors `TagAvailabilityPollerTest`'s existing
   pattern one layer down) and does not weaken the property under test. Not a trivial pass:
   the test still exercises the real poll loop, real dispatcher-sharing, and the real
   `isSuggestionJobActiveForTest` accessor rather than stubbing anything out.

3. **Story 4.5's Task 4.5.1 similarly no longer requires ~20 real seconds.** The rewritten
   test (plan.md:910-951) uses the same shared `StandardTestDispatcher`, `pollIntervalMs =
   10L`, `pollDeadlineMs = 1_000L`, and `advanceUntilIdle()` to reach `formatCalls == 2` /
   `checkAvailabilityCalls == 5` in milliseconds instead of ~20 real seconds. Assertions are
   unchanged from the original intent (pitfall #2 — `format()` not re-triggered per tick).

4. **Task 4.4.3 (`close()` before the deadline) still makes sense and needs no changes.**
   The plan explicitly calls this out (plan.md:847-850): since `close()` cancels before any
   deadline is reached, this test "needs no dispatcher/deadline overrides to pass, and
   compiles unchanged against Task 4.1.1's new optional constructor parameters, which all
   default to today's behavior." Confirmed structurally correct — Task 4.1.1's new params are
   all optional/defaulted, so `TagSuggestionViewModel(engine)` (the construction shape Task
   4.4.3 uses) is unaffected.

5. **No new problem introduced by the fix.** The four new constructor parameters
   (`dispatcher`, `pollDeadlineMs`, `pollIntervalMs`, `pollEscalationThresholdMs`) are all
   optional and default to production values — this is the standard `kotlinx-coroutines-test`
   idiom for putting an independently-scoped collaborator under virtual-time control, not a
   leak of test concerns into the production API surface in any actionable sense (it's
   additive constructor-injection, zero-cost for every existing/production caller). The new
   `internal val isSuggestionJobActiveForTest: Boolean get() = suggestionJob?.isActive == true`
   accessor (plan.md:558-564) is claimed to mirror `FountainDecoder.mixedPartsCountForTest` —
   **spot-checked and confirmed real**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainDecoder.kt:45`
   contains `internal val mixedPartsCountForTest: Int get() = mixedParts.size`, exposing
   private mutable state to same-module tests via an `internal`-visibility computed property
   without weakening the underlying field's `private` visibility — exactly the pattern the new
   `isSuggestionJobActiveForTest` follows. This is a real, pre-existing codebase precedent, not
   a fabricated citation. The one pre-existing observation worth carrying forward (not new):
   this is the VM's 4th–7th constructor parameter addition in this project alone, compounding
   the already-flagged SRP/responsibility-growth concern below — not blocking, but the surface
   area is genuinely growing.

**Sanity check — Task Count Summary internal consistency**: verified by counting `**Task
N.N.N**` headers in each epic body against the summary's per-epic counts. All match exactly:
Epic 0: 1, Epic 1: 7, Epic 2: 2, Epic 3: 7 (including the new Task 3.2.5), Epic 4: 16
(including Tasks 4.4.4/4.5.1's rewrite — no count change, as the summary claims), Epic 5: 10
(including the new Task 5.2.5), Epic 6: 2, Full Regression/CI: 1 — total 46, matching the
summary's stated "46 (+2 vs. the prior draft)". Also spot-checked Task 3.1.1 (plan.md:288-330)
and confirmed it does carry the claimed `Throwable`-catching resilience language/try-catch
contract addressing the adversarial-review blocker re: unguarded `checkAvailability()` calls.
Full depth-verification of the Epic 3/5 adversarial-review fixes is out of scope for this pass
(a separate re-review agent is checking those against `adversarial-review.md` in parallel).

## Concerns

*(Carried forward verbatim from the prior architecture review pass — not re-evaluated this
pass, unrelated to the blocker fix verified above.)*

- [ ] **Epic 3/Epic 4 (`TagAvailabilityPoller.pollUntilAvailable` return type / `runLlmSuggest`'s
  "Unreachable" branch)** — `pollUntilAvailable` returns `LlmProviderAvailability` (3 cases: `Available` /
  `Preparing` / `Unavailable`) but by construction (the `while` loop's early-return conditions plus the
  deadline fallthrough) never actually returns `Preparing`. `runLlmSuggest`'s `when (resolved)` (Task 4.1.2)
  therefore carries a `Preparing ->` branch commented "Unreachable — pollUntilAvailable's contract never
  returns Preparing — kept for exhaustiveness," i.e. an unenforced, comment-only postcondition rather than a
  type that makes the illegal case unrepresentable — a future change to `pollUntilAvailable` that actually
  starts returning `Preparing` would silently start exercising that dead branch with no compile error.
  **Recommendation**: narrow the return type to a private 2-case sealed result (e.g.
  `PollOutcome = Available | GaveUp(reason: String, retryable: Boolean)`) so `runLlmSuggest`'s `when` is
  exhaustive over exactly the values that can occur, eliminating the dead branch entirely.

- [ ] **Story 1.1 / `LlmSynthesisService.kt`'s identical, un-fixed twin bug** — confirmed by reading
  `LlmSynthesisService.kt` (~line 104): `is LlmResult.Failure.OnDeviceUnavailable ->
  DomainError.NetworkError.RequestFailed(result.reason).left()` drops `result.retryable` exactly like the
  bug this project fixes in `LlmTagProvider.kt`. The plan's own research explicitly names this
  (`research/stack.md`) and the Pattern Decisions table acknowledges it as "explicitly left unfixed as
  out-of-scope." Unlike ADR-001's mandatory hardware-revalidation follow-up or pitfall #3's "recommend a
  follow-up backlog item" language, no backlog item is cited for this twin bug, so it risks being silently
  forgotten once this project ships and the `retryable` field looks "handled" project-wide.
  **Recommendation**: either apply the identical one-line fix while `DomainError.NetworkError.RequestFailed`'s
  shape is already being touched in this project (trivial, same pattern Story 1.2 already tests), or add an
  explicit backlog item referencing `LlmSynthesisService.kt`'s line before closing this project out.

- [ ] **Epic 4 (`LlmSuggestionStatus` transition ownership spread across 3 collaborators)** — the decision
  of *which* `LlmSuggestionStatus` value to produce is made independently in three places:
  `TagAvailabilityPoller.pollUntilAvailable` (pushes `Pending(ESCALATED_WAIT_CAPTION)` on escalation),
  `runLlmSuggest` (pushes `Pending(failure.message)` before polling starts), and `requestSuggestions`'s
  `fold` (constructs the terminal `Stalled`/`Failed`/`Resolved`). Each piece is individually well-tested
  (Epic 3's tests, Epic 4's Given-When-Thens), but there is no single place a future maintainer reads to see
  the full "event → status" mapping — a Transaction-Script-adjacent smell for a 5-state sealed type. Not
  severe enough to block given the test coverage, but worth flagging per the design-patterns lens.
  **Recommendation**: consider consolidating the terminal-state decision (retryable → `Stalled` vs.
  non-retryable → `Failed`) into a single pure function callable from `requestSuggestions`'s `fold`, e.g.
  `LlmSuggestionStatus.terminalFrom(error: DomainError): LlmSuggestionStatus`, as a small, low-risk follow-up
  if this area is touched again.

- [ ] **`TagSuggestionViewModel` responsibility growth (SRP)** — the VM already owns job lifecycle
  (`suggestionJob`/`activeBlockUuid`), a results cache, bulk-scan orchestration (`scanJob`/`_scanState`), and
  now (this project) poll-loop orchestration (`runLlmSuggest`) plus retry-argument storage (`LastRequest`) —
  and, as of the NFR-3 fix verified above, 4 additional test-injection constructor parameters. The plan's
  justification — the poll loop's lifecycle *is* the VM's existing job lifecycle, and the actual polling
  algorithm is correctly extracted into the stateless `TagAvailabilityPoller` — is sound for this change
  specifically, so this is not a blocker. Flagged as a CONCERN only because the VM is a growing single point
  of responsibility; if a future project adds more, consider splitting a thin UI-state holder from a
  `SuggestionRequestCoordinator` that owns job/cache/retry-state, mirroring how the poll algorithm was
  already extracted here.

## Nitpicks

*(Carried forward verbatim from the prior architecture review pass — not re-evaluated this
pass, unrelated to the blocker fix verified above.)*

- `TagSuggestionState.Ready.blockUuid` stays a raw `String` (Task 2.1.1 rewrites the whole file but doesn't
  change this). The codebase already has a proper newtype for this exact concept —
  `dev.stapler.stelekit.model.BlockUuid` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Uuid.kt:9`,
  an `expect value class`) — used elsewhere (e.g. `PageView.kt`'s `blockStateManager.appendToBlock(BlockUuid(uuid), ...)`).
  Pre-existing, not introduced by this plan, and out of this bug-fix's scope — but Task 2.1.1 is already a
  full-file rewrite, so it would have been a near-zero-cost opportunity to close.
- Task 1.3.3 cites "the 3 existing test-construction sites" with specific line numbers
  (`TagSuggestionViewModelTest.kt:145`, `TagSuggestionEngineTest.kt:50`, `TagInsertionFlagshipUiTest.kt:73`).
  A grep of `TagSuggestionEngine(` construction call sites finds at least 8 across those 3 files (6 alone in
  `TagSuggestionEngineTest.kt`, at lines 61/78/98/114/152/180 — not line 50), plus 2 in
  `TagSuggestionViewModelTest.kt`. This doesn't affect correctness (the new `checkAvailability` parameter is
  defaulted to `null`, so any number of existing call sites keep compiling unmodified), but the specific line
  citations are stale/inaccurate and could confuse an implementer trying to "confirm the 3 sites still
  compile unmodified" literally as written.
- `checkAvailability: suspend () -> LlmProviderAvailability` and `onStatusUpdate: (LlmSuggestionStatus) -> Unit`
  as bare function types rather than named interfaces (e.g. `AvailabilityProbe`) — verified consistent with
  existing precedent (`GitHubDeviceFlowClient.pollForToken`'s `onStateChange: (DeviceFlowPollState) -> Unit`
  uses the same bare-function-type style), so not flagged as a real issue — purely a style note if the team
  ever wants named types for readability.
- Verified: the plan's rejection of Arrow `Schedule`/`CircuitBreaker` in favor of hand-writing the poll loop
  matches `research/build-vs-buy.md`'s explicit recommendation ("hand-write it... Verdict: Recommended"), and
  `TagAvailabilityPoller` contains no Arrow-resilience usage — Pattern Decision holds up.
- Verified: Story 5.3's claim of exactly 2 `SuggestionBottomSheet(...)` call sites (`JournalsView.kt:345`,
  `PageView.kt:591`) is accurate. Note there are actually 4 `requestSuggestions(...)` call sites in production
  code (`PageView.kt:377` and `:577`, `JournalsView.kt:206` and `:332`) — all inherit the new
  `allowPolling: Boolean = true` default automatically since none pass it explicitly, which is the intended
  FR-2 behavior (single-suggestion request paths get polling by default) — not a discrepancy, just noting the
  full call-site count differs from "2" if read as "requestSuggestions call sites" rather than "SuggestionBottomSheet
  call sites."
