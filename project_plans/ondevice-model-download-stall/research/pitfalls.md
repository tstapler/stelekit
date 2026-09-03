# Pitfalls: On-Device Model Download Status Stall (Settings → AI Providers)

Research date: 2026-07-29. Grounded in a direct read of
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/MlKitAvailabilityMapping.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/GenAiErrorMapping.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderListScreen.kt`
(lines 90-208), plus decompiled bytecode of the actual pinned SDK jars (see §6 — do not trust
Google's prose docs alone here, the beta jar was inspected directly with `javap`).

This project (`ondevice-model-download-stall`) targets the **Settings → AI Providers** row
(`LlmProviderListScreen.kt`), which is a different call site from the already-researched
`llm-tag-download-stall` project (targets `SuggestionBottomSheet`/`TagChipRow`) and
`llm-service`/`llm-provider` (broader multi-provider abstraction). All three share the same root
SDK and the same `MlKitLlmFormatterProvider`/`MlKitAvailabilityMapping` code, so their prior
research is directly reusable here, not just analogous — see §1.

---

## 0. Reuse map — do not re-derive, cite these directly

| Question | Already answered in | What it says |
|---|---|---|
| Post-reset init window / foreground-only / BUSY quota pitfalls | `project_plans/llm-service/research/pitfalls.md` §2.1 | Verbatim source of the code comments already in `MlKitAvailabilityMapping.kt:38-40` and `GenAiErrorMapping.kt:23`. See §1 below for the exact citations. |
| Real download-progress API exists (`generativeModel.download()`) | `project_plans/llm-tag-download-stall/research/stack.md` §1 | Discovered a `Flow<DownloadStatus>` that supersedes the `generateContent()`-as-trigger hack. **Confirmed independently by bytecode inspection in this doc, §6** — the SDK surface is real, not a docs-only claim, and is richer than that doc realized (see §6.1: total byte size *is* available). |
| Existing polling idioms in this codebase | `project_plans/llm-tag-download-stall/research/stack.md` §3 | `GitHubDeviceFlowClient.pollForToken()` (wall-clock-deadline poll loop with state callback — best structural analog), `SafChangeDetector` (`while (isActive) { delay(30_000); ... }` — simplest analog). No generic retry/backoff utility exists anywhere in the codebase; don't invent one for a single call site. |
| Compose/coroutine lifecycle risks of polling a downloadable-model status | `project_plans/depth-model-download-stall/research/pitfalls.md` §1-2 | Different SDK (Android `DownloadManager`, not AICore) but the *lifecycle* lessons transfer directly: don't detach the poll loop from the composable's scope, don't poll on the main thread synchronously, pause polling when not visible. See §3 below for how this maps onto `produceState`/`LaunchedEffect` specifically (simpler here since there's no `BroadcastReceiver` to leak). |
| "Stalled, not just failed" UI pattern | `project_plans/depth-model-download-stall/decisions/ADR-003-route-stall-timeout-into-existing-failed-state.md` | Reuse the existing state's `reason`/`detail` field for stall copy instead of adding a new sealed variant — avoids touching every exhaustive `when` for a distinction the AC doesn't strictly require. `LlmProviderAvailability.Preparing(detail: String?)` and `.Unavailable(reason, retryable)` already have the same shape (`detail`/`reason` are free-form strings) — the same trick applies here without adding a new sealed case. |

---

## 1. Known pitfalls already documented for THIS SDK (cite, don't re-derive)

`project_plans/llm-service/research/pitfalls.md` §2.1 ("Android ML Kit GenAI / AICore"),
verified against Google docs and a real GitHub issue (googlesamples/mlkit#985), documents — and
the current code's own comments cite the same findings:

- **Foreground-only inference** (`BACKGROUND_USE_BLOCKED`, error code 30). AICore only permits
  inference while the calling app is foregrounded. `GenAiErrorMapping.kt:29-32` already maps this
  to a retryable `OnDeviceUnavailable`. Relevant to this fix: **any polling loop this fix adds
  must not call `generateContent()` (or anything that could trip this) from a background
  context** — but since polling only needs `checkStatus()`/`download()`, not `generateContent()`,
  this specific error class shouldn't fire from a Settings-screen poll loop (see §2 — this is a
  reason to prefer `checkStatus()`/`download()` over the current `generateContent()`-as-trigger
  pattern for this fix, not just an unrelated warning).
- **Per-app quota / `BUSY`** (error code 9). AICore enforces an inference quota per calling app;
  requests issued faster than the quota allows return `BUSY` rather than queuing, and non-Pixel
  OEM devices cannot bypass it. Already mapped in `GenAiErrorMapping.kt:33-36`. Relevant here: a
  poll loop that calls `checkStatus()` (a status read, not an inference call) is very unlikely to
  consume this quota, but if the fix instead keeps `generateContent()` as the trigger and polls by
  re-invoking `format()`/`generateContent()` repeatedly, a tight poll interval risks tripping
  `BUSY` on non-Pixel hardware — another argument for polling `checkStatus()`/`download()`
  directly rather than re-firing `generateContent()` on a timer.
- **Post-reset initialization window.** Immediately after a device reset or AICore data-clear,
  `checkStatus()` can misreport eligibility with no way to distinguish "genuinely unsupported"
  from "still initializing" other than retrying later. `MlKitAvailabilityMapping.kt:38-40` already
  encodes this: unknown/null status codes map to a *retryable* `Unavailable`, never a permanent
  one. **Any polling logic this fix adds must preserve this distinction** — do not let a poll loop
  "give up permanently" on a null/unrecognized `checkStatus()` result; keep retrying at the normal
  interval (or the stall-timeout escalation, §4), not a hard failure.
- **Beta API instability**: `com.google.mlkit:genai-prompt:1.0.0-beta2`. See §6.2 for a
  concrete, previously-undocumented version-skew finding (the `genai-common` transitive dependency
  is actually pinned to a *different* beta — `1.0.0-beta3` — than `genai-prompt` itself).
- **Output cap / capability ceiling** (256 tokens) — not directly relevant to a status-check fix,
  noted only because it's the reason `generateContent()` is unattractive as a "just to check
  things" side channel even before considering it's the wrong tool for triggering a download (§2).

`project_plans/llm-provider/research/pitfalls.md` does **not** cover ML Kit/AICore specifics at
all — it's scoped to Claude/OpenAI HTTP plumbing and local tag matching. Nothing there is directly
reusable for this fix beyond the general cross-cutting discipline in its §6 (rethrow
`CancellationException`, don't hardcode dispatchers, etc.), which the existing
`MlKitLlmFormatterProvider` already follows.

---

## 2. Risk of `generateContent()`-as-download-trigger fired from a status-check path

The requirements.md Root Cause section already identifies that `checkAvailability()` (the
function this Settings screen actually calls) never triggers the download at all — only
`format()` does, and only when a real feature (voice formatting, tag suggestion) is used. The
naive fix — make `checkAvailability()` also call `generateContent()` on `DOWNLOADABLE`, mirroring
`format()`'s existing hack — has real costs beyond "it's a hack":

- **It performs a real (if immediately-failing) inference-shaped API call as a side effect of
  opening a settings screen.** `generateContent()` is the SDK's *inference* entry point, not a
  download-trigger API — the existing code comment (`MlKitLlmFormatterProvider.kt:70-72`)
  concedes this is a workaround ("It will throw a `GenAiException` since the model isn't ready —
  we swallow it"). A user who merely opens Settings → AI Providers (arguably the single most
  passive, exploratory action in the whole app) would trigger this call, potentially every time
  `produceState(provider) { ... }` re-runs (e.g., on process restart navigating back into
  Settings, or if a future change makes `provider`'s identity unstable across recompositions).
- **Quota consumption**: per §1, AICore enforces a per-app inference quota (`BUSY`) on non-Pixel
  hardware. Even a call that immediately fails because the model isn't ready may still count
  against that quota depending on AICore's internal accounting (undocumented either way) — firing
  it opportunistically from a read-only status check is a needless risk for a code path that has
  no inference need at all.
- **Foreground-only semantics leak into an unexpected place.** `generateContent()`/inference is
  gated on the app being foregrounded (`BACKGROUND_USE_BLOCKED`). A `checkAvailability()` that
  calls `generateContent()` as a side effect inherits that constraint even though the caller only
  wanted a status read — if `checkAvailability()` is ever called from a background-eligible
  context in the future (e.g. a background sync/reconcile pass, which this codebase's dispatcher
  matrix explicitly plans for elsewhere), it would fail in a confusing way tied to inference rules
  that have nothing to do with "what's the status."
- **Logging/noise**: `format()`'s `DOWNLOADABLE` branch already logs nothing on the swallowed
  exception (bare `runCatching { ... }`), but a future engineer adding diagnostics to
  `generateContent()` call sites (a reasonable thing to do since it's the "real" inference path)
  would now also see noise from this fake status-check-triggered call, polluting logs meant for
  actual inference attempts.
- **Cost**: for AICore/Gemini Nano specifically there is no per-call monetary cost (on-device,
  no API billing) — this differs from a remote provider where the same anti-pattern would also
  burn API-key spend. So the *cost* risk here is specifically quota/rate-limit and log-noise, not
  dollars — worth stating explicitly since "side-effecting call from a status check" is a smell
  that usually implies a billing risk, and it's useful to know that particular risk doesn't apply
  here.

**Is there a way to trigger the download without inference?** Yes — confirmed directly against the
pinned SDK jar (see §6): `GenerativeModel.download(): Flow<DownloadStatus>` is a dedicated,
purpose-built download API, structurally identical in shape to older ML Kit "downloadable model"
APIs like Translate's `RemoteModelManager.download()`. Calling `.collect { }` on this Flow (or
even just `.first()` to fire-and-await the first emission) starts the AICore download as its
actual, intended side effect — no swallowed exception, no inference attempt, no quota risk tied to
`generateContent()`. This is the SDK-correct replacement for the `generateContent()`-as-trigger
hack in **both** `format()`'s `DOWNLOADABLE` branch and (for this project specifically) whatever
`checkAvailability()`-adjacent code ends up triggering the download from Settings. Recommend this
over calling `generateContent()` from `checkAvailability()`.

---

## 3. Risks of polling from a Compose `produceState`/row composable

Today `LlmProviderRow` (`LlmProviderListScreen.kt:114-120`) uses:

```kotlin
val availability by produceState<LlmProviderAvailability?>(initialValue = null, provider) {
    value = provider.checkAvailability()
}
```

`produceState` runs its block once per distinct `provider` key and does **not** natively support
"run once, then repeat every N seconds" — that requires an explicit loop inside the block. Risks,
mapped from `depth-model-download-stall`'s pitfalls doc (§0 table) onto this simpler case:

- **Lifecycle is actually simpler here than the `DownloadManager` case** — there's no
  `BroadcastReceiver` to double-unregister and no continuation to double-resume. A `produceState`
  block (or a `LaunchedEffect` driving a `StateFlow`) that does `while (true) { value =
  checkAvailability(); delay(POLL_INTERVAL) }` is automatically cancelled by Compose when the
  composable (`LlmProviderRow`, and therefore the whole `LlmProviderListScreen`/dialog) leaves
  composition — i.e., when the user closes the AI Providers dialog. This satisfies AC's implicit
  "polling stops when the dialog closes" requirement *for free*, the same way `depth-model-
  download-stall`'s pitfalls doc flagged as the correct (if easy-to-get-wrong) pattern for
  `rememberCoroutineScope()`-adjacent code — except here it's even more structural since
  `produceState`'s coroutine scope is inherently tied to composition, not a manually-created
  scope that could accidentally escape it.
- **Do not build a second/independent polling mechanism** (e.g., a `remember { CoroutineScope(...)
  }`-backed poller stored on the row) — that would reintroduce exactly the `rememberCoroutineScope
  ()`-must-not-escape-composition risk CLAUDE.md warns about, for no benefit over letting
  `produceState`'s own coroutine loop.
- **Battery/CPU cost is bounded and low-risk** for this specific screen: unlike
  `depth-model-download-stall`'s `DownloadManager.query()` (a `ContentProvider`/SQLite call
  suitable for 200-500ms cadence), `checkStatus()` here is a suspend call into AICore's binder
  interface — cheaper to call less often. AC1 of the sibling `llm-tag-download-stall` project
  specifies "every 3-5s" for a similar poll; that cadence is reasonable here too, and importantly
  this screen (a modal Settings dialog) is only ever polling while it's the user's actively visible
  foreground UI — there's no backgrounded-polling risk to guard against the way there was for
  `depth-model-download-stall`'s screen (which could poll while backgrounded during navigation,
  per that doc's §2 "must pause when screen isn't visible"). A Settings dialog polling only while
  open, at a 3-5s cadence, on a suspend call rather than a `ContentProvider` cursor query, is
  low-risk without needing an explicit visibility-pause mechanism.
- **Multiple simultaneous rows**: `LlmProviderListScreen` renders one `LlmProviderRow` per
  configured provider (`providers.forEachIndexed { ... }`, line 99). If a future change adds
  polling to *every* row's `produceState` (not just the on-device one), N concurrent poll loops
  each firing their own `checkAvailability()`/HTTP or SDK call every few seconds could add up.
  Scope the polling loop specifically to `provider.kind == LlmProviderKind.ON_DEVICE` — remote
  providers (Claude/OpenAI/etc.) don't have a download-in-progress state that benefits from
  polling and should keep the current one-shot `checkAvailability()` behavior; only wrap the
  on-device row's `produceState` block in a poll loop.
- **`produceState`'s `provider` key**: currently the loop restarts if `provider`'s identity
  changes (e.g., a new `LlmProvider` instance is constructed on every recomposition of the parent
  — worth verifying at implementation time whether `providers` in
  `LlmProviderListScreen` is a stable list or re-derived each recomposition, since an unstable key
  would restart the poll loop on every parent recomposition, not just when providers are actually
  added/removed/edited).

---

## 4. Flakiness/slowness of `checkStatus()` and redundant-poll risk

`checkStatus()` is a suspend call into AICore over IPC (binder), not a local field read — so it
has real latency and can itself hang or throw (the existing code already treats a thrown
`checkStatus()` as `null` → retryable `Unavailable`, `MlKitLlmFormatterProvider.kt:44-51`).
Concrete risks if polling calls it every few seconds:

- **A poll loop that does `delay(interval)` *before* awaiting the next `checkStatus()` call is
  safe from overlap** (sequential, not concurrent, calls) — this is the natural shape of
  `while (isActive) { value = checkStatus(); delay(interval) }` and should be preferred over
  firing calls on a fixed-rate timer that could overlap if a single `checkStatus()` call takes
  longer than the interval. The existing `SafChangeDetector` idiom (`while (isActive) { delay(...);
  onExternalChange() }`) already establishes this "delay-then-call" shape as the codebase
  convention — reuse it as-is rather than a fixed-rate scheduler.
- **A hung/slow `checkStatus()` call blocks that iteration's UI update but does not block the next
  poll attempt** as long as the loop is sequential (per above) — the *cost* of a slow call is a
  stale-looking UI for longer than the nominal interval, not a growing backlog of in-flight calls.
  This is an acceptable degradation mode; no additional guard (e.g., a per-call `withTimeout`) is
  strictly required by the acceptance criteria, but adding one (a few seconds, generous relative to
  a local IPC call) is cheap insurance against a single wedged call stalling the whole poll loop
  indefinitely — recall `checkStatus()` itself has no SDK-documented timeout.
- **Redundant calls are not free even though they're not billed**: each `checkStatus()` call is a
  real binder round-trip to the AICore system service. Polling faster than genuinely useful (e.g.,
  sub-second) buys no responsiveness the user can perceive (AICore's own download almost never
  changes state that fast) while adding IPC overhead and (per §1) a theoretical, if unlikely,
  contribution to per-app rate accounting. 3-5s (matching the sibling `llm-tag-download-stall`
  project's AC1) is a reasonable floor; there's no benefit to going faster.
- **If the fix uses `download(): Flow<DownloadStatus>` instead of polling `checkStatus()`** (§2,
  §6), this entire class of risk mostly disappears: `download()` is a *push*-based Flow driven by
  AICore's own internal download-completion signal, not a client-side poll loop guessing when to
  ask again — no redundant calls, no polling-interval tuning tradeoff at all. This is a strong
  argument for preferring `download()`-Flow-collection over `checkStatus()`-polling for the
  "actively downloading" sub-state specifically, reserving a `checkStatus()` poll (or a single
  re-check) only for the "not yet started" → "did it actually start" transition if `download()`
  isn't collected continuously. This mirrors the exact recommendation already made independently
  in `llm-tag-download-stall/research/stack.md` §5 for the sibling bug — the same tradeoff applies
  identically here.

---

## 5. ML Kit GenAI beta-instability risks (`genai-prompt:1.0.0-beta2`)

- Per `llm-service`'s pitfalls doc: "the Prompt API itself (not just a model release stage) is
  beta, meaning binary-incompatible method signature changes remain possible between beta
  releases." Pin the exact version (already done — no floating range in `kmp/build.gradle.kts`)
  and rely on `ciCheck`/`assembleDebug` compiling against the real SDK as the breakage smoke test,
  since there's no dedicated unit test surface that can exercise the real `GenerativeModel`
  (androidMain-only, no `androidUnitTest`/`androidTest` file was found for
  `MlKitLlmFormatterProvider` as of the `llm-service` research pass).
- **New finding, this research pass**: `genai-prompt:1.0.0-beta2`'s transitive dependency on
  `genai-common` resolves to `1.0.0-beta3` (confirmed by locating both artifacts in the local
  Gradle/Coursier cache — see §6.2), i.e. **the two halves of this "one" beta SDK are not on
  matching beta numbers.** This is normal for Google's ML Kit GenAI modularization (common code
  shared across `genai-prompt`, `genai-image-description`, etc. versions independently) but is
  worth flagging explicitly: a future `./gradlew` dependency bump that only touches
  `genai-prompt`'s version pin could silently pull a *different* `genai-common` transitive version
  than what's implicitly tested today, changing `FeatureStatus`/`DownloadStatus`/`GenAiException`
  behavior without an obvious diff in `build.gradle.kts` pointing at the actual changed artifact.
  If this fix adds a Gradle version-catalog entry or explicit pin, consider pinning
  `genai-common` explicitly alongside `genai-prompt` rather than leaving it to transitive
  resolution, so a future bump is a visible, deliberate two-line diff.
- Using `download(): Flow<DownloadStatus>` (§2, §6) is *itself* new-to-this-fix surface area on a
  beta SDK — it's real (confirmed by decompilation, not just docs) but has, per grep of this
  repo, **zero existing call sites or tests anywhere in the codebase today**. Both
  `format()`'s `generateContent()`-as-trigger hack and `checkAvailability()`'s `checkStatus()`-only
  read are the *only* two `GenerativeModel` methods this codebase currently exercises in
  production. Adopting `download()` is the SDK-correct fix, but it is unavoidably the
  least-battle-tested API surface this codebase will have touched in this SDK family — test it
  concretely (a real device/emulator with AICore, not just a mock) before relying on it,
  especially its cancellation behavior (does cancelling collection of the Flow cancel the
  underlying AICore download, or does the download continue in the background regardless of
  whether anything is collecting? — undocumented, not verifiable from bytecode alone, and directly
  relevant to whether closing the Settings dialog mid-download should be expected to leave the
  real AICore download running, which is almost certainly the *desired* behavior since the
  download is shared system-wide via AICore, not per-app).

---

## 6. Verification: `GenerativeModel.download()` confirmed by direct bytecode inspection

`project_plans/llm-tag-download-stall/research/stack.md` asserted this API exists based on
(unspecified) documentation. For this project, the claim was re-verified independently and more
concretely by decompiling the actual pinned jars from the local Gradle/Coursier cache with
`javap`, rather than trusting prose docs a second time. This is worth recording because it
upgrades the finding from "docs say so" to "confirmed against the exact bytecode this build
compiles against":

```
$ javap -p GenerativeModel.class | grep download
public abstract kotlinx.coroutines.flow.Flow<com.google.mlkit.genai.common.DownloadStatus> download();
```

### 6.1 `DownloadStatus` sealed hierarchy (from `genai-common:1.0.0-beta3`) — corrects a gap in the sibling doc

```
DownloadStatus.DownloadStarted(bytesToDownload: Long)      // ← total size IS available
DownloadStatus.DownloadProgress(totalBytesDownloaded: Long) // running total downloaded so far
DownloadStatus.DownloadCompleted                            // object, no payload
DownloadStatus.DownloadFailed(e: GenAiException)
```

`llm-tag-download-stall/research/stack.md` only found `DownloadProgress.totalBytesDownloaded` and
concluded "no known total-size to compute a percentage from." That is **incorrect** —
`DownloadStarted.bytesToDownload` gives the total size up front, so a real
`totalBytesDownloaded / bytesToDownload` percentage *is* computable by capturing the first
`DownloadStarted` emission and comparing subsequent `DownloadProgress` emissions against it. This
directly affects requirements.md's non-goal framing ("ML Kit's Prompt API does not expose
byte-level/percentage download progress") — that statement is not accurate for this exact SDK
version. It's still reasonable to keep percentage-progress UI out of this fix's scope (the
acceptance criteria don't require it, and it adds surface area), but planning should not assume
the *data* doesn't exist — only that surfacing it in the UI is being deliberately deferred, not
technically impossible. A synchronous `DownloadCallback` (Java-interop shape) with the same four
events also exists (`onDownloadStarted(long)/onDownloadProgress(long)/onDownloadCompleted()/
onDownloadFailed(GenAiException)`), confirming the Kotlin `Flow` surface isn't a fluke of the
decompiler.

### 6.2 Version skew between `genai-prompt` and `genai-common`

Confirmed present in both the local Coursier and Gradle module caches:

```
genai-prompt-1.0.0-beta2.aar   (direct dependency, kmp/build.gradle.kts:296)
genai-common-1.0.0-beta3.aar   (transitive — one beta ahead of genai-prompt)
```

See §5 above for the implication.

### 6.3 What was *not* found — do not assume it exists

- No `DownloadConditions`-equivalent (unlike older ML Kit vision/translate APIs, there's no
  visible API on `GenerativeModel` to constrain the download to Wi-Fi-only or require charging —
  `download()` takes no arguments). If a future requirement wants "only download on Wi-Fi," that
  constraint would need to be enforced by the caller checking network state before invoking
  `download()`, not by an SDK-provided condition object — the SDK doesn't expose one at this
  version, confirmed by `download()`'s zero-arg signature.
- No cancellation-specific method beyond standard `Flow`/coroutine cancellation semantics was
  found on `GenerativeModel` (no `cancelDownload()`). Whether cancelling collection of the
  `download()` Flow actually stops the underlying AICore transfer, or merely stops *this app's*
  awareness of it (transfer continues since it's a shared system-level resource), is not
  determinable from the class file alone — flagged in §5 as something to verify on-device before
  relying on any "cancel = stop the download" assumption in the UI.
