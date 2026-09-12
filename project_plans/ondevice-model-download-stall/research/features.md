# Research: Feature Landscape — On-Device Model Download Status

## 1. Existing "async status display" patterns in this codebase

Two distinct families exist; the fix should pick the one matching each sub-problem rather than
inventing a third.

### 1a. `produceState` keyed on identity — the pattern currently in use (and the bug)

`LlmProviderRow` (`LlmProviderListScreen.kt:118-120`):
```kotlin
val availability by produceState<LlmProviderAvailability?>(initialValue = null, provider) {
    value = provider.checkAvailability()
}
```
This is a **one-shot** resolution keyed on `provider`'s object identity. Since `llmProviderRegistry`
is `remember`'d in `App.kt:485-487` (only rebuilt when credentials/settings change via
`llmRegistryRefreshToken`), `provider` never changes identity while the Settings dialog stays open
— `produceState` never re-runs. This is root cause #2 from requirements.md, confirmed structurally.

Same `LaunchedEffect(feature, registry)` one-shot pattern appears in `PerFeatureProviderPicker.kt:60-65`
— also never refreshes after mount.

### 1b. `LaunchedEffect` + `delay()` self-driving timer loop — the pattern to imitate for polling

Two precedents show the idiom this codebase already uses for "update on a timer without an
external trigger":

- `SyncStatusBadge.kt:252-271` (`SyncState.Success` branch): `LaunchedEffect(syncState) { delay(3_000); visible = false }` — single-shot delayed state flip, not a repeating poll, but shows the `LaunchedEffect` + `delay` idiom colocated with a `mutableStateOf`.
- `GitHubOAuthDialog.kt` (`OAuthDialogState.Polling`): device-flow OAuth polling — uses `kotlin.time.Clock` + `delay()` inside `LaunchedEffect`, driven by a state machine (`Loading → ShowCode → Polling → Success/Error`) passed in from outside the dialog (the polling *loop* itself lives in the ViewModel/caller, not inline in the dialog composable — the dialog is a pure state renderer). This is the closer architectural precedent: the composable renders a sealed state, a non-Compose owner (ViewModel-ish class) drives the timer and re-invokes the check.

**Recommendation for consistency**: rather than putting `delay()`-loop polling directly inside
`LlmProviderRow` (a private composable with no owner beyond composition), prefer a `LaunchedEffect(provider)` loop that re-invokes `checkAvailability()` on an interval *only while* the current value `is Preparing` — mirroring `GitHubOAuthDialog`'s "only poll in the Polling state" discipline. This directly serves req. #2 (periodic refresh while downloading) without polling forever on `Available`/`Unavailable` terminal states (see §3 below on `FeatureStatus.UNAVAILABLE`).

### 1c. Manual refresh affordance — existing precedent for req. #3

`ProviderStatusIndicator`'s `Unavailable` branch already has the exact shape needed for
`Preparing`: `IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, ...) }` gated on
`availability.retryable && onRetry != null` (`LlmProviderListScreen.kt:186-192`). The `Preparing`
branch (lines 182-185) renders `StatusDotLabel` only, with no `onRetry` parameter threaded through
at all — `ProviderStatusIndicator(availability)` is called from `LlmProviderRow` without an
`onRetry` argument (line 149), so even the existing `Unavailable`-retry button is currently
unreachable from this call site. Fixing req. #3 means: (a) thread an `onRetry: () -> Unit` from
`LlmProviderRow` into `ProviderStatusIndicator`, (b) add the same `IconButton` to the `Preparing`
branch, reusing `StatusDotLabel`'s existing color/label rendering.

### 1d. `FolderSyncReconciliationProgress.kt` — sealed UI-state precedent, not a polling precedent

`ReconciliationUiState` (`Connecting`/`Summary`/`Failed`) is a good example of a dedicated sealed
UI-state type for an async operation, and uses `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`
for screen-reader announcement on state transitions (Task 3.1.2c). If the fix introduces a new
"stalled/timeout" copy variant (req. #4), consider the same `liveRegion` treatment so a screen
reader user is told when the status text changes from "downloading" to "taking longer than
expected" — `LlmProviderListScreen.kt` currently has no `liveRegion` semantics anywhere.

## 2. Other call sites of `checkAvailability()` / `LlmProviderAvailability` beyond `LlmProviderListScreen.kt`

This is the most consequential finding: **`checkAvailability()` is called from at least four
places that are simultaneously composed on the same Settings screen**, all assuming it is a pure,
side-effect-free read:

1. `LlmProviderListScreen.kt:119` — `LlmProviderRow`'s `produceState` (1 on-device row).
2. `LlmProviderRegistry.availableProviders()` (`LlmProviderRegistry.kt:44-45`) — `providers.filter { it.checkAvailability() !is Unavailable }`. Explicitly documented as "re-evaluated on every call, never a cached snapshot."
3. `LlmProviderRegistry.availableForFeature()` (`LlmProviderRegistry.kt:58-69`) — calls `availableProviders()` internally, i.e. calls `checkAvailability()` again.
4. `PerFeatureProviderPicker.kt:60-65` — `LaunchedEffect(feature, registry) { availableProviders = registry.availableForFeature(feature, ...) }`. **`LlmProviderSettings.kt:77` and `:122` render `LlmProviderListScreen` AND one `PerFeatureProviderPicker` per `LlmFeature` (`VOICE_FORMATTING`, `TAG_SUGGESTION`, `GRAPH_EDIT_SYNTHESIS` — `LlmFeature.kt:6`) on the same screen simultaneously** — so on opening Settings → AI Providers, `checkAvailability()` fires **4 times concurrently** against the identical `AndroidOnDeviceLlmProvider` instance (registry is `remember`'d once in `App.kt:485-487`, so it's the same object every time).
5. `VoicePipelineFactory.kt:58` — `registry.availableForFeature(LlmFeature.VOICE_FORMATTING)` — a real feature-resolution path (not UI), runs every time the voice pipeline needs to pick a provider.
6. `LlmSynthesisService.kt:55,73` — `registry.availableForFeature(...)` / `registry.availableProviders()` — same, for graph-edit synthesis provider resolution.

**Implication for the fix**: if `MlKitLlmFormatterProvider.checkAvailability()` is changed to call
`generateContent()` as a trigger (mirroring `format()`'s existing workaround at lines 69-77), that
side effect would now also fire from `LlmProviderRegistry.availableProviders()`/`availableForFeature()`
— meaning **every feature-resolution call** (`VoicePipelineFactory`, `LlmSynthesisService`, both
`PerFeatureProviderPicker` instances) would also kick off a redundant `generateContent()` call, not
just the Settings screen. This is broader blast radius than the requirements doc's framing ("fixing
the Settings row") suggests — the trigger fix should almost certainly live in a narrower place than
`checkAvailability()` itself (see §3).

## 3. Edge cases

### 3a. `generateContent()` triggered from `checkAvailability()` racing a real `format()` call

Confirmed: there is exactly **one** `GenerativeModel` instance per process. `MlKitLlmFormatterProvider.create()`
(`MlKitLlmFormatterProvider.kt:28-34`) calls `Generation.getClient()` once; `platformOnDeviceLlmProvider()`
→ `AndroidOnDeviceLlmProvider` wraps that single `MlKitLlmFormatterProvider`; `buildLlmProviderRegistry()`
(`LlmProviderRegistryFactory.kt:76`) adds it once; `App.kt:485-487` `remember`s the resulting
`LlmProviderRegistry` for the whole app session. So **all 4+ call sites in §2 share the identical
`model: GenerativeModel` object.**

If `checkAvailability()` calls `model.generateContent(...)` as a trigger, and a user simultaneously
triggers real voice formatting (`format()` also calls `model.generateContent()` on `DOWNLOADABLE`),
two concurrent `generateContent()` calls hit the same `GenerativeModel` instance. Neither `format()`
nor `checkAvailability()` synchronizes access to `model` — there is no mutex/actor here (unlike the
DB layer's `DatabaseWriteActor` pattern this codebase uses elsewhere for serializing writes). The
ML Kit SDK's own thread-safety for concurrent `generateContent()` calls on one `GenerativeModel` is
undocumented in this codebase (no comment addresses it); `GenAiErrorMapping.kt`'s `BUSY` (per-app
quota, errorCode 9) and `BACKGROUND_USE_BLOCKED` (errorCode 30) mappings suggest the SDK itself
expects and reports contention/quota errors rather than crashing, so a concurrent
`checkAvailability()`-triggered call likely surfaces as a swallowed/retryable failure rather than a
crash — but this is inference from the existing error-mapping surface, not a confirmed SDK
guarantee. **Recommendation**: do not add the trigger to `checkAvailability()` directly; instead
gate the trigger behind a single dedicated method (e.g. `ensureDownloadStarted()`) called from
exactly one place (the Settings row's first composition), or de-dupe with an in-flight
`Deferred`/mutex so concurrent callers coalesce into one `generateContent()` call rather than firing
N.

### 3b. Row composed multiple times simultaneously

Confirmed real, not hypothetical (§2, item 4): `LlmProviderSettings.kt` renders 1
`LlmProviderListScreen` row + 3 `PerFeatureProviderPicker` instances on the same screen, all
resolving availability for the same on-device provider on mount. Today (pure read) this just means
4 redundant `checkStatus()` calls — harmless. Once a trigger side effect is added, this becomes 4
redundant `generateContent()` calls unless coalesced (see 3a's recommendation). This also affects
polling design: if each of the 4 call sites independently starts its own poll timer once the fix
adds one, that's 4 concurrent polling loops against the same provider — wasteful but not
incorrect, since `checkStatus()` itself is a cheap pure read with no side effect risk (only the
trigger call needs coalescing).

### 3c. `FeatureStatus.UNAVAILABLE` (permanently unsupported hardware) — must not poll forever

`mapMlKitFeatureStatus()` maps `UNAVAILABLE` to `LlmProviderAvailability.Unavailable(reason = "...",
retryable = false)` (`MlKitAvailabilityMapping.kt:52-55`) — already distinct from the `retryable =
true` "genuinely unknown" fallback branch. `ProviderStatusIndicator`'s existing retry button is
already gated on `availability.retryable` (`LlmProviderListScreen.kt:188`), so a polling loop that
only runs while `availability is Preparing` (per the §1b recommendation) naturally never polls on
`Unavailable` regardless of `retryable` — the `Preparing`-only gate is sufficient by itself to avoid
polling forever on unsupported devices. No additional guard needed beyond "poll only in the
`Preparing` branch," but this should be stated explicitly in the implementation plan since it's easy
to instead gate polling on "`!is Available`" (which would incorrectly include `Unavailable` and poll
forever on unsupported hardware).

### 3d. Unstated user needs beyond the literal bug

- **"Why is it slow" transparency**: ML Kit exposes no byte/percentage progress (confirmed by
  requirements.md's Non-Goals and by `GenerativeModel`'s API surface used here — only `checkStatus()`'s
  4-state `FeatureStatus` and `generateContent()`). The closest the codebase gets to "why" for a
  stalled async op is `FolderSyncReconciliationProgress`'s `Failed` state's message + retry/cancel
  pair, and `SyncStatusBadge`'s `SyncState.RateLimited` copy "Retrying…" (deliberately not phrased
  as actionable). A reasonable analog for req. #4's "unreasonably long" escalation: after a
  threshold (e.g. 30+ min of elapsed `Preparing` time, tracked client-side since ML Kit gives no
  server-side ETA), swap copy to something in the `RateLimited`-style "still working, here's what to
  do" register rather than inventing new alarming language.
- **Cancel option**: no existing precedent for cancelling an ML Kit download — `GenerativeModel` has
  no documented cancel API surfaced anywhere in this codebase's ML Kit usage. Out of scope to invent;
  align with requirements.md's Non-Goals (no progress bar) — likely also no cancel affordance, only
  refresh/wait/switch-provider.
2. **Switch to a remote provider while waiting**: already fully supported today via
  `PerFeatureProviderPicker` — a user can pick a `RemoteLlmProvider` (Anthropic/OpenAI/Gemini) per
  `LlmFeature` independently of the on-device row's status, no new plumbing needed. The fix's copy
  for `Preparing`/timeout states could explicitly point users at Settings → AI Providers' per-feature
  picker ("or switch to a remote provider below") — this is copy-only, not a new mechanism, since
  the mechanism already exists on the exact same screen (`LlmProviderSettings.kt:77` +`:122`).

## 4. Summary of structural findings for the plan phase

- Root cause #2 (never refreshes) is because the shared, `remember`'d `LlmProviderRegistry`/`LlmProvider`
  never changes identity — confirmed via `App.kt:485-487` + `LlmProviderRow`'s `produceState(provider)` key.
- The trigger fix (root cause #1) should NOT be implemented as "make `checkAvailability()` call
  `generateContent()`" — that function is called from 6 places (§2), several of which are hot paths
  (`VoicePipelineFactory`, `LlmSynthesisService`) or render 4x concurrently on Settings mount (§3a/3b).
  A narrower, explicit, de-duped trigger (e.g. one-shot `ensureDownloadStarted()` invoked once from
  the Settings row, or a shared in-flight guard on `MlKitLlmFormatterProvider`) avoids both the
  blast-radius and the concurrency risk.
- Polling (root cause #2 fix) should gate strictly on `availability is Preparing`, matching the
  `GitHubOAuthDialog` "only poll in the Polling state" idiom, which also naturally satisfies req.'s
  implicit "don't poll forever on `Unavailable`" requirement (§3c) without a separate timeout guard.
- Manual refresh (req. #3) has a ready-made UI pattern one branch away — `ProviderStatusIndicator`'s
  `Unavailable`-branch retry `IconButton` just needs to also render (with `onRetry` wired through
  from `LlmProviderRow`) on the `Preparing` branch.
- Escalation copy (req. #4) has no existing "elapsed-time-based" state in this codebase to copy
  directly — closest analogs are `SyncStatusBadge`'s timed auto-fade (`LaunchedEffect` + `delay`) and
  `RateLimited`'s non-alarming "still working" register.
