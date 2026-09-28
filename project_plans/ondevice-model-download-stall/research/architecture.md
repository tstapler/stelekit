# Architecture Research: On-Device Model Download Status Stall

## 1. Full call chain (as it exists today)

```
LlmProviderListScreen (commonMain, Compose)
  -> LlmProviderRow (private @Composable, LlmProviderListScreen.kt:114-160)
       produceState<LlmProviderAvailability?>(initialValue = null, provider) {   // :118-120
           value = provider.checkAvailability()
       }
  -> LlmProvider.checkAvailability(): LlmProviderAvailability   // interface, LlmProvider.kt:33
       (suspend fun, no Flow — a point-in-time pull, not an observable stream)

     Android:  AndroidOnDeviceLlmProvider.checkAvailability()   // AndroidOnDeviceLlmProvider.kt:24
                 -> delegate.checkAvailability()  where delegate: MlKitLlmFormatterProvider
                    MlKitLlmFormatterProvider.checkAvailability()  // MlKitLlmFormatterProvider.kt:43-53
                      -> model.checkStatus()          // ML Kit SDK call, PURE READ, no side effect
                      -> mapMlKitFeatureStatus(statusCode)   // commonMain pure function

     iOS:      IosOnDeviceLlmProvider.checkAvailability()   // IosOnDeviceLlmProvider.kt:46-57
                 -> suspendCancellableCoroutine { shim.checkAvailabilityWithCompletion { code, detail -> ... } }
                 -> mapShimCodeToAvailability(code, detail)   // separate pure mapper, own businessTest
                 (always non-null provider; unavailability expressed via checkAvailability, not construction
                 failure — ADR-013. Un-verified/un-compiled in this environment per class kdoc.)

     JVM/WASM: platformOnDeviceLlmProvider() returns **null** (jvmMain/wasmJsMain
                PlatformOnDeviceLlmProvider.kt) -> LlmProviderRegistryFactory's
                `onDeviceProvider()?.let { providers += it }` never adds an ON_DEVICE entry at all.
                LlmProviderListScreen's `providers.forEachIndexed` loop therefore never renders an
                on-device row on these platforms — there is no "unsupported" placeholder row, the
                row simply does not exist. This is the existing precedent for how platforms without
                the capability opt out (silently, at registry-construction time), distinct from
                iOS's "always constructed, expresses absence via checkAvailability" precedent.
```

Root cause 1 (confirmed): `MlKitLlmFormatterProvider.checkAvailability()` calls only
`model.checkStatus()`. The only place that ever calls `model.generateContent()` — the call that
actually triggers the AICore download as an SDK side effect — is `format()`'s `DOWNLOADABLE`
branch (lines 69-77, wrapped in `runCatching { }` and swallowed). `checkAvailability()` was never
given the same trigger, so a user who opens Settings before ever using voice formatting or tag
suggestions sees "downloading" while nothing has been kicked off.

Root cause 2 (confirmed): `produceState(provider) { ... }` only re-invokes when `provider`'s
*identity* changes. `LlmProviderRegistry`/its member `LlmProvider` instances are rebuilt in
`App.kt` only via the existing `llmRegistryRefreshToken` (`App.kt:483-486`, bumped only at
`App.kt:1739` on `onLlmCredentialsChange` — i.e. only when the user adds/edits a provider's
credentials, never on a timer). So nothing re-triggers `checkAvailability()` while the dialog
stays open.

## 2. Design option (a): where should the download trigger live?

Two candidate shapes, both consistent with the existing `runCatching { generateContent() }`
workaround already shipped in `format()`:

**Option A — bake the trigger into `checkAvailability()` itself (Android-local change only).**
Add the same `DOWNLOADABLE -> runCatching { model.generateContent(...) }` call inside
`MlKitLlmFormatterProvider.checkAvailability()`, mirroring `format()`'s existing branch almost
verbatim. No `LlmProvider` interface change at all — `AndroidOnDeviceLlmProvider.checkAvailability()`
already just delegates.
- Pro: smallest diff, matches the repo's own established precedent (`format()` already conflates
  "check" and "trigger" for exactly this SDK — see the code comment at lines 70-72: "Without this
  call the download never starts"). Requires a `systemPrompt`-shaped argument for
  `generateContent()`, though — `checkAvailability()` currently takes none; the simplest workaround
  is calling it with an empty/minimal placeholder string (same purpose as `format()`'s call: kick
  the download, discard the result).
- Con: violates Command-Query Separation — a method literally named "check" now has an
  observable, costly (multi-hundred-MB download, background network/battery) side effect on every
  invocation, including the periodic-poll invocations from part 3 below. This is surprising from
  any caller's perspective, including `LlmProviderRegistry.availableProviders()`/
  `availableForFeature()` (`LlmProviderRegistry.kt:44-45, 59-64`), which also call
  `checkAvailability()` — meaning enumerating "what's available for feature X" would now also
  side-effect a device into downloading a model it may never end up needing for that feature.

**Option B — a separate sibling method, e.g. `ensureDownloadStarted()` (interface-level, default no-op).**
Add `suspend fun ensureDownloadStarted() {}` (default no-op) to `LlmProvider`, overridden only by
`AndroidOnDeviceLlmProvider`/`MlKitLlmFormatterProvider` to call the same `generateContent()`
trigger. `RemoteLlmProvider`, `CustomOpenAiCompatibleLlmProvider`, and (unless/until it needs the
same workaround) `IosOnDeviceLlmProvider` inherit the no-op and need zero changes.
- Pro: CQS-respecting — `checkAvailability()` stays a pure read everywhere, matching its own KDoc
  ("Live availability check — always re-evaluated") and `LlmProviderRegistry`'s existing reliance
  on it as a filter predicate. The caller (Settings row) explicitly opts into the side effect only
  when it has a legitimate reason to (first time observing `DOWNLOADABLE`), not on every poll tick.
  Mirrors the sibling `depth-model-download-stall` plan's own explicit pattern choice: a capability
  interface (`DownloadableDepthModel`) with `downloadModel()`/`cancelDownload()` kept **separate**
  from the passive state getter, precisely to avoid overloading one method with two concerns.
- Con: slightly larger diff — one new interface member (even as a default), one Android override,
  one call site in `LlmProviderRow` deciding *when* to call it (e.g. only once per
  `DOWNLOADABLE` observation, not every poll tick, to avoid re-triggering `generateContent()`
  redundantly — though `generateContent()` on an already-downloading model is presumably idempotent
  from the SDK's side, this hasn't been verified against the ML Kit docs in this pass).

**Recommendation for the planning phase:** Option B. The interface already has a documented KDoc
promise ("Live availability check") that Option A would quietly break, `LlmProviderRegistry`
already depends on `checkAvailability()` being filter-safe (no side effects across N providers on
every list build), and the sibling `depth-model-download-stall` plan sets exactly this
separate-method precedent for the *same class of problem* (SDK read vs. SDK trigger) in the same
codebase, one epic away. The cost is genuinely small given the default-no-op interface member
keeps every non-Android implementation untouched. Acceptance criterion 1 in requirements.md
explicitly allows the alternative, lower-effort fix (distinguish the copy without triggering
anything) as a valid AC-satisfying outcome — that remains the fallback if planning decides the
trigger is out of scope for this ticket's complexity budget.

## 3. Design option (b): live/refreshing status in `LlmProviderRow`

Three shapes were compared, in light of what `checkAvailability()` actually is (a `suspend fun`
pull, backed by `model.checkStatus()` — a **cheap, pull-only SDK call with no callback/push API**;
ML Kit exposes no observable download-progress stream at all, confirmed by requirements.md's
Non-Goals section and the class's own KDoc):

1. **`Flow<LlmProviderAvailability>` exposed directly on `LlmProvider`, `collectAsState()`'d by the row.**
   Would require every implementation — including the trivial `RemoteLlmProvider` (`return
   flowOf(Available)`) and `CustomOpenAiCompatibleLlmProvider` — to gain a Flow-shaped member, and
   the on-device implementations would have to build the polling loop *themselves*, which then
   needs its own instance-owned `CoroutineScope` per the repo's `rememberCoroutineScope`-must-not-
   escape-composition rule (since a `LlmProvider` instance already outlives a single composition —
   it's held in `App.kt`'s `remember(..., llmRegistryRefreshToken)` block). This is the heaviest
   option and pushes UI-refresh-cadence concerns down into the domain-layer interface that every
   provider (including ones that never need polling) must satisfy.

2. **`produceState` with an internal poll loop (`delay()` inside the same producer).**
   `produceState<LlmProviderAvailability?>(provider) { while (true) { value =
   provider.checkAvailability(); delay(interval); if (value !is Preparing) break } }`. Keeps all
   the polling/cadence logic local to the row, no interface change. `produceState`'s producer
   coroutine is itself scoped to the composable's lifecycle (cancelled on leaving composition),
   so this does not violate the `rememberCoroutineScope` rule — the coroutine is *owned by
   Compose's own composition machinery*, not manually captured and handed to a longer-lived class.

3. **`LaunchedEffect` re-keyed on a tick counter (`var tick by remember { mutableStateOf(0) }`, incremented by a manual-refresh button or a separate timer `LaunchedEffect`).**
   Functionally equivalent to option 2 but splits "what triggers a re-check" (tick change) from
   "how the check result is stored" (a separate `mutableStateOf`) — useful specifically because
   AC3 requires a **manual** refresh affordance on the `Preparing` state (mirroring the existing
   `Unavailable` branch's `onRetry`/`IconButton` — `LlmProviderListScreen.kt:186-192` — which
   `Preparing` currently lacks entirely, `:182-185`). A tick-counter naturally unifies "timer fired"
   and "user clicked refresh" into the same re-key mechanism, `produceState`'s single producer
   block would need an explicit internal signal (e.g. a `Channel`) to accept an external manual
   trigger mid-poll, which is more code for the same result.

**Recommendation for the planning phase:** Option 3 (`LaunchedEffect` + tick state, or the
close cousin of option 2 with an added manual-trigger `Channel`) over option 1. Reasoning:
- ML Kit's `checkStatus()` has **no push/callback API** — there is nothing to make genuinely
  "observable" at the SDK boundary; any `Flow` would just be a poll loop wrapped in `flow { }`,
  so option 1 buys no real capability, only relocates the same polling code from the UI layer down
  into every `LlmProvider` implementation (most of which don't need it).
- The Settings row is the **only** consumer of this liveness — `LlmProviderRegistry.availableProviders()`/
  `availableForFeature()` intentionally re-evaluate `checkAvailability()` fresh on every call
  already (no caching, per their own KDoc) and are called from non-UI, one-shot contexts (tag
  engine/voice pipeline construction), not from a long-lived observer — so there's no second
  caller that would benefit from a shared `StateFlow`.
- Keeping the poll loop UI-local also naturally gives AC4's "unreasonably long" escalation logic
  (elapsed-time-in-`Preparing` tracking) a clean home: it's inherently presentation/session state
  (how long *this dialog visit* has observed `Preparing`), not domain state the provider should
  own or persist.

## 4. Precedent: is there an existing "poll an external status, expose as StateFlow" idiom?

Yes — but it is **not** the right fit here, and the mismatch is instructive. `GraphFileWatcher.kt`
(`kmp/src/commonMain/.../db/GraphFileWatcher.kt:49-61`) and `SafChangeDetector.kt`
(`kmp/src/androidMain/.../platform/SafChangeDetector.kt`) both establish the pattern: **own a
private `CoroutineScope(SupervisorJob() + Dispatchers.Default)` as an instance field, launch a
polling `Job` on it, expose results via `SharedFlow`/callback, tear down explicitly in a
`stop()`/`close()` method.** The unrelated `project_plans/depth-model-download-stall/implementation/plan.md`
(ADR-001) explicitly adopts this same idiom for `DepthModelDownloader`, citing
`GraphFileWatcher.kt:115-133` and `SafChangeDetector.kt:185-190` as precedent, specifically because
that downloader must **outlive a single screen visit** (survive navigation away, be
reattachment-safe against double-enqueue, support explicit cancel distinct from screen-teardown).

None of those preconditions hold for the on-device LLM status row:
- There is no `DownloadManager` (or any download-orchestration object) in the ML Kit path — ML
  Kit's AICore download is entirely opaque to the app; the app can only *ask* `checkStatus()` and
  *nudge* via `generateContent()`. There is nothing analogous to `DepthModelDownloader`'s
  `activeDownloadId`/reattachment-guard/cancel semantics to build a `StateFlow` around.
- The Settings dialog is the only place this status is ever observed; nothing needs it to survive
  navigating away from Settings (unlike the depth panel, which the sibling plan found isn't even
  reachable from other screens either — but was *designed* to be long-lived because a background
  download should keep progressing after the user leaves that specific panel).
- Introducing an instance-owned-scope `LlmProvider` (or a wrapper around
  `MlKitLlmFormatterProvider`) that polls in the background for as long as the app process lives
  would mean polling `checkStatus()` continuously even when Settings is never opened — unnecessary
  battery/CPU cost for a purely diagnostic screen, and a second lifecycle concern (`close()` needs
  a caller) is added to a currently-simple, stateless wrapper class.

Net: the instance-owned-scope + `StateFlow` idiom is real and reusable precedent *in this
codebase*, but its applicability test is "does the polled thing need to keep running/be shared
across screens/support explicit cancel distinct from navigation." This feature fails all three, so
a UI-local `LaunchedEffect` poll (section 3, option 3) is the right-sized choice, not
under-engineering.

## 5. CLAUDE.md dispatcher/scope rules — constraints on where the poll loop can live

- **`rememberCoroutineScope` must not escape composition.** A `LaunchedEffect`/`produceState`
  poll loop is exempt by construction — its coroutine is owned by Compose's own recomposition
  scope (tied to the composable's key(s) and lifecycle), not a `rememberCoroutineScope()` value
  manually threaded into a `remember`-stored class. This rule is about *classes that outlive
  composition* holding a cancelled scope; a loop declared directly inside `LaunchedEffect`/
  `produceState` is inherently composition-scoped and self-cancelling, so it is the *compliant*
  pattern here, not a workaround of the rule.
- **Uncaught `Throwable` kills the process on Android.** `MlKitLlmFormatterProvider.checkAvailability()`
  already catches `Exception` (not just specific SDK exceptions) around `model.checkStatus()` and
  falls back to `null` -> `Unavailable(retryable = true)` (lines 44-51) — this guard is already in
  place for the read path. If option 2/B's trigger call (`generateContent()`) is added to
  `checkAvailability()` (or a new `ensureDownloadStarted()`), it must be wrapped the same way
  `format()` already wraps it — `runCatching { }` (format() line 73) rather than a bare call —
  so a `GenAiException`/any `Throwable` from the trigger never escapes the `LaunchedEffect` poll
  coroutine. Compose's `LaunchedEffect` does propagate uncaught exceptions up through the
  composition; on Android this can still surface as a crash, so the existing swallow-and-report
  pattern must be preserved/extended, not bypassed, when wiring the trigger into a per-poll-tick
  call path.
- **`PlatformDispatcher` dispatcher matrix.** Not DB-related, so `PlatformDispatcher.DB` is
  irrelevant here. `model.checkStatus()`/`model.generateContent()` are ML Kit's own suspend APIs
  (Play-Services-style, backed by `Task`/coroutine bridging that is main-safe per Google's usual
  convention) and neither `checkAvailability()` nor `format()` currently wraps them in an explicit
  `withContext(PlatformDispatcher.IO)` — this matches the existing code as-is; nothing in this
  investigation found evidence it blocks the main thread today. Any new polling loop added in the
  Settings row should keep calling the existing `suspend fun`s as-is (no dispatcher change needed)
  unless profiling surfaces a real main-thread stall, which is outside this bug's scope.

## 6. Integration point: how the commonMain row already handles an Android-only capability

`LlmProviderRow` (commonMain) never special-cases `LlmProviderKind.ON_DEVICE` beyond icon/label
choice (`LlmProviderListScreen.kt:132,141`) — it operates purely on the `LlmProvider` interface,
which is already fully platform-erased by the time it reaches the row. The platform split is
resolved **earlier**, at registry-construction time in `LlmProviderRegistryFactory.kt:76`
(`onDeviceProvider()?.let { providers += it }`), via the `expect fun platformOnDeviceLlmProvider(): LlmProvider?`
declared in commonMain (`PlatformOnDeviceLlmProvider.kt`) and actualized per platform:
- **Android**: returns a real, non-null `AndroidOnDeviceLlmProvider` (unless ML Kit itself fails
  to construct — `MlKitLlmFormatterProvider.create()` returns null on `GenerativeModel`
  construction failure, `MlKitLlmFormatterProvider.kt:28-34`).
- **iOS**: always returns a non-null `IosOnDeviceLlmProvider`; absence/ineligibility is expressed
  *through* `checkAvailability()`'s `Unavailable` branch, never through registry omission
  (ADR-013, `IosOnDeviceLlmProvider.kt:16-24`).
- **JVM/WASM**: always returns `null` — no on-device row is ever constructed, so
  `LlmProviderListScreen`'s render loop simply never produces one.

This means any fix scoped to "Android's on-device provider specifically triggers the download" is
naturally isolated to `MlKitLlmFormatterProvider`/`AndroidOnDeviceLlmProvider` (androidMain) plus,
if Option B (section 2) is chosen, one new default-no-op member on the shared `LlmProvider`
interface (commonMain) that every other implementation — including `IosOnDeviceLlmProvider`,
which per requirements.md's stated scope is **not** part of this fix — inherits for free without
needing its own override. No commonMain UI code needs to know which platform it's running on; the
`LaunchedEffect`/poll-loop change in `LlmProviderRow` operates uniformly on the `LlmProvider`
interface regardless of which concrete platform type is behind it.

## Key files (for the implementation plan)

| File | Role |
|---|---|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderListScreen.kt` | `LlmProviderRow` (`:114-160`, `produceState` at `:118-120`), `ProviderStatusIndicator` (`:167-195`, `Preparing` branch with no retry at `:182-185`) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProvider.kt` | Interface — `checkAvailability()` (`:33`), candidate home for a default-no-op `ensureDownloadStarted()` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt` | Tri-state sealed type; `Preparing(detail: String?)` is where distinguishing DOWNLOADABLE-vs-DOWNLOADING copy would land |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/MlKitAvailabilityMapping.kt` | `mapMlKitFeatureStatus()` (`:44-62`) — currently collapses DOWNLOADABLE/DOWNLOADING to identical copy (`:47-50`) |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt` | `checkAvailability()` (`:43-53`, pure read only), `format()`'s existing trigger workaround (`:69-77`, `runCatching { model.generateContent(...) }`) |
| `kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/AndroidOnDeviceLlmProvider.kt` | Thin `LlmProvider` wrapper delegating to `MlKitLlmFormatterProvider` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistry.kt` | `availableProviders()`/`availableForFeature()` (`:44-45,59-64`) — both call `checkAvailability()` per-provider with no caching; relevant to Option A's CQS concern |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | `llmProviderRegistry` construction (`:483-486`) and the existing manual-refresh precedent `llmRegistryRefreshToken` (`:483`, bumped at `:1739` only on credential changes) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt` / `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt` | Instance-owned-scope polling precedent — evaluated and found **not** applicable here (section 4) |
| `kmp/src/iosMain/kotlin/dev/stapler/stelekit/llm/IosOnDeviceLlmProvider.kt` | Precedent for "always-constructed, unavailability via checkAvailability" (ADR-013) — out of this fix's scope but shapes the interface-change blast radius |
