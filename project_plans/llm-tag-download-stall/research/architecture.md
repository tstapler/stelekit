# Research: Architecture — llm-tag-download-stall

Scope: where the poll-and-auto-retry loop for on-device LLM tag suggestion should
live, how `retryable` should be threaded from `LlmResult` to the UI, and what
state-machine shape `TagSuggestionState` needs. Proposes structure only — no code
changes.

## Staleness note on prior research docs

`project_plans/llm-service/research/architecture.md` (L13-131, L299-324) is the
design lineage for `LlmProvider`/`LlmProviderAvailability`/`LlmProviderRegistry` and
is now **partially stale**, confirmed against current code:

| Then (llm-service doc) | Now (current code) |
|---|---|
| `MlKitLlmFormatterProvider` "referenced nowhere... dead code" (L15) | Wired via `AndroidOnDeviceLlmProvider implements LlmProvider` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/AndroidOnDeviceLlmProvider.kt`), which is resolved into `App.kt`'s `tagLlmProviderState` (App.kt:1088-1103) and reaches `TagSuggestionEngine`/`LlmTagProvider` in production, not just tests. |
| `checkEligible(): Boolean` | Replaced by `suspend fun checkAvailability(): LlmProviderAvailability` (tri-state) on both `MlKitLlmFormatterProvider` and the `LlmProvider` interface it backs. |
| Proposed `LlmProviderAvailability` had 3 cases: `Available`, `Downloading`, `Unavailable(reason: String)` (L56-60) | Shipped shape (`llm/LlmProviderAvailability.kt`) has `Available`, `Preparing(detail: String?)`, `Unavailable(reason: String, retryable: Boolean = false)` — `Downloading` was renamed/generalized to `Preparing`, and critically **`retryable` was added to `Unavailable`**. This `retryable` field is the signal this project needs to thread through — it did not exist yet when the llm-service doc was written. |
| `LlmProviderRegistry`, `LlmSettings`, `TagSettings`-shaped settings class — all proposed | All now implemented and wired into `App.kt` (`llmProviderRegistry`, `llmSettings`, `LlmFeature.TAG_SUGGESTION`, `tagSettings.isLlmTierEnabled()`). |

Everything else in that doc (provider abstraction rationale, registry-over-enum
reasoning, expect/actual on-device wiring pattern) is still accurate and not
re-litigated here.

## Summary of current code reviewed

| File | Current role |
|---|---|
| `tags/TagSuggestionViewModel.kt` (L38-186) | Owns `_state: StateFlow<TagSuggestionState>`, a `SupervisorJob`-backed `scope` that survives `dismiss()` (comment L178: "Do NOT cancel suggestionJob — let the LLM finish in the background"), and a `cache: MutableMap<String, TagSuggestionState.Ready>` keyed by block UUID. `requestSuggestions()` (L75-125) launches one job: emit local matches immediately, then `engine.llmSuggest()` once, fold into `llmSuggestions`/`llmError`/`llmPending=false`. No retry, no re-check. |
| `tags/LlmTagProvider.kt` (L14-79) | Constructed with only `LlmFormatterProvider` (L14-15) — the narrow `fun interface { suspend fun format(...): LlmResult }`, **not** `LlmProvider`. Has no access to `checkAvailability()`. `suggestTags()` calls `provider.format()` once inside a `withTimeout(90s)`, maps `LlmResult.Failure.OnDeviceUnavailable(reason, retryable)` → `DomainError.NetworkError.RequestFailed(result.reason)` (L59-61), **dropping `retryable`** — this is the exact bug named in FR-3. |
| `tags/TagSuggestionEngine.kt` (L8-81) | Constructor holds `pageNameIndex`, `llmTagProvider: LlmTagProvider?`, `vocabularyProvider`. **No reference to `LlmProvider`** — only ever sees the pre-wrapped `LlmTagProvider`. `llmSuggest()` (L57-75) is a single suspend call, no polling. |
| `llm/LlmProviderAvailability.kt` | Tri-state: `Available`, `Preparing(detail: String?)`, `Unavailable(reason: String, retryable: Boolean = false)`. Lives in `commonMain` — satisfies NFR-2 already. |
| `llm/LlmProvider.kt` | `interface LlmProvider { id, displayName, kind, formatter: LlmFormatterProvider, supportsLongFormOutput, suspend fun checkAvailability(): LlmProviderAvailability }`. **This is the only type with `checkAvailability()`.** |
| `androidMain/.../llm/AndroidOnDeviceLlmProvider.kt` | `LlmProvider` impl; `checkAvailability()` just delegates to `MlKitLlmFormatterProvider.checkAvailability()`. |
| `androidMain/.../voice/MlKitLlmFormatterProvider.kt` (L43-53, L55-109) | `checkAvailability()` maps ML Kit `FeatureStatus` → `LlmProviderAvailability` (pure logic in `mapMlKitFeatureStatus`, testable off-device). `format()` independently re-checks `model.checkStatus()` and on `DOWNLOADABLE`/`DOWNLOADING` returns `LlmResult.Failure.OnDeviceUnavailable(reason, retryable = true)` (L69-84) without waiting — this is root-cause site #1. |
| `ui/App.kt` (L1088-1129) | `tagLlmProviderState: LlmProvider?` is resolved live via `produceState` (L1088-1103) — **the full `LlmProvider`, including `checkAvailability()`, is already available here.** But wiring immediately narrows it: `LlmTagProvider(it.formatter)` (L1108) — only `.formatter` (an `LlmFormatterProvider`) is threaded down into `TagSuggestionEngine`/`LlmTagProvider`. `checkAvailability()` is dropped at this exact call site. `TagSuggestionViewModel(tagEngine, onPropose=...)` (L1122) receives only the engine — no independent path to `LlmProvider`. |
| `error/DomainError.kt` (L45-50) | `NetworkError` has 4 cases: `HttpError`, `CircuitOpen`, `Timeout`, `RequestFailed(message)`. No `retryable` field anywhere in `NetworkError`. Precedent for retry metadata living *inside* a `DomainError` case already exists: `GitError.RateLimited(val retryAfterSeconds: Int?)` (L97-99). |
| `git/GitHubDeviceFlowClient.kt` (L96-130) | **Direct architectural precedent for this exact shape of problem** (OAuth device-flow polling: "check status, wait, check again, stop at a deadline, surface intermediate states"). `pollForToken(deviceCode, expiresIn, initialInterval, onStateChange: (DeviceFlowPollState) -> Unit): Either<DomainError.GitError, String>` is a single **stateless** suspend function (class doc L34: "no internal CoroutineScope and no mutable state" — caller launches it in their own scope). Deadline computed once via `Clock.System.now().toEpochMilliseconds() + expiresIn * 1000L` (L106), loop is `while (now < deadline) { delay(intervalMs); check; onStateChange(...) }`. `GitHubDeviceFlowClientTest.kt` uses plain `kotlinx.coroutines.test.runTest` with **no injected clock/dispatcher abstraction** — `runTest` auto-fast-forwards real `delay()` calls, which is sufficient for NFR-3's "poll loop timing must be controllable in tests" with zero new test infrastructure. |
| `ui/components/tags/SuggestionBottomSheet.kt`, `TagChipRow.kt` | Render `state.llmPending: Boolean` (spinner) and `state.llmError: String?` (static red text, L93-100 of `SuggestionBottomSheet.kt`) directly off `TagSuggestionState.Ready`. No DomainError type ever reaches the UI layer — only the flattened `llmError` string. Confirms `DomainError` is purely an internal transport signal here, never pattern-matched in Compose code. |

---

## 1. Which layer owns the poll loop

### Decision: `TagSuggestionViewModel` (or a small collaborator it owns), not `LlmTagProvider`.

**Why not `LlmTagProvider.suggestTags()`:**
1. **No access to `checkAvailability()`.** `LlmTagProvider` is constructed with `LlmFormatterProvider` (the narrow format-only contract), not `LlmProvider`. Giving it polling responsibility means either (a) widening its constructor to accept `LlmProvider` instead — which touches 3 existing test-construction sites (`TagSuggestionViewModelTest.kt:145`, `TagSuggestionEngineTest.kt:50`, `TagInsertionFlagshipUiTest.kt:73`, all of which build it from a bare fake `LlmFormatterProvider`) and conflates "build prompt + call format" with "poll for readiness" in one class — or (b) adding a second, redundant availability dependency alongside the formatter. Neither is clean.
2. **A single suspend call can't emit incremental UI state.** FR-2 requires the caption to visibly change at ~45s *before* the terminal state. `suggestTags()` returns one `Either<DomainError, List<TagSuggestion>>` at the end of its own `withTimeout(90s)` — there is no channel for it to push intermediate "still waiting, N seconds elapsed" updates out to the ViewModel mid-call without inventing a callback/Flow return type that changes its whole call contract (and `TagSuggestionEngine.llmSuggest()`'s, transitively).
3. **`TagSuggestionEngine` doesn't have the availability signal either** — it only ever receives the pre-built `LlmTagProvider`, never the `LlmProvider` it was built from (App.kt:1108 discards it at construction). So even "poll one layer down, in the Engine" isn't available without new wiring, same problem as #1.

**Why the ViewModel is the right level:**
- It already owns the only coroutine `scope` in this stack that is deliberately kept alive across `dismiss()` (L178, and FR-5's ADR-002 deviation is about *this exact scope*) — the poll loop's lifecycle requirement ("self-terminates at deadline, not hard-cancelled by sheet dismiss") is something the ViewModel's `scope` already does for the LLM call; extending it to cover polling too is a lifecycle extension, not a new pattern.
- `requestSuggestions()` is already the per-block orchestration point (cache lookup, cancel-if-different-block, launch) — the natural place to decide "poll or call now."
- `TagSuggestionEngine.hasLlmProvider` is already a property the ViewModel reads directly (L68) — the ViewModel already has a narrow, one-property relationship to engine-level LLM plumbing; extending that surface with one more read-only capability (`checkAvailability()`) is consistent, not a layering violation.

**Wiring change required:** `TagSuggestionEngine` needs one new **optional** constructor dependency to expose availability, since it currently has none:

```kotlin
class TagSuggestionEngine(
    private val pageNameIndex: PageNameIndex,
    private val llmTagProvider: LlmTagProvider? = null,
    private val vocabularyProvider: () -> List<String> = { pageNameIndex.vocabularyNames() },
    // New — narrow function type, not the full LlmProvider, so Engine doesn't need to
    // import dev.stapler.stelekit.llm.LlmProvider, only LlmProviderAvailability (already
    // commonMain, already a dependency of this feature via LlmResult.Failure.OnDeviceUnavailable's
    // sibling type). Defaults to null so every existing test construction site
    // (TagSuggestionEngineTest.kt, TagSuggestionViewModelTest.kt) keeps compiling unchanged.
    private val checkAvailability: (suspend () -> LlmProviderAvailability)? = null,
) {
    suspend fun checkAvailability(): LlmProviderAvailability? = checkAvailability?.invoke()
    ...
}
```

App.kt wiring (L1104-1109) changes from discarding `tagLlmProviderState.value` down to
`.formatter` only, to also threading the availability probe:

```kotlin
val tagEngine = remember(viewModel.pageNameIndex, tagSettings.isEnabled(), tagLlmProviderState.value) {
    if (!tagSettings.isEnabled()) null
    else TagSuggestionEngine(
        pageNameIndex = viewModel.pageNameIndex,
        llmTagProvider = tagLlmProviderState.value?.let { LlmTagProvider(it.formatter) },
        checkAvailability = tagLlmProviderState.value?.let { p -> { p.checkAvailability() } },
    )
}
```

`LlmTagProvider` itself is **unchanged** — zero ripple to its 3 test-construction sites.

**Alternative considered and rejected:** thread `LlmProvider` (not just `.formatter`)
straight into `LlmTagProvider`'s constructor, replacing `LlmFormatterProvider`. Rejected
because it still doesn't solve problem #2 above (no incremental caption updates), and it
widens `LlmTagProvider`'s dependency for a capability (`checkAvailability`) it would never
actually use internally — the poll loop still has to live above it. Pure churn without a
payoff.

**Poll loop shape — model it on `GitHubDeviceFlowClient.pollForToken()`:** a stateless
suspend function/method, not a class holding mutable poll state, with an `onStatusUpdate`
callback for intermediate ticks:

```kotlin
// New method, either directly on TagSuggestionViewModel or extracted to a small
// dedicated collaborator (e.g. LlmAvailabilityPoller) if TagSuggestionViewModel's line
// count becomes a concern in planning — the shape is identical either way.
private suspend fun pollUntilAvailable(
    checkAvailability: suspend () -> LlmProviderAvailability,
    onStatusUpdate: (LlmSuggestionStatus) -> Unit,
): LlmProviderAvailability {
    val deadline = Clock.System.now().toEpochMilliseconds() + DEFAULT_POLL_DEADLINE_MS
    val startedAt = Clock.System.now().toEpochMilliseconds()
    while (true) {
        when (val availability = checkAvailability()) {
            is LlmProviderAvailability.Available -> return availability
            is LlmProviderAvailability.Unavailable -> {
                if (!availability.retryable) return availability   // FR-4: genuinely unavailable, stop now
            }
            is LlmProviderAvailability.Preparing -> Unit
        }
        val now = Clock.System.now().toEpochMilliseconds()
        if (now >= deadline) return LlmProviderAvailability.Unavailable("Taking longer than expected", retryable = true)
        val elapsed = now - startedAt
        onStatusUpdate(
            if (elapsed >= CAPTION_ESCALATION_THRESHOLD_MS) LlmSuggestionStatus.Pending(LONG_WAIT_CAPTION)
            else LlmSuggestionStatus.Pending(DEFAULT_WAIT_CAPTION)
        )
        delay(DEFAULT_POLL_INTERVAL_MS)
    }
}
```

`kotlinx.coroutines.test.runTest` fast-forwards real `delay()` calls automatically (already
proven by `GitHubDeviceFlowClientTest.kt`, which asserts on `pollForToken`'s multi-iteration
loop with zero injected clock/dispatcher). This satisfies NFR-3 with no new test
infrastructure — no `TimeProvider`/`Clock` injection needed, just plain `delay()` +
`kotlinx.coroutines.test`.

---

## 2. `DomainError` extension for `retryable`

### Decision: both — additive field on `DomainError.NetworkError.RequestFailed`, *and* a mirrored field on `TagSuggestionState.Ready`. Not either/or.

These solve two different gaps and one doesn't substitute for the other:

**Why `DomainError` still needs it (closes a TOCTOU race the poll loop can't cover):**
The poll loop's own terminal `Unavailable(retryable)` (FR-2's "taking longer than expected"
state) already carries `retryable` natively via `LlmProviderAvailability` — no `DomainError`
involved, since the ViewModel never calls `engine.llmSuggest()` in that branch at all. But
there's a second path: the poll loop sees `Available`, calls `engine.llmSuggest()` →
`LlmTagProvider.suggestTags()` → `provider.format()`, and *at that exact moment* the
on-device model regresses (quota hit, backgrounded, AICore reset) and `format()` itself
returns `LlmResult.Failure.OnDeviceUnavailable(reason, retryable)` (L59-61 of
`LlmTagProvider.kt` today). In that race window, `retryable` only reaches the ViewModel
through `llmSuggest()`'s `Either<DomainError, ...>` return — so `DomainError` has to carry
it, or the manual-retry affordance is simply wrong in that case (shows non-retryable UI for
a condition that is retryable, or vice versa).

**Shape:** add `retryable: Boolean = false` as an additive default param to the existing
`RequestFailed` case, not a new sealed case:

```kotlin
data class RequestFailed(override val message: String, val retryable: Boolean = false) : NetworkError
```

This is fully backward compatible — all 12 existing call sites across
`CustomOpenAiCompatibleLlmProvider.kt`, `WaybackMachineService.kt`, `LlmSynthesisService.kt`
(×4), `LlmTagProvider.kt` (×4), and `DomainErrorTest.kt` (×2) construct it with only the
message positional arg today and keep compiling unchanged. `LlmTagProvider.kt`'s L59-61
mapping becomes:

```kotlin
is LlmResult.Failure.OnDeviceUnavailable -> DomainError.NetworkError.RequestFailed(
    result.reason, retryable = result.retryable,
).left()
```

Precedent for embedding retry metadata directly in a `DomainError` case already exists:
`GitError.RateLimited(val retryAfterSeconds: Int?)` (DomainError.kt:97-99). A new sealed
case was considered and rejected — it would force a new branch in `toUiMessage()`'s
exhaustive `when` (small, acceptable) but buys nothing over the additive-field approach
since `RequestFailed` is already the exact case this maps to today; a new case only adds a
type-level distinction nothing currently needs.

**Side note (not in scope, flagging for awareness):** `LlmSynthesisService.kt:104` has the
identical drop-`retryable` bug (`is LlmResult.Failure.OnDeviceUnavailable ->
DomainError.NetworkError.RequestFailed(result.reason)`), outside this project's scope
(`dev.stapler.stelekit.tags` package only). Extending `RequestFailed` with a default
`retryable` param does not fix it silently (still defaults to `false` there), but it's a
one-line follow-up on the same fix elsewhere if someone wants it later.

**Why `TagSuggestionState` *also* needs its own field, not just a DomainError read-through:**
`SuggestionBottomSheet.kt`/`TagChipRow.kt` never touch `DomainError` today — they render
`state.llmError: String?`/`state.llmPending: Boolean` only (confirmed: no `DomainError`
import in either file). `DomainError` is purely the ViewModel-internal transport signal
between `LlmTagProvider`→`TagSuggestionEngine`→`TagSuggestionViewModel`; the UI-facing
contract is `TagSuggestionState`. So the ViewModel must **read** `retryable` off whichever
source produced the terminal state (poll-loop's own `LlmProviderAvailability.Unavailable
.retryable`, or `DomainError.NetworkError.RequestFailed.retryable` from the TOCTOU race) and
**write** it into a single UI-facing field on `TagSuggestionState`. One boolean, two possible
producers, unified before it reaches Compose.

---

## 3. `TagSuggestionState` shape

### Decision: replace the flat `llmPending: Boolean` / `llmError: String?` pair on `Ready` with a small sealed `LlmSuggestionStatus` field, not more flat fields.

The naive extension — bolting on `llmCaption: String?`, `llmRetryable: Boolean`,
`llmStalled: Boolean` next to the existing `llmPending`/`llmError` — would leave `Ready`
with 5 loosely-coupled, partially-redundant fields with implicit invariants nothing
enforces (`llmError` should never be non-null while `llmPending` is true; `llmStalled`
and `llmError` are mutually exclusive terminal states but nothing says so). This is exactly
the "primitive obsession / illegal states representable" shape the codebase avoids
elsewhere — `LlmProviderAvailability`, `LlmResult`, and `BulkScanState` (this same file,
L20-24) are all small sealed hierarchies for precisely this reason, not boolean+nullable-
string bags.

```kotlin
sealed interface LlmSuggestionStatus {
    /** No LLM provider configured — engine.hasLlmProvider was false at request time. */
    data object NotStarted : LlmSuggestionStatus
    /** Actively polling or running the LLM call. caption escalates at CAPTION_ESCALATION_THRESHOLD_MS. */
    data class Pending(val caption: String? = null) : LlmSuggestionStatus
    /** llmSuggestions is populated (possibly empty — a genuine "nothing relevant" result). */
    data object Resolved : LlmSuggestionStatus
    /** FR-2 terminal state: poll deadline reached without becoming Available. */
    data class Stalled(val retryable: Boolean) : LlmSuggestionStatus
    /** FR-3: llmSuggest() itself failed (including the TOCTOU OnDeviceUnavailable race). */
    data class Failed(val message: String, val retryable: Boolean) : LlmSuggestionStatus
}

data class Ready(
    val blockUuid: String,
    val localSuggestions: List<TagSuggestion>,
    val llmSuggestions: List<TagSuggestion>,
    val llmStatus: LlmSuggestionStatus = LlmSuggestionStatus.NotStarted,
) : TagSuggestionState
```

Both `Stalled` and `Failed` carry `retryable` — FR-3's manual-retry button reads
`(llmStatus as? LlmSuggestionStatus.Stalled)?.retryable ?: (llmStatus as? Failed)?.retryable`,
or more simply a small `val TagSuggestionState.Ready.canRetry: Boolean` extension that
matches both.

**Ripple to size in planning (explicitly not executed here):** `SuggestionBottomSheet.kt`
(L80, L93-100), `TagChipRow.kt` (L25-26, L52, L60-63), `TagSuggestionViewModelTest.kt`,
`TagSuggestionEngineTest.kt`, `TagInsertionFlagshipUiTest.kt` all currently read
`llmPending`/`llmError` directly and need updating to pattern-match `llmStatus` instead.
Moderate, mechanical diff — this is a Phase 3 sizing question, not an architecture blocker.

**Alternative (flat fields) is viable if planning wants to minimize diff size** — noted as
the explicit tradeoff, not dismissed: smaller PR, no ripple to the 5 call sites above, at
the cost of the illegal-state risk described. Recommend the sealed subtype given the
codebase's consistent precedent, but this is a legitimate judgment call for Phase 3.

---

## 4. Constant placement

Following the `TagSuggestionEngine.AUTO_APPLY_THRESHOLD` (`TagSuggestionEngine.kt:24`) /
`LlmTagProvider.MAX_BLOCK_CHARS` (`LlmTagProvider.kt:19`) pattern — a `private const val` in
the `companion object` of the class that owns the behavior the constant governs. Since the
poll loop lives in `TagSuggestionViewModel` (§1), the constants belong there:

```kotlin
class TagSuggestionViewModel(...) {
    companion object {
        /** FR-0: background poll interval while a suggestion is pending. */
        const val DEFAULT_POLL_INTERVAL_MS = 4_000L   // midpoint of the "3-5s" FR-0 range
        /**
         * FR-2: bounded wait ceiling. FR-6 — MUST be set from a real on-device AICore
         * first-download timing measurement, not a guess. THIS VALUE IS A PLACEHOLDER;
         * do not carry it into planning/implementation without the measurement.
         */
        const val DEFAULT_POLL_DEADLINE_MS = /* TBD — blocked on FR-6 */
        /** FR-2: caption must change at least once before the Stalled terminal state. */
        const val CAPTION_ESCALATION_THRESHOLD_MS = 45_000L
    }
}
```

**FR-6 is a hard prerequisite this research phase cannot satisfy** — it explicitly requires
real-hardware measurement of AICore first-download duration, which is an empirical
measurement task (device time, not code archaeology), not something derivable from reading
the codebase. Flagging this prominently: **planning must not proceed to picking a
`DEFAULT_POLL_DEADLINE_MS` value until that measurement exists** — this is a blocking
dependency for Phase 3, likely worth its own dedicated measurement task/agent with access to
a physical Pixel 9+/AICore-capable device before implementation starts.

If a small dedicated `LlmAvailabilityPoller` collaborator is extracted instead of keeping
the loop inline in the ViewModel (see §1's "if line count becomes a concern" note), these
constants move with it to that class's companion object instead — same pattern, different
host class.

---

## 5. EventStorming (Event-Command-Policy)

Warranted — this is a genuine small state machine (model status transitions × polling
policy × user-triggered retry), not a single linear request/response.

| Trigger | Command | Policy (business rule) | Event | Resulting state |
|---|---|---|---|---|
| User opens suggestion sheet for a block | `RequestSuggestions(blockUuid)` | If cached & resolved, serve from cache (existing L76-85 behavior — unchanged) | `LocalMatchesFound` | `Ready(llmStatus=Pending)` |
| — (continued, no cache hit / stale pending) | `CheckAvailability` | FR-4: if `Unavailable(retryable=false)`, stop immediately — no polling | `AvailabilityChecked(Available \| Unavailable \| Preparing)` | `Failed(retryable=false)` (terminal, non-retryable) **or** continue polling |
| Poll tick, still not available, < deadline | `CheckAvailability` (repeat) | FR-0: re-check every 3-5s | `AvailabilityChecked` | `Pending(caption=default)` |
| Poll tick, elapsed ≥ 45s, still not available | `CheckAvailability` (repeat) | FR-2: escalate caption once threshold crossed | `CaptionEscalationDue` | `Pending(caption=longWait)` |
| Poll tick, elapsed ≥ `DEFAULT_POLL_DEADLINE_MS` | `StopPolling` | FR-2: bounded wait — never poll forever | `PollDeadlineReached` | `Stalled(retryable=true)` (terminal) |
| `checkAvailability()` returns `Available`, sheet still open for this block | `RunLlmSuggest` | FR-1: auto-resolve, no manual retrigger | `LlmSuggestSucceeded \| LlmSuggestFailed` | `Resolved` or `Failed(retryable=?)` (TOCTOU race, §2) |
| User switches to a different block mid-poll | `CancelPreviousJob` (existing L88 behavior) | Only cancel if job is for a *different* block (existing rule, unchanged) | `SuggestionJobCancelled` | Poll loop for old block ends silently; no state write for stale block |
| Sheet dismissed while polling/pending | *(no command — explicit no-op, FR-5/ADR-002)* | Do NOT cancel the job; let it finish/self-terminate in background (existing L177-181 pattern, extended to cover polling) | — | `_state` → `Idle`; background job continues, writes to `cache` when it eventually resolves/stalls |
| User taps "Retry" on a `Stalled`/`Failed(retryable=true)` state | `RequestSuggestions(blockUuid)` (re-invoke) | FR-3: manual retry re-enters the same flow from the top — no separate retry code path needed, since `requestSuggestions()` already has "pending but job was cancelled — fall through to re-run" logic (L84) | `SuggestionJobRestarted` | Back to `Pending` |
| Bulk scan entry processed | `RunLlmSuggestNoPolling` (`allowPolling=false`) | FR-7: fail-fast — on `Unavailable`, skip this entry immediately, no poll, no retry, continue to next entry (existing L136-137 `ifLeft = { /* skip */ }` behavior, unchanged) | `EntrySkipped \| EntryResolved` | Per-entry `Ready` states written straight to `cache`, `Resolved`/no `Pending`/`Stalled` intermediate ever surfaces |

`allowPolling` (named directly in the requirements' Scope section) is best implemented as a
parameter on a shared private helper (e.g. `runLlmSuggest(blockContent, alreadyLinkedTerms,
allowPolling, onStatusUpdate)`) that both `requestSuggestions()` (`allowPolling=true`) and
`scanEntries()` (`allowPolling=false`) call — this keeps the fail-fast bulk-scan behavior and
the new interactive polling behavior on one tested code path instead of two independently
maintained ones, and gives `FR-7`'s requirement a literal, greppable implementation instead
of relying on scanEntries() simply never calling the new poll method.

---

## Key files for planning phase

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModel.kt` — houses the new poll loop (or delegates to a new collaborator), new companion constants, `requestSuggestions()`/`scanEntries()` refactored onto a shared `allowPolling`-gated helper.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionEngine.kt` — add optional `checkAvailability: (suspend () -> LlmProviderAvailability)?` constructor param + `suspend fun checkAvailability(): LlmProviderAvailability?`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt` — one-line fix: preserve `result.retryable` when mapping `OnDeviceUnavailable` → `DomainError.NetworkError.RequestFailed` (L59-61). No constructor change.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/TagSuggestionState.kt` — replace `llmPending`/`llmError` with `llmStatus: LlmSuggestionStatus` sealed field (or flat-field alternative, per Phase 3 call).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` — add `retryable: Boolean = false` to `NetworkError.RequestFailed`; one new branch in `toUiMessage()`'s exhaustive `when`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (L1104-1109) — thread `tagLlmProviderState.value?.checkAvailability` into `TagSuggestionEngine`'s new param.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/tags/SuggestionBottomSheet.kt` (L78-101), `TagChipRow.kt` (L25-26, L52, L60-63) — render `llmStatus` (caption text, stalled/retry affordance) instead of `llmPending`/`llmError`.
- `kmp/src/git/GitHubDeviceFlowClient.kt` (L96-130) + `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClientTest.kt` — structural + test-pattern precedent to copy for the poll loop and its tests (`kotlinx.coroutines.test.runTest`, no injected clock).
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/tags/TagSuggestionViewModelTest.kt`, `TagSuggestionEngineTest.kt`, `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/TagInsertionFlagshipUiTest.kt` — existing test-construction sites for `LlmTagProvider`/`TagSuggestionEngine`/`TagSuggestionViewModel`; unaffected by the `LlmTagProvider` change (no ripple), affected by the `TagSuggestionState` shape change (ripple to size in Phase 3).
- **FR-6 blocker**: real-hardware AICore first-download timing measurement must happen before `DEFAULT_POLL_DEADLINE_MS` can be set — not resolvable from code research, flag for a dedicated measurement task ahead of Phase 3 planning.
