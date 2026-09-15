# ADR-014: Flat `LlmProviderRegistry` Over Single-Active-Backend Enum

**Date**: 2026-07-01
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: llm-service Phase 3 (planning)

---

## Context

The project already has an established pattern for backend selection:
`RepositoryFactory.kt`'s `GraphBackend` enum (`SQLDELIGHT`, `IN_MEMORY`, ...), which
selects exactly **one** active implementation per repository interface per graph,
chosen once at construction (`RepositoryFactoryImpl` with an internal
`instances: MutableMap<String, Any>` per-key singleton cache). This is the natural
first pattern to reach for when designing how features discover and use LLM
providers.

However, the LLM provider requirement is different in kind, not just in scale:

- **Multiple providers can be configured and usable simultaneously.** A user may have
  both an Anthropic API key configured *and* Android on-device (ML Kit) eligible at
  the same time — these are not mutually exclusive alternatives to a single active
  backend, they are two independently available capabilities.
- **Selection is per-feature, not per-graph or per-process.** Tag suggestion might use
  on-device (privacy/offline preference), voice formatting might use Claude, and a
  future synthesis feature might need to exclude on-device providers entirely (per
  their ~256-token-class output ceiling — pitfalls research §2.1/§2.2/§3.2) and only
  offer remote providers. `GraphBackend`'s single-slot-per-graph model cannot express
  "list what's usable right now for *this* feature, then let the feature pick."
- **Availability is dynamic, not fixed at construction time.** On-device eligibility
  can flip from `DOWNLOADABLE` to `AVAILABLE` mid-session (Android AICore background
  download, iOS Foundation Models background download); a remote API key can be
  rotated or revoked; region eligibility for iOS on-device is explicitly a
  session-to-session moving target (pitfalls research §2.2). `GraphBackend` is chosen
  once and does not need to re-evaluate availability after construction — the LLM
  case does.
- **v1 scope requires eight provider types** (Anthropic, OpenAI, Gemini, generic
  OpenAI-compatible custom endpoint, Azure OpenAI considerations, Android on-device,
  iOS on-device, and the existing `NoOpLlmFormatterProvider` null object) with
  per-feature independent selection (`VOICE_FORMATTING`, `TAG_SUGGESTION`,
  `GRAPH_EDIT_SYNTHESIS`) — an enum-per-backend model would need either one enum
  value per (provider × feature) combination, or a separate enum per feature, neither
  of which generalizes as providers are added.

Architecture research also notes the codebase's existing precedent for *this* shape of
problem is not `RepositoryFactory` but `VoicePipelineFactory`'s existing
on-device-first-then-remote-fallback logic (`if (deviceLlmProvider != null &&
settings.getUseDeviceLlm()) deviceLlmProvider else <remote lookup>`) — the closest
existing analogue to "list what's available, apply a selection policy," just not yet
generalized into a reusable registry.

## Decision

Use a flat `LlmProviderRegistry` holding N concurrently-available `LlmProvider`
wrappers, with per-feature selection via a new `LlmSettings` class — not
`RepositoryFactory`'s single-active-backend-enum pattern.

```kotlin
enum class LlmProviderKind { REMOTE, ON_DEVICE }

sealed interface LlmProviderAvailability {
    data object Available : LlmProviderAvailability
    data object Downloading : LlmProviderAvailability
    data class Unavailable(val reason: String) : LlmProviderAvailability
}

interface LlmProvider {
    val id: String                          // "anthropic", "openai", "gemini",
                                             // "android-ondevice", "ios-ondevice",
                                             // "custom:<uuid>"
    val displayName: String
    val kind: LlmProviderKind
    val formatter: LlmFormatterProvider     // delegates to the existing, unchanged contract
    suspend fun checkAvailability(): LlmProviderAvailability
}

class LlmProviderRegistry(private val providers: List<LlmProvider>) {
    fun all(): List<LlmProvider> = providers
    fun find(id: String): LlmProvider? = providers.firstOrNull { it.id == id }
    suspend fun availableProviders(): List<LlmProvider> =
        providers.filter { it.checkAvailability() !is LlmProviderAvailability.Unavailable }
}

enum class LlmFeature { VOICE_FORMATTING, TAG_SUGGESTION, GRAPH_EDIT_SYNTHESIS }

class LlmSettings(private val platformSettings: Settings) {
    fun getSelectedProviderId(feature: LlmFeature): String? = ...
    fun setSelectedProviderId(feature: LlmFeature, providerId: String?) = ...
}
```

The existing `LlmFormatterProvider` `fun interface` (`format(transcript,
systemPrompt): LlmResult`) is **unchanged** — `LlmProvider` is a thin metadata +
availability wrapper around it, so every existing implementation
(`ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`,
`MlKitLlmFormatterProvider`) and every existing test fake continues to compile and
work with zero call-site churn.

`checkAvailability()` generalizes `MlKitLlmFormatterProvider.checkEligible()` to a
tri-state (`Available` / `Downloading` / `Unavailable(reason)`) rather than a boolean
— per pitfalls research §0, a boolean collapses "eligible but still downloading" into
"available," which caused `checkEligible()` and `format()` to disagree today
(`checkEligible()` reports `DOWNLOADABLE`/`DOWNLOADING` as eligible, but `format()`
treats those same states as a failure). The tri-state is load-bearing, not
decorative.

Wiring stays composable-scoped, following the existing `App.kt` pattern
(`tagEngine`, `voiceCaptureViewModel` built via `remember(...)`) rather than a global
service locator: `val llmRegistry = remember(llmCredentialStore, llmSettings) {
buildLlmProviderRegistry(...) }`, threaded down exactly like `voiceSettings`/
`tagSettings` today. Platform-only providers (Android ML Kit, iOS Foundation Models
shim per ADR-013) are supplied via `expect fun platformOnDeviceLlmProvider():
LlmProvider?`, consistent with how `GraphManager`/`DriverFactory` already use
`expect`/`actual` for platform-specific construction elsewhere.

`buildLlmFormatterForTags` (`App.kt` L1647) is replaced by
`llmRegistry.find(llmSettings.getSelectedProviderId(TAG_SUGGESTION))` with a fallback
scan over `availableProviders()` when nothing is explicitly selected — this is what
gives tag suggestion automatic on-device fallback, the concrete gap requirements.md
calls out.

## Consequences

**Positive**:
- Correctly models the actual multiplicity of the domain: N providers configured and
  available simultaneously, selected independently per feature — something a
  single-active-backend enum structurally cannot express without combinatorial
  enum growth.
- Zero call-site churn for the low-level `LlmFormatterProvider` contract — existing
  providers, existing tests, and existing fakes are untouched by this decision.
- `availableProviders()` being a `suspend` re-evaluation (not a cached snapshot)
  directly addresses the key-rotation staleness risk noted in pitfalls research §1.4
  and the dynamic-availability requirement above — no separate cache-invalidation
  mechanism needs to be designed.
- Settings UI, tag suggestion, voice formatting, and future synthesis all converge on
  one registry/settings pair instead of each hand-rolling provider-selection logic,
  directly satisfying the requirements' extensibility success metric ("adding a new
  LLM provider/backend is a self-contained change... no edits required in tag
  suggestion, voice formatting, or other call sites").

**Negative / risks**:
- Testing an 8-provider fallback matrix (provider-availability × feature ×
  expected-selected-provider) is meaningfully more combinatorial than today's
  2-provider `buildLlmFormatterForTags` check. Per pitfalls research §3.3, this must
  be planned as a table-driven test structure from the start, exercised against fakes
  of the platform-only providers (ML Kit, Foundation Models) — those SDKs cannot run
  in `businessTest`/`jvmTest` at all.
- `VoiceSettings.getUseDeviceLlm()` (an existing boolean, currently voice-pipeline-
  specific) must be explicitly reconciled with the new per-feature
  `LlmSettings.getSelectedProviderId(feature)` model during implementation — reusing
  the same underlying setting key for both voice and tag suggestion would silently
  couple two features that the per-feature model is supposed to keep independent.
  This reconciliation is implementation-phase work flagged here, not resolved by this
  ADR.
- `LlmProviderRegistry` is a plain, constructor-injected class (like
  `RepositoryFactoryImpl`, not an `object`), so it must be scoped and threaded
  correctly (e.g. via `remember` in `App.kt`, not a process-wide singleton) to avoid
  the same class of scope-ownership bug `CLAUDE.md` already warns about for
  `rememberCoroutineScope`-derived objects — the registry itself holds no coroutine
  scope, but care is still needed that it isn't accidentally captured somewhere
  longer-lived than its credential/settings dependencies.

## Alternatives Considered

### Reuse `RepositoryFactory`'s single-active-backend-enum pattern

Rejected. `GraphBackend` (`SQLDELIGHT` xor `IN_MEMORY`) models mutually exclusive
alternatives selected once per graph at construction — the opposite of the LLM
domain's actual shape, where multiple providers are simultaneously configured and
selection happens per-feature, dynamically, based on live availability. Forcing this
pattern would require either a combinatorial enum (provider × feature) that doesn't
scale as providers are added, or a separate `GraphBackend`-style enum per feature that
duplicates the same provider list N times with no shared availability-checking logic
— directly working against the requirements' extensibility success metric.

### Global singleton service locator (`object LlmProviders { ... }`)

Considered implicitly and rejected in favor of constructor-injected, composable-scoped
wiring. The codebase has no DI framework and deliberately avoids one; the closest
existing singleton-style entry point (`Repositories` companion object in
`RepositoryFactory.kt`) exists specifically for callers that don't want to thread a
factory instance through, but the primary pattern remains constructor/parameter
injection from platform entry points into `commonMain` factory functions. A global
`LlmProviderRegistry` singleton would make credential-store and settings dependencies
implicit and harder to fake in tests (directly working against the table-driven
testing need noted above), so the registry is a plain class threaded via `remember`,
consistent with `RepositoryFactoryImpl`'s non-singleton default.
