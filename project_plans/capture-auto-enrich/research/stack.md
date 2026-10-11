# Research: Stack — wiring CaptureViewModel into the Import/enrichment pipeline

**Phase**: 2 — Research (Stack dimension)
**Scope**: libraries/frameworks/versions/patterns for connecting `CaptureViewModel`
(`androidApp`, an `AndroidViewModel` outside the main Compose nav graph) to
`ImportService.scan()` / `TopicExtractor` / `ClaudeTopicEnricher` / `PageNameIndex` /
`AhoCorasickMatcher` (all `kmp/src/commonMain/.../domain/`) and to the `llm-service`
provider abstraction (`kmp/src/commonMain/.../llm/`, `voice/LlmFormatterProvider.kt`).

## 1. No new dependencies needed — confirmed

`androidApp/build.gradle.kts:104` already declares `implementation(project(":kmp"))`, which
exposes every `commonMain` class (`ImportService`, `TopicExtractor`, `TopicSuggestion`,
`ClaudeTopicEnricher`, `PageNameIndex`, `AhoCorasickMatcher`, the whole `llm/` package) to
`androidApp` with zero extra wiring. `androidApp`'s own direct dependencies
(`androidApp/build.gradle.kts:102-123`) are UI-only (`arrow-core`, `activity-compose`,
`compose-bom`, `glance-appwidget`, `androidx.car.app`) — nothing there needs to change.

`CaptureViewModel` already extends `androidx.lifecycle.AndroidViewModel` and uses
`viewModelScope` (`androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureViewModel.kt:8-9,27`),
so the `androidx.lifecycle` viewmodel-ktx artifact is already on the classpath transitively
(via `kmp`'s `androidMain` — `kmp/build.gradle.kts:231-268` — and/or `activity-compose`).
No lifecycle/viewmodel dependency needs to be added.

**Verdict**: this is pure wiring — reuse `kmp/commonMain` domain code directly, no `libs`/Gradle
changes required, matching the requirements doc's "Constraints" section
(`project_plans/capture-auto-enrich/requirements.md:103-105`).

## 2. Version facts (for compatibility awareness, no action needed)

- Kotlin `2.3.21` (`settings.gradle.kts:9-13`)
- `kotlinx-coroutines-core` `1.10.2` (commonMain), `kotlinx-coroutines-android` `1.10.2`
  (androidMain) — `kmp/build.gradle.kts:94,237`
- `arrow-core` `2.2.1.1` (`kmp/build.gradle.kts:88`, also direct in `androidApp/build.gradle.kts:105`)
- `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` `2.8.4` (commonMain,
  `kmp/build.gradle.kts:113`) — the Compose-Multiplatform lifecycle artifact used by the main
  app's `StelekitViewModel`; `CaptureViewModel` instead uses the platform
  `androidx.lifecycle.AndroidViewModel`/`viewModels()` delegate (`CaptureActivity.kt:70`), which
  is the correct, already-proven pattern for an Android-only translucent activity outside the
  Compose nav graph — no need to migrate it to the KMP lifecycle artifact.
- SQLDelight `2.3.2`, Compose Material3 `1.4.0` (androidMain) — unrelated to this item but
  confirms nothing here is blocked on a version mismatch.

## 3. How `ImportViewModel` gets its matcher — the pattern to mirror

`PageNameIndex` is **not** owned by `GraphManager`/`RepositorySet` — it is constructed and
owned per-consumer. Today there is exactly one production owner: `StelekitViewModel`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt:516`):

```kotlin
val pageNameIndex = PageNameIndex(pageRepository, scope)
val suggestionMatcher: StateFlow<AhoCorasickMatcher?> = pageNameIndex.matcher
```

`ScreenRouter.kt:252-262` then passes `viewModel.suggestionMatcher` straight into
`ImportViewModel(matcherFlow = viewModel.suggestionMatcher, ...)`. `ImportViewModel` never
builds its own matcher — it only *collects* the `StateFlow<AhoCorasickMatcher?>` handed to it
(`ImportViewModel.kt:163-172`, `init` block re-scans when the matcher first becomes non-null).

**Implication for `CaptureViewModel`**: since `CaptureActivity` is a separate Android
`Activity`/`AndroidViewModel` outside the main Compose nav graph, it has no access to
`StelekitViewModel.suggestionMatcher` — that instance only exists while `MainActivity`'s
Compose tree is composed. `CaptureViewModel` must build **its own** `PageNameIndex` instance
from the same source `RepositorySet.pageRepository` it already reaches via
`graphManager.getActiveRepositorySet()` (`CaptureViewModel.kt:76-77`), scoped to its own
`viewModelScope`:

```kotlin
val pageNameIndex = PageNameIndex(repoSet.pageRepository, viewModelScope)
val matcher: StateFlow<AhoCorasickMatcher?> = pageNameIndex.matcher
```

This is safe and idiomatic per `PageNameIndex`'s own design — it's a plain, constructor-injected
class (`pageRepository: PageRepository, scope: CoroutineScope`, `PageNameIndex.kt:35-41`), not a
singleton, and every existing caller constructs its own instance per lifecycle owner. Building a
second `PageNameIndex` against the same graph is not new architecture, just a second instance of
an already-designed-to-be-instantiated-per-owner class.

**Cost / timing** (relevant to the requirements doc's "must not delay
`focusRequester.requestFocus()`" pitfall, `requirements.md:146-147`,
`CaptureActivity.kt:214,229`): `PageNameIndex`'s `matcher: StateFlow` is built via
`.flowOn(Dispatchers.Default)` off a `pageRepository.getPageNameEntries()` flow with a
500 ms `debounce` (`PageNameIndex.kt:61-72,83-86`) — i.e. construction itself is O(1)
(field/flow setup only); the actual trie build happens asynchronously on `Dispatchers.Default`
and is `null` until the first emission lands. This means `PageNameIndex(...)` can be constructed
synchronously in `CaptureViewModel.init`/`save()` without blocking `onCreate`/focus — but the
matcher will very likely still be `null` on first `save()` for a fast share-sheet capture (no
500 ms+ wait has elapsed), so the enrichment/auto-link pass must tolerate `matcher == null`
(same tolerance `ImportViewModel.onRawTextChanged` already implements at
`ImportViewModel.kt:186-192`) and fall back to the raw-text save per the requirements doc's
"never block Save" success criterion (`requirements.md:59-62`).

## 4. `ClaudeTopicEnricher` / `TopicEnricher` — current wiring state (important correction to requirements.md)

The requirements doc (`requirements.md:23-24`) describes `ClaudeTopicEnricher` as "already
shipped for the in-app Import screen." **That is not accurate as of this research pass.**
`ScreenRouter.kt:255-261` constructs `ImportViewModel` without a `topicEnricher` argument:

```kotlin
dev.stapler.stelekit.ui.screens.ImportViewModel(
    pageRepository = repos.pageRepository,
    graphWriter = graphWriter,
    graphPath = graphPath,
    urlFetcher = urlFetcher,
    matcherFlow = viewModel.suggestionMatcher,
)
```

`topicEnricher` defaults to `NoOpTopicEnricher()` (`ImportViewModel.kt:147`,`116`). A
repo-wide grep confirms `ClaudeTopicEnricher(` / `.withDefaults(` is referenced only inside
`ClaudeTopicEnricher.kt` itself and `ClaudeTopicEnricherTest.kt` — **there is no current
production call site wiring `ClaudeTopicEnricher` into the in-app Import screen, or anywhere
else.** So the in-app Import screen today only ever shows local heuristic (`TopicExtractor`)
suggestions; the Claude enhancement tier exists as a tested, working class but is orphaned from
production wiring. This item (capture-auto-enrich) would be the **first** production consumer
of `ClaudeTopicEnricher`/`TopicEnricher` if it wires it in directly — or, better, per §5 below,
it's an opportunity to wire `ClaudeTopicEnricher` into the registry-based path for both
`CaptureViewModel` and (as a follow-up) `ImportViewModel` at once.

## 5. `llm-service` provider abstraction — landed, not just planned

`project_plans/llm-service/` has two feature commits already merged to `main`:

- `4a9386e8f5` — "feat(llm): Epic 1 — provider abstraction + registry"
- `13de1d80ca` — "feat(llm): Epic 8 — migrate voice/tag-suggestion/topic-enricher onto the registry"

Landed files in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/`:
`LlmProvider.kt`, `LlmProviderRegistry.kt`, `LlmProviderAvailability.kt`,
`LlmProviderRegistryFactory.kt`, `LlmFeature.kt`, `LlmSynthesisContextBuilder.kt`,
`LlmSynthesisService.kt`, `PlatformOnDeviceLlmProvider.kt`, `LlmCredentialMigration.kt`
(+ `androidMain/llm/AndroidOnDeviceLlmProvider.kt`, `PlatformOnDeviceLlmProvider.kt`).

Key shapes:

```kotlin
// LlmProvider.kt
interface LlmProvider {
    val id: String
    val displayName: String
    val kind: LlmProviderKind
    val formatter: LlmFormatterProvider          // unchanged contract — Claude/OpenAI/MlKit/... still implement this
    val supportsLongFormOutput: Boolean get() = true
    suspend fun checkAvailability(): LlmProviderAvailability
}

// LlmFeature.kt
enum class LlmFeature { VOICE_FORMATTING, TAG_SUGGESTION, GRAPH_EDIT_SYNTHESIS }  // no capture/topic-enrichment case yet

// LlmProviderRegistry.kt
class LlmProviderRegistry(private val providers: List<LlmProvider>) {
    fun all(): List<LlmProvider>
    fun find(id: String): LlmProvider?
    suspend fun availableProviders(): List<LlmProvider>
    suspend fun availableForFeature(feature: LlmFeature, excludeShortFormOnly: Boolean = false): List<LlmProvider>
    companion object { const val DISABLED_SENTINEL = "__disabled__" }
}
```

`buildLlmProviderRegistry(llmCredentialStore, llmSettings, onDeviceProvider)` in
`LlmProviderRegistryFactory.kt` is the single composition root (built once in `App.kt:498`,
inside the main Compose tree) that assembles Anthropic/OpenAI/Gemini remote providers (only if
a credential exists) plus the platform on-device provider (`MlKitLlmFormatterProvider` on
Android via `AndroidOnDeviceLlmProvider`).

`App.kt:1097-1126` shows the reference resolution pattern this item should mirror for its own
feature (tag suggestion's `Auto`/explicit-selection/disabled-sentinel logic):

```kotlin
val tagLlmProviderState = produceState<LlmProvider?>(initialValue = null, llmProviderRegistry, llmSettings, tagSettings) {
    value = if (tagSettings.isLlmTierEnabled()) {
        when (val selectedId = llmSettings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION)) {
            LlmProviderRegistry.DISABLED_SENTINEL -> null
            null -> llmProviderRegistry.availableForFeature(LlmFeature.TAG_SUGGESTION).firstOrNull()  // "Auto"
            else -> llmProviderRegistry.find(selectedId)
        }
    } else null
}
val tagEngine = remember(...) { TagSuggestionEngine(..., llmTagProvider = tagLlmProviderState.value?.let { LlmTagProvider(it.formatter) }, ...) }
```

**Implication**: `llm-service`'s abstraction is landed enough to consume directly — this item
does **not** need to fall back to calling `ClaudeLlmFormatterProvider`/`ClaudeTopicEnricher`'s
own dependency directly (the requirements doc's `requirements.md:150-151` fallback-risk
question is resolved: use the registry). But `CaptureViewModel` is a plain `AndroidViewModel`,
not a `@Composable`, so it cannot use `produceState` — it needs the `suspend` form directly:
`llmProviderRegistry.availableForFeature(LlmFeature.<X>).firstOrNull()` called inside a
`viewModelScope.launch { }`/`withTimeoutOrNull { }` block instead of a Compose effect. `Capture`
needs both (a) access to a `LlmProviderRegistry` instance — which today is built once inside
`App.kt`'s Compose tree from `llmCredentialStore`/`llmSettings`, not exposed anywhere
`CaptureActivity` can reach without its own composition root call — and (b) a new `LlmFeature`
case (e.g. `TOPIC_ENRICHMENT` or reuse `TAG_SUGGESTION`) if per-feature provider selection via
`LlmSettings.getSelectedProviderId(...)` is desired; `LlmProviderRegistryFactory.buildLlmProviderRegistry(...)`
is a plain, injectable function (`llmCredentialStore: LlmCredentialStore, llmSettings: LlmSettings? = null, onDeviceProvider: ...`)
so `CaptureViewModel` can call it directly given the same `LlmCredentialStore`/`LlmSettings`
instances `SteleKitApplication`/`MainActivity` already construct — this is the "composition
root," not a singleton, so `CaptureViewModel` building its own registry instance from the same
credential store is consistent with how every other consumer does it (no shared/global state to
plumb through).

## 6. Coroutine/scope pattern to follow (per `CLAUDE.md`)

- `CaptureViewModel` must **not** be handed a `rememberCoroutineScope()` — it already isn't
  (it's a plain `AndroidViewModel`, uses its own `viewModelScope`, which is the correct
  ViewModel-scoped lifecycle per the repo's "Coroutine scope ownership" rule).
- `ImportViewModel`'s pattern (`coroutineScope: CoroutineScope? = null` constructor param,
  defaulting to `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned internally, closed
  via `close()`/`DisposableEffect`, `ImportViewModel.kt:125-133`) is the KMP-side idiom for
  classes instantiated outside a `ViewModel`. `CaptureViewModel` doesn't need this pattern
  itself (it already has `viewModelScope` from `AndroidViewModel`), but any new `commonMain`
  collaborator class it constructs (e.g. a shared `CaptureEnrichmentCoordinator` if one is
  added) should follow the same `coroutineScope: CoroutineScope? = null` + owned-scope pattern
  if it's meant to be usable from both `ImportViewModel` and `CaptureViewModel`.
- Time-boxing: `ImportViewModel.runScan` wraps the enrichment call in
  `withTimeout(8_000) { topicEnricher.enhance(...) }` with `TimeoutCancellationException` handled
  explicitly (`ImportViewModel.kt:246-265`) — same pattern applies to `CaptureViewModel`'s
  enrichment pass per the requirements doc's "never block Save" criterion; `withTimeoutOrNull`
  (rather than `withTimeout` + catch) may be a tighter fit if `Capture`'s enrichment result is
  optional-and-discardable rather than needing distinct failure-state UI like `ClaudeStatus`.
- Dispatcher: run `ImportService.scan(...)` on `Dispatchers.Default` (CPU-bound trie match), same
  as `ImportViewModel.scanDispatcher` default (`ImportViewModel.kt:119,216`) — not
  `PlatformDispatcher.DB`/`IO` (this is pure in-memory computation, not a DB or file read).

## Summary of open wiring gaps (for the Architecture research dimension, flagged here since discovered)

1. No `LlmProviderRegistry` instance is reachable from `CaptureActivity`/`CaptureViewModel`
   today — it must be constructed there via `buildLlmProviderRegistry(...)` using the same
   `LlmCredentialStore`/`LlmSettings` `SteleKitApplication`/`MainActivity` already build, or that
   construction needs to be hoisted onto `SteleKitApplication` so both entry points share one
   instance (avoids rebuilding provider list — mostly cheap credential lookups — per capture).
2. `LlmFeature` enum has no case for this feature yet (`VOICE_FORMATTING`, `TAG_SUGGESTION`,
   `GRAPH_EDIT_SYNTHESIS` only) — needed only if per-feature provider selection
   (`LlmSettings.getSelectedProviderId`) is in scope for v1; otherwise `availableProviders()`
   (feature-agnostic) is sufficient.
3. `ClaudeTopicEnricher` has zero production callers today — wiring it into `CaptureViewModel`
   is new production wiring, not "already shipped" as the requirements doc states; recommend
   also fixing `ImportViewModel`'s orphaned `topicEnricher` parameter in the same pass since both
   consumers need identical `LlmProvider.formatter`-backed `ClaudeTopicEnricher` instances.
