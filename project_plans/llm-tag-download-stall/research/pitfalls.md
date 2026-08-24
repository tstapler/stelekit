# Pitfalls: On-Device LLM Tag Suggestion — Download Stall

Research agent 4 findings. Ground truth read from:
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/{TagSuggestionViewModel,TagSuggestionState,TagSuggestionEngine,LlmTagProvider}.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/AndroidOnDeviceLlmProvider.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/GenAiErrorMapping.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt`,
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/{JournalsView,PageView}.kt`.

---

## 1. Coroutine leak: TWO jobs, ONE cancellation guard

**Confirmed bug shape, not hypothetical.** `TagSuggestionViewModel` (lines 56-125) has exactly
one `Job` field (`suggestionJob`) and one `activeBlockUuid` guard. `requestSuggestions()`'s
switch-block logic is:

```kotlin
suggestionJob?.cancel()      // cancels the PREVIOUS block's job
activeBlockUuid = blockUuid
suggestionJob = scope.launch { ... }   // the ONE job tracked
```

If the poll loop is implemented as a second `scope.launch { ... }` (e.g. `pollJob = scope.launch
{ while (...) { delay(pollIntervalMs); checkStatus() } }`), started from inside — or alongside —
the `llmSuggest` coroutine, and it is **not** assigned into the same `suggestionJob` field (or a
sibling field that gets cancelled at the same call sites), then:

- Switching to a different block only cancels `suggestionJob` (the `llmSuggest` coroutine); the
  poll loop for the *old* block keeps running, checking AICore status every 3-5s, and — per FR-1 —
  will auto-re-run LLM suggestion for a block the user is no longer looking at, silently
  overwriting `cache[oldBlockUuid]` and potentially calling `onPropose?.invoke(...)`-adjacent side
  effects for a stale block.
- `dismiss()` deliberately does *not* cancel `suggestionJob` (documented, ADR-002) so the LLM can
  finish in the background — but the poll loop has an unbounded-ish lifetime (up to
  `DEFAULT_POLL_DEADLINE_MS`, likely tens of seconds to minutes per FR-2/FR-6). If poll survives
  dismiss by the *same* design intent as `llmSuggest`, that's fine — but it must still terminate
  itself, and if it's a separate untracked job, `close()` (`scope.cancel()`) is the only thing that
  stops it, meaning a poll loop for a long-abandoned block runs for the full deadline in the
  background on every dismiss.
- **Design requirement**: the poll loop must be a child coroutine *of* `suggestionJob` (nested
  `launch` inside the same job, or `Job()` parented under it) — not a sibling field — so exactly
  one `cancel()` call at the existing switch-block guard (line 88) tears down both the LLM call and
  any in-flight/pending poll. If a separate field is unavoidable (e.g. poll needs to outlive the
  initial `llmSuggest` failure), it must be cancelled at every single site `suggestionJob` is
  cancelled or reassigned: line 88 (block switch) and implicitly via `scope.cancel()` in `close()`
  (line 184) — audit both.
- **Test to write**: call `requestSuggestions("block-A", ...)` with a provider that returns
  `DOWNLOADABLE` forever (poll never resolves), then call `requestSuggestions("block-B", ...)`.
  Assert no state update for `block-A` occurs after switching (e.g. via a spy/counter on
  `cache["block-A"]` mutation, or asserting `activeBlockUuid == "block-B"` and no further
  `_state.update` matching `blockUuid == "block-A"`). This is the regression test that would have
  caught the two-jobs bug.

## 2. AICore-specific gotchas

### 2.1 `BACKGROUND_USE_BLOCKED` and polling itself
`MlKitLlmFormatterProvider.format()` maps `GenAiException` error code 30
(`BACKGROUND_USE_BLOCKED`) to a retryable `OnDeviceUnavailable` (see `GenAiErrorMapping.kt`
lines 8, 29-32) — this fires from **`generateContent()`**, not from `checkStatus()`. The
`checkStatus()` call used by `checkAvailability()` (lines 43-53 of
`MlKitLlmFormatterProvider.kt`) has no equivalent try/catch for `GenAiException` at all — any
exception from `checkStatus()` is swallowed generically (`catch (e: Exception) { ... null }`,
line 48-51) and mapped through `mapMlKitFeatureStatus(null)`. **Open question requiring
verification during implementation** (flag explicitly in plan.md, don't assume): does ML Kit's
`checkStatus()` itself throw/report `BACKGROUND_USE_BLOCKED` when called from a backgrounded
activity, or is that restriction scoped only to `generateContent()`? If `checkStatus()` is
also gated to foreground, then a 3-5s poll loop that keeps running after the user backgrounds
the app (Android doesn't suspend coroutines on background — `TagSuggestionViewModel.scope` is a
free-standing `Dispatchers.Default` scope, not lifecycle-bound) will either throw repeatedly
(harmless if the generic catch holds) or, worse, silently report a *misleading* status (e.g.
`Preparing`/`Unavailable`) that doesn't reflect why. Either way this is wasted binder/IPC traffic
every 3-5s while backgrounded. **Recommend**: pause polling on `onStop`/background (or at minimum
document as a known gap) rather than assuming `checkStatus()` is side-effect-free in the
background just because `format()`'s foreground restriction is documented.

### 2.2 `DOWNLOADABLE` triggers a real side effect on every poll tick — not idempotent-free
`format()`'s `DOWNLOADABLE` branch (lines 69-78) calls `runCatching { model.generateContent(...)
}` *specifically to trigger the AICore download as a side effect* — the comment says "Without
this call the download never starts." This means **every poll tick that calls `format()` (not
just `checkStatus()`) while status is `DOWNLOADABLE` re-fires `generateContent()`**. If the new
poll loop calls `checkAvailability()` (which only calls `checkStatus()`, not `format()`) that's
fine and side-effect-free per-tick. But if anyone wires the poll loop to call
`LlmTagProvider.suggestTags()` / `format()` directly instead of `checkAvailability()` (e.g. to
"just retry the real request" instead of a lightweight status check), every 3-5s tick would
re-trigger the download-kickoff `generateContent()` call, potentially spamming AICore with
redundant download-trigger requests for the full poll deadline. **The poll loop must call
`checkAvailability()`, never `format()`/`suggestTags()`, until status flips to `AVAILABLE`** —
this is the FR-1 boundary and should be an explicit code-review checklist item.

### 2.3 `BUSY` (error code 9) is retryable but not distinguished from "still downloading"
`GenAiErrorMapping.kt` maps both `BACKGROUND_USE_BLOCKED` and `BUSY` to the *same*
`OnDeviceUnavailable(retryable = true)` shape, just different reason strings. If
`TagSuggestionState`/UI collapses all retryable reasons into one generic "taking longer than
expected" caption (FR-2), a `BUSY` (per-app quota) case reads identically to a genuine download
stall to the user, even though the remediation differs (quota clears in seconds vs. download can
take minutes). Worth deciding in plan.md whether the reason string is surfaced verbatim in the
UI or generalized — losing it entirely repeats exactly the FR-3 bug this project is fixing.

## 3. Test flakiness risk — NFR-3 is already failing today, not just a future risk

**This is the single highest-severity finding.** `TagSuggestionViewModelTest.kt` (lines 45-62)
already documents the problem: `TagSuggestionViewModel` owns `scope = CoroutineScope(SupervisorJob()
+ Dispatchers.Default + ...)` — a **real** dispatcher, not `TestScope`/`StandardTestDispatcher`.
The existing test suite works around this by polling `state.value` in a **real-time** loop
(`delay(20)` against `Clock.System.now()`, wall-clock, 2-5s timeouts) because `runTest`'s virtual
time / `advanceUntilIdle()` has **zero control** over anything running on `scope`. This is called
out explicitly in the test file's own doc comment: "which `advanceUntilIdle` cannot control."

Consequences for this feature:
- A naive `while (elapsed < deadlineMs) { delay(pollIntervalMs); checkAvailability() }` loop added
  to `scope.launch { }` will **not** be virtual-time-controllable by `runTest` — any test exercising
  FR-0/FR-1/FR-2 end-to-end (poll → auto-resolve, or poll → deadline → terminal state) will burn
  real wall-clock seconds. At `DEFAULT_POLL_DEADLINE_MS` likely in the tens-of-seconds-to-minutes
  range (FR-6, pending real measurement), a deadline-exhaustion test could take that long for real,
  or worse, exceed `runTest`'s default dispatch timeout (10s, configurable via
  `kotlinx.coroutines.test.default_timeout` or `runTest(timeout = ...)`) and fail as "test timed
  out" rather than as a meaningful assertion failure.
- **This must be designed around, not tested around.** The clean fix is to make the polling
  mechanism take an injectable clock/delay strategy (e.g. a `PollScheduler`/`suspend fun delay(ms:
  Long)` lambda, or restructure so the poll loop's timing logic is a pure function of elapsed time
  that can be driven by a fake clock in tests, decoupled from `TagSuggestionViewModel`'s
  production `Dispatchers.Default` scope). Do **not** just extend the existing `awaitState(
  timeoutMs = ...)` real-time-polling pattern to cover a multi-minute deadline — that makes the
  business-test suite slow (CI cost) and flaky under load (a busy CI runner can blow past a 5s
  wall-clock budget for reasons unrelated to the code under test).
- Flag as a plan.md decision point: either (a) inject a `TestDispatcher`-compatible scope/clock
  into `TagSuggestionViewModel` for the poll loop specifically (larger refactor, fixes the root
  cause for *all* future timing-sensitive features on this ViewModel, not just this one), or (b)
  keep production on `Dispatchers.Default` but extract poll-loop *decision* logic (when to tick,
  when to escalate caption, when to hit deadline) into a pure/testable unit driven by injected
  elapsed-time, and test only that unit with virtual/fake time — leaving a thin, untested-in-detail
  integration wrapper. (b) is much cheaper and consistent with existing patterns
  (`mapGenAiErrorCode`, `mapMlKitFeatureStatus` are already pure functions extracted for exactly
  this reason — same technique should apply to "what should the poll loop do at elapsed time T".)

## 4. Race conditions: `preload()` vs. the new poll loop vs. `requestSuggestions()`

- `TagSuggestionViewModel.preload()` (line 71-73) is called once from `App.kt` line 1129
  (`LaunchedEffect(tagSuggestionViewModel) { tagSuggestionViewModel?.preload() }`) and internally
  calls `engine.preload()` → `llmTagProvider?.preload()` → `provider.format("", "Ready?")`
  (`LlmTagProvider.kt` line 34). Per §2.2 above, if status is `DOWNLOADABLE`, this **already**
  fires a `generateContent()` side effect to kick off the AICore download, on a totally separate
  `scope.launch` from any `suggestionJob`.
- If the user then opens the suggestion sheet for a block while that `preload()` coroutine is
  still in flight (plausible — preload fires once at app/screen mount, requestSuggestions fires
  per block edit), `requestSuggestions()` starts its own `llmSuggest` → `format()` call, which
  (per §2.2) *also* hits the `DOWNLOADABLE` branch and *also* calls `generateContent()` as a
  trigger. Two concurrent download-trigger calls are not obviously harmful (AICore almost
  certainly dedupes/no-ops a redundant download-start call), but this is exactly the kind of
  "verify, don't assume" item flagged in the requirements — there is no test or comment in this
  codebase confirming AICore's behavior under concurrent `generateContent()` calls while
  `DOWNLOADABLE`.
- Layering the new poll loop on top: if `checkAvailability()` (poll tick) and `preload()`'s
  `format()` call race, and *either* naively calls `format()` again while `DOWNLOADABLE` (see §2.2
  — this is the trap), you get a third concurrent trigger. **Recommend**: the poll loop must be
  strictly read-only (`checkAvailability()`/`checkStatus()` only) and the *only* code path allowed
  to call `format()`/`generateContent()` while non-`AVAILABLE` is the original `preload()` /
  first `requestSuggestions()` invocation — i.e. the download-trigger side effect should fire
  **once**, not on every poll tick and not from multiple call sites simultaneously. This likely
  needs a dedicated single-flight guard (e.g. an app-scoped `AtomicBoolean`/mutex "download
  already triggered this session") rather than relying on each call site independently
  rate-limiting itself.

## 5. UI escalation pitfalls — caption timing must be driven by the poll loop, not a separate `LaunchedEffect` timer

`SuggestionBottomSheet.kt` (lines 67-103) is a `when (state)` render with no internal timers
today — it's purely a function of `TagSuggestionState`. This is good: it means the "~45s caption
change" (FR-2) should be implemented as **another `TagSuggestionState.Ready` field** (or a new
state variant) computed by the poll loop itself (which already tracks elapsed time to decide when
to hit the deadline) and pushed via `_state.update`, not as a `LaunchedEffect(Unit) {
delay(45_000); caption = "..." }` timer living in the composable.

Why the composable-timer approach is a trap here specifically:
- Compose recomposes `SuggestionBottomSheet` on every `state` change already (LLM results
  arriving, local matches, etc.) — a naive `LaunchedEffect(Unit)` keyed on `Unit` inside this
  composable would restart correctly across recompositions (key doesn't change), but if it's
  instead keyed on `state` or `state.blockUuid` (an easy mistake when someone wants "reset the
  45s timer when a new block's sheet opens"), the timer restarts on *every* unrelated state change
  (e.g. local suggestions arriving before LLM does), and 45s becomes "45s since the last
  recomposition", which drifts and can fire multiple times or never fire within the visible
  window.
- The sheet's visibility itself is state-driven (`isVisible = state is Ready || Loading`, line
  33) — if the sheet is dismissed and reopened (`dismiss()` sets `Idle` but does *not* cancel the
  poll job, §1 above / ADR-002) while the poll loop is still running in the background, a
  composable-owned timer would restart from zero on reopen even though the poll loop's real
  elapsed time is, say, 50s already past the 45s mark — showing the "still working" caption
  freshly instead of jumping straight to (or past) the "taking longer than expected" state.
  Driving the caption off the ViewModel's own elapsed-time tracking (single source of truth,
  survives sheet dismiss/reopen) avoids this entirely.
- Corollary: this also naturally satisfies "changes at least once, doesn't jitter" — a
  monotonic-clock-driven state field only transitions forward (never re-triggers on
  recomposition), whereas a `LaunchedEffect` timer re-armed by an unrelated key can fire more than
  once or reset.

## 6. Battery/resource cost of `checkStatus()` polling

- `MlKitLlmFormatterProvider.checkAvailability()` calls `model.checkStatus()`, which per ML Kit's
  architecture is a **binder/IPC call into the on-device AICore service process** (ML Kit Prompt
  API is a Play Services–adjacent bound-service model, not a local in-process check) — this is
  I/O-equivalent cost, not free, though far cheaper than `generateContent()`. No local
  measurement exists in this codebase; treat the "how expensive per call" question as unverified
  and note it needs confirming during implementation (aligns with FR-6's "must happen before
  implementation" instruction, which explicitly names this as a real-hardware measurement task,
  not a guessed constant).
- **Fixed 3-5s interval vs. exponential backoff** — FR-0 specifies fixed 3-5s, so this is not a
  decision point for scope, but worth recording the tradeoff explicitly in plan.md so it's a
  documented, deliberate choice rather than an oversight: a fixed interval for a deadline that
  could be tens of seconds to minutes (FR-6) means potentially 10-40+ `checkStatus()` binder calls
  per pending suggestion sheet, all while the phone may be screen-on/foreground (per §2.1,
  backgrounding likely should pause polling anyway). An exponential backoff (e.g. 3s → 5s → 8s →
  13s, capped) would cut call count roughly in half over a 60s window with negligible UX cost
  (early ticks matter most for responsiveness; later ticks matter far less once the user's
  attention has likely moved on). Since FR-0 pins fixed 3-5s, this is a "flag as a known deviation
  from best practice, revisit later" note rather than a required design change — but the plan
  should say *why* fixed was chosen (e.g. "AICore downloads are unpredictable in duration, backoff
  doesn't meaningfully help vs. added complexity") rather than leaving the tradeoff undiscussed.
- Multiple concurrent suggestion sheets are not possible today (`activeBlockUuid` is a single
  field, one poll loop can exist at a time per the fix in §1), so total worst-case concurrent
  polling is bounded to 1 loop — no fan-out risk.

## Summary of must-address items for plan.md

1. Poll `Job` must be nested inside/parented by `suggestionJob` (or cancelled at every site
   `suggestionJob` is), not a sibling field — otherwise block-switch leaves a stale poll loop
   running and auto-resolving into the wrong block's cache/state.
2. Poll loop calls `checkAvailability()` only — never `format()`/`suggestTags()` — to avoid
   re-triggering `generateContent()`'s download-kickoff side effect on every tick.
3. Verify (real device, not assumed) whether `checkStatus()` is subject to
   `BACKGROUND_USE_BLOCKED`-equivalent restrictions when backgrounded; pause/stop polling on
   background regardless as a defensive default.
4. Poll-loop timing logic must be a pure, elapsed-time-driven function (mirroring
   `mapGenAiErrorCode`/`mapMlKitFeatureStatus`'s existing pure-function extraction pattern) so it
   is unit-testable with a fake clock — do NOT rely on `runTest`/`advanceUntilIdle` against
   `TagSuggestionViewModel.scope` (confirmed non-controllable today, see
   `TagSuggestionViewModelTest.awaitState`'s own doc comment) or on real-time `delay()` loops in
   tests, which will be slow and can exceed `runTest`'s default timeout at
   `DEFAULT_POLL_DEADLINE_MS` scale.
5. Single-flight guard around the download-trigger side effect so `preload()`, the first
   `requestSuggestions()`, and poll-loop ticks can't independently double/triple-trigger
   `generateContent()` while `DOWNLOADABLE`.
6. Caption escalation (~45s) must be a `TagSuggestionState` field set by the poll loop's own
   elapsed-time tracking, not a `LaunchedEffect` timer in `SuggestionBottomSheet` — avoids
   drift/jitter/reset-on-recompose and reset-on-dismiss-reopen bugs.
7. `DEFAULT_POLL_DEADLINE_MS` needs a real-device measurement (FR-6) — no existing constant in
   this codebase to anchor a guess against; do this before locking the deadline value into
   plan.md/tests.
