# Implementation Plan: On-Device LLM Tag Suggestion — Download Stall

## Summary

When Gemini Nano (Android on-device LLM via ML Kit/AICore) is `DOWNLOADABLE`/`DOWNLOADING`,
`MlKitLlmFormatterProvider.format()` returns a single retryable failure and nothing ever
re-checks. `LlmTagProvider.suggestTags()` then drops the `retryable` flag on the floor, and
`TagSuggestionViewModel` freezes the suggestion sheet on that one caption forever. This
plan: (1) threads `retryable` through `DomainError.NetworkError.RequestFailed` and
`TagSuggestionState` end-to-end instead of dropping it; (2) adds a bounded,
elapsed-time-driven poll loop (`TagAvailabilityPoller.pollUntilAvailable`) — a stateless
collaborator mirroring the existing `GitHubDeviceFlowClient.pollForToken` shape so it is
directly unit-testable with `kotlinx.coroutines.test.runTest` virtual time, sidestepping
`TagSuggestionViewModel`'s real `Dispatchers.Default` scope; (3) replaces the flat
`llmPending`/`llmError` pair on `TagSuggestionState.Ready` with a sealed
`LlmSuggestionStatus` (`NotStarted`/`Pending`/`Resolved`/`Stalled`/`Failed`) so illegal
combinations (e.g. "pending AND has a retry button") are unrepresentable; (4) adds a
manual-retry affordance to `SuggestionBottomSheet` wired to `retryable`; (5) opts the bulk
`scanEntries()` path out of polling via a new `allowPolling` parameter threaded through one
shared helper (`runLlmSuggest`) both call paths use, so fail-fast bulk-scan timing is a
single, greppable, tested code path rather than a special case.

Story 4.6 (`downloadFirstObservedAtMs`/`startedAtOverride`, Tasks 4.6.1/4.6.2) was added
during Phase 4's pre-mortem gate, not this plan's original Phase 3 scope — it fixes a real
elapsed-time-reset gap the initial plan missed (a block-switch-and-return or a manual retry
was silently restarting the poll's escalation/deadline clock from zero instead of treating
it as continuous), not a cosmetic addition.

`DEFAULT_POLL_DEADLINE_MS = 120_000L` is set from desk research (no physical AICore
hardware was available this planning session) — see **ADR-001**
(`project_plans/llm-tag-download-stall/decisions/ADR-001-poll-deadline-estimate.md`) for
sources and reasoning, and its mandatory real-hardware re-validation follow-up. **ADR-002**
(`project_plans/llm-tag-download-stall/decisions/ADR-002-dismiss-does-not-cancel-poll-loop.md`)
documents why sheet dismiss deliberately does not hard-cancel the loop (FR-5's named
deviation).

---

## Domain Glossary

| Term | Definition |
|---|---|
| **`LlmSuggestionStatus`** | New sealed interface on `TagSuggestionState.Ready` replacing the flat `llmPending: Boolean`/`llmError: String?` pair. Cases: `NotStarted` (no request made yet — transient, never actually cached), `Pending(caption: String? = null)` (LLM call in flight or poll loop active; `caption` is `null` until a caption string is known, then the SDK-sourced reason or the 45s-escalated string), `Resolved` (terminal success — real results or an explicit empty list), `Stalled(retryable: Boolean)` (poll deadline reached, i.e. `DomainError.NetworkError.RequestFailed.retryable == true` — the on-device-availability signal; distinct from `Failed` because retry always makes sense here by definition, so `retryable` is always `true` in practice), `Failed(message: String, retryable: Boolean)` (a hard provider failure unrelated to on-device availability — `retryable` is genuinely computed per Task 4.2.1, not hardcoded: `true` for `DomainError.NetworkError.Timeout` (plausibly transient), `false` for `HttpError`, content-rejection-mapped `RequestFailed`, generic-exception-mapped `RequestFailed`, or a genuinely-unsupported-device `Unavailable(retryable=false)`). |
| **`DEFAULT_POLL_INTERVAL_MS`** | `4_000L`. Midpoint of the FR-0 3–5s polling-interval requirement. Lives in `TagAvailabilityPoller`'s companion. |
| **`DEFAULT_POLL_DEADLINE_MS`** | `120_000L` (2 minutes). Wall-clock bound on the *interactive* poll loop, per ADR-001 — not the full model-download time (which is much longer and continues in the background regardless, per ADR-002). |
| **`CAPTION_ESCALATION_THRESHOLD_MS`** | `45_000L`. Fixed by FR-2/AC2 verbatim. When elapsed poll time crosses this threshold, the caption changes exactly once from the initial SDK-sourced reason to an escalated "still downloading" string. |
| **`allowPolling`** | New `Boolean` parameter on `TagSuggestionViewModel.requestSuggestions()` (default `true`) and the shared private `runLlmSuggest()` helper. `scanEntries()` passes `false` explicitly (FR-7) to preserve today's fail-fast-per-entry bulk-scan timing. |
| **`retryable`** | Existing field on `LlmResult.Failure.OnDeviceUnavailable` and `LlmProviderAvailability.Unavailable`. This project adds it (additively, default `false`) to `DomainError.NetworkError.RequestFailed` so it survives the `LlmTagProvider.suggestTags()` → `TagSuggestionEngine.llmSuggest()` → `TagSuggestionViewModel` boundary instead of being dropped. |
| **`Preparing` / `Available` / `Unavailable`** | Existing cases of `LlmProviderAvailability` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt`) — unchanged by this project, defined here only for glossary completeness since the poll loop is written directly against this tri-state. |
| **`TagAvailabilityPoller`** | New object (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagAvailabilityPoller.kt`) holding the poll constants and the stateless `suspend fun pollUntilAvailable(checkAvailability, onStatusUpdate, ...): LlmProviderAvailability`. Mirrors `GitHubDeviceFlowClient.pollForToken`'s shape: no owned `CoroutineScope`, takes the probe and callback as parameters, callable directly under `runTest` virtual time. |
| **`runLlmSuggest`** | New private suspend helper on `TagSuggestionViewModel` — the single call site both `requestSuggestions()` (`allowPolling=true`) and `scanEntries()` (`allowPolling=false`) route through. Makes the first `engine.llmSuggest()` attempt; if it fails with a retryable `RequestFailed` *and* polling is allowed *and* an availability probe is wired, hands off to `TagAvailabilityPoller.pollUntilAvailable` and re-attempts once `Available` is observed. |
| **`retryLastRequest()`** | New public method on `TagSuggestionViewModel`. Re-invokes the most recently stored `requestSuggestions()` arguments — the FR-3 manual-retry affordance's call target. No new per-call-site retry wiring needed beyond `onRetry = { tagSuggestionViewModel.retryLastRequest() }`. |
| **`checkAvailability` (on `TagSuggestionEngine`)** | New optional constructor property `val checkAvailability: (suspend () -> LlmProviderAvailability)? = null`. Defaults to `null` so the 3 existing test-construction sites (`TagSuggestionViewModelTest.kt:145`, `TagSuggestionEngineTest.kt:50`, `TagInsertionFlagshipUiTest.kt:73`) keep compiling and keep today's non-polling behavior unchanged. `App.kt` wires it from `LlmProvider.checkAvailability()`. |
| **`dispatcher` (on `TagSuggestionViewModel`)** | New optional constructor parameter `private val dispatcher: CoroutineDispatcher = Dispatchers.Default`, used to build `scope` (`CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler {...})`). Exists solely so `TagSuggestionViewModelTest.kt` can inject `StandardTestDispatcher(testScheduler)` — built from `runTest`'s own `TestScope.testScheduler` — so the VM's independently-owned scope shares the test's `TestCoroutineScheduler` and its `delay()` calls fast-forward under `advanceUntilIdle()`. No production call site (`App.kt`) passes a non-default value (NFR-3; see Blocker-1 fix). |
| **`pollDeadlineMs` / `pollIntervalMs` / `pollEscalationThresholdMs` (on `TagSuggestionViewModel`)** | New optional `Long` constructor parameters defaulting to `TagAvailabilityPoller.DEFAULT_POLL_DEADLINE_MS` / `DEFAULT_POLL_INTERVAL_MS` / `CAPTION_ESCALATION_THRESHOLD_MS` respectively. Forwarded into every `TagAvailabilityPoller.pollUntilAvailable(...)` call inside `runLlmSuggest` (Task 4.1.2). Exists so tests can shrink the poll deadline/interval to millisecond scale instead of exercising the full 120s/4s/45s production values — this is what makes Task 4.4.4 (own-deadline termination) and Story 4.5's test (format-called-at-most-twice across a full poll cycle) complete in milliseconds of both real and virtual time instead of ~120s/~20s of real sleep (NFR-3). |
| **`isSuggestionJobActiveForTest` (on `TagSuggestionViewModel`)** | New `internal`-visibility test-only accessor: `internal val isSuggestionJobActiveForTest: Boolean get() = suggestionJob?.isActive == true`. Mirrors the existing `FountainDecoder.mixedPartsCountForTest` precedent (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainDecoder.kt:45`) for exposing private mutable state to same-module tests without weakening `suggestionJob`'s `private` visibility for production callers. Lets Task 4.4.4 assert the coroutine itself terminates on its own, not just that `_state`'s value reached a terminal case. |
| **`downloadFirstObservedAtMs` (on `TagSuggestionViewModel`)** | New `private var downloadFirstObservedAtMs: Long? = null` field (Task 4.1.1). A session-scoped "when did THIS APP SESSION first observe the on-device model as not-yet-available" timestamp — set ONCE, the first time any `runLlmSuggest` call observes a retryable-unavailable/preparing signal for the on-device model in this ViewModel's lifetime, and NEVER reset by a block-switch or a manual retry. Only cleared back to `null` when `engine.llmSuggest()` actually succeeds (`Either.Right`) — the model becoming genuinely `Available`. In-memory-only state is intentional and sufficient: the on-device model download is a single, app-wide singleton resource (there is only ever one Gemini Nano download in flight, regardless of which block or how many times the user asks), and a fresh future download (app restart, reinstall) naturally starts a new `TagSuggestionViewModel` instance anyway — consistent with how `LlmProvider.checkAvailability()` is already documented elsewhere in this codebase as "live, never cached, on-device eligibility can flip mid-session." Threaded into `TagAvailabilityPoller.pollUntilAvailable` as the new `startedAtOverride` parameter (Task 3.1.1) so a second-or-later poll invocation (block-switch-and-return, or manual retry) computes its 45s-escalation/120s-deadline math relative to the ORIGINAL first-observed time, not a fresh "now" — this is the fix for pre-mortem P1 #1 and P1 #2 (see the new Pattern Decisions row and Stories 3.2/4.6). |

---

## Pattern Decisions

| Decision | Chosen Pattern | Alternative Rejected | Reason |
|---|---|---|---|
| Where does the poll loop live? | `TagSuggestionViewModel` owns a private `runLlmSuggest()` helper that delegates ticking to the stateless `TagAvailabilityPoller` collaborator | (a) Inside `LlmTagProvider.suggestTags()`; (b) inside `TagSuggestionEngine.llmSuggest()` | (a) `LlmTagProvider` is constructed with only `LlmFormatterProvider` — no `checkAvailability()` access; widening it touches 3 test-construction sites for a narrow, single-consumer need. (b) `TagSuggestionEngine` has no incremental-state emission mechanism (`llmSuggest()` is one suspend call); FR-2's 45s caption escalation needs a callback mid-call, which only the ViewModel layer (which already owns `_state`) can usefully consume. |
| How does the poll loop get unit-tested under virtual time (NFR-3)? | Extract as a **stateless top-level object function** (`TagAvailabilityPoller.pollUntilAvailable`, no owned scope) callable directly under `runTest`, mirroring `GitHubDeviceFlowClient.pollForToken` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt:96-140`, proven pattern — see `GitHubDeviceFlowClientTest.kt`) | A private method on `TagSuggestionViewModel` tested by driving the real `TagSuggestionViewModel` end-to-end | `TagSuggestionViewModelTest.kt`'s own `awaitState` helper already documents in-code that `advanceUntilIdle`/virtual time has zero effect on `TagSuggestionViewModel.scope` (real `Dispatchers.Default`). A method embedded in the VM inherits that real-dispatcher problem; a top-level function taking its scope from the *caller* (the test, via `runTest`) does not. |
| Illegal-state representation for LLM suggestion status | Sealed `LlmSuggestionStatus` (`NotStarted`/`Pending`/`Resolved`/`Stalled`/`Failed`) replacing `llmPending: Boolean` + `llmError: String?` on `TagSuggestionState.Ready` | Keep the flat boolean/nullable-string pair and add a third flag (e.g. `llmStalled: Boolean`) | Flat fields would leave `Ready` with unenforced invariants (e.g. `llmPending=true` and `llmError!=null` simultaneously is meaningless but compiles). Matches existing codebase precedent for exactly this situation — `LlmProviderAvailability`, `LlmResult`, `BulkScanState` are all small sealed hierarchies chosen for "illegal states unrepresentable." Ripple cost (4 files: `TagChipRow.kt`, `SuggestionBottomSheet.kt`, `VoiceCaptureButton.kt`, `ErrorStateNoDeadEndTest.kt`) is small and mechanical — verified via grep of every `llmPending`/`llmError` reference in the tree (see Epic 6). |
| Where does `retryable` get threaded from `LlmResult.Failure.OnDeviceUnavailable` to the UI? | Additive field on `DomainError.NetworkError.RequestFailed` (`retryable: Boolean = false`) **and** a mirrored field on the UI-facing `LlmSuggestionStatus` cases — both, not either/or | Only add it to `TagSuggestionState`, leaving `DomainError` unchanged | Closes a TOCTOU race: the poll loop can observe `Available`, but by the time `engine.llmSuggest()` actually calls `format()`, the model can regress (quota hit, backgrounded, AICore reset) and return a *fresh* `OnDeviceUnavailable` from that specific call — this only reaches the ViewModel through `llmSuggest()`'s `Either<DomainError, _>` return, so `DomainError` must carry `retryable` too or the manual-retry UI would be wrong in that window. `DomainError.GitError.RateLimited(val retryAfterSeconds: Int?)` is existing precedent for a `DomainError` case embedding retry metadata. |
| Real progress via `GenerativeModel.download(): Flow<DownloadStatus>` (stack.md's major finding) vs. uniform `checkAvailability()` interval polling | **Uniform `checkAvailability()` polling** (as FR-0 literally specifies) | Android-specific `model.download()` `Flow` collection for real byte-level progress | `model.download()` is Android/ML-Kit-specific — using it would mean either (a) special-casing Android inside a commonMain poll loop (violates NFR-2's "platform-agnostic where possible," and the loop is written directly against the already-platform-agnostic `LlmProviderAvailability` tri-state), or (b) a parallel Android-only progress path alongside the uniform one, roughly doubling this bug-fix-shaped project's scope for a UX improvement (a progress bar instead of a spinner+caption) that the requirements do not ask for. Recorded here as a conscious, explicit deferral — not an oversight — and could be a good follow-up project once the uniform fix has shipped and been validated against real hardware (ADR-001's follow-up). |
| Manual-retry call target | `TagSuggestionViewModel.retryLastRequest()` — stores the last `requestSuggestions()` args, re-invokes them; `SuggestionBottomSheet` gets one new `onRetry: () -> Unit` param wired at both call sites to `{ tagSuggestionViewModel.retryLastRequest() }` | Have each call site (`JournalsView.kt`, `PageView.kt`) re-supply `blockUuid`/`blockContent`/`alreadyLinkedTerms` to a bespoke retry callback | The content/terms a retry needs are already known at the *original* request time and don't change between then and a retry tap (the user hasn't edited the block from inside the stalled sheet) — storing them once in the VM avoids duplicating capture logic at both `SuggestionBottomSheet` call sites and matches the EventStorming finding that retry is "just re-invoke `requestSuggestions()`," not a new code path. |
| Should the poll loop's elapsed-time math reset on every relaunch (block-switch, manual retry)? | **No.** A session-scoped `downloadFirstObservedAtMs: Long?` field on `TagSuggestionViewModel` (Task 4.1.1) is set ONCE — the first time this VM instance ever observes a retryable-unavailable signal for the on-device model — and threaded into every subsequent `TagAvailabilityPoller.pollUntilAvailable` call as `startedAtOverride` (Task 3.1.1), only cleared when the model actually resolves `Available`. A relaunch's 45s-escalation/120s-deadline math is therefore always relative to the ORIGINAL first-observed time. | Reset elapsed time on every poll invocation (compute `startedAt = Clock.System.now()` fresh on every `pollUntilAvailable` call, the plan's original design) | Defeats FR-2's escalation/terminal-state UX for realistic multi-minute-to-tens-of-minutes downloads under normal usage patterns. Pre-mortem P1 #1: a user who bounces between blocks during a download never accumulates enough continuous dwell-time on ONE block to reach the 45s/120s thresholds — each visit silently resets to the cold-start caption, reproducing the original "stuck forever" bug via a different path. Pre-mortem P1 #2: for ADR-001's own cited 15–30 minute realistic total download time, a user retrying every ~2 minutes sees the identical cold-start caption sequence 7–15 times with zero cumulative-progress signal. The on-device model download is a single, app-wide singleton resource (only one Gemini Nano download in flight regardless of block/retry count), so "elapsed time since this session first observed it downloading" is the correct clock to measure against — not "elapsed time since the most recent poll-loop invocation started." |
| How do Epic 4's VM-level tests reach deadline-crossing states without ~120s/~20s of real sleep (NFR-3)? | Add optional `dispatcher: CoroutineDispatcher = Dispatchers.Default` and `pollDeadlineMs`/`pollIntervalMs`/`pollEscalationThresholdMs` constructor params to `TagSuggestionViewModel` (Task 4.1.1), all defaulting to production values; tests construct the VM inside `runTest { ... }` with `dispatcher = StandardTestDispatcher(testScheduler)` (sharing `runTest`'s own `TestScope.testScheduler`) plus millisecond-scale deadline/interval overrides | (a) Leave `TagSuggestionViewModel.scope` hardcoded to `Dispatchers.Default` and accept ~120s/~20s of real sleep per Task 4.4.4/Story 4.5 run; (b) rewrite `TagSuggestionViewModel` to not own a scope at all, fully stateless like `TagAvailabilityPoller` | (a) directly violates NFR-3 and would make Epic 4's test suite alone add ~140s to every CI run. (b) is not viable — the VM's scope must outlive individual suspend calls (ADR-002: dismiss does not cancel the poll loop; `cache` survives across `requestSuggestions()` calls), so it cannot be a pure stateless function the way `TagAvailabilityPoller` is. Building a `StandardTestDispatcher` off the test's own `testScheduler` and injecting it into the VM constructor is the standard `kotlinx-coroutines-test` idiom for putting an independently-scoped collaborator under virtual-time control (shared `TestCoroutineScheduler`); this mirrors, at the VM layer, the same virtual-time-testability goal Epic 3's Pattern Decision already established for `TagAvailabilityPoller` at the collaborator layer. |
| Which retryable `DomainError`s become `Stalled` vs a retryable `Failed`? | In Task 4.2.1's `ifLeft` branch: `DomainError.NetworkError.RequestFailed` instances with `retryable = true` → `LlmSuggestionStatus.Stalled(retryable = true)` (the on-device-availability signal — this is the only thing `RequestFailed.retryable` ever means, per the row above); `DomainError.NetworkError.Timeout` → `LlmSuggestionStatus.Failed(message = err.message, retryable = true)`; everything else (`HttpError`, `ContentRejected`-mapped `RequestFailed`, generic-exception-mapped `RequestFailed`, all with `retryable = false`) → `Failed(message = err.message, retryable = false)` | A single flat `retryable` boolean deciding `Stalled` vs `Failed` with `Failed` always hardcoded to `retryable = false` (the plan's first draft) | The first draft made `LlmSuggestionStatus.Failed.retryable` structurally dead code — `Failed` was only ever constructed with a literal `false` — while a genuine `DomainError.NetworkError.Timeout` (plausibly transient: a slow network round-trip, not a model-availability condition) collapsed to a dead-end caption with no retry affordance, contradicting AC3's literal "stalled/failed" wording. `Timeout` is deliberately kept out of `Stalled` rather than folded into the same flat check, because `Stalled`'s definition (Domain Glossary) is specifically "poll deadline reached" / on-device-model-availability — routing a completed-but-failed HTTP round-trip through that state would render a misleading "still downloading" caption for an error that has nothing to do with downloading. |
| Does the poll loop activate for iOS once/if an iOS on-device `LlmProvider` is registered? | **Yes — deliberately, as an ACCEPTED consequence, not gated off.** The poll loop (Epic 3/4) is written directly against the platform-agnostic `LlmProviderAvailability` tri-state per NFR-2 — that is the explicit point of NFR-2's "platform-agnostic where possible" framing, and Epic 1's `checkAvailability` wiring through `LlmProvider.checkAvailability()` has zero platform gating by design. `IosAvailabilityMapping.kt` already has a `Preparing` case today, so this mechanism will technically activate for iOS the instant an iOS on-device `LlmProvider` is registered as the active tag-suggestion provider — with `DEFAULT_POLL_DEADLINE_MS`/`CAPTION_ESCALATION_THRESHOLD_MS`/caption copy whose evidentiary basis (ADR-001) is Android/AICore-specific and not validated for iOS's actual on-device download characteristics. | Add an iOS/Android platform gate around poll-loop activation (e.g. an `if (Platform.isAndroid)` check in `runLlmSuggest` or `TagAvailabilityPoller`) so the loop provably cannot reach iOS until this project explicitly revisits it | `requirements.md`'s Out-of-Scope wording — "do not add polling infrastructure [for iOS] speculatively" — refers to not writing iOS-SPECIFIC code/UX for this project, not to preventing the already-platform-agnostic mechanism from technically reaching iOS if/when it's wired up elsewhere. Adding a platform gate would itself be net-new iOS-specific code this project has no mandate to write, test, or validate, and would contradict NFR-2's explicit design goal. Flagged by the adversarial review as a Concern; reconciled here as a deliberate, accepted consequence of NFR-2's design — not a contradiction requiring a code change — recorded so a future iOS on-device integration knows to re-validate `DEFAULT_POLL_DEADLINE_MS`/copy for iOS's own download characteristics before shipping, rather than silently inheriting Android's numbers. |

---

## Epic 0: FR-6 Gate — Poll-Deadline Estimate (COMPLETE, prerequisite for Epic 3)

**Goal**: Satisfy FR-6/AC6 before any task below references `DEFAULT_POLL_DEADLINE_MS`.

**Status**: Already done as part of this planning session (no physical AICore hardware was
available in this environment; a desk-research-based interim estimate was substituted as a
pragmatic necessity — **not** because `requirements.md` authorizes a fallback, which it does
not: FR-6/AC6's text has no fallback clause. The fallback instructions came directly from the
planning-session coordinator as special session context outside `requirements.md`. See
ADR-001's "Attribution correction" for the full framing. Read strictly, FR-6/AC6 remain only
partially satisfied — a sourced interim value exists; a real physical-hardware measurement
does not — until ADR-001's mandatory real-hardware re-validation follow-up happens).

**Files**:
- `project_plans/llm-tag-download-stall/decisions/ADR-001-poll-deadline-estimate.md` (written)

#### Task 0.1: Confirm ADR-001 before starting Epic 3

**Task 0.1.1**: Before implementing `TagAvailabilityPoller` (Epic 3), read
`project_plans/llm-tag-download-stall/decisions/ADR-001-poll-deadline-estimate.md` and
confirm `DEFAULT_POLL_DEADLINE_MS = 120_000L` is the value to hard-code — this is a
read-only gate task, not a code change. If real AICore hardware has since become available,
prefer a real measurement over the desk-research estimate and update ADR-001's status
before proceeding.

**Given-When-Then for AC6**: **Given** ADR-001 documents that no physical AICore-capable
device was available during planning and cites Google's official ML Kit AICore Developer
Preview docs ("downloading models can take a few minutes") plus corroborating secondary
sources and the existing in-repo `Preparing` detail string ("15–30 minutes on first use")
as the evidence base, **When** `TagAvailabilityPoller.kt` (Epic 3, Task 3.1.1) is written,
**Then** `DEFAULT_POLL_DEADLINE_MS = 120_000L` is hard-coded with an inline comment pointing
at `ADR-001-poll-deadline-estimate.md` rather than an unexplained magic number — satisfying
AC6's "set from that data rather than an unvalidated guess" requirement via a documented,
sourced interim value plus ADR-001's mandatory real-hardware re-validation follow-up (not a
literal on-device measurement, which this planning session's environment could not
produce).

---

## Epic 1: Thread `retryable` through the domain layer (INDEPENDENT)

**Goal**: Stop dropping `LlmResult.Failure.OnDeviceUnavailable.retryable` at the
`DomainError` boundary (`research/stack.md` finding #1), and wire a narrow
`checkAvailability` probe into `TagSuggestionEngine` for the poll loop to use later.

**Dependency**: INDEPENDENT — no other epic must land first. Epics 2–3 do not need this to
compile, but Epic 4 (ViewModel wiring) needs both Epic 1 and Epic 2.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

#### Story 1.1: Additive `retryable` field on `DomainError.NetworkError.RequestFailed`

**Task 1.1.1**: In `DomainError.kt` line 49, change
`data class RequestFailed(override val message: String) : NetworkError` to
`data class RequestFailed(override val message: String, val retryable: Boolean = false) : NetworkError`.
The default keeps all 14 existing call sites (`CustomOpenAiCompatibleLlmProvider.kt`,
`WaybackMachineService.kt`, `LlmSynthesisService.kt`, `LlmTagProvider.kt`,
`DomainErrorTest.kt`) compiling with zero changes required to them.

**Task 1.1.2**: Run `./gradlew jvmTest --tests "dev.stapler.stelekit.error.DomainErrorTest"`
to confirm the existing tests still pass unmodified.

#### Story 1.2: `LlmTagProvider` stops dropping `retryable`

**Task 1.2.1**: In `LlmTagProvider.kt` lines 59-61, change:
```kotlin
is LlmResult.Failure.OnDeviceUnavailable -> DomainError.NetworkError.RequestFailed(
    result.reason
).left()
```
to:
```kotlin
is LlmResult.Failure.OnDeviceUnavailable -> DomainError.NetworkError.RequestFailed(
    result.reason, retryable = result.retryable
).left()
```
Leave every other branch of the `when` (lines 50-69) unchanged — this project does not
touch `ApiError`/`NetworkError`/`ContentRejected` mapping.

**Task 1.2.2**: Add a unit test to `TagSuggestionEngineTest.kt` (or a new
`LlmTagProviderTest.kt` if one does not already assert on `DomainError` shape — check
first) asserting that a fake `LlmFormatterProvider.format()` returning
`LlmResult.Failure.OnDeviceUnavailable("Downloading on-device model — this may take a few minutes", retryable = true)`
produces `DomainError.NetworkError.RequestFailed(message = "Downloading on-device model — this may take a few minutes", retryable = true)`
from `LlmTagProvider.suggestTags()` — not `retryable = false`. This is the direct
regression test for the bug named in the Root Cause section of requirements.md.

#### Story 1.3: `TagSuggestionEngine` gets an optional `checkAvailability` probe

**Task 1.3.1**: In `TagSuggestionEngine.kt`, add a new constructor parameter after
`vocabularyProvider` (line 16):
```kotlin
/**
 * Lightweight, SDK-independent availability probe. Null when no provider is wired (fast
 * path, cloud-only providers) or the provider offers no availability check. Narrow
 * function type — not the full `LlmProvider` — so `LlmTagProvider`'s own contract stays
 * unchanged. `TagSuggestionViewModel.runLlmSuggest` uses this ONLY for lightweight
 * checkAvailability() polling — never to trigger inference (see pitfall #2 in this
 * project's research/pitfalls.md).
 */
val checkAvailability: (suspend () -> dev.stapler.stelekit.llm.LlmProviderAvailability)? = null,
```
Keep it a public `val` (not `private`) so `TagSuggestionViewModel` can pass it directly
into `TagAvailabilityPoller.pollUntilAvailable` without an extra wrapper method. Import
`dev.stapler.stelekit.llm.LlmProviderAvailability` at the top of the file instead of using
the fully-qualified name inline if preferred — either compiles.

**Task 1.3.2**: In `App.kt`, at the `TagSuggestionEngine(...)` construction (currently
lines 1106-1109), add the new argument:
```kotlin
else TagSuggestionEngine(
    pageNameIndex = viewModel.pageNameIndex,
    llmTagProvider = tagLlmProviderState.value?.let { LlmTagProvider(it.formatter) },
    checkAvailability = tagLlmProviderState.value?.let { p -> { p.checkAvailability() } },
)
```
`tagLlmProviderState.value` is already a `dev.stapler.stelekit.llm.LlmProvider?`
(line 1088) which already exposes `suspend fun checkAvailability(): LlmProviderAvailability`
(`LlmProvider.kt:33`) — no new dependency, just threading an existing capability one level
further down. The `remember` key list on line 1104
(`viewModel.pageNameIndex, tagSettings.isEnabled(), tagLlmProviderState.value`) does not
need to change — `tagLlmProviderState.value` is already a key, so the engine is already
correctly rebuilt when the provider changes.

**Task 1.3.3**: Run `./gradlew jvmTest --tests "dev.stapler.stelekit.tags.*"` to confirm
the 3 existing test-construction sites (`TagSuggestionViewModelTest.kt:145`,
`TagSuggestionEngineTest.kt:50`, `TagInsertionFlagshipUiTest.kt:73`) still compile
unmodified — they omit the new parameter and get the default `null`.

---

## Epic 2: `LlmSuggestionStatus` sealed type on `TagSuggestionState.Ready` (INDEPENDENT)

**Goal**: Replace `llmPending: Boolean` / `llmError: String? ` with a sealed
`LlmSuggestionStatus`, per the type-driven-design Pattern Decision above.

**Dependency**: INDEPENDENT of Epic 1 to write, but Epic 4 needs both to compile.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionState.kt`

#### Story 2.1: Define `LlmSuggestionStatus` and rewrite `Ready`

**Task 2.1.1**: Replace the full contents of `TagSuggestionState.kt` with:
```kotlin
package dev.stapler.stelekit.tags

sealed interface TagSuggestionState {
    data object Idle : TagSuggestionState
    data object Loading : TagSuggestionState
    data class Ready(
        val blockUuid: String,
        val localSuggestions: List<TagSuggestion>,
        val llmSuggestions: List<TagSuggestion>,
        val llmStatus: LlmSuggestionStatus = LlmSuggestionStatus.NotStarted,
    ) : TagSuggestionState
    data class Error(val message: String) : TagSuggestionState
}

/**
 * Replaces the former flat `llmPending: Boolean` / `llmError: String?` pair on
 * [TagSuggestionState.Ready] — see project_plans/llm-tag-download-stall for the bug this
 * fixes (a frozen "Downloading..." caption with no retry path) and the Pattern Decisions
 * table for why this is a sealed type rather than more flat fields.
 */
sealed interface LlmSuggestionStatus {
    /** Transient — before the first `requestSuggestions()` call for a block resolves its initial state. */
    data object NotStarted : LlmSuggestionStatus

    /** LLM call in flight, or the availability poll loop is active. [caption] is `null` until a
     *  caption string is known (the SDK-sourced reason, then the 45s-escalated string). */
    data class Pending(val caption: String? = null) : LlmSuggestionStatus

    /** Terminal success — real results, or an explicit empty-results outcome. */
    data object Resolved : LlmSuggestionStatus

    /** Poll deadline reached (FR-2) without the model becoming available. Always surfaces a
     *  retry affordance when [retryable] — reaching this state at all implies retry makes sense. */
    data class Stalled(val retryable: Boolean) : LlmSuggestionStatus

    /** A hard provider failure unrelated to on-device availability polling. [retryable] is a
     *  real, non-dead field (see Task 4.2.1): `true` for a `DomainError.NetworkError.Timeout`
     *  (plausibly transient), `false` for an HTTP error, content rejection, or a
     *  genuinely-unsupported-device `Unavailable(retryable=false)`. */
    data class Failed(val message: String, val retryable: Boolean) : LlmSuggestionStatus
}
```

**Task 2.1.2**: Run `./gradlew jvmTest` (compile-only expectation at this point — this task
intentionally breaks 4 downstream files; Epics 5 and 6 fix them). Confirm the compiler
errors are exactly the expected set: `TagChipRow.kt`, `SuggestionBottomSheet.kt`,
`VoiceCaptureButton.kt`, `TagSuggestionViewModel.kt`, `ErrorStateNoDeadEndTest.kt`. If any
other file fails to compile, grep `\.llmPending\b\|\.llmError\b` again — it means a
reference was missed during planning.

---

## Epic 3: `TagAvailabilityPoller` — stateless, virtual-time-testable poll loop (DEPENDS-ON-EPIC-0, DEPENDS-ON-EPIC-2)

**Goal**: Implement the bounded, elapsed-time-driven poll loop as a new standalone
collaborator, satisfying FR-0/FR-2 and NFR-3.

**Dependency**: DEPENDS-ON-EPIC-0 (needs `DEFAULT_POLL_DEADLINE_MS`'s value from ADR-001)
and DEPENDS-ON-EPIC-2 (its callback signature uses `LlmSuggestionStatus`).

**Files to create**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagAvailabilityPoller.kt`

**Files to create (tests)**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagAvailabilityPollerTest.kt`

**File to read for the pattern**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt:96-140`
  (`pollForToken` — same wall-clock-deadline-`while`-loop-with-`delay`-first shape)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClientTest.kt`
  (proves `runTest` fast-forwards `delay()` inside a directly-called stateless suspend fn
  with zero injected dispatcher/clock)

#### Story 3.1: Implement `pollUntilAvailable`

**Task 3.1.1**: Create `TagAvailabilityPoller.kt`:
```kotlin
package dev.stapler.stelekit.tags

import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Stateless, wall-clock-bounded poll loop over an [LlmProviderAvailability] probe. Mirrors
 * GitHubDeviceFlowClient.pollForToken's shape (kmp/src/commonMain/kotlin/dev/stapler/
 * stelekit/git/GitHubDeviceFlowClient.kt:96-140) deliberately: a plain suspend function
 * with no owned CoroutineScope, so it is directly unit-testable under
 * kotlinx.coroutines.test.runTest with virtual time instead of fighting
 * TagSuggestionViewModel's real Dispatchers.Default scope (NFR-3).
 *
 * Pitfall #2 (research/pitfalls.md): [checkAvailability] MUST be a lightweight status
 * probe only (LlmProvider.checkAvailability() / MlKitLlmFormatterProvider.checkAvailability())
 * — NEVER the suggestion/format() call. format()'s DOWNLOADABLE branch fires
 * generateContent() as a side effect to kick off the AICore download; calling it on every
 * poll tick would re-trigger that side effect every DEFAULT_POLL_INTERVAL_MS.
 *
 * Resilience contract: a single [checkAvailability] tick that throws (including [Throwable]
 * subtypes such as [OutOfMemoryError] or a native binder crash — not just [Exception]) is
 * treated as a transient failure, logged, and the loop keeps polling — mirroring
 * GitHubDeviceFlowClient.pollForToken's per-tick `catch (e: IOException)` / `catch (e:
 * Exception)` clauses (kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/
 * GitHubDeviceFlowClient.kt:159-169), which likewise back off and continue rather than abort
 * on a single failed attempt. This is deliberately widened to `Throwable` here (unlike
 * `pollForToken`'s `Exception`) because `MlKitLlmFormatterProvider.checkAvailability()` only
 * catches `Exception` internally — an `Error` subtype would otherwise propagate uncaught
 * through this loop into `TagSuggestionViewModel`'s `CoroutineExceptionHandler`, which
 * replaces the *entire* `_state` with `TagSuggestionState.Error(...)`, discarding
 * already-visible local chip suggestions for what may be a single transient tick.
 */
object TagAvailabilityPoller {
    const val DEFAULT_POLL_INTERVAL_MS = 4_000L
    /** ADR-001: interim desk-research estimate — see decisions/ADR-001-poll-deadline-estimate.md */
    const val DEFAULT_POLL_DEADLINE_MS = 120_000L
    const val CAPTION_ESCALATION_THRESHOLD_MS = 45_000L

    const val ESCALATED_WAIT_CAPTION = "Still downloading — this can take a few minutes the first time."
    const val STALLED_REASON = "Taking longer than expected"

    private val logger = Logger("TagAvailabilityPoller")

    /**
     * Polls [checkAvailability] every [intervalMs] until it reports [LlmProviderAvailability.Available]
     * or a non-retryable [LlmProviderAvailability.Unavailable] (FR-4 — permanent failure, stop
     * immediately), or until [deadlineMs] of wall-clock time elapses (FR-2). Calls
     * [onStatusUpdate] exactly once when elapsed time crosses [escalationThresholdMs] — never on
     * every tick — so the UI never reads as a ticking readout (research/ux.md accessibility
     * requirement: at most 3 total caption changes for the whole wait). A [checkAvailability]
     * tick that throws is treated as transient (logged, loop continues) rather than propagated
     * — see the resilience contract in this object's class-level KDoc.
     *
     * [startedAtOverride] (pre-mortem P1 #1/#2 fix): when null (the default), behaves exactly as
     * before — `startedAt` is "now," i.e. a truly first-ever poll for this block/session. When
     * the caller passes a non-null epoch-millis value (`TagSuggestionViewModel.runLlmSuggest`
     * passes its session-scoped `downloadFirstObservedAtMs`), `startedAt` is pinned to that
     * value instead, so a SECOND or LATER invocation (block-switch-and-return, or a manual
     * retry) computes its escalation/deadline math relative to the ORIGINAL first-observed
     * time, not a fresh "now" — this is what makes block-switching and repeated manual retries
     * not silently reset the elapsed-time clock. See plan.md's Pattern Decisions row "Should the
     * poll loop's elapsed-time math reset on every relaunch?".
     */
    suspend fun pollUntilAvailable(
        checkAvailability: suspend () -> LlmProviderAvailability,
        onStatusUpdate: (LlmSuggestionStatus) -> Unit,
        deadlineMs: Long = DEFAULT_POLL_DEADLINE_MS,
        intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        escalationThresholdMs: Long = CAPTION_ESCALATION_THRESHOLD_MS,
        startedAtOverride: Long? = null,
    ): LlmProviderAvailability {
        val startedAt = startedAtOverride ?: Clock.System.now().toEpochMilliseconds()
        val deadline = startedAt + deadlineMs
        // If startedAtOverride already implies we're past the escalation threshold (a resumed
        // poll after a long block-switch or retry), don't re-fire onStatusUpdate — the caller
        // (runLlmSuggest) already shows the escalated caption as its initial caption in that
        // case (see Task 4.1.2), so a second announcement here would be a redundant live-region
        // update, not a new one.
        var escalated = Clock.System.now().toEpochMilliseconds() - startedAt >= escalationThresholdMs

        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            delay(intervalMs)

            val availability = try {
                checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Transient tick failure — log and keep polling. Do NOT propagate: one bad
                // tick (e.g. a momentary AICore binder hiccup) must not collapse the whole
                // Ready state via TagSuggestionViewModel's CoroutineExceptionHandler.
                logger.warn("checkAvailability() threw on a poll tick, continuing to poll", e)
                null
            }

            if (availability is LlmProviderAvailability.Available) return availability
            if (availability is LlmProviderAvailability.Unavailable && !availability.retryable) return availability

            val now = Clock.System.now().toEpochMilliseconds()
            if (!escalated && now - startedAt >= escalationThresholdMs) {
                escalated = true
                onStatusUpdate(LlmSuggestionStatus.Pending(ESCALATED_WAIT_CAPTION))
            }
        }
        return LlmProviderAvailability.Unavailable(STALLED_REASON, retryable = true)
    }
}
```

**Task 3.1.2**: Run `./gradlew jvmTest` — should compile cleanly now (new file, no
dependents yet).

#### Story 3.2: Unit tests under virtual time (NFR-3)

**Task 3.2.1**: Create `TagAvailabilityPollerTest.kt` following
`GitHubDeviceFlowClientTest.kt`'s pattern (`runTest { ... }` calling the function directly,
no injected dispatcher). Test 1 — fast path resolves on first tick:
```kotlin
@Test
fun `pollUntilAvailable returns immediately once Available is observed`() = runTest {
    var calls = 0
    val result = TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = { calls++; if (calls >= 3) LlmProviderAvailability.Available
                               else LlmProviderAvailability.Preparing("downloading") },
        onStatusUpdate = {},
    )
    assertIs<LlmProviderAvailability.Available>(result)
    assertEquals(3, calls)
}
```
**Given** a probe that reports `Preparing` on its first 2 calls then `Available` on the 3rd,
**When** `pollUntilAvailable` is invoked with default 4000ms interval / 120000ms deadline,
**Then** it returns `Available` after exactly 3 probe calls and the virtual clock has
advanced ~8000ms (2 × 4000ms delays before the 3rd, resolving check) — `runTest` completes
in real time on the order of milliseconds, not 8 seconds.

**Task 3.2.2**: Test 2 — deadline reached, terminal `Stalled`-shaped `Unavailable`:
```kotlin
@Test
fun `pollUntilAvailable returns retryable Unavailable when deadline is reached`() = runTest {
    val result = TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
        onStatusUpdate = {},
        deadlineMs = 12_000L,
        intervalMs = 4_000L,
    )
    assertIs<LlmProviderAvailability.Unavailable>(result)
    assertTrue(result.retryable)
    assertEquals("Taking longer than expected", result.reason)
}
```
**Given** a probe that always reports `Preparing`, a 12000ms deadline and 4000ms interval,
**When** `pollUntilAvailable` runs, **Then** after 3 ticks (t=4000, 8000, 12000) the `while`
condition fails and it returns `Unavailable("Taking longer than expected", retryable = true)`.

**Task 3.2.3**: Test 3 — non-retryable `Unavailable` stops immediately (FR-4):
```kotlin
@Test
fun `pollUntilAvailable stops immediately on non-retryable Unavailable`() = runTest {
    var calls = 0
    val result = TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = { calls++; LlmProviderAvailability.Unavailable("Not supported", retryable = false) },
        onStatusUpdate = { fail("must not push a status update for a permanent failure") },
    )
    assertIs<LlmProviderAvailability.Unavailable>(result)
    assertFalse(result.retryable)
    assertEquals(1, calls)
}
```

**Task 3.2.4**: Test 4 — caption escalation fires exactly once at ~45s (AC2):
```kotlin
@Test
fun `pollUntilAvailable escalates the caption exactly once after 45s`() = runTest {
    val updates = mutableListOf<LlmSuggestionStatus>()
    TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
        onStatusUpdate = { updates += it },
        deadlineMs = 120_000L,
        intervalMs = 4_000L,
        escalationThresholdMs = 45_000L,
    )
    val pendingUpdates = updates.filterIsInstance<LlmSuggestionStatus.Pending>()
    assertEquals(1, pendingUpdates.size, "caption must change exactly once before the terminal state")
    assertEquals(
        "Still downloading — this can take a few minutes the first time.",
        pendingUpdates.single().caption,
    )
}
```
**Given** `intervalMs=4000`, `escalationThresholdMs=45000`, ticks land at
t=4000,8000,...,44000,48000 — **When** elapsed crosses 45000 at the 12th tick (t=48000),
**Then** `onStatusUpdate` is called exactly once with
`Pending("Still downloading — this can take a few minutes the first time.")`, matching
AC2's "~45s" wording (48s, one tick past the threshold, is the earliest tick boundary at
or after 45s with a 4s interval).

**Task 3.2.5**: Test 5 — a thrown `checkAvailability()` tick is transient, not terminal
(adversarial-review blocker: resilience contract added to Task 3.1.1's `try`/`catch`):
```kotlin
@Test
fun `pollUntilAvailable treats a thrown checkAvailability as a transient tick and keeps polling`() = runTest {
    var calls = 0
    val result = TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = {
            calls++
            when (calls) {
                2 -> throw IllegalStateException("simulated AICore binder crash")
                3 -> LlmProviderAvailability.Available
                else -> LlmProviderAvailability.Preparing("downloading")
            }
        },
        onStatusUpdate = {},
    )
    assertIs<LlmProviderAvailability.Available>(result)
    assertEquals(3, calls)
}
```
**Given** a probe that reports `Preparing` on tick 1, throws `IllegalStateException` on tick
2, then reports `Available` on tick 3, **When** `pollUntilAvailable` is invoked, **Then** it
does not propagate the exception and does not abort the loop — the `try`/`catch` inside the
`while` body swallows it (logging via `Logger`) and the loop proceeds to tick 3, where it
resolves to `Available` after exactly 3 probe calls, proving a single transient tick failure
degrades to "keep polling," never a collapsed `TagSuggestionState.Error`. (Cancellation
itself is unaffected — `catch (e: CancellationException) { throw e }` still rethrows, so
structured-concurrency cancellation via `suggestionJob?.cancel()`/`vm.close()`, already
covered by Task 4.4.3, keeps working exactly as before.)

**Task 3.2.6**: Test 6 — `startedAtOverride` pins elapsed-time math to a caller-supplied
origin instead of "now" (pre-mortem P1 #1/#2 fix, poller-level unit — the VM-level
block-switch/retry scenario is covered separately by Story 4.6):
```kotlin
@Test
fun `pollUntilAvailable measures elapsed time from startedAtOverride, not from invocation time`() = runTest {
    val now = 1_000_000L
    val startedAtOverride = now - 90_000L // pretend the model has already been "downloading" for 90s

    val updates = mutableListOf<LlmSuggestionStatus>()
    val result = TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = { LlmProviderAvailability.Preparing("still downloading") },
        onStatusUpdate = { updates += it },
        deadlineMs = 120_000L,
        intervalMs = 4_000L,
        escalationThresholdMs = 45_000L,
        startedAtOverride = startedAtOverride,
    )
    assertIs<LlmProviderAvailability.Unavailable>(result)
    assertTrue(result.retryable)
    // 90s already elapsed + 120s deadline means only 30s of *this* invocation's ticks run
    // (30_000 / 4_000 = 7.5 -> 8 ticks), not a fresh 120s/30 ticks.
    assertTrue(updates.none { it is LlmSuggestionStatus.Pending },
        "no escalation update should fire mid-loop — 90s already exceeds the 45s threshold " +
        "before the loop even starts, so 'escalated' starts true and the caller is expected " +
        "to have already shown the escalated caption itself")
}
```
**Given** `startedAtOverride = now - 90_000L` (the model has, per this session's tracking,
already been observed downloading for 90s), a 120000ms deadline and 45000ms escalation
threshold, **When** `pollUntilAvailable` runs, **Then** its internal `startedAt` is pinned to
the override value (not `Clock.System.now()`), so (a) the effective remaining budget is only
`120_000 - 90_000 = 30_000`ms rather than a fresh 120000ms, and the loop reaches its terminal
`Unavailable(STALLED_REASON, retryable = true)` after ~30s of *this* invocation's ticks, not
~120s, and (b) `escalated` starts `true` (90s already exceeds the 45s threshold), so no
redundant `onStatusUpdate` fires mid-loop — proving the primitive itself correctly treats a
resumed poll's elapsed time as continuous with the original observation rather than restarting
at zero.

---

## Epic 4: `TagSuggestionViewModel` wiring — `runLlmSuggest`, polling, retry, `allowPolling` (DEPENDS-ON-EPIC-1, DEPENDS-ON-EPIC-2, DEPENDS-ON-EPIC-3)

**Goal**: Wire the poll loop into the actual suggestion request flow, implement FR-1
(auto-resolve), FR-3 (manual retry), FR-5 (coroutine lifecycle, pitfall #1), FR-7
(`allowPolling`), pitfall #2's format()-not-retriggered guarantee, and pre-mortem P1 #1/#2
(session-scoped `downloadFirstObservedAtMs` so a block-switch or manual retry does not reset
the poll loop's elapsed-time clock — Story 4.6).

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt`

**Files to change (tests)**:
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt` — Tasks
  4.4.4 and 4.5.1 (NFR-3 fix) need two additional imports not already present in this file:
  `kotlinx.coroutines.test.StandardTestDispatcher` and `kotlinx.coroutines.test.advanceUntilIdle`
  (the file currently only imports `UnconfinedTestDispatcher` and `runTest`). Task 4.6.1
  additionally uses `testScheduler.advanceTimeBy(...)` / `.runCurrent()` — add
  `kotlinx.coroutines.test.advanceTimeBy` if the compiler doesn't resolve it as a member call
  on `TestCoroutineScheduler` without it.

#### Story 4.1: Shared `runLlmSuggest` helper

**Task 4.1.1**: In `TagSuggestionViewModel.kt`, add imports:
```kotlin
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.llm.LlmProviderAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Clock
```
Then widen the class declaration and `scope` construction to accept an injectable dispatcher
and poll-timing overrides (NFR-3 fix — architecture-review and adversarial-review both
flagged the original plan's Task 4.4.4/Story 4.5 tests as requiring ~120s/~20s of real sleep
because `TagSuggestionViewModel.scope` was hardcoded to real `Dispatchers.Default`, which
`kotlinx.coroutines.test.runTest`'s virtual time has zero effect on):
```kotlin
class TagSuggestionViewModel(
    private val engine: TagSuggestionEngine,
    private val onPropose: ((PendingLlmSuggestion) -> Unit)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pollDeadlineMs: Long = TagAvailabilityPoller.DEFAULT_POLL_DEADLINE_MS,
    private val pollIntervalMs: Long = TagAvailabilityPoller.DEFAULT_POLL_INTERVAL_MS,
    private val pollEscalationThresholdMs: Long = TagAvailabilityPoller.CAPTION_ESCALATION_THRESHOLD_MS,
) {
    private val logger = Logger("TagSuggestionViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error("Uncaught error: ${e::class.simpleName}: ${e.message}")
                _state.value = TagSuggestionState.Error(e.message ?: "Unknown error")
            }
        }
    )
    // ... unchanged fields (_state, suggestionJob, activeBlockUuid, cache, ...) ...

    /**
     * Session-scoped "when did this VM instance first observe the on-device model as
     * not-yet-available" timestamp (pre-mortem P1 #1/#2 fix). Set ONCE by [runLlmSuggest]
     * the first time a retryable-unavailable signal is observed; NEVER reset by a
     * block-switch or [retryLastRequest]; only cleared back to null when [engine].llmSuggest()
     * actually succeeds. See the Domain Glossary entry and the "Should the poll loop's
     * elapsed-time math reset on every relaunch?" Pattern Decisions row.
     */
    private var downloadFirstObservedAtMs: Long? = null

    /** Test-only accessor — mirrors FountainDecoder.mixedPartsCountForTest
     * (kmp/src/commonMain/kotlin/dev/stapler/stelekit/transfer/qrcode/FountainDecoder.kt:45).
     * Lets Task 4.4.4 assert the suggestionJob coroutine itself terminates on its own once
     * the poll deadline elapses, without weakening suggestionJob's private visibility. */
    internal val isSuggestionJobActiveForTest: Boolean
        get() = suggestionJob?.isActive == true
}
```
All four new parameters default to production values — `App.kt`'s only construction site
(`TagSuggestionViewModel(tagEngine, onPropose = viewModel::proposeLlmSuggestion)`,
`ui/App.kt:1122`) already uses named arguments for `onPropose` and needs zero changes to
keep compiling. Tests inject `StandardTestDispatcher(testScheduler)` — built from `runTest`'s
own `TestScope.testScheduler` — plus millisecond-scale `pollDeadlineMs`/`pollIntervalMs` (see Task
4.4.4 and Task 4.5.1's rewritten test code). See the new Pattern Decisions row ("How do Epic
4's VM-level tests reach deadline-crossing states without ~120s/~20s of real sleep
(NFR-3)?") for the full rationale.

**Task 4.1.2**: Add the shared helper as a new private method:
```kotlin
/**
 * Single call site for both requestSuggestions() (allowPolling=true) and scanEntries()
 * (allowPolling=false) — FR-7's literal, greppable implementation. Pitfall #2: only
 * TagAvailabilityPoller.pollUntilAvailable's checkAvailability probe is called on every
 * poll tick — engine.llmSuggest() (which calls format(), which can trigger the AICore
 * download) is called at most twice: once for the first attempt, once more after Available
 * is observed.
 *
 * Pre-mortem P1 #1/#2: [downloadFirstObservedAtMs] is set once (never reset by a relaunch)
 * and threaded into pollUntilAvailable as startedAtOverride, so a block-switch-and-return or
 * a manual retry resumes the existing elapsed-time budget instead of restarting the 45s
 * escalation / 120s deadline from zero. See the "Should the poll loop's elapsed-time math
 * reset on every relaunch?" Pattern Decisions row.
 */
private suspend fun runLlmSuggest(
    blockContent: String,
    alreadyLinkedTerms: Set<String>,
    allowPolling: Boolean,
    onStatusUpdate: (LlmSuggestionStatus) -> Unit,
): Either<DomainError, List<TagSuggestion>> {
    val firstAttempt = engine.llmSuggest(blockContent, alreadyLinkedTerms)
    if (firstAttempt is Either.Right) {
        // Model actually produced a result — the download (if any was in flight) is over.
        // Clear the session-scoped tracking so a *future* stall starts a fresh clock rather
        // than inheriting this resolved cycle's origin.
        downloadFirstObservedAtMs = null
        return firstAttempt
    }
    if (!allowPolling) return firstAttempt

    val probe = engine.checkAvailability ?: return firstAttempt
    val failure = (firstAttempt as Either.Left).value as? DomainError.NetworkError.RequestFailed
    if (failure == null || !failure.retryable) return firstAttempt

    // Pre-mortem P1 #1/#2 fix: set ONCE per VM lifetime, the first time a retryable signal
    // is observed; a later relaunch (block-switch-and-return, manual retry) reuses this same
    // value rather than overwriting it with "now".
    if (downloadFirstObservedAtMs == null) {
        downloadFirstObservedAtMs = Clock.System.now().toEpochMilliseconds()
    }
    val elapsedSoFar = Clock.System.now().toEpochMilliseconds() - downloadFirstObservedAtMs!!

    // AC0: initial "Downloading..." caption is the SDK-sourced reason string already
    // produced by format() — reused verbatim, not re-invented (research/ux.md row a) — UNLESS
    // this is a resumed poll that's already past the escalation threshold, in which case show
    // the escalated caption immediately rather than a cold-start string the user has already
    // seen once this session (this is the concrete fix for pre-mortem P1 #1: a block-switch
    // no longer resets the caption to the cold-start string).
    val initialCaption = if (elapsedSoFar >= pollEscalationThresholdMs) {
        TagAvailabilityPoller.ESCALATED_WAIT_CAPTION
    } else {
        failure.message
    }
    onStatusUpdate(LlmSuggestionStatus.Pending(initialCaption))

    val resolved = TagAvailabilityPoller.pollUntilAvailable(
        checkAvailability = probe,
        onStatusUpdate = onStatusUpdate,
        deadlineMs = pollDeadlineMs,
        intervalMs = pollIntervalMs,
        escalationThresholdMs = pollEscalationThresholdMs,
        startedAtOverride = downloadFirstObservedAtMs,
    )
    return when (resolved) {
        is LlmProviderAvailability.Available -> {
            val retried = engine.llmSuggest(blockContent, alreadyLinkedTerms) // AC1: auto re-run
            if (retried is Either.Right) downloadFirstObservedAtMs = null
            retried
        }
        is LlmProviderAvailability.Unavailable ->
            // Note: resolved.reason (TagAvailabilityPoller.STALLED_REASON, "Taking longer
            // than expected") is threaded through DomainError.NetworkError.RequestFailed.message
            // here but is NOT what the UI displays — LlmSuggestionStatus.Stalled has no
            // message field (only `retryable`), and SuggestionBottomSheet's Stalled branch
            // (Task 5.2.2) hardcodes its own literal "Taking longer than expected." caption.
            // This is a deliberate, documented duplication (see Risk Control), not a bug: the
            // terminal caption is UI-owned copy, sourced independently of the SDK/domain
            // layer, exactly like row (c)'s secondary "keep typing" line already is.
            DomainError.NetworkError.RequestFailed(resolved.reason, retryable = resolved.retryable).left()
        is LlmProviderAvailability.Preparing ->
            // Unreachable — pollUntilAvailable's contract never returns Preparing — kept
            // for exhaustiveness on the sealed LlmProviderAvailability `when`.
            DomainError.NetworkError.RequestFailed("Taking longer than expected", retryable = true).left()
    }
}
```
Note the consequence for a retry after a genuine `Stalled`: since `downloadFirstObservedAtMs`
is not cleared on the deadline-reached path (only on actual success), a retry that happens
after `pollDeadlineMs` has already elapsed since the original observation will find
`elapsedSoFar >= pollDeadlineMs` — `pollUntilAvailable`'s `while` condition is then false on
entry (its `deadline` is already in the past), so it returns the terminal `Unavailable`
immediately with zero additional ticks. This is deliberate, not a bug: see Task 4.2.4 and
Story 4.6's Task 4.6.2 for the concrete pre-mortem P1 #2 regression test.

**Task 4.1.3**: Given-When-Then for AC0/AC1 combined (the common path this helper exists
for): **Given** block `"block-abc123"` with content `"Meeting notes about Q3 planning"`,
`engine.checkAvailability` wired and returning `Preparing("Downloading on-device model — this may take a few minutes")`
on ticks 1-2 then `Available` on tick 3, and `engine.llmSuggest(...)` returning
`RequestFailed("Downloading on-device model — this may take a few minutes", retryable = true).left()`
on its first call and `listOf(TagSuggestion("Q3-Planning", 0.85f, LLM)).right()` on its
second call, **When** `runLlmSuggest("Meeting notes about Q3 planning", emptySet(), allowPolling = true, onStatusUpdate)`
is called, **Then** `onStatusUpdate` is invoked first with
`Pending("Downloading on-device model — this may take a few minutes")`, then (no
escalation since resolution happens well before 45s) the function returns
`listOf(TagSuggestion("Q3-Planning", 0.85f, LLM)).right()` — the caller (`requestSuggestions`)
writes this into `TagSuggestionState.Ready(llmStatus = Resolved, llmSuggestions = [...])`.

#### Story 4.2: `requestSuggestions()` rewrite

**Task 4.2.1**: Replace the cache-hit branch (lines 76-85) with:
```kotlin
fun requestSuggestions(
    blockUuid: String,
    blockContent: String,
    alreadyLinkedTerms: Set<String> = emptySet(),
    allowPolling: Boolean = true,
) {
    lastRequest = LastRequest(blockUuid, blockContent, alreadyLinkedTerms, allowPolling)

    val cached = cache[blockUuid]
    if (cached != null) {
        _state.value = cached
        val activelyRunning = activeBlockUuid == blockUuid && cached.llmStatus is LlmSuggestionStatus.Pending
        if (activelyRunning) return
        val terminal = cached.llmStatus == LlmSuggestionStatus.Resolved ||
            (cached.llmStatus as? LlmSuggestionStatus.Failed)?.retryable == false
        if (terminal) return
        // NotStarted, Stalled, retryable Failed, or a Pending job that was cancelled
        // (block switch) all fall through to re-run — this is also the FR-3 retry path.
    }

    suggestionJob?.cancel()
    activeBlockUuid = blockUuid

    suggestionJob = scope.launch {
        val localSuggestions = engine.directMatch(blockContent)
        val initial = TagSuggestionState.Ready(
            blockUuid = blockUuid,
            localSuggestions = localSuggestions,
            llmSuggestions = emptyList(),
            llmStatus = if (engine.hasLlmProvider) LlmSuggestionStatus.Pending() else LlmSuggestionStatus.Resolved,
        )
        cache[blockUuid] = initial
        _state.value = initial

        val onStatusUpdate: (LlmSuggestionStatus) -> Unit = { status ->
            cache[blockUuid]?.let { cache[blockUuid] = it.copy(llmStatus = status) }
            _state.update { current ->
                if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) current.copy(llmStatus = status)
                else current
            }
        }

        runLlmSuggest(blockContent, alreadyLinkedTerms, allowPolling, onStatusUpdate).fold(
            ifLeft = { err ->
                // Stalled is reserved for the on-device-availability signal specifically
                // (RequestFailed.retryable — see Domain Glossary). DomainError.NetworkError.Timeout
                // is a different, also-plausibly-transient condition (a completed-but-slow
                // network round-trip, not a model-download wait) and gets its own retryable
                // Failed rather than being folded into Stalled's "still downloading" framing —
                // see the "Which retryable DomainErrors become Stalled vs a retryable Failed?"
                // Pattern Decision row. This is also what makes Failed.retryable a real,
                // non-dead field: it is true exactly when err is a Timeout, false otherwise.
                val status = when {
                    err is DomainError.NetworkError.RequestFailed && err.retryable ->
                        LlmSuggestionStatus.Stalled(retryable = true)
                    err is DomainError.NetworkError.Timeout ->
                        LlmSuggestionStatus.Failed(message = err.message, retryable = true)
                    else ->
                        LlmSuggestionStatus.Failed(message = err.message, retryable = false)
                }
                onStatusUpdate(status)
            },
            ifRight = { llmSuggestions ->
                cache[blockUuid]?.let {
                    cache[blockUuid] = it.copy(llmSuggestions = llmSuggestions, llmStatus = LlmSuggestionStatus.Resolved)
                }
                _state.update { current ->
                    if (current is TagSuggestionState.Ready && current.blockUuid == blockUuid) {
                        current.copy(llmSuggestions = llmSuggestions, llmStatus = LlmSuggestionStatus.Resolved)
                    } else current
                }
            }
        )
        activeBlockUuid = null
    }
}
```

**Task 4.2.2**: Add the `LastRequest` storage just above `requestSuggestions()`:
```kotlin
private data class LastRequest(
    val blockUuid: String,
    val blockContent: String,
    val alreadyLinkedTerms: Set<String>,
    val allowPolling: Boolean,
)
private var lastRequest: LastRequest? = null

/** FR-3 manual-retry call target — re-invokes the most recent requestSuggestions() call. No-op if none yet. */
fun retryLastRequest() {
    lastRequest?.let { requestSuggestions(it.blockUuid, it.blockContent, it.alreadyLinkedTerms, it.allowPolling) }
}
```

**Task 4.2.3**: Given-When-Then for AC2 (bounded wait, terminal state): **Given** block
`"block-abc123"`, `allowPolling = true`, `engine.checkAvailability` wired and always
returning `Preparing(...)` (model never resolves within the deadline), **When**
`requestSuggestions("block-abc123", "some content")` is called and the poll loop runs to
its 120000ms deadline, **Then** `TagSuggestionState.Ready.llmStatus` transitions
`Pending(null)` → `Pending("Downloading on-device model — this may take a few minutes")`
(from `runLlmSuggest`'s first-failure caption) → `Pending("Still downloading — this can take a few minutes the first time.")`
(at ~48s, `TagAvailabilityPoller`'s escalation) → `Stalled(retryable = true)` (at 120s,
deadline reached) — and `cache["block-abc123"].llmStatus` is also `Stalled(retryable = true)`.
(Implementation note: if this GWT is written as an actual runnable test rather than covered
transitively by Task 4.4.4's test, construct the VM with the same `dispatcher`/`pollDeadlineMs`/
`pollIntervalMs` overrides introduced in Task 4.1.1 — do not exercise the real 120000ms
deadline in a test, per NFR-3. This scenario is also the point at which `downloadFirstObservedAtMs`
gets set for the very first time — it is `null` before this call, and becomes
`Clock.System.now()`-at-the-time-of-the-first-retryable-failure once `runLlmSuggest` observes
the first retryable `RequestFailed`.)

**Task 4.2.4**: Given-When-Then for AC3 (manual retry) — **updated for the pre-mortem P1 #1/#2
fix** (persistent `downloadFirstObservedAtMs`; supersedes the plan's original text, which
described retry as unconditionally "restarting the whole flow from `Pending(null)`" with a
fresh 120s wait — that was the exact behavior pre-mortem P1 #2 flagged as still reading as
"stuck" across repeated retries): **Given** the AC2 scenario has just completed
(`cache["block-abc123"].llmStatus == Stalled(retryable = true)`, `activeBlockUuid == null`,
and — new — `downloadFirstObservedAtMs` is still set to its original observation time from
Task 4.2.3, now `pollDeadlineMs` (120000ms) or more in the past), **When** the user taps
Retry, firing `tagSuggestionViewModel.retryLastRequest()`, **Then**
`requestSuggestions("block-abc123", "some content", emptySet(), true)` is invoked again with
the stored args; the cache-hit branch sees `Stalled` is not `terminal` and falls through to
launch a fresh `suggestionJob`; `runLlmSuggest`'s first `engine.llmSuggest()` re-attempt fails
retryable again (model still not available); `downloadFirstObservedAtMs` is **NOT** reset
(it's already non-null); `TagAvailabilityPoller.pollUntilAvailable` is invoked with
`startedAtOverride = downloadFirstObservedAtMs`, whose `while` condition is false on entry
because `deadline = downloadFirstObservedAtMs + pollDeadlineMs` is already in the past — it
returns the terminal `Unavailable(STALLED_REASON, retryable = true)` with **zero** additional
`checkAvailability()` calls or `delay()` ticks — so `llmStatus` goes
`Pending(ESCALATED_WAIT_CAPTION)` (per Task 4.1.2's `initialCaption` logic, since
`elapsedSoFar >= pollEscalationThresholdMs` trivially holds here too) → `Stalled(retryable =
true)` again almost immediately, **not** a fresh 120s wait and **not** a reset to the
cold-start caption. This is the direct fix for pre-mortem P1 #2: repeated manual retries
against a genuinely-still-downloading model surface "still stalled" quickly instead of
replaying the whole animated escalation sequence each time. (See also `design/ux.md` Row
(c), updated to match this behavior, and Story 4.6's Task 4.6.2 for the runnable regression
test.)

**Task 4.2.5**: Given-When-Then for AC4 fast path (no regression): **Given** block
`"block-fast1"`, `engine.checkAvailability` wired and returning `Available` immediately,
`engine.llmSuggest(...)` returning `listOf(TagSuggestion("Kotlin", 0.85f, LLM)).right()` on
its **first** call, **When** `requestSuggestions("block-fast1", "Learning Kotlin today")` is
called, **Then** `runLlmSuggest`'s `firstAttempt` is `Either.Right` immediately, the
`if (firstAttempt !is Either.Left) return firstAttempt` guard fires with zero calls to
`TagAvailabilityPoller.pollUntilAvailable` and zero extra `checkAvailability()` calls beyond
what `format()` already does internally — `llmStatus` goes straight from `Pending(null)` to
`Resolved` with no intermediate caption.

**Task 4.2.6**: Given-When-Then for AC4 genuinely-unsupported path (no regression): **Given**
block `"block-unsupported1"`, `engine.llmSuggest(...)` returning
`DomainError.NetworkError.RequestFailed("On-device AI is not supported on this device", retryable = false).left()`
on its first call, **When** `requestSuggestions("block-unsupported1", "content")` is called,
**Then** `runLlmSuggest`'s `failure.retryable == false` guard returns `firstAttempt`
immediately (no poll loop started, matching FR-4's "genuinely unsupported → no poll loop"),
and `llmStatus` becomes `Failed(message = "On-device AI is not supported on this device", retryable = false)`
— `SuggestionBottomSheet` renders this with no retry button (UX row d).

#### Story 4.3: `scanEntries()` — `allowPolling = false`

**Task 4.3.1**: In `scanEntries()` (lines 128-166), replace the direct
`engine.llmSuggest(entry.fullContent, entry.alreadyLinked)` call with
`runLlmSuggest(entry.fullContent, entry.alreadyLinked, allowPolling = false) { }` and
update the `Ready` construction in the `ifRight` branch to use
`llmStatus = LlmSuggestionStatus.Resolved` instead of `llmPending = false`.

**Task 4.3.2**: Given-When-Then for AC7: **Given** a bulk scan of 3
`JournalScanEntry` items where `engine.llmSuggest(...)` for entry 2 would return a
retryable `RequestFailed("Downloading on-device model — this may take a few minutes", retryable = true)`
if polling were allowed, **When** `scanEntries(listOf(entry1, entry2, entry3))` runs,
**Then** `runLlmSuggest(..., allowPolling = false, ...)`'s
`if (!allowPolling) return firstAttempt` guard returns the failure immediately — no
`TagAvailabilityPoller.pollUntilAvailable` call, no up-to-120s wait — entry 2 is skipped
(existing `ifLeft = { /* skip */ }` behavior, unchanged) and the scan proceeds to entry 3
without delay, matching today's fail-fast-per-entry timing exactly.

#### Story 4.4: Regression test — stale-block leak (pitfall #1)

**Task 4.4.1**: Add to `TagSuggestionViewModelTest.kt`:
```kotlin
@Test
fun `poll loop for a stale block does not write into a newly active block's cache`() = runTest {
    // Given: block-A stuck at Preparing forever (never resolves within the test's window).
    val engine = /* engine with checkAvailability always returning Preparing, llmSuggest
                     for block-A's content always returning retryable RequestFailed */
    val vm = TagSuggestionViewModel(engine)
    vm.requestSuggestions("block-A", "content A")
    vm.awaitState { it is TagSuggestionState.Ready && (it as TagSuggestionState.Ready).llmStatus is LlmSuggestionStatus.Pending }

    // When: user switches to block-B before block-A's poll loop resolves or hits deadline.
    vm.requestSuggestions("block-B", "content B")
    vm.awaitState { it is TagSuggestionState.Ready && (it as TagSuggestionState.Ready).blockUuid == "block-B" }

    // Then: block-A's cache entry is frozen at whatever it was when cancelled — never
    // transitions to Stalled or Resolved after the switch, proving the poll Job was
    // cancelled as part of suggestionJob (nested, not a sibling per pitfall #1) rather
    // than surviving to write a stale result into cache["block-A"].
    val blockAStatusAtSwitch = (vm.state.value as? TagSuggestionState.Ready)?.let { null } // captured before switch instead — see note below
    vm.close()
    // Assert cache["block-A"] status did not advance past Pending after the switch.
}
```
Concretely: capture `(vm.cache-equivalent observable)` — since `cache` is private,
capture the `Ready` state for block-A right before requesting block-B, then after
requesting block-B and awaiting its resolution, request block-A again
(`vm.requestSuggestions("block-A", "content A")`) and assert its `llmStatus` is `Pending(null)`
again (a *fresh* run, proving the old poll job did not silently finish and populate a stale
`Resolved`/`Stalled` result while block-B was active) rather than instantly returning a
`Resolved`/`Stalled` value that could only have come from the old, supposedly-cancelled job.

**Task 4.4.2**: This test is the direct enforcement for pitfall #1's structural requirement
— the poll `Job` inside `runLlmSuggest` (via `TagAvailabilityPoller.pollUntilAvailable`) is
a plain suspend call *inside* `suggestionJob`'s coroutine, never a sibling `scope.launch`,
so `suggestionJob?.cancel()` at the top of `requestSuggestions()` (unchanged, line 88 in
the original) already cancels it structurally. No new cancellation code is needed —
this task exists to prove that structural guarantee holds, not to add a new mechanism.

#### Story 4.4b: Given-When-Then coverage for AC5 (full lifecycle, beyond the pitfall #1 stale-block case already covered in Story 4.4)

**Task 4.4.3**: Given-When-Then for AC5's `close()` termination clause: **Given** block
`"block-abc123"` with `engine.checkAvailability` wired and always returning
`Preparing("still downloading")` (never resolves), **When**
`vm.requestSuggestions("block-abc123", "content")` is called and, before the 120000ms
deadline or any block switch, `vm.close()` is called (→ `scope.cancel()`), **Then** the
`suggestionJob` coroutine — and the `TagAvailabilityPoller.pollUntilAvailable` suspend call
running inside it — is cancelled via structured concurrency (the poll loop's `delay(intervalMs)`
call is a cancellation point) with no further `checkAvailability()` calls or `_state`
writes occurring after `close()` returns. Assert via a probe that increments a counter on
each call: the counter's value immediately after `close()` never increases again, even if
the test additionally advances virtual time past what would have been the 120000ms deadline.
(Consistency note: this test does not hit the real-sleep problem Task 4.4.4/Story 4.5 had —
`close()` cancels before any deadline is reached — so it needs no dispatcher/deadline
overrides to pass, and compiles unchanged against Task 4.1.1's new optional constructor
parameters, which all default to today's behavior.)

**Task 4.4.4**: Given-When-Then for AC5's own-deadline termination clause (distinct from
Story 4.2.3's state-transition assertion — this one asserts the *coroutine itself*
terminates, not just the `_state` value). **NFR-3 note (architecture-review and
adversarial-review both flagged this)**: the original draft of this test drove
`requestSuggestions()` past the full real 120000ms `DEFAULT_POLL_DEADLINE_MS`, and because
`TagSuggestionViewModel.scope` used to be hardcoded to real `Dispatchers.Default`,
`kotlinx.coroutines.test.runTest`'s virtual time had zero effect on it — the test would
require ~120 real wall-clock seconds per CI run. Task 4.1.1 fixed this by making the
dispatcher and poll deadline/interval injectable; this test now uses those overrides so it
completes in milliseconds of both real and virtual time:
```kotlin
@Test
fun `suggestionJob becomes inactive on its own once the poll deadline elapses`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val indexScope = CoroutineScope(testDispatcher)
    val engine = /* engine with checkAvailability always returning Preparing, llmSuggest
                     for the block's content always returning retryable RequestFailed,
                     built on indexScope */
    val vm = TagSuggestionViewModel(
        engine,
        dispatcher = testDispatcher,
        pollDeadlineMs = 200L,
        pollIntervalMs = 50L,
    )
    vm.requestSuggestions("block-abc123", "content")
    advanceUntilIdle()

    assertFalse(vm.isSuggestionJobActiveForTest)
    vm.close()
    indexScope.cancel()
}
```
**Given** the same always-`Preparing` probe as Task 4.4.3, a `StandardTestDispatcher` built
on `runTest`'s own `testScheduler` (so `advanceUntilIdle()` in the test body also advances
the VM's independently-owned `scope`), and `pollDeadlineMs = 200L`/`pollIntervalMs = 50L`
standing in for the production `120000L`/`4000L` values (the override exists purely for test
speed per NFR-3 — the production default, used whenever the constructor's optional
parameters are omitted, is unchanged), **When** `requestSuggestions("block-abc123",
"content")` runs past the overridden `pollDeadlineMs` and `advanceUntilIdle()` drains all
pending virtual-time work, **Then** `isSuggestionJobActiveForTest` (the new
`internal` test accessor added in Task 4.1.1) becomes `false` on its own — the
`runLlmSuggest` → `pollUntilAvailable` → `requestSuggestions`'s `scope.launch` lambda all
return normally once `pollUntilAvailable` returns its terminal `Unavailable` — without
requiring `close()` or a block switch. This confirms the loop is bounded, not indefinite (the
"even when left running in the background" half of ADR-002's contract), and does so entirely
under virtual time: the test completes in milliseconds of real wall-clock time regardless of
how large `pollDeadlineMs` is set.

#### Story 4.5: Regression test — `format()` not re-triggered per poll tick (pitfall #2)

**Task 4.5.1**: Add to `TagSuggestionViewModelTest.kt`. **NFR-3 note**: the original draft
let `checkAvailability` report `Preparing` 4 times against the VM's real
`Dispatchers.Default` scope with the production 4000ms poll interval — ~5 real ticks × 4000ms
≈ 20 real seconds per CI run (the test's `awaitState` spin-poll doesn't fast-forward a real
scope). This rewrite uses Task 4.1.1's injected `StandardTestDispatcher` and a
millisecond-scale `pollIntervalMs` override so the assertions (`formatCalls == 2`,
`checkAvailabilityCalls == 5`) are unchanged but reached in milliseconds instead:
```kotlin
@Test
fun `format is called at most twice across a full poll cycle, never once per tick`() = runTest {
    var formatCalls = 0
    var checkAvailabilityCalls = 0
    val formatter = object : LlmFormatterProvider {
        override suspend fun format(transcript: String, systemPrompt: String): LlmResult {
            formatCalls++
            return if (formatCalls == 1) {
                LlmResult.Failure.OnDeviceUnavailable("Downloading on-device model — this may take a few minutes", retryable = true)
            } else {
                LlmResult.Success("Kotlin")
            }
        }
    }
    val llmProvider = LlmTagProvider(formatter, timeoutSeconds = 5)
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val indexScope = CoroutineScope(testDispatcher)
    val engine = TagSuggestionEngine(
        pageNameIndex = /* fake index, built on indexScope */,
        llmTagProvider = llmProvider,
        vocabularyProvider = { listOf("Kotlin") },
        checkAvailability = {
            checkAvailabilityCalls++
            if (checkAvailabilityCalls >= 5) LlmProviderAvailability.Available
            else LlmProviderAvailability.Preparing("downloading")
        },
    )
    val vm = TagSuggestionViewModel(
        engine,
        dispatcher = testDispatcher,
        pollDeadlineMs = 1_000L,
        pollIntervalMs = 10L,
    )
    vm.requestSuggestions("block-abc123", "Learning Kotlin")
    advanceUntilIdle()

    assertEquals(2, formatCalls, "format() must be called exactly once for the initial attempt and once after Available resolves — never per poll tick")
    assertEquals(5, checkAvailabilityCalls, "checkAvailability() carries the per-tick polling load, not format()")
    vm.close()
    indexScope.cancel()
}
```
**Given** a formatter that fails with retryable `OnDeviceUnavailable` once then succeeds, a
`checkAvailability` probe that reports `Preparing` 4 times then `Available`, and a VM
constructed with a shared `StandardTestDispatcher(testScheduler)` plus
`pollIntervalMs = 10L`/`pollDeadlineMs = 1_000L` (5 ticks × 10ms = 50ms of virtual time, well
under the 1000ms override — standing in for the production `4000L`/`120000L` values purely
for test speed per NFR-3), **When** `requestSuggestions` runs to resolution and
`advanceUntilIdle()` drains all pending virtual-time work, **Then** `format()` (proxy for
`generateContent()`/the AICore download-kickoff side effect) is called exactly twice total —
never once per `checkAvailability` tick — directly enforcing pitfall #2, with the whole test
completing in milliseconds of real wall-clock time instead of ~20 real seconds.

#### Story 4.6: Regression tests — elapsed-time persistence across block-switch and manual retry (pre-mortem P1 #1/#2)

**Goal**: Directly prove the fix in Task 4.1.1/4.1.2 (`downloadFirstObservedAtMs`) resolves
both pre-mortem P1 items — block-switching no longer resets the poll clock, and retrying
after a genuine `Stalled` reaches `Stalled` again almost immediately rather than replaying a
fresh 120s wait.

**Task 4.6.1**: Block-switch-and-return no longer resets the elapsed-time clock (P1 #1).
Scaled test constants stand in for production's `120_000L`/`4_000L`/`45_000L` while
preserving the same ratio (`450:1200 ≈ 45:120`), per NFR-3:
```kotlin
@Test
fun `poll elapsed time survives a block-switch-and-return, escalating immediately and reaching Stalled early`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val indexScope = CoroutineScope(testDispatcher)
    val engine = /* engine built on indexScope; checkAvailability always returns Preparing
                     (one shared on-device model, both blocks poll the same probe);
                     llmSuggest for both "block-A"/"content A" and "block-B"/"content B"
                     always returns retryable RequestFailed */
    val vm = TagSuggestionViewModel(
        engine,
        dispatcher = testDispatcher,
        pollDeadlineMs = 1_200L,           // stands in for 120_000L
        pollIntervalMs = 100L,             // stands in for 4_000L
        pollEscalationThresholdMs = 450L,  // stands in for 45_000L
    )

    // Block A: request, run past the escalation threshold (450ms) but well short of the
    // 1200ms deadline, then switch away before A's poll loop resolves or times out.
    vm.requestSuggestions("block-A", "content A")
    testScheduler.advanceTimeBy(500L)
    testScheduler.runCurrent()

    vm.requestSuggestions("block-B", "content B")
    vm.awaitState { it is TagSuggestionState.Ready && (it as TagSuggestionState.Ready).blockUuid == "block-B" }

    // Switch back to block A.
    vm.requestSuggestions("block-A", "content A")

    // Then: block A's relaunch shows the ESCALATED caption immediately — never the
    // cold-start caption again — proving downloadFirstObservedAtMs was not reset.
    vm.awaitState {
        it is TagSuggestionState.Ready &&
            (it as TagSuggestionState.Ready).blockUuid == "block-A" &&
            it.llmStatus == LlmSuggestionStatus.Pending(TagAvailabilityPoller.ESCALATED_WAIT_CAPTION)
    }

    // And: it reaches Stalled after only ~700ms more of virtual time (1200 - 500 already
    // elapsed), not a fresh 1200ms.
    advanceUntilIdle()
    val finalState = vm.state.value as TagSuggestionState.Ready
    assertEquals("block-A", finalState.blockUuid)
    assertEquals(LlmSuggestionStatus.Stalled(retryable = true), finalState.llmStatus)

    vm.close()
    indexScope.cancel()
}
```
**Given** block A's poll cycle has been running for 500ms (past `pollEscalationThresholdMs =
450ms`) when the user switches to block B, **When** the user switches back to block A and
`requestSuggestions("block-A", ...)` fires again, **Then** block A's `llmStatus` transitions
straight to `Pending(ESCALATED_WAIT_CAPTION)` on this relaunch — never re-showing the
cold-start caption — and reaches `Stalled(retryable = true)` after only ~700ms of additional
virtual time (1200ms deadline − 500ms already elapsed), not a fresh 1200ms wait — directly
proving pre-mortem P1 #1 is fixed.

**Task 4.6.2**: Manual retry after a genuine `Stalled` reaches `Stalled` again almost
immediately, not after a fresh full deadline (P1 #2):
```kotlin
@Test
fun `retryLastRequest after Stalled reaches Stalled again immediately, not after a fresh deadline`() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val indexScope = CoroutineScope(testDispatcher)
    var checkAvailabilityCalls = 0
    val engine = /* engine built on indexScope; checkAvailability always returns Preparing
                     (never resolves); llmSuggest always returns retryable RequestFailed */
    val vm = TagSuggestionViewModel(
        engine,
        dispatcher = testDispatcher,
        pollDeadlineMs = 1_200L,
        pollIntervalMs = 100L,
        pollEscalationThresholdMs = 450L,
    )

    vm.requestSuggestions("block-abc123", "content")
    advanceUntilIdle() // runs the full 1200ms poll cycle to its own Stalled deadline
    assertEquals(
        LlmSuggestionStatus.Stalled(retryable = true),
        (vm.state.value as TagSuggestionState.Ready).llmStatus,
    )
    val callsAtFirstStall = checkAvailabilityCalls

    vm.retryLastRequest()
    advanceUntilIdle()

    // Then: back to Stalled again, but with (at most) 1 additional checkAvailability() call
    // — not a fresh ~12 ticks (1200ms / 100ms) worth of polling.
    assertEquals(
        LlmSuggestionStatus.Stalled(retryable = true),
        (vm.state.value as TagSuggestionState.Ready).llmStatus,
    )
    assertTrue(
        checkAvailabilityCalls - callsAtFirstStall <= 1,
        "retry after a genuine Stalled must not restart a fresh multi-tick poll cycle",
    )

    vm.close()
    indexScope.cancel()
}
```
**Given** block `"block-abc123"` has already reached `Stalled(retryable = true)` on its first
poll cycle (`downloadFirstObservedAtMs` is now `pollDeadlineMs` or more in the past), **When**
the user calls `retryLastRequest()` and the model is still not available (the re-attempted
`engine.llmSuggest()` fails retryable again), **Then**
`TagAvailabilityPoller.pollUntilAvailable`'s `startedAtOverride = downloadFirstObservedAtMs`
makes its `while` condition false on entry — it returns the terminal `Unavailable` with
**zero** additional `checkAvailability()` calls and **zero** additional `delay()` ticks, so
`llmStatus` reaches `Stalled(retryable = true)` again within (at most) one
`engine.llmSuggest()` round-trip, not a fresh `pollDeadlineMs`-long wait — directly proving
pre-mortem P1 #2 is fixed: repeated retries against a genuinely-still-downloading model
surface "still stalled" almost immediately instead of replaying the full escalation sequence
each time.

---

## Epic 5: UI — `TagChipRow` / `SuggestionBottomSheet` (DEPENDS-ON-EPIC-4)

**Goal**: Render the 4 UX states from `research/ux.md`'s table, add the FR-3 retry
affordance, satisfy the accessibility requirements (live-region captions, focusable retry
button, structurally-absent-not-disabled retry for row d).

**Dependency**: DEPENDS-ON-EPIC-4 — needs `LlmSuggestionStatus` fully wired through the
ViewModel and `retryLastRequest()` to exist.

**Files to change**:
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/TagChipRow.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/VoiceCaptureButton.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsView.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`

#### Story 5.1: `TagChipRow` signature change

**Task 5.1.1**: In `TagChipRow.kt`, replace the `isLlmLoading: Boolean, llmError: String?`
parameters (lines 25-26) with `llmStatus: LlmSuggestionStatus` and add
`import dev.stapler.stelekit.tags.LlmSuggestionStatus`. Compute
`val isLlmLoading = llmStatus is LlmSuggestionStatus.Pending` inside the function body.
Change the early-return guard (line 33) from
`if (displaySuggestions.isEmpty() && !isLlmLoading && llmError == null) return` to
`if (displaySuggestions.isEmpty() && !isLlmLoading) return` — the caption/error text block
(lines 60-67) moves to `SuggestionBottomSheet` in Story 5.2, so `TagChipRow` no longer
needs `llmError` at all; delete lines 60-67 entirely.

**Task 5.1.2**: Update `VoiceCaptureButton.kt` line 193-197's `TagChipRow(...)` call —
replace `isLlmLoading = false, llmError = null,` with
`llmStatus = dev.stapler.stelekit.tags.LlmSuggestionStatus.Resolved,` (this call site
always passes a fully-resolved suggestion list from voice capture, never a pending LLM
call, so `Resolved` is the correct terminal status — matches the removed `isLlmLoading = false`
exactly). Add the import `dev.stapler.stelekit.tags.LlmSuggestionStatus` near the existing
`dev.stapler.stelekit.voice.VoiceCaptureState` import (line 40) instead of using the
fully-qualified name inline, if preferred.

#### Story 5.2: `SuggestionBottomSheet` caption/retry rendering

**Task 5.2.1**: In `SuggestionBottomSheet.kt`, add imports:
```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.stapler.stelekit.tags.LlmSuggestionStatus
```
Add a new parameter `onRetry: () -> Unit` to the `SuggestionBottomSheet` function
signature, after `onDismiss`.

**Task 5.2.2**: Replace the `Ready` branch body (lines 78-100) with:
```kotlin
is TagSuggestionState.Ready -> {
    val allSuggestions = state.localSuggestions + state.llmSuggestions

    TagChipRow(
        suggestions = allSuggestions,
        llmStatus = state.llmStatus,
        onAccept = { suggestion -> onAcceptTag(state.blockUuid, suggestion.term) },
        onDismiss = { /* dismiss silently */ },
        modifier = Modifier.padding(top = 8.dp),
    )

    when (val status = state.llmStatus) {
        is LlmSuggestionStatus.Pending -> status.caption?.let { caption ->
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        is LlmSuggestionStatus.Stalled -> {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = "Taking longer than expected.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(
                    text = "Tap Retry to check again, or keep typing the tag yourself.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Structurally absent (an `if`, not enabled=false) when not retryable — a
                // disabled-but-visible button reads as broken to screen readers.
                if (status.retryable) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.semantics { contentDescription = "Retry downloading tags" },
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
        is LlmSuggestionStatus.Failed -> {
            Column(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(
                    text = status.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                // Structurally absent (an `if`, not enabled=false) when not retryable — same
                // accessibility rule as the Stalled branch above (a disabled-but-visible
                // button reads as broken to screen readers). Retryable Failed (e.g. a
                // DomainError.NetworkError.Timeout, per Task 4.2.1) needs this exactly like
                // Stalled does — adversarial-review blocker: Failed.retryable was previously
                // never read here at all, so no Failed state ever rendered a retry affordance.
                if (status.retryable) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.semantics { contentDescription = "Retry downloading tags" },
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
        LlmSuggestionStatus.NotStarted, LlmSuggestionStatus.Resolved -> Unit
    }
}
```
Note the `Failed` branch deliberately uses `onSurfaceVariant` (calm, not
`MaterialTheme.colorScheme.error`) even for the retryable=false "genuinely unsupported"
case (UX row d — "must NOT look like an error") — and now also for the retryable=true
`Timeout` case, for the same reason: a retry button is present, but the tone stays calm.

**Accessibility fix (Phase 4 triad-review BLOCKER, UX lens)**: the `Failed` branch's
`status.message` `Text` now carries `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`
— the same treatment `Pending`'s caption and `Stalled`'s heading already had — applied
unconditionally, i.e. for both the retryable=false unsupported-device sub-case (row d) and the
retryable=true `Timeout` sub-case (row e), regardless of whether the `TextButton` also renders.
Before this fix, a background transition into `Failed` (a `Timeout` firing while the user
wasn't looking at the sheet, or a poll resolving to unsupported-device) was silently NOT
announced to screen readers — the only one of the five caption states missing the
announcement it should have had per the design's own stated principle that captions must
announce on transition, not merely be present. The `Modifier.semantics(mergeDescendants = true)`
on the enclosing `Column` still groups the message/secondary-line/button into one semantics
unit for navigation (Step 3 criterion 7 of `design/ux.md`); the `liveRegion` modifier on the
inner `Text` is what triggers the TalkBack announcement itself — the two serve different jobs
and both are required, matching the `Stalled` branch's existing pattern exactly.

**Task 5.2.3**: Given-When-Then for AC2's visual distinctness requirement: **Given**
`state.llmStatus == LlmSuggestionStatus.Stalled(retryable = true)`, **When**
`SuggestionBottomSheet` composes, **Then** the rendered text is "Taking longer than
expected." in `MaterialTheme.colorScheme.tertiary` plus a secondary "keep typing" line and
a focusable `TextButton` labeled "Retry" with `contentDescription = "Retry downloading tags"`
— visually and semantically distinct from the `Pending` caption's plain
`onSurfaceVariant` text with no button, satisfying "distinct 'taking longer than expected'
state (visually and semantically different from the initial 'Downloading…' state)."

**Task 5.2.4**: Given-When-Then for AC3: **Given** the `Stalled(retryable = true)` state
from Task 5.2.3, **When** the user taps the "Retry" button, **Then** `onRetry()` fires,
which (per Story 5.3's wiring) calls `tagSuggestionViewModel.retryLastRequest()`.

**Task 5.2.5**: Given-When-Then for AC3's retryable-`Failed` case (adversarial-review
blocker — the original plan's `Failed` branch never rendered a retry button, unconditionally,
even though AC3's wording names both "stalled/failed"): **Given**
`engine.llmSuggest(...)` returns `DomainError.NetworkError.Timeout("LLM tag suggestion timed
out after 90s").left()` — a slow round-trip, not a model-availability condition — so Task
4.2.1's `ifLeft` branch computes `status = LlmSuggestionStatus.Failed(message = "LLM tag
suggestion timed out after 90s", retryable = true)`, **When** `SuggestionBottomSheet`
composes with this `Ready` state, **Then** the rendered text is the timeout message in
`MaterialTheme.colorScheme.onSurfaceVariant` (still calm, not `colorScheme.error`) plus a
focusable `TextButton` labeled "Retry" with `contentDescription = "Retry downloading tags"`,
and tapping it fires `onRetry()` → `tagSuggestionViewModel.retryLastRequest()` — proving AC3's
"stalled/failed" wording is satisfied by an actual `Failed`-state retry path, not only by
`Stalled`.

**Accessibility assertion (Phase 4 triad-review BLOCKER fix, folded into this task rather
than a new one)**: this same test additionally asserts the `Failed` branch's message `Text`
node carries `liveRegion = LiveRegionMode.Polite` semantics — e.g. via
`onNodeWithText("LLM tag suggestion timed out after 90s").fetchSemanticsNode().config[SemanticsProperties.LiveRegion] == LiveRegionMode.Polite`
(or the equivalent `SemanticsMatcher`) — proving the `Failed` branch's caption is announced on
transition exactly like `Pending`'s and `Stalled`'s captions already are. A second, minimal
assertion in the same test class covers the retryable=false unsupported-device sub-case (row
d, `Failed(message, retryable = false)`): the same `liveRegion = LiveRegionMode.Polite`
semantics is present on its message `Text` too, and — unlike row e — no `TextButton` node
exists, confirming the LiveRegion announcement is wired to the message text itself, not
conditionally on the retry button rendering.

#### Story 5.3: Wire `onRetry` at both `SuggestionBottomSheet` call sites

**Task 5.3.1**: In `JournalsView.kt` (around line 345-353), add
`onRetry = { tagSuggestionViewModel.retryLastRequest() },` to the `SuggestionBottomSheet(...)`
call, alongside the existing `onDismiss = { tagSuggestionViewModel.dismiss() }`.

**Task 5.3.2**: In `PageView.kt` (around line 591-603), add the same
`onRetry = { tagSuggestionViewModel.retryLastRequest() },` to its `SuggestionBottomSheet(...)`
call.

**Task 5.3.3**: Run `./gradlew jvmTest` (compile check — both screens should now compile
against the new `SuggestionBottomSheet` signature).

---

## Epic 6: Fix remaining direct `TagSuggestionState.Ready` construction ripple (DEPENDS-ON-EPIC-2)

**Goal**: Update the one test file found (via exhaustive grep of
`\.llmPending\b\|\.llmError\b` across `kmp/src`) that constructs `TagSuggestionState.Ready`
with the old flat fields directly, outside the 3 sites already confirmed unaffected
(`TagSuggestionViewModelTest.kt`, `TagSuggestionEngineTest.kt`, `TagInsertionFlagshipUiTest.kt`
only reference `it is TagSuggestionState.Ready` / `.llmSuggestions`, never `.llmPending`/`.llmError`
— confirmed via grep, no changes needed there).

**Files to change**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/ErrorStateNoDeadEndTest.kt`

#### Story 6.1: `ErrorStateNoDeadEndTest.kt`

**Task 6.1.1**: In `ErrorStateNoDeadEndTest.kt` lines 52-58, replace:
```kotlin
state = TagSuggestionState.Ready(
    blockUuid = "block-1",
    localSuggestions = emptyList(),
    llmSuggestions = emptyList(),
    llmError = errorMessage,
),
```
with:
```kotlin
state = TagSuggestionState.Ready(
    blockUuid = "block-1",
    localSuggestions = emptyList(),
    llmSuggestions = emptyList(),
    llmStatus = dev.stapler.stelekit.tags.LlmSuggestionStatus.Failed(message = errorMessage, retryable = false),
),
```
Also add `onRetry = {},` to this test's `SuggestionBottomSheet(...)` call (new required
parameter from Story 5.2.1).

**Task 6.1.2**: Run
`./gradlew jvmTest --tests "dev.stapler.stelekit.ui.ErrorStateNoDeadEndTest"` (requires a
display — see CLAUDE.md's `xvfb-run` note for headless environments) and confirm the
existing assertion (error message text is rendered, Dismiss action is reachable) still
passes — the message now flows through `LlmSuggestionStatus.Failed.message` instead of
`Ready.llmError`, rendered by the same `SuggestionBottomSheet` `Failed` branch added in
Story 5.2.2.

---

## Full Regression / CI Task

**Task 7.0.1** (final gate, after all epics land): Run
`xvfb-run --auto-servernum ./gradlew ciCheck` (headless — see CLAUDE.md's display-detection
snippet for non-headless environments) to confirm detekt, `jvmTest`, Android unit tests, and
`assembleDebug` all pass together. Run
`./gradlew jvmTest --tests "dev.stapler.stelekit.tags.*"` and
`./gradlew jvmTest --tests "dev.stapler.stelekit.ui.*"` individually first if `ciCheck`'s
full run is slow, to get faster feedback on this project's specific surface before the full
gate.

---

## ADR References

| ADR | Decision | Status |
|---|---|---|
| ADR-001 | Interim `DEFAULT_POLL_DEADLINE_MS = 120_000L` from desk research (no physical AICore hardware available this session) — see `project_plans/llm-tag-download-stall/decisions/ADR-001-poll-deadline-estimate.md` | Written (Epic 0) |
| ADR-002 | Sheet dismiss deliberately does not hard-cancel the poll loop (FR-5's named deviation) — see `project_plans/llm-tag-download-stall/decisions/ADR-002-dismiss-does-not-cancel-poll-loop.md` | Written |

---

## Risk Control / Unresolved Questions

Mapped 1:1 to `research/pitfalls.md`'s 7 must-address items:

1. **Poll `Job` nested under `suggestionJob`, not a sibling** — satisfied structurally:
   `runLlmSuggest` (and the `TagAvailabilityPoller.pollUntilAvailable` suspend call inside
   it) executes as a plain suspend call within `suggestionJob`'s coroutine (Epic 4, Story
   4.1) — there is no second `scope.launch` anywhere in this plan. Story 4.4's regression
   test proves this holds at runtime, not just by code inspection.
2. **Poll loop calls `checkAvailability()` only, never `format()`/`suggestTags()`** —
   satisfied by construction: `TagAvailabilityPoller.pollUntilAvailable`'s only parameter
   for talking to the provider is the `checkAvailability` probe; `runLlmSuggest` calls
   `engine.llmSuggest()` (which reaches `format()`) exactly twice — before polling starts
   and once after `Available` is observed. Story 4.5's regression test proves this
   directly. As of the adversarial-review fix (Task 3.1.1), a `checkAvailability` tick that
   throws — including `Throwable` subtypes, not just `Exception` — is caught inline inside
   `pollUntilAvailable`'s loop, logged, and treated as transient (loop continues); it is
   never allowed to propagate up through `runLlmSuggest` into
   `TagSuggestionViewModel`'s `CoroutineExceptionHandler`. Task 3.2.5 is the regression test.
3. **`BACKGROUND_USE_BLOCKED` verification for `checkStatus()` while backgrounded** —
   **deferred, not resolved by this plan.** No `ProcessLifecycleOwner` wiring exists for
   `TagSuggestionViewModel` today (confirmed by `research/features.md`), and whether
   `checkStatus()` itself (not just `generateContent()`) is subject to background-blocking
   is unverified in this codebase or Google's docs. **Correction from the adversarial
   review**: the plan's original text here claimed `MlKitLlmFormatterProvider.checkAvailability()`'s
   generic `catch (e: Exception)` (lines 44-51) already degraded any unexpected throw to a
   safe `Unavailable(retryable = true)` — that claim is false for `Error` subtypes (OOM,
   native binder crash), which `catch (e: Exception)` does not catch at all, so it would
   have propagated uncaught. The actual safety net is now the `try`/`catch (e: Throwable)`
   added directly inside `TagAvailabilityPoller.pollUntilAvailable`'s loop (item 2 above,
   Task 3.1.1) — that is what degrades a background poll tick's unexpected throw to "log and
   keep polling" rather than crashing or nuking `_state`. Recommend a follow-up backlog item
   (`ProcessLifecycleOwner`-gated pause, mirroring `SafChangeDetector`'s existing pattern)
   rather than adding it speculatively to this bug-fix-shaped project.
4. **NFR-3 test-timing risk** — resolved by two layers. Epic 3's design:
   `TagAvailabilityPoller.pollUntilAvailable` is a stateless top-level function with no owned
   scope, directly callable under `runTest` virtual time (Story 3.2's 5 tests), exactly
   mirroring the proven `GitHubDeviceFlowClientTest.kt` pattern. **Correction (both reviews
   flagged this as a BLOCKER in the plan's first draft)**: `TagSuggestionViewModelTest.kt`'s
   Stories 4.4 and 4.5 tests do *not* get a free pass just because their probes resolve
   quickly in wall-clock terms — Task 4.4.4 specifically drives the VM past the *full*
   `DEFAULT_POLL_DEADLINE_MS` (120000ms) and Story 4.5's test needed ~5 real ticks × 4000ms ≈
   20 real seconds, and neither `awaitState`'s spin-poll nor `runTest`'s virtual time had any
   effect on the VM's real `Dispatchers.Default` scope. Task 4.1.1 fixed the root cause: the
   dispatcher and poll deadline/interval are now injectable constructor parameters, defaulting
   to production values, so `TagSuggestionViewModelTest.kt` can pass the *same*
   `StandardTestDispatcher` instance `runTest` uses plus millisecond-scale deadlines/intervals
   — Tasks 4.4.4 and 4.5.1 now complete in milliseconds of both real and virtual time, with no
   change to what either test asserts.
5. **Single-flight guard for the download-trigger side effect (`preload()` vs. first
   `requestSuggestions()` vs. poll ticks)** — **not newly introduced by this plan** (poll
   ticks never call `format()`, per item #2 above, so the poll loop is not a new source of
   this race). The `preload()`-vs-`requestSuggestions()` race is pre-existing and outside
   this project's scope per requirements.md's Out of Scope section (no mention of
   `preload()` changes) — noted here as an accepted, pre-existing risk, not a new one.
6. **Caption escalation driven by the loop's own elapsed-time state, not a composable
   timer** — satisfied by construction: `TagAvailabilityPoller.pollUntilAvailable` tracks
   `startedAt`/`escalated` internally and pushes `onStatusUpdate` exactly once at the
   escalation threshold; `SuggestionBottomSheet` (Story 5.2) only ever reads
   `state.llmStatus.caption`, it owns no `LaunchedEffect(...) { delay(45_000) }` timer of
   its own. Reopening a dismissed sheet mid-poll (per ADR-002) correctly shows whatever
   caption the loop last pushed into `cache`, not a reset one.
7. **`DEFAULT_POLL_DEADLINE_MS` real-hardware measurement** — resolved via ADR-001's desk
   research substitute + mandatory follow-up validation item (see ADR-001's Follow-up
   section). Not fully resolved in the sense FR-6 originally asked for (a real measurement) —
   `requirements.md`'s FR-6/AC6 itself authorizes no fallback (see ADR-001's "Attribution
   correction"); the interim substitution was a pragmatic planning-session decision, not a
   requirements-sanctioned exception, and is explicitly flagged as interim pending ADR-001's
   mandatory real-hardware re-validation.

**Additional open item — iOS activation via NFR-2's platform-agnostic design**: per the
Pattern Decisions row "Does the poll loop activate for iOS once/if an iOS on-device
`LlmProvider` is registered?", the poll loop's platform-agnostic construction means it will
technically activate for iOS automatically if/when an iOS on-device provider is registered,
using Android/AICore-sourced timing constants and copy. This is a known, accepted
consequence of NFR-2's design (reconciling the apparent conflict with requirements.md's
Out-of-Scope wording), not an open question requiring resolution before this project ships —
but any future iOS on-device integration should re-validate `DEFAULT_POLL_DEADLINE_MS` and
caption copy against iOS's actual download characteristics rather than silently inheriting
these Android-derived values.

**Additional open item — two disconnected sources of the "taking longer than expected"
string**: `TagAvailabilityPoller.STALLED_REASON` is threaded into
`DomainError.NetworkError.RequestFailed.message` in Task 4.1.2's `Unavailable` branch, but
that message is then discarded — `LlmSuggestionStatus.Stalled(retryable: Boolean)` has no
message field — and `SuggestionBottomSheet`'s `Stalled` branch (Task 5.2.2) independently
hardcodes its own literal `"Taking longer than expected."` (with a trailing period
`STALLED_REASON` lacks). This is accepted as-is rather than unified into a single source of
truth (e.g. adding a `reason: String` field to `Stalled` mirroring `Failed`'s shape) because
doing so would touch `LlmSuggestionStatus`, `runLlmSuggest`, and `SuggestionBottomSheet`
simultaneously for a purely cosmetic consistency fix with no user-visible behavior change —
out of proportion for this bug-fix-shaped project. Task 4.1.2 now carries an inline comment
documenting the duplication explicitly (rather than leaving it silently discoverable only by
grep) so a future contributor touching either string knows the other one exists.

**Additional open item** (pitfalls.md, not one of the numbered 7): `BUSY` (quota) and
`BACKGROUND_USE_BLOCKED` both map to a generic retryable `OnDeviceUnavailable` today with
*different* reason strings. This plan's design preserves the distinction where it matters
most (the initial caption, `Pending(failure.message)` in `runLlmSuggest`, is the verbatim
SDK-sourced reason for whichever condition actually occurred) and only generalizes to
"Taking longer than expected" at the terminal `Stalled` state (Epic 3, `TagAvailabilityPoller.STALLED_REASON`)
— this is a deliberate resolution of that open question, not an oversight: by the time the
120s deadline is reached, further distinguishing "still downloading" from "still hitting a
per-app quota" is not actionable to the user either way (both resolve the same way: wait,
then retry), so collapsing to one terminal message is acceptable.

### Follow-up items requiring a tracked backlog entry (not created by this project — flag for the human reviewer at ship time)

This project runs autonomously (no interactive user session) and cannot itself file tickets
in an external tracker. Both the architecture review and the Phase 4 triad review flagged
two items below that need a real backlog entry so they don't silently disappear once this
PR merges. Neither is blocking for this project to ship — both are pre-existing conditions
this project did not introduce — but both need a tracked follow-up. Whoever reviews/ships
this PR should file these in the team's tracker (30 seconds each, using the detail below).

1. **`LlmSynthesisService.kt:104` has the identical `retryable`-dropping bug this project
   fixes in `LlmTagProvider.kt`, and is explicitly out of scope here.** The line:
   ```kotlin
   is LlmResult.Failure.OnDeviceUnavailable -> DomainError.NetworkError.RequestFailed(result.reason).left()
   ```
   drops `result.retryable` on the floor exactly like `LlmTagProvider.suggestTags()` did
   before Epic 1 of this project (Task 1.2.2) fixed it — `LlmSynthesisService` (the
   "synthesize suggestions across a page" flow, distinct from `LlmTagProvider`'s per-block
   tag suggestion flow this project targets) still collapses a retryable on-device-download
   condition into a non-retryable failure with no poll/retry path. Backlog ticket should
   reference this file:line and this project's Epic 1 as the template fix.
2. **ADR-001's mandatory real-hardware re-validation of `DEFAULT_POLL_DEADLINE_MS` has no
   confirmed tracking item.** `project_plans/llm-tag-download-stall/decisions/ADR-001-poll-deadline-estimate.md`'s
   "Follow-up (mandatory)" section (lines 121–131) requires re-validating
   `DEFAULT_POLL_DEADLINE_MS` (currently `120_000L`, sourced from desk research — no physical
   AICore hardware was available during this planning session) against real Pixel 9+/AICore
   hardware, capturing actual first-download timing via app-side `Logger` transitions
   bracketing the `DOWNLOADABLE` → `AVAILABLE` transition cross-referenced with
   `adb logcat -s AiCoreService:* GenerativeAIService:*`, and adjusting
   `DEFAULT_POLL_DEADLINE_MS` in
   `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt`'s
   companion object if the measured value differs by more than 2x in either direction from
   the 120s interim estimate. ADR-001 itself says "log a backlog item for this validation
   pass if none exists at ship time" — this row is that confirmation that none is known to
   exist yet.

---

## Task Count Summary

- **Epics**: 7 (0 through 6, plus the standalone Full Regression/CI task)
- **Stories**: 17 (+1 vs. the prior draft — Story 4.6, added to resolve the Phase-4
  pre-mortem gate's P1 #1/#2 findings)
- **Tasks**: 49 (+3 vs. the prior draft — Task 3.2.6, Task 4.6.1, and Task 4.6.2, added to
  resolve the Phase-4 pre-mortem gate's P1 #1/#2 findings re: elapsed-time tracking resetting
  on block-switch and manual retry; see below. The prior draft's own +2 vs. its predecessor —
  Task 3.2.5 and Task 5.2.5 — resolved the earlier Phase-3 adversarial-review BLOCKER
  findings and are unchanged here.)
  - Epic 0: 1 task (Story 0.1)
  - Epic 1: 7 tasks (Stories 1.1–1.3)
  - Epic 2: 2 tasks (Story 2.1)
  - Epic 3: 8 tasks (Stories 3.1–3.2, +1: Task 3.2.5 — `checkAvailability()` throw-then-recover
    regression test, resolving the Phase-3 adversarial-review BLOCKER re: unguarded
    `checkAvailability()` calls inside `pollUntilAvailable`; +1: Task 3.2.6 — `startedAtOverride`
    poller-level unit test, resolving the Phase-4 pre-mortem P1 #1/#2 findings at the
    `TagAvailabilityPoller` primitive level)
  - Epic 4: 18 tasks (Stories 4.1–4.6, including 4.4b's AC5 lifecycle coverage; Task 4.1.1 now
    also adds the `dispatcher`/`pollDeadlineMs`/`pollIntervalMs`/`pollEscalationThresholdMs`
    constructor parameters and Tasks 4.4.4/4.5.1 are rewritten to use them — resolving the
    Phase-3 NFR-3 BLOCKER flagged by both reviews; no task count change from that fix, existing
    tasks expanded. +2: Story 4.6 — Tasks 4.6.1/4.6.2, the `downloadFirstObservedAtMs`
    block-switch/manual-retry regression tests, resolving pre-mortem P1 #1/#2)
  - Epic 5: 10 tasks (Stories 5.1–5.3, +1: Task 5.2.5 — retryable-`Failed` retry-button GWT,
    resolving the Phase-3 adversarial-review BLOCKER re: `LlmSuggestionStatus.Failed.retryable`
    dead code)
  - Epic 6: 2 tasks (Story 6.1)
  - Full Regression/CI: 1 task
- **New files**: 2 (`TagAvailabilityPoller.kt`, `TagAvailabilityPollerTest.kt`)
- **Modified files**: 11 (`DomainError.kt`, `LlmTagProvider.kt`, `TagSuggestionEngine.kt`,
  `App.kt`, `TagSuggestionState.kt`, `TagSuggestionViewModel.kt`, `TagChipRow.kt`,
  `SuggestionBottomSheet.kt`, `VoiceCaptureButton.kt`, `JournalsView.kt`, `PageView.kt`) plus
  2 modified test files (`TagSuggestionViewModelTest.kt`, `ErrorStateNoDeadEndTest.kt`) and
  possibly `TagSuggestionEngineTest.kt` for Task 1.2.2's regression test if a dedicated
  `LlmTagProviderTest.kt` is not created instead.

## Parallelization Guide

- **Epic 0** must complete first (already done) — it only gates Epic 3.
- **Epic 1** and **Epic 2** are mutually independent and can run in parallel branches; both
  must land before **Epic 4** starts (Epic 4's `runLlmSuggest` needs `checkAvailability` from
  Epic 1 and `LlmSuggestionStatus` from Epic 2).
- **Epic 3** depends only on Epic 0 (constant) and Epic 2 (callback type) — can run in
  parallel with Epic 1.
- **Epic 4** depends on Epics 1, 2, 3 — first point where all three prior epics must have
  landed.
- **Epic 5** depends on Epic 4 (`retryLastRequest()` must exist to wire `onRetry`).
- **Epic 6** depends only on Epic 2 (the `Ready` shape) — could technically run in parallel
  with Epics 3–5, but is trivial (1 file) and cheapest to do last as cleanup.
- Recommended sequencing for a single implementer: 0 → {1, 2 in parallel} → 3 → 4 → 5 → 6 →
  Full Regression/CI gate. For 2 implementers: one takes {1 → wait for 2 → 4's DomainError
  half}, the other takes {2 → 3 → hand off}, converge at Epic 4.
