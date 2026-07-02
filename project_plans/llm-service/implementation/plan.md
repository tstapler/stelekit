# Implementation Plan: llm-service

**Feature**: Unified multi-provider LLM abstraction, secure credential consolidation,
Android + iOS on-device parity, provider Settings UI, and an approval-gated graph-edit
workflow.
**Date**: 2026-07-01
**Status**: Ready for validation (Phase 4)
**Supersedes/extends**: `project_plans/llm-provider/` (tag-suggestion local-match tier —
untouched; its LLM tier gains on-device fallback here via Epic 8)

**ADRs required** (written in parallel — see summary at the end of this document):
- ADR: Consolidate credential storage onto `CredentialStore`, remove `PlatformSettings`-backed secrets
- ADR: Flat `LlmProviderRegistry` over N simultaneously-available providers, not a `RepositoryFactory`-style single-active-backend enum
- ADR: `LlmSuggestionInbox` is in-memory only for v1, not a persisted SQLDelight table
- ADR: Swift shim + Kotlin/Native cinterop for iOS Foundation Models, implemented in v1 (not deferred)
- ADR: Generic OpenAI-compatible provider relaxes HTTPS-only for loopback hosts only; legacy Azure deployment-path auth is explicitly out of scope

---

## Ground rules every task inherits from `CLAUDE.md`

Every task below must respect these without restating them per-task:

- **Arrow `Either`** at repository/service boundaries — no thrown domain exceptions, no nullable-as-error.
- **Writes go through `DatabaseWriteActor`/`GraphWriter`**, never direct `SteleDatabaseQueries` mutation. New `.sq` mutating queries need a `@DirectSqlWrite`-annotated forwarding stub on `RestrictedDatabaseQueries` (not expected to be needed in this plan — no new tables).
- **Dispatcher matrix**: DB work on `PlatformDispatcher.DB`, non-DB IO (HTTP, files) on `PlatformDispatcher.IO` or plain suspend, never mixed.
- **No `rememberCoroutineScope()` escaping composition** — every long-lived class (`LlmProviderRegistry`, `LlmSuggestionInbox`, view models) owns its own `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler)`.
- **No O(graph) reads** — the synthesis proposal generator (Epic 7) must use projected/chunked reads only (`getPageNameEntries()`, `getPagesByNames()` ≤500-chunk), never a full-table scan.
- **Uncaught `Throwable`, not `Exception`**, guarded on every long-lived scope introduced.

---

## Sequencing overview

Per pitfalls research §6.3, this is sequenced so the existing, shipped
`VoiceCaptureViewModelTest` / `TagSuggestionEngineTest` / `LlmProviderSupportTest` suite
passes **unchanged** at every intermediate commit. The new abstraction is built and tested
standing alongside the old code first; existing consumers are migrated one at a time last
(Epic 8), with `ClaudeTopicEnricher` migrated last of all because it has the most divergent
existing behavior to reconcile.

```
Epic 1 (provider abstraction + registry)  ─┬─→ Epic 3 (Gemini + generic OpenAI-compatible)
   builds LlmProvider/LlmProviderRegistry  │
   alongside existing Claude/OpenAI/ML Kit │
   providers — zero call-site churn yet    ├─→ Epic 4 (Android on-device fix + wiring)
                                            │
Epic 2 (credential consolidation) ─────────┼─→ Epic 6 (Settings UI — needs registry +
   relocates CredentialStore, migrates     │      credential store + LlmSettings)
   VoiceSettings keys, no permanent        │
   plaintext fallback path                 ├─→ Epic 5 (iOS on-device — independent,
                                            │      by far the largest single unit post-
                                            │      review (greenfield Xcode scaffolding +
                                            │      first-ever cinterop shim), can run in
                                            │      parallel with 3/4/6 once Epic 1 lands)
                                            │
                                            └─→ Epic 7 (approval workflow — needs registry +
                                                   credential store + GraphWriter, independent
                                                   of 3/4/5/6 once Epic 1 + 2 land)

Epic 8 (migrate existing consumers) ── LAST. Depends on 1, 2, 3, 4 (on-device fallback),
   6 (so users can configure the newly-required per-feature selection), and 7 (inbox exists
   before ClaudeTopicEnricher's replacement can optionally feed it). Order within Epic 8:
   voice pipeline → tag suggestion → ClaudeTopicEnricher (most divergent, last).
```

Epic 1 and Epic 2 are hard prerequisites for everything else and should land first, in
either order (they touch disjoint files). Epics 3, 4, 5, 6, 7 can then proceed in parallel
across separate subagents/branches once 1+2 are merged. Epic 8 is the integration epic and
must be last.

---

## Epic 1: Provider abstraction + registry

**Goal**: Introduce `LlmProvider` (thin wrapper around the existing `LlmFormatterProvider`
fun interface), `LlmProviderAvailability` (tri-state, not boolean), `LlmProviderRegistry`
(flat, multi-provider, not a `RepositoryFactory`-style single-backend enum), and
`LlmSettings` (per-feature provider selection) — all new code, alongside the existing
`ClaudeLlmFormatterProvider`/`OpenAiLlmFormatterProvider`/`MlKitLlmFormatterProvider`,
which are untouched in this epic. New package: `dev.stapler.stelekit.llm` (commonMain),
parallel to `voice/`, `tags/`, `git/`.

### Story 1.1: Availability + kind domain types

**As a** developer, **I want** a shared tri-state availability type, **so that** the
Android `DOWNLOADABLE`/`DOWNLOADING` vs `AVAILABLE` distinction (and iOS's analogous
`modelNotReady` vs `available`) is never collapsed into a boolean, closing the exact bug
pitfalls research found in `MlKitLlmFormatterProvider.checkEligible()`.

**Acceptance Criteria**:
- `LlmProviderAvailability` sealed interface with exactly three cases: `Available`,
  `Preparing(detail: String? = null)` (downloading/initializing — retry later, no user
  action needed), `Unavailable(reason: String, retryable: Boolean = false)` (permanent
  unless `retryable`, e.g. "still initializing after reset" vs "unsupported hardware").
- `LlmProviderKind` enum: `REMOTE`, `ON_DEVICE`.
- Both are plain Kotlin, no platform dependencies, in `commonMain`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailability.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderKind.kt` (new)

##### Task 1.1a: Create `LlmProviderAvailability.kt`

```kotlin
package dev.stapler.stelekit.llm

sealed interface LlmProviderAvailability {
    data object Available : LlmProviderAvailability
    data class Preparing(val detail: String? = null) : LlmProviderAvailability
    data class Unavailable(val reason: String, val retryable: Boolean = false) : LlmProviderAvailability
}
```

##### Task 1.1b: Create `LlmProviderKind.kt`

```kotlin
package dev.stapler.stelekit.llm

enum class LlmProviderKind { REMOTE, ON_DEVICE }
```

##### Task 1.1c: `businessTest` — availability type has no behavior to test directly; instead add a `LlmProviderAvailabilityTest` asserting the `Unavailable`/`Preparing` data-class equality/copy semantics used later by fallback-selection logic (guards against accidental `retryable` default drift).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmProviderAvailabilityTest.kt` (new)

---

### Story 1.2: `LlmProvider` wrapper interface + wrappers for existing Claude/OpenAI providers

**As a** developer, **I want** a thin `LlmProvider` wrapper around the existing
`LlmFormatterProvider` contract, **so that** the registry has identity, kind, and a live
availability check per provider without touching `LlmFormatterProvider` itself (zero
churn for `VoiceCaptureViewModelTest`'s existing fakes).

**Acceptance Criteria**:
- `LlmProvider` interface: `id: String`, `displayName: String`, `kind: LlmProviderKind`,
  `formatter: LlmFormatterProvider`, `suspend fun checkAvailability(): LlmProviderAvailability`,
  and `val supportsLongFormOutput: Boolean` (defaults `true`; on-device providers override
  to `false` — closes the 256-token-cap / synthesis-eligibility gap from pitfalls §2.1/2.2,
  consumed later by Epic 7's synthesis provider filter).
- `RemoteLlmProvider` — a generic concrete wrapper (`id`, `displayName`, `formatter`,
  `kind = REMOTE`, `checkAvailability()` returns `Available` unconditionally — remote
  providers are "available" once constructed with a key; reachability is not probed on
  every call). Used to wrap `ClaudeLlmFormatterProvider` and `OpenAiLlmFormatterProvider`
  instances without writing one bespoke class per remote provider.
- `id` values fixed by convention for the built-in types: `"anthropic"`, `"openai"`,
  `"gemini"` (Epic 3), `"android-ondevice"` (Epic 4), `"ios-ondevice"` (Epic 5),
  `"custom:<uuid>"` for user-added generic OpenAI-compatible instances (Epic 3).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProvider.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/RemoteLlmProvider.kt` (new)

##### Task 1.2a: Create `LlmProvider.kt`

```kotlin
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider

interface LlmProvider {
    val id: String
    val displayName: String
    val kind: LlmProviderKind
    val formatter: LlmFormatterProvider
    val supportsLongFormOutput: Boolean get() = true
    suspend fun checkAvailability(): LlmProviderAvailability
}
```

##### Task 1.2b: Create `RemoteLlmProvider.kt`

```kotlin
package dev.stapler.stelekit.llm

import dev.stapler.stelekit.voice.LlmFormatterProvider

class RemoteLlmProvider(
    override val id: String,
    override val displayName: String,
    override val formatter: LlmFormatterProvider,
) : LlmProvider {
    override val kind = LlmProviderKind.REMOTE
    override suspend fun checkAvailability(): LlmProviderAvailability = LlmProviderAvailability.Available
}
```

##### Task 1.2c: `jvmTest` — `RemoteLlmProviderTest` asserting `checkAvailability()` is always `Available`, `kind == REMOTE`, and `formatter` delegates calls through unchanged (fake `LlmFormatterProvider`).

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/llm/RemoteLlmProviderTest.kt` (new)

---

### Story 1.3: `LlmProviderRegistry`

**As a** developer, **I want** a flat registry over N simultaneously-configured providers,
**so that** a feature can ask "what's usable right now" and pick, instead of the codebase's
existing `RepositoryFactory`-style single-active-backend-per-interface pattern (which
cannot express "both an Anthropic key and on-device ML Kit are available at once").

**Acceptance Criteria**:
- `LlmProviderRegistry(private val providers: List<LlmProvider>)` — plain class (not an
  `object`), constructor-injected, test-injectable like `RepositoryFactoryImpl`.
- `fun all(): List<LlmProvider>` — static list, no suspend, safe to call from composition.
- `fun find(id: String): LlmProvider?`
- `suspend fun availableProviders(): List<LlmProvider>` — filters `all()` by
  `checkAvailability() !is Unavailable` (i.e. `Available` or `Preparing` both count as
  "usable/soon-usable" for display purposes; callers that need strictly-ready-now use
  `availableProviders().filter { it.checkAvailability() is Available }` — see Task 1.3b
  for the strict variant).
- `suspend fun availableForFeature(feature: LlmFeature, excludeShortFormOnly: Boolean = false): List<LlmProvider>`
  — same as `availableProviders()` but additionally filters out
  `!supportsLongFormOutput` providers when `excludeShortFormOnly = true` (used by Epic 7's
  synthesis feature, which cannot use the 256-token-capped Android on-device provider).
- Constructed once, not per-call — credential rotation (pitfalls §1.4) is handled by
  providers reading credentials fresh inside `formatter.format()`/`checkAvailability()`
  rather than caching a key at construction time; this is a requirement on Epic 1's
  wrapper factories and Epic 3's remote providers, not on the registry itself.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistry.kt` (new)

##### Task 1.3a: Create `LlmProviderRegistry.kt` with `all()`, `find()`, `availableProviders()`, `availableForFeature()` as specified above.

##### Task 1.3b: `businessTest` — `LlmProviderRegistryTest`, **table-driven** (per pitfalls §3.3's explicit recommendation to avoid one-bespoke-test-per-scenario as providers scale): a matrix of `(provider availability combinations) × feature × excludeShortFormOnly` → expected `availableForFeature()` result, using fake `LlmProvider` implementations (no real Claude/OpenAI/ML Kit dependency). Include at minimum: all-unavailable → empty; on-device-only-available+excludeShortFormOnly=true → empty; on-device-only-available+excludeShortFormOnly=false → contains it; mixed remote+on-device available → both.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryTest.kt` (new)

---

### Story 1.4: `LlmFeature` + `LlmSettings`

**As a** developer, **I want** per-feature provider selection storage parallel to
`TagSettings`/`VoiceSettings`, **so that** "tagging uses on-device, voice uses Claude" is
expressible (Zed's per-feature-override pattern from features research).

**Acceptance Criteria**:
- `LlmFeature` enum: `VOICE_FORMATTING`, `TAG_SUGGESTION`, `GRAPH_EDIT_SYNTHESIS`.
- `LlmSettings(private val platformSettings: Settings)`:
  - `fun getSelectedProviderId(feature: LlmFeature): String?` — `null` means "Auto"
    (registry fallback-scans `availableForFeature()` in priority order: on-device first,
    then remote — see Epic 8 for the exact precedence this replaces).
  - `fun setSelectedProviderId(feature: LlmFeature, providerId: String?)`
  - Keys namespaced `llm.feature.<feature-name-lowercase>.provider_id`.
- No new SQL table — reuses the existing `Settings` key-value store, same pattern as
  `TagSettings`/`VoiceSettings`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmFeature.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSettings.kt` (new)

##### Task 1.4a: Create `LlmFeature.kt`.

##### Task 1.4b: Create `LlmSettings.kt` per spec above.

##### Task 1.4c: `businessTest` — `LlmSettingsTest`: round-trip get/set per feature, default `null` ("Auto") when unset, independent storage across the three `LlmFeature` values (regression guard for pitfalls §6.2's warning about accidentally sharing one boolean across features).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSettingsTest.kt` (new)

---

### Story 1.5: `platformOnDeviceLlmProvider()` expect/actual scaffold

**As a** developer, **I want** an `expect fun platformOnDeviceLlmProvider(): LlmProvider?`
scaffold across all four platforms now, **so that** Epics 4 and 5 only need to fill in
their `actual` bodies rather than also wiring the expect/actual mechanism.

**Acceptance Criteria**:
- `expect fun platformOnDeviceLlmProvider(): LlmProvider?` in `commonMain`.
- `actual fun platformOnDeviceLlmProvider(): LlmProvider? = null` in `jvmMain` and
  `wasmJsMain` (no on-device story on desktop/web in v1 — permanent `null`, not a
  placeholder to fill in later).
- `actual fun platformOnDeviceLlmProvider(): LlmProvider? = null` in `androidMain` and
  `iosMain` **as a temporary placeholder** — replaced with real implementations in Epic 4
  (Story 4.2) and Epic 5 (Story 5.5) respectively. This task's job is only to make the
  project compile on all targets with the new expect/actual in place; it must not block on
  Epics 4/5.
- Mirrors the existing `expect class CredentialStore()` / `MlKitLlmFormatterProvider.create()
  -> null` "capability not available" convention already established in the codebase —
  no new pattern.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (new), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (new), `kmp/src/iosMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (new), `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (new), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (new)

##### Task 1.5a–e: One file per source set as specified. Build verification: `bazel build //kmp:jvm_tests //kmp:android_app --config=android` (and the Gradle iOS/wasm targets per `CLAUDE.md`) must succeed with all five files in place before this story is done.

---

### Story 1.6: Registry composition root (`buildLlmProviderRegistry`)

**As a** developer, **I want** a single commonMain factory function that assembles the
registry from credential-backed Claude/OpenAI wrappers plus the platform on-device
provider, **so that** Epic 6 (Settings UI) and Epic 8 (consumer migration) have one call
site to depend on. **This story does not touch `App.kt`'s existing
`buildLlmFormatterForTags` or any current wiring** — it adds the new factory
side-by-side, unused by production code until Epic 8.

**Acceptance Criteria**:
- `fun buildLlmProviderRegistry(llmCredentialStore: LlmCredentialStore): LlmProviderRegistry`
  — takes the Epic 2 credential wrapper (stub this story's test with a fake if Epic 2
  hasn't landed yet; do not block Epic 1 on Epic 2 — if sequenced first, use the real
  type). Constructs: `RemoteLlmProvider("anthropic", ..., ClaudeLlmFormatterProvider.withDefaults(key))`
  only if a key is present, same for `"openai"`; appends `platformOnDeviceLlmProvider()`
  if non-null. Returns a registry with only the providers that have credentials/capability
  — an absent Anthropic key means no `"anthropic"` entry in `all()`, not an entry that
  fails on first use.
- Does **not** yet include Gemini or the generic OpenAI-compatible provider (Epic 3 lands
  after this; extend the composition root then, not now).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryFactory.kt` (new)

##### Task 1.6a: Implement `buildLlmProviderRegistry` per spec.

##### Task 1.6b: `businessTest` — `LlmProviderRegistryFactoryTest`: no credentials + no on-device → empty registry; Anthropic key only → one entry; both keys + fake on-device provider → three entries with correct `id`s.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryFactoryTest.kt` (new)

---

### Story 1.7: `Settings.containsKey()` capability (prerequisite for Epic 8 Story 8.2's install-vs-upgrade guard)

**As a** developer, **I want** a way to distinguish "key was never set" from "key is
absent so the typed getter returned its default," **so that** Epic 8 Story 8.2's
existing-install-vs-fresh-install default-behavior guard is actually implementable.
Verified against the current `Settings` interface (`platform/Settings.kt`): it exposes
only typed getters with defaults (`getBoolean`/`getString`) and no presence check, so
"key absent" and "key present but equal to the default" are indistinguishable today —
this blocks Story 8.2 as originally written. Landed in Epic 1 (not Epic 8) so it merges
before Story 8.2 needs it, consistent with Epic 1 being a hard prerequisite for
everything else.

**Acceptance Criteria**:
- `Settings` gains `fun containsKey(key: String): Boolean`.
- Implemented on all three existing platform actuals (verified — these are the only
  ones that currently exist; there is no `iosMain` `Settings`/`PlatformSettings` actual
  in this codebase today, a pre-existing gap out of scope for this plan):
  - `androidMain` (`PlatformSettings.android.kt`): `prefs.contains(key)`, wrapped in the
    same `try/catch(CancellationException) { throw } catch(Exception) { false }` pattern
    the other methods on this class already use.
  - `jvmMain` (`PlatformSettings.kt`): `props.containsKey(key)` (the in-memory
    `Properties` instance already loaded from `prefs.properties`).
  - `wasmJsMain` (`PlatformSettings.kt`): `localStorage.getItem(key) != null`.
- Every fake/test-double `Settings` implementation already used by the test suite (e.g.
  `VoiceSettingsTest`'s private `MapSettings`, and any other in-repo fake `Settings`
  used by `businessTest`) is updated to implement `containsKey` correctly — `true` iff a
  value was ever `put`, independent of what the typed getters would return as a default.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/Settings.kt` (modify), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.android.kt` (modify), `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` (modify), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` (modify)

##### Task 1.7a: Add `containsKey(key: String): Boolean` to the `Settings` interface and implement it on the three existing platform actuals (android/jvm/wasmJs) per spec above.

##### Task 1.7b: Update every fake `Settings` test double used by `businessTest` (grep for `: Settings` implementations under `businessTest`/`commonTest`, e.g. `VoiceSettingsTest.MapSettings`) to implement `containsKey` with correct presence semantics, so downstream tests (Story 8.2c) can rely on it.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceSettingsTest.kt` (modify — `MapSettings` fake), plus any other fake `Settings` implementations found by grep

---

### Story 1.8: `wasmJs` Ktor client engine (prerequisite for Epic 3's new remote providers to link on the web target)

**As a** developer, **I want** a declared Ktor HTTP engine artifact for `wasmJsMain`,
**so that** `commonMain` code that constructs an `HttpClient` (all remote
`LlmFormatterProvider`s — existing Claude/OpenAI today, Gemini/generic-OpenAI-compatible
from Epic 3) has something to resolve against on the web target. Verified against
`kmp/build.gradle.kts`: the `wasmJsMain` dependency block (`enableJs` guarded) currently
declares only an `npm("@sqlite.org/sqlite-wasm", ...)` dependency and no Ktor engine —
every other source set (`jvmMain`, `androidMain`, `iosMain`) declares one
(`ktor-client-okhttp`/`ktor-client-darwin`) but `wasmJsMain` does not. Landed here, in
Epic 1, since it's a shared build-config prerequisite for every remote provider (existing
and Epic 3's new ones), not specific to Gemini/generic-OpenAI-compatible.

**Acceptance Criteria**:
- `wasmJsMain`'s dependency block in `kmp/build.gradle.kts` gains
  `implementation("io.ktor:ktor-client-js:3.1.3")` (stack research's recommended engine
  for `wasmJsMain`, matching the `3.1.3` Ktor version already pinned everywhere else in
  this file).
- `bazel build //kmp:web_app` (delegates to Gradle `wasmJsBrowserDistribution` per
  `CLAUDE.md`) succeeds with the new dependency present.
- This task only makes the web target **link**; it does not claim remote providers are
  actually reachable from the browser at runtime — CORS behavior is verified separately
  in Epic 3 Task 3.3d once Gemini/generic-OpenAI-compatible are implemented.

**Files**: `kmp/build.gradle.kts` (modify)

##### Task 1.8a: Add `ktor-client-js:3.1.3` to `wasmJsMain`'s dependency block.

##### Task 1.8b: Build verification — confirm `bazel build //kmp:web_app` succeeds with the new engine dependency present. Document as a baseline (before Epic 3 adds two more remote providers to the same target graph) whether the existing Claude/OpenAI providers already compiled into the web bundle before this change, or whether this dependency was silently missing/unused until now.

---

## Epic 2: Credential store consolidation

**Goal**: Relocate `CredentialStore`/`CredentialAccess` from `git/` to a platform-layer
security package, add a typed `LlmCredentialStore` wrapper for provider secrets, and run a
**one-shot, no-permanent-fallback** migration of `VoiceSettings`' plaintext Anthropic/OpenAI
keys into it. This directly fixes the live plaintext-on-desktop/WASM security gap pitfalls
research confirmed (§0, §1.1). **This epic owns `VoiceCaptureSettings.kt` too** —
the existing Settings-screen composable
(`ui/components/settings/VoiceCaptureSettings.kt`) has 4 call sites
(`voiceSettings.getAnthropicKey()`/`getOpenAiKey()`/`setAnthropicKey()`/`setOpenAiKey()`
at ~L36-37, L183-184) that read/write the plaintext keys directly from the UI layer, not
just from `LlmCredentialMigration`. Retiring these is scoped explicitly as Epic 6 Story
6.6 (once the unified provider-settings UI exists to redirect users to) — it is called
out here so it isn't silently left as "someone else's problem" behind Epic 8 Story 8.4's
grep checkpoint.

### Story 2.1: Relocate `CredentialStore`/`CredentialAccess` to `platform/security/`

**As a** developer, **I want** the existing, already-correct
`expect class CredentialStore` moved out of `git/` into a package that doesn't imply
git-specific scope, **so that** LLM code importing it isn't reaching across an unrelated
feature boundary.

**Acceptance Criteria**:
- New package `dev.stapler.stelekit.platform.security` contains: `CredentialAccess.kt`
  (interface, unchanged contents), `CredentialStore.kt` (expect, unchanged contents), and
  the four platform actuals (`AndroidCredentialStore.kt`, `IosCredentialStore.kt`,
  `JvmCredentialStore.kt`, `wasmJsMain`'s stub) moved with only their `package` line and
  imports changed — **no behavior change**, with exactly one explicit carve-out (Task
  2.1d below): `AndroidCredentialStore`/`CredentialAccess` gain one new method,
  `storeBlocking()`, used only by Story 2.3's migration write path. No existing
  git-credential call site is changed to use it — `store()`'s existing
  `apply()`-based (fire-and-forget) behavior is untouched for git credentials. This is
  the one deliberate exception to "no behavior change," carved out here rather than left
  implicit, because ADR-011 requires the migration write be synchronous
  (`commit()`, not `apply()`) and Story 2.3's original write-then-read-back check does
  not actually achieve that with `apply()` alone (see Task 2.1d).
  `git/VaultCredentialStore.kt` stays in `git/`
  (it's genuinely git/paranoid-mode-specific per architecture research) but its `package`
  import for `CredentialAccess` updates to the new location; it continues to `implement
  CredentialAccess` exactly as today.
- Every existing call site (`GraphManager.removeGraph()`, any git-setup code constructing
  `CredentialStore()` directly) updates its import — zero functional change (aside from
  the Task 2.1d carve-out above), this is a pure move + import fix.
- `GitConfigRepository`/`GitSyncService`/anything else referencing `git.CredentialStore`
  updates accordingly.

**Files**: move `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/CredentialStore.kt` → `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`, move `git/CredentialAccess.kt` → `platform/security/CredentialAccess.kt`, move 4 platform actuals similarly, update `git/VaultCredentialStore.kt` import, update `git/GraphManager.kt` (or wherever `removeGraph()` lives) import, update any other call sites found by grep.

##### Task 2.1a: Move the 6 files (expect + interface + 4 actuals) to `platform/security/`, updating `package` declarations.

##### Task 2.1b: Grep-and-fix every import of `dev.stapler.stelekit.git.CredentialStore` / `dev.stapler.stelekit.git.CredentialAccess` across the codebase (expect ~5–10 call sites: `VaultCredentialStore`, `GraphManager`, git setup screens/viewmodels). Run `bazel build //...` (or `./gradlew ciCheck` per repo convention) to confirm zero compile errors post-move.

##### Task 2.1c: Confirm existing git-credential tests (`AndroidCredentialStoreTest`/`JvmCredentialStoreTest`/etc. — grep for their actual names) still pass unmodified after the package move (behavior must be identical; only package/imports changed).

##### Task 2.1d: Add a synchronous write path for migration use only. Verified against the current code: `AndroidCredentialStore.store()` calls `prefs.edit().putString(key, value).apply()` — `apply()` updates `SharedPreferences`' in-memory cache **synchronously** before returning but flushes to disk **asynchronously** afterward. An immediate `retrieve()`/read-back after `apply()` reads the in-memory cache and reports success regardless of whether the disk write has actually landed; a process crash in the window between `apply()` returning and its async disk flush completing silently loses the write even though the read-back check passed. This is exactly the race ADR-011 (Decision, step 2) requires closing with a **synchronous** write (`commit()`, not `apply()`) — Story 2.3's original read-back-only mitigation does not close it. Fix:
  - Add `fun storeBlocking(key: String, value: String): Boolean` to `CredentialAccess`, with a default implementation `{ store(key, value); return true }` (correct as-is for `JvmCredentialStore`/`IosCredentialStore`/`VaultCredentialStore`/the `wasmJsMain` stub, whose underlying writes are already synchronous — file I/O, Keychain, and a no-op respectively).
  - Override on `AndroidCredentialStore`: `fun storeBlocking(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()` — returns `commit()`'s own boolean success/failure result, giving the caller a durable-before-return guarantee `apply()` cannot provide.
  - **Do not** change `AndroidCredentialStore.store()` itself, and do not route any existing git-credential call site through `storeBlocking()` — `commit()` blocks the calling thread until the write lands on disk, which is the correct tradeoff only for the one-shot migration path (a handful of writes at app startup), not for interactive git-credential entry on the main thread.
  - Document this rationale as a code comment directly on `storeBlocking()` so a future refactor does not "simplify" it back to `apply()` for consistency with `store()` — the two methods have intentionally different durability contracts.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialAccess.kt` (modify — add `storeBlocking` with default impl), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/security/AndroidCredentialStore.kt` (modify — override with `commit()`)

---

### Story 2.2: `LlmCredentialStore` typed wrapper + custom-provider config schema

**As a** developer, **I want** a typed wrapper over the relocated `CredentialStore` for
provider secrets, plus a non-secret config schema for the generic OpenAI-compatible
provider (base URL, model, optional Azure deployment/api-version), **so that** every
future field addition isn't a migration (pitfalls §1.2's explicit warning against an ad-hoc
JSON blob in `Settings`).

**Acceptance Criteria**:
- `LlmCredentialStore(private val credentialStore: CredentialAccess)`:
  - `fun getApiKey(providerId: String): String?` / `fun setApiKey(providerId: String, key: String)` / `fun deleteApiKey(providerId: String)` — keys namespaced `llm.<providerId>.api_key`, e.g. `llm.anthropic.api_key`, `llm.custom.<uuid>.api_key`.
  - `fun setApiKeyBlocking(providerId: String, key: String): Boolean` — delegates to
    `credentialStore.storeBlocking(...)` (Epic 2 Story 2.1 Task 2.1d) and returns its
    success boolean. **Migration-only** — the only intended caller is
    `LlmCredentialMigration` (Story 2.3); normal credential entry (Settings UI, Epic 6)
    continues to use `setApiKey()`, which is fine to be fire-and-forget on Android since
    it isn't immediately followed by clearing a second, only-copy plaintext source.
- Non-secret custom-provider config (base URL, model name, and for a future Azure-specific
  provider type: deployment name + API version) is **explicitly NOT stored in
  `LlmCredentialStore`** — it lives in `LlmSettings` as individual namespaced `Settings`
  keys (`llm.custom.<uuid>.base_url`, `llm.custom.<uuid>.model`), matching how
  `git/model/GitConfig` already separates non-secret config from `CredentialStore`-held
  secrets. Add `CustomProviderConfig` data class (`id`, `displayName`, `baseUrl`, `model`,
  `allowInsecureHttp: Boolean`) plus `LlmSettings.getCustomProviderConfig(id)` /
  `setCustomProviderConfig(config)` / `getCustomProviderIds(): List<String>` (a
  comma-joined `Settings` string list — no JSON blob, consistent with the rest of this
  story's schema rule) / `removeCustomProviderConfig(id)`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialStore.kt` (new), extend `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSettings.kt` (from Epic 1) with `CustomProviderConfig` + methods

##### Task 2.2a: Create `LlmCredentialStore.kt` per spec.

##### Task 2.2b: Extend `LlmSettings.kt` with `CustomProviderConfig` and the custom-provider-id-list methods per spec.

##### Task 2.2c: `businessTest` — `LlmCredentialStoreTest` (fake `CredentialAccess`) round-trips API keys per provider id, isolation between provider ids confirmed. `businessTest` — `LlmSettingsCustomProviderTest`: add two custom provider configs, list ids, round-trip each config, remove one, confirm the other survives.

##### Task 2.2d: Implement `setApiKeyBlocking()` on `LlmCredentialStore` per spec above (delegates to `CredentialAccess.storeBlocking()`). `businessTest` addition to `LlmCredentialStoreTest`: a fake `CredentialAccess` whose `storeBlocking()` returns `false` (simulating a failed durable write) → `setApiKeyBlocking()` returns `false` and does not throw; a fake whose `storeBlocking()` returns `true` → returns `true` and `getApiKey()` reflects the write.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmCredentialStoreTest.kt` (new), `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSettingsCustomProviderTest.kt` (new)

---

### Story 2.3: One-shot `VoiceSettings` key migration (no permanent fallback)

**As a** developer, **I want** a single migration pass, modeled on `db/UuidMigration.kt`,
that copies any existing `VoiceSettings` Anthropic/OpenAI key into `LlmCredentialStore`
exactly once and then clears the plaintext original, **so that** no user silently loses a
configured provider on upgrade (pitfalls §1.1's "silent data loss" risk) and there is no
second, permanently-live plaintext code path.

**Acceptance Criteria**:
- `LlmCredentialMigration(private val voiceSettings: VoiceSettings, private val llmCredentialStore: LlmCredentialStore, private val platformSettings: Settings)`
  with `fun runIfNeeded()`:
  1. If `platformSettings.getBoolean("llm.migration.voice_settings_migrated_v1", false)` is
     already `true`, return immediately (idempotent no-op — safe to call on every app
     start).
  2. For each of Anthropic/OpenAI: if `voiceSettings.getAnthropicKey()` (resp. OpenAI) is
     non-null AND `llmCredentialStore.getApiKey("anthropic")` (resp. `"openai"`) is
     currently `null`, write the value into `llmCredentialStore` using
     **`setApiKeyBlocking()`, not `setApiKey()`** (Epic 2 Story 2.2 Task 2.2d — the
     migration-only synchronous path backed by `AndroidCredentialStore.storeBlocking()`'s
     `commit()`, Story 2.1 Task 2.1d). This step exists because `setApiKey()`'s default
     path (`store()` → Android's `apply()`) is **fire-and-forget**: `apply()` updates the
     in-memory `SharedPreferences` cache synchronously but flushes to disk
     asynchronously, so an immediate read-back after a plain `setApiKey()` call would
     read the in-memory cache and report success even if the disk write hasn't landed —
     a crash in that window loses the key while this migration believes it succeeded.
     `setApiKeyBlocking()` blocks until the write is durable (or reports failure via its
     `Boolean` return) before this step proceeds. Only after `setApiKeyBlocking()`
     returns `true`, **additionally read it back via `getApiKey()`** to verify the value
     round-trips correctly (defense-in-depth against a durable-but-corrupted write) —
     before touching the plaintext source. Then clear the plaintext original.
  3. Set `platformSettings.putBoolean("llm.migration.voice_settings_migrated_v1", true)`
     only after both keys have been processed (whether migrated, already-absent, or
     already-migrated) — so a crash mid-migration does not mark it done prematurely and
     leave the second key unmigrated forever.
  4. If step 2's `setApiKeyBlocking()` returns `false`, or the subsequent `getApiKey()`
     read-back does not match what was written, do **not** clear the plaintext source and
     do **not** set the migrated flag — leave state exactly as before so the migration
     retries on next app start rather than losing the key.
- **Do not** revert this to a plain `setApiKey()` + read-back for "simplicity" or
  "consistency with the rest of the migration" — the read-back alone was the original,
  insufficient mitigation this story replaces; `setApiKeyBlocking()`'s synchronous
  `commit()` is the actual fix, and the read-back is a secondary check on top of it, not
  a substitute for it.
- `VoiceSettings` needs a way to clear a key: add `fun clearAnthropicKey()` /
  `fun clearOpenAiKey()` (setting the underlying `Settings` string to `""` — consistent
  with existing getters already treating blank as "absent" via `.takeIf { it.isNotBlank() }`).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigration.kt` (new), extend `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt` with `clearAnthropicKey()`/`clearOpenAiKey()`

##### Task 2.3a: Implement `LlmCredentialMigration.runIfNeeded()` per spec above, calling `llmCredentialStore.setApiKeyBlocking()` (not `setApiKey()`) for the migration write.

##### Task 2.3b: Add `clearAnthropicKey()`/`clearOpenAiKey()` to `VoiceSettings.kt`.

##### Task 2.3c: `businessTest` — `LlmCredentialMigrationTest`, the highest-value test in this epic per pitfalls §6.1 ("do not just delete failing `VoiceSettingsTest` assertions — replace them with tests asserting the migration path explicitly"):
  - Seed `VoiceSettings` with both keys set, `LlmCredentialStore` empty → run → both migrated, both `VoiceSettings` getters now return `null`, migrated flag set.
  - Run again (already migrated) → no-op, no exceptions, values unchanged.
  - Seed only Anthropic (OpenAI never configured) → only Anthropic migrates; OpenAI absence is not treated as a migration failure.
  - Assert the migration calls `setApiKeyBlocking()`, not `setApiKey()`, for the write (fake `LlmCredentialStore` recording which method was invoked) — regression guard against a future edit silently reverting to the non-durable path.
  - Simulate durable-write failure (fake `LlmCredentialStore` whose `setApiKeyBlocking` returns `false`) → plaintext source is NOT cleared, migrated flag NOT set.
  - Simulate a `setApiKeyBlocking` that returns `true` but a subsequent `getApiKey` read-back that doesn't match (corrupted-write edge case) → plaintext source is NOT cleared, migrated flag NOT set.
  - Partial-then-resume: run once with the store failing (per above), then run again with the store fixed → completes the migration on the second call.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigrationTest.kt` (new)

##### Task 2.3d: Wire `LlmCredentialMigration.runIfNeeded()` to run exactly once at app startup, before any code path reads `LlmCredentialStore`/`VoiceSettings` keys for provider selection. Call site: top of `App.kt`'s root composable (`LaunchedEffect(Unit) { llmCredentialMigration.runIfNeeded() }`), constructed alongside `voiceSettings`/`platformSettings` in the existing `remember` block area (~App.kt L983). This must run before `tagEngine`/`voiceCaptureViewModel` are built so they never observe a not-yet-migrated state on first frame — if ordering can't guarantee this within Compose's effect scheduling, run it synchronously (non-suspend fast path — it's a handful of `Settings` reads/writes, not IO-bound) during the `remember` block itself rather than in `LaunchedEffect`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (modify)

---

### Story 2.4: Remove `VoiceSettings` plaintext key storage; lock in Android's fail-loud behavior

**As a** developer, **I want** the now-dead `VoiceSettings` Anthropic/OpenAI getters/setters
removed once Epic 8 has migrated every call site off them, **so that** the plaintext code
path cannot silently come back.

**Acceptance Criteria**:
- **This story's tasks are blocked on Epic 8 Story 8.4** (confirmation that
  `App.kt`'s `buildLlmFormatterForTags` and any other direct
  `voiceSettings.getAnthropicKey()`/`getOpenAiKey()` call sites are gone) **and on Epic 6
  Story 6.6** (confirmation that `VoiceCaptureSettings.kt`'s 4 direct call sites of the
  same accessors have been retired). Do not start
  this story until both Epic 8 Story 8.4 and Epic 6 Story 6.6 are merged — sequence it as
  the **last** task in the whole plan, not literally within Epic 2's own execution window.
- `VoiceSettings.getAnthropicKey()`, `setAnthropicKey()`, `getOpenAiKey()`, `setOpenAiKey()`,
  `clearAnthropicKey()`, `clearOpenAiKey()`, and their backing `KEY_ANTHROPIC`/`KEY_OPENAI`
  constants are deleted entirely.
- `VoiceSettingsTest`'s assertions covering the deleted methods are removed (not just
  commented out); the migration-path assertions live in `LlmCredentialMigrationTest`
  (Story 2.3c) instead, per pitfalls §6.1's explicit guidance not to just delete failing
  tests without a replacement.
- Separately (not blocked on Epic 8): add `AndroidCredentialStoreFailsLoudTest` —
  asserts that if `EncryptedSharedPreferences.create(...)` throws inside the relocated
  `AndroidCredentialStore`, the exception propagates (from the `by lazy` init) rather than
  silently falling back to plaintext `SharedPreferences` — this is already the correct
  behavior per architecture research (`CredentialStore` does not have `PlatformSettings`'
  fallback), this task locks it in with a regression test so a future refactor can't
  reintroduce the fallback.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt` (modify), `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/VoiceSettingsTest.kt` (modify), `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/security/AndroidCredentialStoreFailsLoudTest.kt` (new)

##### Task 2.4a: Delete dead `VoiceSettings` methods/constants (after Epic 8 confirmation).

##### Task 2.4b: Update `VoiceSettingsTest`.

##### Task 2.4c: Add `AndroidCredentialStoreFailsLoudTest` (can run any time, not blocked on Epic 8).

---

## Epic 3: New remote providers

**Goal**: Add Google Gemini (new, raw Ktor REST) and generalize
`OpenAiLlmFormatterProvider` into the generic OpenAI-compatible provider covering
Ollama/LM Studio/OpenRouter/current-gen Azure OpenAI. Both live in `voice/` alongside their
siblings (existing file-placement convention); their `LlmProvider` wrappers live in `llm/`.

### Story 3.1: Gemini REST provider

**As a** developer, **I want** a `GeminiLlmFormatterProvider` matching the existing
Claude/OpenAI raw-Ktor-REST pattern, **so that** Gemini needs no new dependency (stack
research confirmed no trustworthy KMP SDK exists) and reuses `LlmProviderSupport`'s
shared error-mapping/truncation-detection.

**Acceptance Criteria**:
- `GeminiLlmFormatterProvider(httpClient: HttpClient, apiKey: String, model: String = "gemini-2.0-flash", circuitBreaker: CircuitBreaker = defaultCircuitBreaker())` implementing `LlmFormatterProvider`.
- `POST https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent`, header `x-goog-api-key: $apiKey`.
- Request/response DTOs mirror Gemini's `generateContent` shape (`contents: [{ parts: [{ text }] }]`, system instruction via Gemini's `systemInstruction` field — do not smuggle the system prompt into the first user turn the way `OpenAiLlmFormatterProvider` avoids re-sending the transcript; use Gemini's actual `systemInstruction` field).
- Same circuit-breaker policy as Claude/OpenAI (`defaultCircuitBreaker()`: 3 failures / 30s reset / 2x backoff / 5min max), same `LlmProviderSupport.mapHttpError`/`detectTruncation` reuse, same `withDefaults(apiKey)` companion factory pattern.
- HTTP status/error mapping identical in shape to `ClaudeLlmFormatterProvider`'s `httpCallInternal` (200 → parse, else → `LlmProviderSupport.mapHttpError`, `IOException` → `NetworkError`, other `Exception` → `ApiError(-1, "Unexpected error")`).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/GeminiLlmFormatterProvider.kt` (new)

##### Task 3.1a: Implement `GeminiLlmFormatterProvider.kt` per spec, closely mirroring `ClaudeLlmFormatterProvider.kt`'s structure.

##### Task 3.1b: `jvmTest` — `GeminiLlmFormatterProviderTest` using Ktor `MockEngine`: success parse, 401/429/5xx mapping (assert identical codes-to-`Failure` mapping as the existing `LlmProviderSupportTest` table), empty-candidates response → `NetworkError`, circuit breaker opens after 3 consecutive failures (mirror `ClaudeLlmFormatterProviderTest`'s existing circuit-breaker test structure).

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/voice/GeminiLlmFormatterProviderTest.kt` (new)

##### Task 3.1c: `RemoteLlmProvider`-wrapped registration — extend `buildLlmProviderRegistry` (Epic 1 Story 1.6) to include `"gemini"` when a Gemini key is present in `LlmCredentialStore`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryFactory.kt` (modify), extend `LlmProviderRegistryFactoryTest.kt`

---

### Story 3.2: Generalize `OpenAiLlmFormatterProvider`

**As a** developer, **I want** the existing 90%-complete OpenAI provider to accept
`http://localhost`/loopback endpoints and a configurable model name, **so that**
Ollama/LM Studio/OpenRouter/Azure-v1-GA all work through it without a bespoke
implementation per vendor.

**Acceptance Criteria**:
- Constructor gains `model: String = "gpt-4o-mini"` (replacing the `private const val
  OPENAI_MODEL`) and `allowInsecureHttp: Boolean = false`.
- `init` validation becomes: `require(baseUrl.startsWith("https://") || (allowInsecureHttp && isLoopbackHttpUrl(baseUrl))) { "baseUrl must use HTTPS, or HTTP to a loopback host with allowInsecureHttp=true" }`. `isLoopbackHttpUrl` checks the URL starts with `http://` **and** the host is `localhost`, `127.0.0.1`, or `[::1]` — never a blanket "any HTTP allowed" (this is the security-relevant scope boundary; a non-loopback HTTP endpoint stays rejected even with the flag set, closing the "leaks a key over plaintext to a non-local host" risk).
- Empty/blank `apiKey` **omits the `Authorization` header entirely** rather than sending `Authorization: Bearer ` (malformed, trailing-space header) — matches Ollama/LM Studio convention (pitfalls §4 finding) better than a placeholder string, and is simpler to reason about.
- `OPENAI_MODEL` is used as the default constructor value only; the request body always sends the constructor-provided `model`.
- 100% backward compatible: existing callers using `withDefaults(apiKey)` (no `model`/`allowInsecureHttp` args) get byte-identical behavior to today.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/OpenAiLlmFormatterProvider.kt` (modify)

##### Task 3.2a: Add `model` and `allowInsecureHttp` constructor params, update `init` validation, implement `isLoopbackHttpUrl` helper.

##### Task 3.2b: Fix `Authorization` header — only append when `apiKey.isNotBlank()`.

##### Task 3.2c: Update `jvmTest` — extend `OpenAiLlmFormatterProviderTest`: `http://localhost:11434` + `allowInsecureHttp=true` → constructs successfully; `http://192.168.1.5:11434` + `allowInsecureHttp=true` → still throws (non-loopback rejected); `https://...` unaffected regardless of the flag; blank `apiKey` → request has no `Authorization` header (assert via `MockEngine` request capture); custom `model` value appears in the serialized request body.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/voice/OpenAiLlmFormatterProviderTest.kt` (modify)

---

### Story 3.3: Generic/custom OpenAI-compatible `LlmProvider` + reachability probe

**As a** user, **I want** to add a custom OpenAI-compatible endpoint (Ollama, LM Studio,
OpenRouter, Azure v1-GA) in Settings, with the "fetch models" action doubling as a
connectivity/compatibility check, **so that** I don't save a typo'd URL/key silently
(resolves requirements Open Question 2 per features research's Obsidian-AI-Providers
precedent).

**Acceptance Criteria**:
- `CustomOpenAiCompatibleLlmProvider(config: CustomProviderConfig, apiKey: String?, httpClient: HttpClient) : LlmProvider` — `kind = REMOTE`, `formatter` wraps a `OpenAiLlmFormatterProvider(httpClient, apiKey ?: "", config.baseUrl, config.model, config.allowInsecureHttp)`.
- `checkAvailability()`: `GET {baseUrl}/v1/models` (best-effort probe; OpenAI, Ollama, and LM Studio all implement this endpoint per stack research). 200 → `Available`; connection failure/timeout → `Unavailable("Could not reach $baseUrl", retryable = true)`; 401/403 → `Unavailable("Authentication failed", retryable = false)`; other non-2xx → `Unavailable("Unexpected response ($status)", retryable = true)`.
- `fun fetchAvailableModels(): Either<DomainError.NetworkError, List<String>>` — same `GET /v1/models` call, parses the `data[].id` field of the OpenAI-shaped models-list response, returns the model id list for the Settings UI's model-picker dropdown (Epic 6 Story 6.3 consumes this).
- Explicitly **out of scope for this provider type**: legacy Azure OpenAI (pre-v1-GA
  deployment-path + `api-version` query param + `api-key` header auth). Document this as a
  code comment on `CustomOpenAiCompatibleLlmProvider` and do not attempt to special-case
  Azure's legacy auth scheme here — per stack research, that's a structurally different
  provider type, not a variation of the generic one.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/CustomOpenAiCompatibleLlmProvider.kt` (new)

##### Task 3.3a: Implement `CustomOpenAiCompatibleLlmProvider.kt` per spec, including `fetchAvailableModels()`.

##### Task 3.3b: `jvmTest` — `CustomOpenAiCompatibleLlmProviderTest` using `MockEngine`: 200 on `/v1/models` → `Available` + correct model list parsed; connection-refused simulated → `Unavailable(retryable=true)`; 401 → `Unavailable(retryable=false)`.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/llm/CustomOpenAiCompatibleLlmProviderTest.kt` (new)

##### Task 3.3c: Wire multi-instance support into `buildLlmProviderRegistry` — for each id in `LlmSettings.getCustomProviderIds()`, construct a `CustomOpenAiCompatibleLlmProvider` from its `CustomProviderConfig` + `LlmCredentialStore.getApiKey(id)`, append to the registry's provider list.

##### Task 3.3d: `wasmJs` (web target) build + runtime-story verification. Builds on Epic 1 Story 1.8's `ktor-client-js` prerequisite. Confirm `bazel build //kmp:web_app` still succeeds now that Gemini (`GeminiLlmFormatterProvider`) and the generic OpenAI-compatible provider (`CustomOpenAiCompatibleLlmProvider`/`OpenAiLlmFormatterProvider`) are part of the same `commonMain` target graph as the existing Claude/OpenAI providers. Separately, **document explicitly** (in this task's notes, referenced from Epic 6 Story 6.2) whether remote providers are actually usable from the browser at runtime: per stack research, direct browser-side calls to Anthropic/OpenAI/Gemini APIs will likely be blocked by CORS regardless of the Ktor engine being present, since these APIs are not designed for direct browser calls. If CORS blocks them in practice (verify with a manual browser check against at least one provider), this is a real, documented constraint — not a silent gap — and Story 6.2 must hide or disable remote-provider configuration on the web target with a clear explanatory message rather than let a web user configure a provider that will never work.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryFactory.kt` (modify), extend `LlmProviderRegistryFactoryTest.kt`

---

## Epic 4: Android on-device provider fix + wiring

**Goal**: Fix the `checkEligible()`/`format()` tri-state mismatch pitfalls research found
(reports `DOWNLOADABLE`/`DOWNLOADING` as eligible, then fails immediately on first use),
handle AICore's foreground-only + per-app-quota constraints, and wire the
previously-dead-code `MlKitLlmFormatterProvider` into the registry and tag suggestion.

### Story 4.1: Fix eligibility tri-state + AICore error-code handling

**As a** developer, **I want** `MlKitLlmFormatterProvider` to expose the same
`LlmProviderAvailability` tri-state as every other provider, and to distinguish
`BACKGROUND_USE_BLOCKED`/`BUSY` from generic failures, **so that** the Settings UI never
shows "Available" for a model that's still downloading, and a rate-limited call gets a
"try again shortly" message instead of a generic error.

**Acceptance Criteria**:
- `MlKitLlmFormatterProvider` gains `suspend fun checkAvailability(): LlmProviderAvailability`
  (replacing the boolean `checkEligible()` — search the codebase first: `checkEligible()`
  currently has zero call sites per architecture research, so this is a clean rename/retype,
  not a compatibility-preserving addition):
  - `FeatureStatus.AVAILABLE` → `Available`
  - `FeatureStatus.DOWNLOADABLE`, `FeatureStatus.DOWNLOADING` → `Preparing("On-device model is downloading — this can take 15–30 minutes on first use")`
  - anything else → `Unavailable("On-device AI is not supported on this device", retryable = false)` — except immediately-post-reset states are indistinguishable from genuine unsupported at the ML Kit API level (pitfalls §2.1), so this case additionally sets `retryable = true` with reason `"Not yet available — check back in a few minutes"` when `checkStatus()` itself throws or returns an unrecognized value (vs. a clean `UNAVAILABLE` response, which is `retryable = false`).
- `LlmResult.Failure` (in `voice/LlmFormatterProvider.kt`) gains one new case:
  `data class OnDeviceUnavailable(val reason: String, val retryable: Boolean) : Failure`.
  This is a **shared sealed-interface change** — grep the whole codebase for every
  exhaustive `when` over `LlmResult`/`LlmResult.Failure` (`LlmTagProvider.suggestTags`'s
  `when (result)` at minimum) and add a branch mapping this new case to
  `DomainError.NetworkError.RequestFailed` (reuse existing error family — do not add a new
  `DomainError` case for this unless a genuinely distinct UI treatment is required; note in
  the task that the *reason string* is preserved through to the UI even though the
  `DomainError` case is reused).
- `format()`'s `DOWNLOADABLE`/`DOWNLOADING` branch returns
  `LlmResult.Failure.OnDeviceUnavailable("Model is still downloading", retryable = true)`
  instead of the current generic `ApiError(-1, "...")`.
- Catch ML Kit's `GenAiException`-shaped errors (check the actual exception type shipped by
  `com.google.mlkit:genai-prompt:1.0.0-beta2` at implementation time) specifically for
  `BACKGROUND_USE_BLOCKED` → `OnDeviceUnavailable("On-device AI requires the app to be in the foreground", retryable = true)` and `BUSY` → `OnDeviceUnavailable("On-device AI is busy — try again shortly", retryable = true)`, falling back to the existing generic `"On-device LLM error: ${e.message}"` `ApiError` for unrecognized exceptions.

**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/voice/MlKitLlmFormatterProvider.kt` (modify), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/LlmFormatterProvider.kt` (modify — add `OnDeviceUnavailable` case), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/tags/LlmTagProvider.kt` (modify — new `when` branch)

##### Task 4.1a: Add `OnDeviceUnavailable` to `LlmResult.Failure`; fix the resulting compile break in `LlmTagProvider.suggestTags`'s exhaustive `when`.

##### Task 4.1b: Rewrite `checkEligible()` → `checkAvailability(): LlmProviderAvailability` in `MlKitLlmFormatterProvider`; extract the `FeatureStatus`-to-`LlmProviderAvailability` mapping into a small **pure function** (no `GenerativeModel` dependency) so it's unit-testable without mocking the ML Kit SDK (per pitfalls §3.3's finding that this file has zero existing test coverage and the SDK likely isn't mockable in `androidUnitTest`).

##### Task 4.1c: Update `format()`'s `DOWNLOADABLE`/`DOWNLOADING`/error branches per spec, including the `BACKGROUND_USE_BLOCKED`/`BUSY` catch.

##### Task 4.1d: `businessTest` (or `jvmTest`, wherever the pure mapping function from 4.1b lands — it must NOT require `androidUnitTest` since it has no Android SDK dependency) — `MlKitAvailabilityMappingTest`: table-driven over all `FeatureStatus` values → expected `LlmProviderAvailability`.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/voice/MlKitAvailabilityMappingTest.kt` (new, exact location depends on where the pure function is extracted to — commonMain if fully SDK-independent, otherwise document as an androidUnitTest-only exception in the story with justification)

---

### Story 4.2: `androidMain` `platformOnDeviceLlmProvider()` wiring

**As a** developer, **I want** the Epic 1 scaffold's Android placeholder replaced with a
real `AndroidOnDeviceLlmProvider`, **so that** the registry can discover it.

**Acceptance Criteria**:
- `AndroidOnDeviceLlmProvider(private val delegate: MlKitLlmFormatterProvider) : LlmProvider` — `id = "android-ondevice"`, `displayName = "On-device (Gemini Nano)"`, `kind = ON_DEVICE`, `supportsLongFormOutput = false` (closes the 256-token-cap gap from Story 1.2/pitfalls §2.1), `formatter = delegate`, `checkAvailability()` delegates to `delegate.checkAvailability()` (Story 4.1).
- `actual fun platformOnDeviceLlmProvider(): LlmProvider? = MlKitLlmFormatterProvider.create()?.let { AndroidOnDeviceLlmProvider(it) }` replaces the Epic 1 placeholder in `androidMain`.

**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/AndroidOnDeviceLlmProvider.kt` (new), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (modify)

##### Task 4.2a: Implement `AndroidOnDeviceLlmProvider.kt` and update the `actual` per spec.

---

### Story 4.3: Wire into registry + tag suggestion (closes the Android-parity success metric)

**As a** developer, **I want** the on-device provider discoverable by the registry and
selectable by `LlmFeature.TAG_SUGGESTION`'s Auto fallback, **so that** an Android user with
zero API keys gets LLM-tier tag suggestions — the requirements doc's headline success
metric.

**Acceptance Criteria**:
- `buildLlmProviderRegistry` (Epic 1 Story 1.6) already calls `platformOnDeviceLlmProvider()`
  unconditionally — no change needed there once Story 4.2 lands; this story is verification
  + the fallback-ordering test, not new wiring code (the actual `App.kt` call-site
  migration that makes `LlmTagProvider` *use* this happens in Epic 8 Story 8.2 — this
  story only proves the registry-level plumbing is correct in isolation).
- `businessTest` table-driven fallback-order test (per pitfalls §3.3): "no remote key
  configured + on-device `Available`" → `registry.availableForFeature(TAG_SUGGESTION)`
  contains the on-device provider. "no remote key + on-device `Unavailable`" → empty list
  (feature correctly reports nothing usable, not a crash). "remote key present + on-device
  also available" → both present, ordering left to the caller (Epic 8 decides precedence,
  not the registry).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/AndroidOnDeviceFallbackTest.kt` (new)

##### Task 4.3a: Write `AndroidOnDeviceFallbackTest.kt` using a fake `LlmProvider` standing in for the real `AndroidOnDeviceLlmProvider` (no real ML Kit dependency — this test must run in `businessTest`, not `androidUnitTest`).

---

## Epic 5: iOS on-device provider (Swift Foundation Models shim + cinterop)

**Goal**: Ship a working iOS on-device provider in v1 (per requirements' resolved
decision). **Scope correction (post-adversarial-review):** this is substantially closer
to greenfield work than the original 3-story/~11-task estimate implied. Verified against
the actual repository: there is **zero existing Xcode project scaffolding** (no
`.xcodeproj`, `.xcworkspace`, `Podfile`, `iosApp/` directory, `Info.plist`, or `.swift`
file anywhere in the repo today) and **zero precedent for a hand-authored Kotlin/Native
cinterop `.def` file** binding to a custom Swift shim — the codebase's one existing iOS
cinterop example, `IosCredentialStore.kt`, binds directly against Apple's system
`Security` C/Obj-C framework headers, which is a materially simpler case (no custom
module, no hand-written shim, no `.def` authored from scratch). This epic therefore
stands up: the repo's first-ever iOS Xcode project, its first-ever hand-authored
cinterop `.def`, and a Swift shim with zero prior pattern to copy — against a framework
(`FoundationModels`) that also has zero prior binding precedent in this codebase.
Story/task count is roughly doubled from the original estimate (3 stories/~11 tasks →
6 stories/~19 tasks) to reflect this, with concrete new scaffolding and
bridge-smoke-test tasks added — not by relabeling existing tasks as "harder."

**Corrected CI framing**: the original framing ("this project's CI is Bazel/Gradle on
Linux") is **factually wrong** — verified: `ci-ios.yml`, `build-native-libs.yml`, and
`release.yml` all run on `macos-latest` runners today. The accurate statement is
narrower and already documented in-repo: `ci-ios.yml`'s `ios-framework` job explicitly
notes that full `compileKotlinIos*` compilation is blocked by **Gradle issue #17559**
(a `KotlinNativeBundleBuildService` classloader mismatch in multi-project builds with
AGP) and, separately, that `compileCommonMainKotlinMetadata` is blocked because
**Kotlin 2.3.x's K2 compiler cannot read Compose Multiplatform 1.7.x klibs** (compiled
with Kotlin 2.1.x) — `ci-ios.yml` works around both by validating `commonMain` via
`compileKotlinJvm` instead as a proxy. **No CI lane currently builds or runs the actual
iOS/cinterop compile path for this feature** — that is the real, narrower gap (a known,
already-documented upstream-bug limitation, not an absence of macOS CI capacity), and
every "manual verification only" task below exists because of it, not because CI is
"Linux-only." iOS on-device work in this epic requires manual verification by the
developer on macOS/Xcode until `ci-ios.yml`'s documented blockers clear.

Every task below separates "pure logic, unit-testable" from "real framework call,
manual-verification-only" explicitly, per pitfalls §2.2/§3.3.

### Story 5.1: Xcode project scaffolding (new — no existing project to attach to)

**As a** developer, **I want** a minimal iOS Xcode project to exist in this repo at all,
**so that** Stories 5.2–5.5 have a real project to add targets/schemes to, instead of the
original plan's "identify the exact Xcode project path during implementation," which
presupposed a project that a repo-wide search confirmed does not exist.

**Acceptance Criteria**:
- Repo-wide search for `.xcodeproj`/`.xcworkspace`/`Podfile`/`iosApp/`/`Info.plist`/`.swift`
  confirmed zero results before starting this story — re-confirm at implementation time in
  case something landed in the interim, but treat this as greenfield.
- A minimal Xcode project (e.g. `iosApp/iosApp.xcodeproj`) with a single bare app target
  (SwiftUI or UIKit, whichever is simpler to scaffold) that can link against the
  Kotlin/Native framework `kmp`'s iOS targets produce, following the standard "iosApp"
  module convention used by typical Kotlin Multiplatform projects.
- Document the manual build/run workflow (Xcode version, scheme, how the Kotlin framework
  is currently embedded — likely a manual "run `./gradlew :kmp:linkDebugFrameworkIos...`
  then open Xcode" step today, since `compileKotlinIos*` is blocked per the corrected CI
  framing above) in a short README or code comment inside `iosApp/`, since no CI lane
  automates this yet.

**Files**: new `iosApp/` directory (Xcode project + minimal app target — exact structure decided at implementation time and documented in-repo)

##### Task 5.1a: Search the repo for any existing iOS project files (re-confirm the zero-results baseline) and create the minimal Xcode project/workspace at `iosApp/` with a single bare app target.

##### Task 5.1b: Wire the new Xcode project's framework search path / embed step to consume the Kotlin/Native framework Gradle produces for `iosMain`; document the current manual workflow given the `compileKotlinIos*` blocker (corrected CI framing above) — this is not automatable in CI today.

##### Task 5.1c: **Manual verification only** (documented limitation): confirm the bare project builds and runs (e.g. shows a placeholder screen) on a contributor's Mac via Xcode. This is the gate that proves Story 5.2 onward has a real project to build against.

---

### Story 5.2: Cinterop bridge smoke test (de-risk the mechanism itself, before any Foundation Models logic)

**As a** developer, **I want** to prove a hand-authored `.def` file can bind Kotlin/Native
to a custom Swift-authored Obj-C-visible symbol *in this codebase*, using the smallest
possible no-op example, **so that** Stories 5.3–5.5's real Foundation Models integration
is not the first time this mechanism is exercised end-to-end. This directly addresses the
review's core B3 finding: `IosCredentialStore.kt` (the cited precedent) binds against
Apple's own `Security` system framework headers, not a hand-authored shim module — there
is no existing evidence in this repo that a custom `.def` + custom Swift shim compiles and
crosses the Kotlin↔Swift boundary at all. Prove that narrow claim here, isolated from the
(separately risky) Foundation Models API surface.

**Acceptance Criteria**:
- A trivial Swift shim class with a single `@objc` no-op method and **no**
  `FoundationModels` dependency, e.g.:
  ```swift
  @objc(PingShim) public class PingShim: NSObject {
      @objc public func ping() -> Int32 { return 42 }
  }
  ```
- A minimal `.def` file pointing at this shim's generated Obj-C header, plus a `cinterop`
  block in `kmp/build.gradle.kts`'s iOS target config referencing it.
- Kotlin/Native generates a callable binding for `ping()`, callable from `iosMain` Kotlin
  code.
- **Manual verification, but a hard gate**: call `ping()` from a throwaway `iosMain`
  entry point (or the Story 5.1 scaffold app) and confirm it returns `42` across the
  Kotlin→cinterop→Swift boundary on a contributor's Mac. **Do not start Story 5.3 until
  this passes** — it is the concrete, isolated proof that the bridge mechanism itself
  works in this codebase, decoupled from whether `FoundationModels` integration
  specifically works.

**Files**: new minimal Swift shim under `iosApp/` (exact path documented at implementation time), new `.def` file (e.g. `kmp/src/nativeInterop/cinterop/PingShim.def`), `kmp/build.gradle.kts` (modify)

##### Task 5.2a: Create the trivial no-op Swift shim (`PingShim` or similar) with zero `FoundationModels` dependency.

##### Task 5.2b: Author the smoke-test `.def` file and add its `cinterop` block to `kmp/build.gradle.kts`'s iOS target config; confirm Kotlin/Native generates a callable binding for the no-op method.

##### Task 5.2c: **Manual verification only** — call the no-op method from Kotlin and confirm the expected return value crosses the boundary correctly, on a contributor's Mac. Document the verification steps taken (Xcode/OS version, device/simulator). This is the hard gate described above.

---

### Story 5.3: Swift shim module — real Foundation Models methods

**As a** developer, **I want** a small Swift target exposing `@objc`-visible methods that
internally call the Swift-only `FoundationModels` framework, **so that** Kotlin/Native
cinterop has something it can actually bind against (pure Swift generics/macros are
invisible to Obj-C interop per stack research). Builds directly on Story 5.2's proven
bridge mechanism — this story is "expand the now-proven smoke-test shim with real logic,"
not "prove the mechanism works" (that risk is already retired).

**Acceptance Criteria**:
- New Swift target within the Story 5.1 Xcode project (e.g.
  `iosApp/FoundationModelsShim/`), structured the same way as the Story 5.2 smoke-test
  shim.
- Exposes an `@objc(FoundationModelsShim) public class FoundationModelsShim: NSObject` with:
  - `@objc public func checkAvailability(completion: @escaping (Int, String?) -> Void)` —
    returns an integer code (`0 = available`, `1 = deviceNotEligible`,
    `2 = appleIntelligenceNotEnabled`, `3 = modelNotReady`, `4 = other`) plus an optional
    human-readable detail string, mapped from `SystemLanguageModel.default.availability`.
  - `@objc public func format(transcript: String, systemPrompt: String, completion: @escaping (String?, Int, String?) -> Void)` — on success, `(text, 0, nil)`; on guardrail content rejection, `(nil, 1, reason)`; on any other failure, `(nil, 2, errorDescription)`. Internally constructs a `LanguageModelSession` and calls it with structured concurrency, bridging to the completion-handler shape the Kotlin side expects (Kotlin wraps this in `suspendCancellableCoroutine`, per stack research's recommended pattern).
- Content-safety guardrail rejections are distinguished from other failures at the Swift
  layer (code `1` above) — this is the concrete hook Story 5.5 needs to implement the new
  `LlmResult.Failure.ContentRejected` case (pitfalls §2.2).

**Files**: new Swift files under `iosApp/FoundationModelsShim/` (or equivalent, matching Story 5.1's project structure)

##### Task 5.3a: Create the `FoundationModelsShim` target with `checkAvailability` and `format` methods per spec, building on the Story 5.2 smoke-test target's structure.

##### Task 5.3b: Xcode project wiring — add the shim target to the scheme; confirm it emits an Objective-C-compatible generated header (`-Swift.h`) that cinterop can consume.

##### Task 5.3c: **Manual verification only** (documented limitation, not a task failure): on a Mac with macOS 26+, Apple Intelligence enabled, and Apple-Intelligence-eligible hardware, exercise `checkAvailability`/`format` from a throwaway Swift host app or Xcode Playground to confirm the shim compiles and returns expected values for at least the `available` and one `unavailable` case. Record the verification method used in a code comment — the corrected CI framing above (Gradle #17559 + K2/CMP klib incompatibility) is why no CI lane can repeat it, not "CI is Linux-only."

---

### Story 5.4: cinterop `.def` + Kotlin/Native binding for the real shim

**As a** developer, **I want** a `.def` file pointing at the real shim's generated header,
wired into `iosMain`'s Kotlin/Native cinterop config, **so that** Kotlin code can call the
Swift shim directly. This is now genuinely new cinterop-authoring work for this codebase
(confirmed, not merely hedged as in the original ADR-013 draft) — `.def` authoring against
a hand-written shim is a materially different, first-time exercise compared to
`IosCredentialStore.kt`'s direct binding against Apple's `Security` system framework
headers.

**Acceptance Criteria**:
- New `.def` file (e.g. `kmp/src/nativeInterop/cinterop/FoundationModelsShim.def`)
  pointing at the real shim module's generated Obj-C header — extends the Story 5.2
  smoke-test `.def`'s authoring pattern (now proven) rather than starting from zero.
- `kmp/build.gradle.kts` iOS target config gains (or extends) a `cinterop` block
  referencing the new `.def` file, scoped to `iosMain`/`iosArm64`/`iosSimulatorArm64`
  targets consistent with how other iOS-only native dependencies are declared in this
  build file.
- Build verification: the Gradle iOS compile target succeeds and generates Kotlin bindings
  for `FoundationModelsShim`'s two methods with recognizable Kotlin signatures (visible via
  IDE completion or `gradle :kmp:compileKotlinIosArm64 --dry-run`-style inspection).

**Files**: `kmp/src/nativeInterop/cinterop/FoundationModelsShim.def` (new), `kmp/build.gradle.kts` (modify)

##### Task 5.4a: Author the real `.def` file, extending the Story 5.2 smoke-test `.def`'s pattern.

##### Task 5.4b: Add/extend the `cinterop` block in `build.gradle.kts`'s iOS target config.

##### Task 5.4c: Build verification per acceptance criteria — this is a real gate, not optional, since a broken cinterop binding fails silently as "iOS build doesn't compile" rather than a clear test failure; run the actual iOS compile target before marking this story done.

---

### Story 5.5: `iosMain` `LlmProvider` implementation

**As a** developer, **I want** the cinterop bindings wrapped in a Kotlin
`LlmFormatterProvider`/`LlmProvider`, **so that** the registry can discover and use iOS
on-device inference exactly like every other provider.

**Acceptance Criteria**:
- `LlmResult.Failure` (shared sealed interface, `voice/LlmFormatterProvider.kt`) gains a
  second new case (alongside Epic 4's `OnDeviceUnavailable`, ideally added in the same PR
  if these epics run close together — otherwise this story adds it on top):
  `data class ContentRejected(val reason: String) : Failure` — distinct from
  `OnDeviceUnavailable` because a guardrail rejection is not "try again later," it's "this
  specific input was refused." Update `LlmTagProvider`'s exhaustive `when` again (same
  grep-and-fix task shape as Epic 4 Story 4.1a) if not already covered.
- `IosOnDeviceLlmFormatterProvider : LlmFormatterProvider` — `format()` wraps the shim's
  completion-handler `format(transcript:systemPrompt:completion:)` in
  `suspendCancellableCoroutine`, mapping the shim's `(text, code, detail)` triple to
  `LlmResult.Success` / `Failure.ContentRejected` / `Failure.ApiError` per the codes defined
  in Story 5.3.
- `IosOnDeviceLlmProvider : LlmProvider` — `id = "ios-ondevice"`, `displayName = "On-device (Apple Intelligence)"`, `kind = ON_DEVICE`, `supportsLongFormOutput = false` (Apple's ~3B on-device model has the same "not eligible for every feature" carve-out as Android's per pitfalls §2.2), `checkAvailability()` wraps the shim's `checkAvailability` completion-handler call in `suspendCancellableCoroutine`, mapping codes `0..4` to `Available` / `Unavailable("This device doesn't support on-device AI", retryable=false)` / `Unavailable("Turn on Apple Intelligence in Settings to use on-device AI", retryable=false)` / `Preparing("The on-device model is still downloading")` / `Unavailable(detail ?: "Unknown", retryable=true)` respectively — **evaluated fresh every call, never cached across app sessions** (region eligibility is a live, moving target per pitfalls §2.2, not a one-time check).
- `actual fun platformOnDeviceLlmProvider(): LlmProvider? = IosOnDeviceLlmProvider(...)` replaces the Epic 1 `iosMain` placeholder — always non-null (unlike Android, there's no "SDK failed to init" case here; unavailability is expressed through `checkAvailability()`, not through returning `null`).

**Files**: `kmp/src/iosMain/kotlin/dev/stapler/stelekit/voice/IosOnDeviceLlmFormatterProvider.kt` (new), `kmp/src/iosMain/kotlin/dev/stapler/stelekit/llm/IosOnDeviceLlmProvider.kt` (new), `kmp/src/iosMain/kotlin/dev/stapler/stelekit/llm/PlatformOnDeviceLlmProvider.kt` (modify), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/LlmFormatterProvider.kt` (modify — add `ContentRejected`)

##### Task 5.5a: Add `ContentRejected` to `LlmResult.Failure`; fix resulting exhaustive-`when` compile breaks.

##### Task 5.5b: Implement `IosOnDeviceLlmFormatterProvider.kt` (the `suspendCancellableCoroutine` wrapper).

##### Task 5.5c: Implement `IosOnDeviceLlmProvider.kt` and update the `actual` per spec.

##### Task 5.5d: Extract the shim-code-to-`LlmProviderAvailability` mapping (codes `0..4` → the four cases) into a **pure function taking an `Int` and `String?`**, independent of the actual cinterop call — this is the unit-testable seam pitfalls §3.3 calls for. `businessTest` — `IosAvailabilityMappingTest`: table-driven over all 5 codes.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/IosAvailabilityMappingTest.kt` (new — must be pure Kotlin, zero iOS/cinterop dependency, so it can run in `businessTest`/JVM despite testing iOS-provider logic)

##### Task 5.5e: **Manual verification only**, same caveat as Story 5.1c/5.2c/5.3c: exercise the full Kotlin→cinterop→Swift shim→`FoundationModels` path end-to-end on physical/simulator hardware a contributor owns. Document the verification steps taken; do not claim automated coverage that doesn't exist.

---

## Epic 6: Settings UI

**Goal**: A single provider-hub Settings surface (per features research: Obsidian AI
Providers' "provider list + add/edit form + fetch-models-as-validation" pattern, not
per-feature config screens), plus per-feature provider pickers.

### Story 6.1: `AppState` + `StelekitViewModel` plumbing

**As a** developer, **I want** visibility state and open/dismiss methods for the new
settings surface, mirroring the existing `gitSetupVisible`/`openGitSetup()`/
`dismissGitSetup()` pattern, **so that** no new state-management concept is introduced.

**Acceptance Criteria**:
- `AppState` gains `val llmProviderSettingsVisible: Boolean = false`.
- `StelekitViewModel` gains `fun openLlmProviderSettings()` / `fun dismissLlmProviderSettings()`, structurally identical to `openGitSetup()`/`dismissGitSetup()` (StelekitViewModel.kt ~L232-249).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (modify), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (modify)

##### Task 6.1a: Add the field + two methods per spec.

---

### Story 6.2: Provider list screen

**As a** user, **I want** a flat list of my configured provider instances with live
connection/eligibility status, **so that** I can see at a glance what's usable
(features research §1's synthesized design, level 1).

**Acceptance Criteria**:
- `LlmProviderListScreen(registry: LlmProviderRegistry, onAddProvider: () -> Unit, onEditProvider: (String) -> Unit, onDismiss: () -> Unit)` composable.
- Renders `registry.all()` (static, no suspend) as the row list immediately, then
  asynchronously resolves each row's `checkAvailability()` and updates that row's status
  dot/text in place — **never an optimistic "Available" default while the check is
  pending** (hard rule from Android's own docs per features research §2: show "Checking
  availability…" first).
- Each row: display name, provider-type icon/label, status (`Available` → green dot +
  "Connected"; `Preparing` → amber + detail text; `Unavailable` → red + reason text, plus a
  retry affordance only when `retryable = true`).
- "Add provider" button (custom OpenAI-compatible instances only — built-in providers
  Anthropic/OpenAI/Gemini/on-device are edited, not added/removed as new instances).
- Tapping a row opens the edit form (Story 6.3).
- **Web-target constraint (per Epic 3 Task 3.3d's finding)**: if remote-provider calls
  are confirmed CORS-blocked from the browser, this screen hides/disables remote-provider
  ("Add provider" for custom endpoints, and editing Anthropic/OpenAI/Gemini) on the
  `wasmJs` target with a clear inline message ("Remote providers aren't available in the
  browser due to CORS — use the desktop or mobile app"), rather than letting a web user
  configure a provider that will never actually work. This bullet is a placeholder for
  whatever Task 3.3d's actual finding was — implement the hide/disable only if that task
  confirmed CORS blocking; skip it if remote providers turned out to work from the
  browser.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/LlmProviderListScreen.kt` (new)

##### Task 6.2a: Implement the composable per spec, using `produceState`/`LaunchedEffect` per row for the async `checkAvailability()` poll (not a single blocking suspend before first render).

---

### Story 6.3: Add/Edit provider form

**As a** user, **I want** a provider-type-aware form where the "fetch available models"
action both populates the model picker and validates connectivity, **so that** I don't save
a broken configuration silently (resolves requirements Open Question 2).

**Acceptance Criteria**:
- `AddEditLlmProviderDialog(existingConfig: CustomProviderConfig?, onSave: (CustomProviderConfig, apiKey: String?) -> Unit, onCancel: () -> Unit)`.
- Provider-type dropdown for new instances (Anthropic/OpenAI/Gemini/on-device are singleton
  built-ins with no "type" picker — this dialog is specifically for the custom
  OpenAI-compatible provider type, per the built-vs-custom split in Story 6.2). Fields:
  display name, base URL (pre-filled with quick-pick presets for Ollama
  `http://localhost:11434/v1` and LM Studio `http://localhost:1234/v1` per pitfalls §4's
  explicit UX recommendation), API key (optional, password-masked), model (populated via
  "Fetch models" button calling `CustomOpenAiCompatibleLlmProvider.fetchAvailableModels()`
  from Story 3.3 — success populates a dropdown and shows a green check; failure shows the
  error inline, does not block manual model-name entry as a fallback).
- Save writes the `CustomProviderConfig` via `LlmSettings.setCustomProviderConfig()` and
  the API key (if any) via `LlmCredentialStore.setApiKey()`.
- HTTP-vs-HTTPS/loopback constraint from Story 3.2 is surfaced as inline validation, not a
  silent `require()` crash — attempting to save a non-loopback `http://` URL shows an error
  message explaining why, before `CustomOpenAiCompatibleLlmProvider` is even constructed.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/AddEditLlmProviderDialog.kt` (new)

##### Task 6.3a: Implement the dialog per spec, including the "Fetch models" action and inline URL validation.

---

### Story 6.4: Per-feature provider picker

**As a** user, **I want** to pick which provider each feature (tagging, voice, synthesis)
uses, defaulting to "Auto," **so that** I get the Zed-style "one global-ish default + explicit
override" model from features research §1.

**Acceptance Criteria**:
- `PerFeatureProviderPicker(feature: LlmFeature, registry: LlmProviderRegistry, llmSettings: LlmSettings)` composable — dropdown of `registry.availableForFeature(feature)` (excluding short-form-only providers when `feature == GRAPH_EDIT_SYNTHESIS`, per Story 1.3's `excludeShortFormOnly` param) plus an "Auto" option at the top, wired to `llmSettings.getSelectedProviderId(feature)`/`setSelectedProviderId`.
- One instance rendered per `LlmFeature` value inside the main settings screen (not three separate screens).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/PerFeatureProviderPicker.kt` (new)

##### Task 6.4a: Implement the composable per spec.

---

### Story 6.5: Wire into `SettingsDialog`

**As a** developer, **I want** the new screens reachable from the existing settings
navigation, **so that** users can actually find this feature.

**Acceptance Criteria**:
- `SettingsCategory` enum gains `LLM_PROVIDERS("AI Providers", <icon>)`, following the exact
  pattern the `llm-provider` project used for `TAG_SUGGESTIONS` (conditionally shown only
  when the relevant settings object is non-null, if that convention still applies —
  otherwise always shown since this feature has no "disabled" precondition the way tag
  suggestion's `TagSettings` did).
- `SettingsDialog` composable gains the params needed to thread `registry`/`llmSettings`/
  `llmCredentialStore` down to `LlmProviderListScreen` + `PerFeatureProviderPicker`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt` (modify)

##### Task 6.5a: Add the enum entry + category content wiring per spec.

##### Task 6.5b: Wire `openLlmProviderSettings()`/`llmProviderSettingsVisible` (Story 6.1) into wherever `SettingsDialog` is invoked from `App.kt`/`MainLayout.kt`.

---

### Story 6.6: Retire `VoiceCaptureSettings.kt`'s redundant Anthropic/OpenAI key fields

**As a** developer, **I want** the existing Settings-screen composable's Anthropic/OpenAI
key entry fields removed now that Story 6.2/6.3's unified provider list + add/edit form
exist, **so that** there is exactly one place in the UI to configure these credentials,
not two. Verified against the actual file
(`ui/components/settings/VoiceCaptureSettings.kt`): it reads/writes the plaintext
`voiceSettings.getAnthropicKey()`/`getOpenAiKey()`/`setAnthropicKey()`/`setOpenAiKey()`
accessors directly (lines ~36-37 for the `remember`-backed field state, ~183-184 for the
save call) — these are exactly the accessors Epic 2's migration/deletion (Story 2.3/2.4)
targets, and this screen is the one UI-layer call site the review found with no home in
the original plan. Landing this fix here, in Epic 6, rather than relying on Epic 8 Story
8.4's grep-based "fix any stragglers found" catch-all, makes it an explicit, scoped
product decision (retire the fields, point users at the new screens) instead of an
incidental compile-fix.

**Acceptance Criteria**:
- Remove the `anthropicKey`/`openAiKey` `remember`-backed field state (~L36-37) and their
  save calls (`voiceSettings.setAnthropicKey(anthropicKey)` /
  `voiceSettings.setOpenAiKey(openAiKey)`, ~L183-184) from `VoiceCaptureSettings.kt`,
  along with their corresponding input fields in the composable's UI tree.
- `VoiceCaptureSettings.kt`'s other fields (`llmEnabled`, `useDeviceStt`, `useDeviceLlm`,
  `includeRawTranscript`, `whisperKey`, `transcriptPageWordThreshold`) are **untouched** —
  only the two redundant credential fields are removed; this is not a broader rewrite of
  the voice settings screen.
- If the screen has no remaining content-worthy fields related to credentials, add a short
  inline note/link ("Configure AI provider keys in Settings → AI Providers") pointing the
  user at the new `LlmProviderListScreen` (Story 6.2), so removing the fields doesn't leave
  a dead end.
- After this story lands, `App.kt`'s direct pass-through of `voiceSettings` into
  `VoiceCaptureSettings.kt` may need one fewer parameter if the removed fields were its
  only use of `getAnthropicKey`/`getOpenAiKey`/`setAnthropicKey`/`setOpenAiKey` — verify
  and simplify the call site if so.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/VoiceCaptureSettings.kt` (modify)

##### Task 6.6a: Remove the 4 call sites and their associated UI fields per spec above; add the redirect note if needed.

##### Task 6.6b: Update/add a screenshot or Compose UI test covering `VoiceCaptureSettings.kt` (grep for existing Roborazzi coverage of this screen first) to confirm it renders correctly without the removed fields; confirm no remaining reference to `voiceSettings.getAnthropicKey()`/`getOpenAiKey()`/`setAnthropicKey()`/`setOpenAiKey()` in this file. This is the explicit checkpoint Epic 2 Story 2.4 and Epic 8 Story 8.4 both depend on.

---

## Epic 7: Approval-gated edit workflow

**Goal**: Replicate the existing, shipped `SyncState.JournalMergeReady` →
`journalMergeReviewVisible` → `acceptJournalMerge()`/`abortJournalMerge()` pattern for
LLM-sourced proposals, with the race-guard re-validation that pattern already has. New
in-memory-only `LlmSuggestionInbox`. Accepted writes route through
`GraphWriter.savePage()`/`queueSave()`, never `DatabaseWriteActor` directly. Block-edit,
tag-change, and new-page-synthesis proposals all resolve to one `Page + List<Block>` shape
before hitting the write layer.

### Story 7.1: `PendingLlmSuggestion` domain model

**As a** developer, **I want** a sealed proposal type covering block edits, tag changes,
and new-page synthesis, with mandatory graph-scoping and a staleness snapshot, **so that**
the approval workflow can re-validate before writing (pitfalls §5.1's exact concern) and
never cross-apply a proposal to the wrong open graph (pitfalls §5.1's multi-graph concern).

**Acceptance Criteria**:

```kotlin
package dev.stapler.stelekit.llm

sealed interface PendingLlmSuggestion {
    val id: String                    // UUIDv7, generated at propose time
    val graphId: String                // mandatory — which GraphManager-scoped graph this targets
    val sourceProviderId: String
    val proposedAtEpochMs: Long
    val rationale: String?

    data class BlockEdit(
        override val id: String, override val graphId: String, override val sourceProviderId: String,
        override val proposedAtEpochMs: Long, override val rationale: String?,
        val pageUuid: String, val blockUuid: String,
        val currentContentSnapshot: String,  // content at propose time — staleness check compares this
        val proposedContent: String,
    ) : PendingLlmSuggestion

    data class TagChange(
        override val id: String, override val graphId: String, override val sourceProviderId: String,
        override val proposedAtEpochMs: Long, override val rationale: String?,
        val pageUuid: String, val blockUuid: String,
        val currentContentSnapshot: String,
        val addedTerms: List<String>, val removedTerms: List<String>,
    ) : PendingLlmSuggestion

    data class NewPage(
        override val id: String, override val graphId: String, override val sourceProviderId: String,
        override val proposedAtEpochMs: Long, override val rationale: String?,
        val proposedTitle: String, val proposedBlocks: List<ProposedBlock>,
    ) : PendingLlmSuggestion
}

data class ProposedBlock(val content: String, val depth: Int, val order: Int)
```

- `TagChange` is deliberately shaped so it can resolve to the same `BlockEdit`-style write
  (append/remove `[[wiki-links]]` on `currentContentSnapshot` → `proposedContent`) rather
  than needing its own write path — Story 7.4 collapses it into the `BlockEdit` write
  function rather than writing a third code path.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/PendingLlmSuggestion.kt` (new)

##### Task 7.1a: Implement `PendingLlmSuggestion.kt` and `ProposedBlock` per spec above.

##### Task 7.1b: `businessTest` — `PendingLlmSuggestionTest`: basic construction/equality sanity checks per variant (this is a data model, most value is in downstream tests, but confirm `id`/`graphId` are present on every variant via a `sealed interface` exhaustiveness test — a `when` over all three variants asserting each has non-blank `id`/`graphId`).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/PendingLlmSuggestionTest.kt` (new)

---

### Story 7.2: `LlmSuggestionInbox`

**As a** developer, **I want** an in-memory-only pending-suggestion store shaped exactly
like the existing `AppState.pendingConflicts: Map<String, PendingConflict>`, **so that** no
new SQLDelight table/migration/`@DirectSqlWrite` machinery is needed for a feature whose
worst-case restart failure mode is "re-run the proposal," not data loss (architecture
research's explicit reasoning — nothing is written to the graph until accept).

**Acceptance Criteria**:

```kotlin
package dev.stapler.stelekit.llm

class LlmSuggestionInbox {
    private val _pending = MutableStateFlow<Map<String, PendingLlmSuggestion>>(emptyMap())
    val pending: StateFlow<Map<String, PendingLlmSuggestion>> = _pending.asStateFlow()

    fun propose(suggestion: PendingLlmSuggestion) {
        _pending.update { it + (suggestion.id to suggestion) }
    }
    fun remove(id: String) {
        _pending.update { it - id }
    }
    /** Pending suggestions scoped to a single open graph — used by the review screen and multi-graph guard. */
    fun pendingForGraph(graphId: String): List<PendingLlmSuggestion> =
        _pending.value.values.filter { it.graphId == graphId }
}
```

- Plain class, no `CoroutineScope` of its own needed (pure `StateFlow` mutation, no async
  work inside).
- Session-scoped only — discarded on app exit, no persistence, per the resolved architecture
  decision (this is the "in-memory only" behavior the ADR documents).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInbox.kt` (new)

##### Task 7.2a: Implement `LlmSuggestionInbox.kt` per spec.

##### Task 7.2b: `businessTest` — `LlmSuggestionInboxTest`: propose adds to `pending`; remove removes; `pendingForGraph` filters correctly across suggestions from two different `graphId`s; proposing two suggestions with the same `id` overwrites (documents the intended semantics — `id` is the dedup key).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInboxTest.kt` (new)

---

### Story 7.3: `AppState`/`StelekitViewModel` wiring — visibility, accept/reject, staleness + multi-graph guards

**As a** developer, **I want** `StelekitViewModel` to observe the inbox and expose
`acceptLlmSuggestion(id)`/`rejectLlmSuggestion(id)`, re-validating staleness and graph scope
before writing, **so that** this workflow gets the exact same race-guard discipline
`acceptJournalMerge()`/`abortJournalMerge()` already have in production.

**Acceptance Criteria**:
- `AppState` gains `val llmSuggestionReviewVisible: Boolean = false`.
- `StelekitViewModel` observes `llmSuggestionInbox.pending` (new `scope.launch { ... collect
  ... }`, structurally parallel to `observeSyncState()`'s `syncState.collect` at L200-213)
  and sets `llmSuggestionReviewVisible = true` when the map becomes non-empty for the
  currently active graph (`pendingForGraph(currentGraphId)`.isNotEmpty()`); does **not**
  auto-dismiss when it becomes empty via accept/reject (those explicitly set visibility —
  same "do NOT auto-dismiss" comment pattern as `observeSyncState()`'s
  `JournalMergeReady` handling).
- `fun rejectLlmSuggestion(id: String)`: `llmSuggestionInbox.remove(id)` — pure, cannot fail,
  no confirmation dialog required at the call site (per features research §3's explicit
  "reject should be a single tap, no are-you-sure" recommendation — Story 7.5 owns not
  showing one).
- `fun acceptLlmSuggestion(id: String)`:
  1. Re-validate: `val suggestion = llmSuggestionInbox.pending.value[id] ?: return` (already
     resolved/expired — matches `abortJournalMerge`'s "state may have advanced" guard
     shape).
  2. Multi-graph guard: if `suggestion.graphId != currentGraphId`, do not apply — set an
     error/toast state ("Switch back to the graph this suggestion targets to review it")
     and return without removing it from the inbox (so it's still there if the user
     switches back).
  3. `llmSuggestionInbox.remove(id)` (optimistic dismiss from the queue).
  4. `scope.launch { materializeAndWrite(suggestion) }` — see Story 7.4 for
     `materializeAndWrite`'s staleness re-check + `GraphWriter` call.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (modify), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (modify)

##### Task 7.3a: Add `llmSuggestionReviewVisible` to `AppState`.

##### Task 7.3b: Add the `pending`-observing `scope.launch` block, `rejectLlmSuggestion`, and `acceptLlmSuggestion` (steps 1–3; step 4 delegates to Story 7.4's function) to `StelekitViewModel`.

---

### Story 7.4: Write path — staleness re-validation + `GraphWriter` materialization

**As a** developer, **I want** `acceptLlmSuggestion`'s write step to re-check the target
block/page hasn't changed since the proposal was generated, then resolve every proposal
variant to one `Page + List<Block>` shape before calling `GraphWriter`, **so that** a stale
accept never silently resurrects deleted content or corrupts a block that's since changed
(pitfalls §5.1's core concern), and the write path is the exact one every other edit in the
app already uses.

**Acceptance Criteria**:
- `suspend fun materializeAndWrite(suggestion: PendingLlmSuggestion)` (private, on
  `StelekitViewModel` or a small extracted `LlmSuggestionWriter` helper class — prefer
  extraction for testability, injected into `StelekitViewModel`):
  - **`BlockEdit`/`TagChange`**: point-lookup the current block via
    `blockRepository`/`pageRepository` (existing bounded point-lookup methods — never a
    full-page or full-graph read). If the block no longer exists → surface
    `DomainError.DatabaseError.NotFound` via the existing toast/error state, do not attempt
    a degraded apply (pitfalls §5.1's "deleted-page/block target" case). If it exists but
    its current content differs from `suggestion.currentContentSnapshot` → surface
    `DomainError.ConflictError.ConcurrentWrite` (reusing the existing case, per architecture
    research's "arguably reuses `DatabaseError.NotFound`/existing conflict cases" guidance)
    with a message distinguishing "this block changed since the suggestion was made" — do
    **not** blind-apply. If it matches → construct the updated `Page` + `List<Block>` (the
    target page's full current block list, with the one target block's content replaced by
    `proposedContent`, or `addedTerms`/`removedTerms` applied for `TagChange`) and call
    `graphWriter.savePage(page, blocks, graphPath)` — **not** `DatabaseWriteActor` directly,
    matching `CLAUDE.md`'s "BlockEditor → BlockStateManager → GraphWriter" convention and
    architecture research's explicit instruction.
  - **`NewPage`**: if referenced context pages were deleted since proposal (best-effort —
    only check pages the proposal explicitly references, not a full re-scan), reject with a
    clear reason rather than a degraded apply (pitfalls §5.1). Otherwise construct a new
    `Page` (title = `proposedTitle`) + `List<Block>` (from `proposedBlocks`, assigning fresh
    `BlockUuid`s) and call `graphWriter.savePage(page, blocks, graphPath)` — same call as
    the edit case, confirming architecture research's claim that new-page-creation and
    existing-page-edit are the same write shape.
  - **`GraphWriter.savePage`/`queueSave` are `suspend fun` returning `Unit`, not
    `Either`** (verified against current `GraphWriter.kt` — do not invent an `Either`
    wrapper it doesn't have). Wrap the call in the same try/catch-and-surface-to-toast
    convention every other `StelekitViewModel` call site already uses for `graphWriter`
    calls. **Citation correction (post-adversarial-review)**: the original citation
    ("grep an existing example, e.g. around L528-529's `writeActor.savePage(updatedPage)`
    call site") was wrong — verified L528-529 is inside `GraphWriter.kt` itself (saga
    step 4), a fire-and-forget `currentScope.launch { writeActor.savePage(updatedPage) }`
    with **no** error surfacing at all; copying that pattern would silently swallow
    write failures for LLM-suggestion writes, exactly the kind of error this workflow
    should surface, not eat. The actual snackbar/toast convention this task wants is
    `StelekitViewModel.sendSnackbar()` (~L1784, used today for the external-file-conflict
    flow at ~L1258) — match that call-and-surface style instead.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionWriter.kt` (new — extracted helper), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (modify — wires the extracted writer into `acceptLlmSuggestion`)

##### Task 7.4a: Implement `LlmSuggestionWriter.materializeAndWrite()` per spec, injected with `pageRepository`, `blockRepository`, `graphWriter`.

##### Task 7.4b: Wire `LlmSuggestionWriter` into `StelekitViewModel.acceptLlmSuggestion`'s step 4.

##### Task 7.4c: `businessTest` — `LlmSuggestionWriterTest` using `InMemoryPageRepository`/`InMemoryBlockRepository` + a fake/spy `GraphWriter`:
  - `BlockEdit` where target content matches snapshot → write called with correct updated content.
  - `BlockEdit` where target content has since changed → write NOT called, `ConcurrentWrite` surfaced.
  - `BlockEdit` where target block was deleted → write NOT called, `NotFound` surfaced.
  - `NewPage` → write called with a freshly-constructed `Page` + correct `Block` list, correct depth/order preserved from `ProposedBlock`.
  - `TagChange` → resolves to the same `savePage` call shape as `BlockEdit` (confirms the "collapses to one write path" design goal from Story 7.1).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSuggestionWriterTest.kt` (new)

---

### Story 7.5: Review UI

**As a** user, **I want** a one-at-a-time review queue with next/previous navigation,
type-specific rendering (diff for edits, full-page preview for new notes), and Apply/Discard
actions with no confirmation on discard, **so that** the approval gate stays meaningful
rather than becoming rubber-stamping (pitfalls §5.2's approval-fatigue research).

**Acceptance Criteria**:
- `LlmSuggestionReviewScreen(pending: List<PendingLlmSuggestion>, onAccept: (String) -> Unit, onReject: (String) -> Unit, onAcceptAll: () -> Unit, onRejectAll: () -> Unit, onDismiss: () -> Unit)` — parallel to `JournalMergeReviewScreen(onAccept, onReject, onDismiss)`.
- One-at-a-time: shows the current item (index state local to the screen) with a type badge
  (`Edit` / `Tag Change` / `New Note`), next/previous nav when `pending.size > 1`.
- `BlockEdit`/`TagChange`: renders `currentContentSnapshot` vs `proposedContent`/
  `addedTerms`/`removedTerms` as a block-level diff, using the same markdown-block rendering
  primitives the rest of the app uses (not a generic text diff) — reuse whatever component
  `PageView`/`BlockList` already use to render a single block's content, in a read-only
  before/after layout.
- `NewPage`: renders a full-page preview (title + block tree) exactly as it would appear if
  created — same rendering primitives, read-only.
- Buttons: `Apply`/`Create` (label depends on variant) and `Discard` — `Discard` fires
  immediately, **no confirmation dialog** (features research §3's Notion-precedent
  recommendation: rejection should feel non-destructive/reversible-feeling since nothing was
  ever written).
- Bulk `Discard all` / `Apply all` are present but only reachable after the user has
  scrolled/navigated past the first item at least once (pitfalls §5.2 — never the first
  action offered) — implement as: bulk buttons render disabled/hidden until
  `currentIndex > 0 || pending.size == 1`, or simpler: always visible but require a second
  confirmation tap specifically for `Apply all` (not `Discard all`, which stays
  single-tap per the no-confirmation-on-reject rule) since bulk-apply is the
  fatigue-risk direction, not bulk-discard.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreen.kt` (new)

##### Task 7.5a: Implement the screen per spec, including the diff and full-page-preview sub-composables (extract as `BlockDiffView.kt`/`NewPagePreview.kt` if they grow beyond a few dozen lines each).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/BlockDiffView.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/NewPagePreview.kt` (new)

##### Task 7.5b: Wire `LlmSuggestionReviewScreen` into `App.kt`/`MainLayout.kt`'s overlay stack, shown when `llmSuggestionReviewVisible`, sourcing `pending = llmSuggestionInbox.pendingForGraph(currentGraphId)`, mirroring exactly how `JournalMergeReviewScreen` is shown today when `journalMergeReviewVisible`.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (modify) or `MainLayout.kt` (modify, whichever currently hosts `JournalMergeReviewScreen`)

---

### Story 7.6: Bounded synthesis proposal generator + one trigger entry point

**As a** developer, **I want** at least one real producer feeding the inbox — a bounded,
graph-wide "suggest tag corrections" or "synthesize a note" generator — **so that** the
approval workflow built in Stories 7.1–7.5 is exercised end-to-end rather than being dead
machinery with no caller, and to prove out the bounded-read discipline `CLAUDE.md` mandates
before any future feature builds on this pattern (pitfalls §5.3's sharpest concrete risk in
the whole project).

**Acceptance Criteria**:
- `LlmSynthesisContextBuilder` — builds LLM context using **only** bounded/projected reads:
  `getPageNameEntries()` (name+journal-flag projection) for the vocabulary, chunked
  `getPagesByNames()` (≤500-chunk per `SQLITE_MAX_VARIABLE_NUMBER`) for any page content
  actually needed, **never** a full-block-content read across every page. Caps total
  characters assembled into the prompt (mirror `LlmTagProvider`'s `MAX_BLOCK_CHARS`/
  `MAX_VOCABULARY_SIZE` pattern — pick concrete caps appropriate to a synthesis prompt,
  e.g. ≤20 candidate pages, ≤500 chars content per page, ≤10,000 chars total).
  - **Candidate-selection heuristic (explicit, not an implementation-time judgment
    call)**: the ≤20 pages that get content-fetched are exactly the union of (a) pages
    that **link to** the current page, via `BlockSearchRepository.getLinkedReferences(
    pageName, limit, offset)` (already bounded/paginated — cap at 20 backlink results),
    and (b) pages the current page **itself links to**, resolved by extracting
    `[[wiki-link]]` page names already present in the current page's own (already-loaded)
    block content and passing that name list through `getPagesByNames()` (chunked
    `IN`-lookup, already bounded). Both are existing bounded primitives — **do not add a
    tag-similarity, full-text-relevance, or any other scan-based heuristic**; no bounded
    "shared tag" query exists in this codebase today, and inventing one is out of scope
    for this story. If the union exceeds 20, prefer backlinks over outbound links
    (backlinks better indicate what other content already treats this page as relevant),
    truncating outbound links first.
  - Task 7.6b's bounded-query-count test (below) must additionally assert that content is
    only fetched for pages reachable via this heuristic — not merely that the total query
    count stays low — so a future implementer substituting a scan-based heuristic that
    happens to still call `getPagesByNames()` would still fail the test.
- `LlmSynthesisService(provider: LlmProvider, contextBuilder: LlmSynthesisContextBuilder, inbox: LlmSuggestionInbox)`:
  - Builds a **free-text, strictly-parseable output contract** (per pitfalls §3.2 — do not
    depend on JSON-mode being available from every provider; design a line-based or
    delimited format recoverable with a tolerant parser, following
    `LlmTagProvider.parseResponse()`'s defensive-parsing precedent).
  - Excludes providers with `supportsLongFormOutput = false` from selection for this
    feature by construction (`registry.availableForFeature(GRAPH_EDIT_SYNTHESIS,
    excludeShortFormOnly = true)` from Story 1.3) — if the only available provider is
    short-form-only, surface "on-device models don't support synthesis — configure a remote
    provider for this feature" rather than attempting the call (pitfalls §2.1/§2.2's
    explicit recommendation).
  - Parses the response into `PendingLlmSuggestion` instances (start with `TagChange`
    proposals only for the first trigger — smallest, best-precedented output shape; `NewPage`
    generation can be a follow-up story if time allows, but is not required to close this
    story's acceptance criteria) and calls `inbox.propose()` per item.
  - **Batches related proposals** rather than flooding the queue (pitfalls §5.2 — cap total
    proposals per synthesis run, e.g. ≤10, and if the model returns more, keep only the
    highest-confidence subset) — reducing proposal volume at the generation source rather
    than relying on the review UI's bulk actions to absorb an unbounded queue.
- One UI trigger: an overflow-menu action (e.g. in `PageView` or a graph-level menu — reuse
  the existing overflow `DropdownMenu` pattern the `llm-provider` project already
  established for "Suggest tags for page") that invokes `LlmSynthesisService` for the
  current page's context and surfaces results via the inbox. This is intentionally narrow
  (one entry point, one proposal type) — broader synthesis UX is out of scope for this plan.

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSynthesisContextBuilder.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSynthesisService.kt` (new), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt` (modify — add trigger)

##### Task 7.6a: Implement `LlmSynthesisContextBuilder.kt` per spec.

##### Task 7.6b: `businessTest` — `LlmSynthesisContextBuilderTest`, styled after `QueryPlanAuditTest`/`LargeGraphWarmStartCrashTest`: assert the builder issues **at most N bounded queries** (not O(graph)) against a large-graph fixture (reuse the existing synthetic large-graph fixture used by those tests if accessible), and never calls a full-page-content read for more than the capped page count. **Additionally** assert the selected candidate set matches the backlinks-plus-outbound-links heuristic exactly (fixture with known backlink/outbound-link structure → assert the fetched page set is exactly the expected union, capped and prioritized as specified) — this is what would catch a future implementer substituting an unbounded relevance scan that happens to still call bounded query methods.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSynthesisContextBuilderTest.kt` (new)

##### Task 7.6c: Implement `LlmSynthesisService.kt` per spec, including the free-text output contract + parser.

##### Task 7.6d: `jvmTest`/`businessTest` — `LlmSynthesisServiceTest`: fake `LlmProvider` returning representative free-text output → parses into expected `TagChange` proposals; malformed/partial output → tolerant parse (no crash, best-effort subset); proposal count capped at the configured max even if the model returns more; short-form-only provider selection → service refuses with a clear message instead of calling `format()`.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/LlmSynthesisServiceTest.kt` (new)

##### Task 7.6e: Wire the overflow-menu trigger into `PageView.kt`, calling `LlmSynthesisService` and letting the existing `llmSuggestionReviewVisible` observation (Story 7.3) pick up the resulting proposals automatically — no new visibility wiring needed here, just the call site.

---

## Epic 8: Migrate existing consumers

**Goal**: Migrate `LlmTagProvider`/`TagSuggestionEngine`, the voice formatting pipeline, and
`ClaudeTopicEnricher` onto the registry/settings from Epics 1–7, in that dependency order
(voice → tag suggestion → `ClaudeTopicEnricher` last, per pitfalls §6.3's explicit
sequencing recommendation — `ClaudeTopicEnricher` has the most divergent existing behavior
to reconcile). **This is the last epic — every other epic must be merged first.**

### Story 8.1: Voice formatting pipeline migration

**As a** developer, **I want** `VoicePipelineFactory`/`buildVoicePipeline` to read from
`LlmProviderRegistry` + `LlmSettings(VOICE_FORMATTING)` instead of ad hoc
`voiceSettings`-key + `deviceLlmProvider`-parameter threading, **so that** voice formatting
gains multi-provider support automatically, while preserving the existing
`VoiceSettings.getUseDeviceLlm()` boolean's *effective* behavior exactly (pitfalls §6.2's
explicit warning against silently reinterpreting this flag).

**Acceptance Criteria**:
- New selection logic in `VoicePipelineFactory`: `llmSettings.getSelectedProviderId(VOICE_FORMATTING)?.let { registry.find(it) } ?: registry.availableForFeature(VOICE_FORMATTING).let { candidates -> candidates.firstOrNull { it.kind == ON_DEVICE } ?: candidates.firstOrNull { it.kind == REMOTE } }` — i.e. explicit selection wins; "Auto" prefers on-device first (matches the existing `VoicePipelineFactory` precedence: `if (deviceLlmProvider != null && settings.getUseDeviceLlm()) deviceLlmProvider else <remote>`).
- **One-time behavioral-preservation step**: as part of the same migration pass that runs
  `LlmCredentialMigration` (Epic 2 Story 2.3), if `voiceSettings.getUseDeviceLlm() == true`
  at migration time, call `llmSettings.setSelectedProviderId(VOICE_FORMATTING,
  "android-ondevice")` (or `"ios-ondevice"` on iOS) so an existing user who had explicitly
  opted into on-device voice formatting keeps getting it after upgrade, without silently
  changing to "Auto" and potentially picking a different provider. If the flag was `false`
  (the default), leave `VOICE_FORMATTING`'s selection at `null` ("Auto") — matches today's
  effective default behavior (remote-preferred when a key exists, since `getUseDeviceLlm()`
  defaulted `false`).
- `VoiceSettings.getUseDeviceLlm()`/`setUseDeviceLlm()` become dead code after this
  migration runs — remove them in this same story (not deferred), since they're superseded
  by `LlmSettings` per-feature selection and keeping them risks the exact "two systems
  disagree" bug pitfalls §6.2 warns about.
- `VoiceCaptureViewModelTest`'s existing fakes continue to compile and pass unmodified
  (`LlmFormatterProvider` fun-interface shape is untouched — only the *selection* logic
  upstream of it changes).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoicePipelineFactory.kt` (modify), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt` (modify — remove `getUseDeviceLlm`/`setUseDeviceLlm`), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigration.kt` (modify — add the one-time flag-to-selection step)

##### Task 8.1a: Update `VoicePipelineFactory`'s selection logic per spec.

##### Task 8.1b: Add the one-time `getUseDeviceLlm()` → `LlmSettings` migration step to `LlmCredentialMigration` (extends Epic 2 Story 2.3's migration, same idempotency/verify-before-clear discipline).

##### Task 8.1c: Remove `VoiceSettings.getUseDeviceLlm()`/`setUseDeviceLlm()`; fix resulting call sites.

##### Task 8.1d: `businessTest` extension to `LlmCredentialMigrationTest` (or a new `VoiceDeviceLlmMigrationTest`): `getUseDeviceLlm()==true` before migration → `LlmSettings` selection for `VOICE_FORMATTING` is the platform on-device provider id after migration. `getUseDeviceLlm()==false` → selection stays `null` (Auto).

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/VoiceDeviceLlmMigrationTest.kt` (new)

##### Task 8.1e: Confirm `VoiceCaptureViewModelTest` passes unmodified (sequencing checkpoint per pitfalls §6.3 — run it explicitly, do not just assume).

---

### Story 8.2: Tag suggestion migration (closes the requirements' headline success metric)

**As a** developer, **I want** `App.kt`'s `buildLlmFormatterForTags` replaced with a
registry lookup that falls back through `availableForFeature(TAG_SUGGESTION)`, **so that**
an Android user with zero API keys configured gets on-device LLM-tier tag suggestions for
the first time — while respecting the existing-vs-fresh-install default-behavior-change
guard pitfalls §6.2 explicitly calls out as a risk, not a nice-to-have.

**Acceptance Criteria**:
- Delete `App.kt`'s private `buildLlmFormatterForTags(voiceSettings: VoiceSettings)`
  function entirely. Replace the `tagEngine` construction (`App.kt` ~L983-996) with:
  `llmSettings.getSelectedProviderId(TAG_SUGGESTION)?.let { registry.find(it) } ?: registry.availableForFeature(TAG_SUGGESTION).firstOrNull()`
  — Auto picks the first available provider from the registry in whatever order
  `buildLlmProviderRegistry` constructs it (on-device appended after remote per Epic 1
  Story 1.6 — first-available-wins in list order is the simplest correct policy here since
  there's no per-feature cost/latency ranking requirement in scope).
- **Must not change `LlmTagProvider`'s own contract** — `suggestTags(request):
  Either<DomainError, List<TagSuggestion>>` is unchanged; the fallback-selection logic
  lives entirely in the `App.kt`/registry composition layer, never inside `LlmTagProvider`
  itself (pitfalls §6.1's explicit constraint, preserving `TagSuggestionEngineTest`/
  `TagSuggestionViewModelTest`'s existing test surface).
- **Existing-install default-behavior guard**: add a `LlmSettings` flag
  `tagSuggestionOnDeviceIntroduced: Boolean` (or reuse the existing
  `llm.migration.voice_settings_migrated_v1`-style one-shot-flag convention) — on first run
  after this feature ships, if `platformSettings.containsKey("tags.llm_tier_enabled")`
  (`TagSettings.KEY_LLM_TIER_ENABLED`, using the `Settings.containsKey()` method added in
  Epic 1 Story 1.7 — this guard is unimplementable without it: `TagSettings`' typed getter
  `isLlmTierEnabled()` returns a default `true` when the key is absent, which is
  indistinguishable at the type level from "key present and happens to be `true`," so
  `containsKey()` is the only way to tell "existing install" from "fresh install")
  returns `true` pre-upgrade (i.e. this is an existing install, not a fresh one),
  explicitly set `llmSettings.setSelectedProviderId(TAG_SUGGESTION, "__disabled__")` (a
  sentinel value the picker/registry lookup treats as "explicitly off," distinct from
  `null`/"Auto") rather than defaulting to Auto, and surface a one-time in-app notice
  ("On-device tag suggestions are now available — enable in Settings") the user can act
  on. Fresh installs (`containsKey("tags.llm_tier_enabled") == false`) default to
  `null`/Auto, getting on-device suggestions immediately with no notice needed (there's no
  prior behavior to preserve).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (modify), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigration.kt` (modify — add the existing-vs-fresh-install guard as part of the same one-shot migration pass), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistry.kt` (modify — recognize the `"__disabled__"` sentinel, or thread an explicit "feature disabled" tri-state through `LlmSettings` instead of overloading the provider-id string — pick whichever is cleaner at implementation time and document the choice)

##### Task 8.2a: Replace `buildLlmFormatterForTags` call site in `App.kt` per spec.

##### Task 8.2b: Add the existing-vs-fresh-install guard to the migration pass, using `platformSettings.containsKey("tags.llm_tier_enabled")` (Epic 1 Story 1.7) — not `isLlmTierEnabled()`'s typed-default return, which cannot distinguish "absent" from "present and true."

##### Task 8.2c: businessTest extension — `TagSuggestionOnDeviceDefaultTest`: simulate pre-existing `tags.llm_tier_enabled` key present (via `containsKey`) → `TAG_SUGGESTION` selection defaults to explicitly-disabled, not Auto. No pre-existing key (fresh install, `containsKey` returns `false`) → defaults to Auto/`null`.

**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/llm/TagSuggestionOnDeviceDefaultTest.kt` (new)

##### Task 8.2d: Confirm `TagSuggestionEngineTest`/`TagSuggestionViewModelTest`/`LlmProviderSupportTest` pass unmodified (sequencing checkpoint).

---

### Story 8.3: `ClaudeTopicEnricher` migration (last — most divergent existing behavior)

**As a** developer, **I want** `ClaudeTopicEnricher` to delegate internally to the unified
`RemoteLlmProvider`-wrapped `ClaudeLlmFormatterProvider` instead of its own hand-rolled
HTTP client, **so that** the third, independent retry-on-429/circuit-breaker-less code path
pitfalls research found is deleted, while `TopicEnricher`'s external interface
(`enhance(rawText, localSuggestions): List<TopicSuggestion>`) — and every one of its
existing callers — stays untouched (architecture research's explicit "lower risk" call).

**Acceptance Criteria**:
- `ClaudeTopicEnricher(private val claudeProvider: LlmFormatterProvider) : TopicEnricher`
  — constructor now takes the shared `LlmFormatterProvider` (e.g. constructed via
  `ClaudeLlmFormatterProvider.withDefaults(apiKey)` at the call site, or better, resolved
  through the registry: `registry.find("anthropic")?.formatter`) instead of a raw `apiKey`
  + owning its own `HttpClient`.
  - `withDefaults(apiKey: String)` companion factory is preserved for callers not yet
    threading the registry through, but internally now constructs a
    `ClaudeLlmFormatterProvider.withDefaults(apiKey)` and delegates — no independent HTTP
    call, no independent retry logic.
- **Deleted, not preserved**: the hand-rolled `delay(2_000)`-then-retry-once-on-429 logic,
  the independent `MessagesRequest`/`MessagesResponse`/`Message`/`ContentBlock` DTOs (reuse
  whatever `ClaudeLlmFormatterProvider` already has, or keep enrichment-specific DTOs only
  for the *prompt content* — the candidate-JSON-array output contract — since that part is
  genuinely `TopicEnricher`-specific and not shared with tag suggestion or voice
  formatting).
- `enhance()`'s implementation now: builds the same prompt string it does today (candidate
  JSON list + document text, `MAX_INPUT_CHARS` truncation preserved exactly), calls
  `claudeProvider.format(transcript = "", systemPrompt = prompt)` (or restructure the
  `LlmFormatterProvider.format(transcript, systemPrompt)` call to fit the existing
  single-user-turn prompt shape — `ClaudeLlmFormatterProvider`'s `httpCallInternal` sends a
  fixed user turn "Output the formatted Logseq note." with the real content in
  `systemPrompt`; `ClaudeTopicEnricher` needs to either match that convention (put the full
  prompt in `systemPrompt`) or this exposes a shape mismatch worth flagging explicitly: if
  `ClaudeLlmFormatterProvider`'s hardcoded user-turn text is `voice`-specific messaging,
  `ClaudeTopicEnricher` reusing it verbatim would produce a confusing prompt — **flag this
  during implementation and adjust the shared provider's hardcoded user-turn string to
  something feature-neutral** (e.g. "Follow the instructions in the system prompt." )
  rather than the voice-specific "Output the formatted Logseq note." — this is a small but
  real compatibility gap between the existing narrow `LlmFormatterProvider` contract and a
  second, structurally different feature reusing it), parses the response same as today
  via `parseResponse()`, on non-2xx or parse failure falls back to `localSuggestions` (this
  fallback-to-local behavior is preserved — it's not one of the anti-patterns being deleted,
  it's `ClaudeTopicEnricher`'s legitimate degrade-gracefully behavior).
- Wire-compatibility preserved exactly: model `claude-haiku-4-5-20251001`, `max_tokens:
  256` for this call (confirm `ClaudeLlmFormatterProvider`'s `LlmProviderSupport.estimateMaxTokens`
  doesn't override this — if it does, `ClaudeTopicEnricher` needs its own max-tokens
  override path, since 256 is a load-bearing constant for this specific use case per
  pitfalls §6.2).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricher.kt` (rewrite), `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/ClaudeLlmFormatterProvider.kt` (modify — feature-neutral hardcoded user-turn string, if flagged per above)

##### Task 8.3a: Rewrite `ClaudeTopicEnricher.kt` to delegate to `LlmFormatterProvider` per spec, resolving the `max_tokens: 256` and hardcoded-user-turn-string issues flagged above.

##### Task 8.3b: Rewrite `ClaudeTopicEnricherTest` per pitfalls §6.1's explicit flag ("will be substantially rewritten, not preserved unchanged") — remove assertions on the deleted retry-on-429 behavior; add assertions that a transient failure now goes through the shared `CircuitBreaker` (e.g. after 3 consecutive failures, further calls short-circuit rather than retrying); keep the wire-compatibility assertions (model name, `max_tokens: 256`, prompt content shape) as regression tests.

**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricherTest.kt` (rewrite)

---

### Story 8.4: Final cleanup + sequencing checkpoint

**As a** developer, **I want** confirmation that every direct
`voiceSettings.getAnthropicKey()`/`getOpenAiKey()` call site is gone and the full existing
test suite still passes, **so that** Epic 2 Story 2.4 (deleting the plaintext
`VoiceSettings` accessors) can finally proceed.

**Acceptance Criteria**:
- Grep confirms zero remaining call sites of `voiceSettings.getAnthropicKey()` /
  `.getOpenAiKey()` / `.setAnthropicKey()` / `.setOpenAiKey()` outside
  `LlmCredentialMigration.kt` itself (which legitimately reads them once, at migration
  time, before they're cleared). **This explicitly includes confirming Epic 6 Story 6.6
  already retired `VoiceCaptureSettings.kt`'s 4 call sites** — that story is this one's
  known, named prerequisite, not a straggler this task discovers incidentally; if Story
  6.6 hasn't landed, this story is blocked, not "grep and fix inline."
- Full existing suite passes unchanged: `VoiceCaptureViewModelTest`,
  `TagSuggestionEngineTest`, `TagSuggestionViewModelTest`, `LlmProviderSupportTest`,
  `ClaudeLlmFormatterProviderTest`, `OpenAiLlmFormatterProviderTest` (the last two validate
  *implementation* behavior, not the interface, and should be genuinely unaffected by
  everything in Epics 1–8 except Story 3.2's additive changes, which have their own new
  assertions, not replacements of old ones).
- This story's completion is the trigger condition for Epic 2 Story 2.4.

**Files**: none (verification-only story)

##### Task 8.4a: Confirm Epic 6 Story 6.6 has landed (`VoiceCaptureSettings.kt`'s call sites already retired), then run the grep + full test suite (`bazel test //...` per `CLAUDE.md`), fix any remaining stragglers found elsewhere, and explicitly hand off to Epic 2 Story 2.4.

---

## Implementation Order Summary

| Epic | Stories | New files (approx.) | Key modified files |
|---|---|---|---|
| 1. Provider abstraction + registry | 1.1–1.8 | `llm/LlmProviderAvailability.kt`, `LlmProviderKind.kt`, `LlmProvider.kt`, `RemoteLlmProvider.kt`, `LlmProviderRegistry.kt`, `LlmFeature.kt`, `LlmSettings.kt`, `PlatformOnDeviceLlmProvider.kt` (×5 source sets), `LlmProviderRegistryFactory.kt` + tests | `platform/Settings.kt` + 3 platform actuals (`containsKey`, Story 1.7), `kmp/build.gradle.kts` (`ktor-client-js`, Story 1.8) |
| 2. Credential store consolidation | 2.1–2.4 | `LlmCredentialStore.kt`, `LlmCredentialMigration.kt` + tests | `git/CredentialStore.kt`→`platform/security/` (move ×6, plus `storeBlocking()` addition on `CredentialAccess`/`AndroidCredentialStore`, Task 2.1d), `voice/VoiceSettings.kt`, `ui/App.kt` |
| 3. New remote providers | 3.1–3.3 | `voice/GeminiLlmFormatterProvider.kt`, `llm/CustomOpenAiCompatibleLlmProvider.kt` + tests | `voice/OpenAiLlmFormatterProvider.kt`, `llm/LlmProviderRegistryFactory.kt` |
| 4. Android on-device fix + wiring | 4.1–4.3 | `llm/AndroidOnDeviceLlmProvider.kt` + tests | `androidMain/voice/MlKitLlmFormatterProvider.kt`, `voice/LlmFormatterProvider.kt`, `tags/LlmTagProvider.kt` |
| 5. iOS on-device provider | 5.1–5.5 | `iosApp/` Xcode project scaffold (Story 5.1), no-op smoke-test Swift shim + `.def` (Story 5.2), real Swift shim target, real `.def` file, `iosMain/voice/IosOnDeviceLlmFormatterProvider.kt`, `iosMain/llm/IosOnDeviceLlmProvider.kt` + tests | `kmp/build.gradle.kts`, `voice/LlmFormatterProvider.kt` |
| 6. Settings UI | 6.1–6.6 | `LlmProviderListScreen.kt`, `AddEditLlmProviderDialog.kt`, `PerFeatureProviderPicker.kt` | `ui/AppState.kt`, `ui/StelekitViewModel.kt`, `ui/components/settings/SettingsDialog.kt`, `ui/components/settings/VoiceCaptureSettings.kt` (Story 6.6) |
| 7. Approval-gated edit workflow | 7.1–7.6 | `llm/PendingLlmSuggestion.kt`, `LlmSuggestionInbox.kt`, `LlmSuggestionWriter.kt`, `LlmSynthesisContextBuilder.kt`, `LlmSynthesisService.kt`, `ui/screens/llm/LlmSuggestionReviewScreen.kt` + diff/preview components + tests | `ui/AppState.kt`, `ui/StelekitViewModel.kt`, `ui/screens/PageView.kt` |
| 8. Migrate existing consumers | 8.1–8.4 | `VoiceDeviceLlmMigrationTest.kt`, `TagSuggestionOnDeviceDefaultTest.kt` | `voice/VoicePipelineFactory.kt`, `voice/VoiceSettings.kt`, `ui/App.kt`, `domain/ClaudeTopicEnricher.kt`, `llm/LlmCredentialMigration.kt` |

**Total**: 8 epics, 39 stories, ~106 tasks (post-adversarial-review patch — up from 34
stories/87 tasks; the increase is concentrated in Epic 5's scaffolding expansion
[+6 tasks, +2 stories], Epic 1's new prerequisite stories 1.7/1.8 [+4 tasks, +2 stories],
Epic 2's synchronous-write fix [+2 tasks], Epic 3's web-target verification [+1 task],
and Epic 6's `VoiceCaptureSettings.kt` retirement [+2 tasks, +1 story] — not from
inflating existing task descriptions).

---

## Key Design Decisions Summary

1. **`LlmFormatterProvider` fun interface is never replaced**, only wrapped — every existing
   fake in `VoiceCaptureViewModelTest`/`LlmProviderSupportTest` keeps compiling through the
   whole plan.
2. **Tri-state availability everywhere** (`Available`/`Preparing`/`Unavailable(reason,
   retryable)`), never a boolean — this is the single concrete bug fix pitfalls research
   demanded of `MlKitLlmFormatterProvider`, generalized to every provider from day one.
3. **`LlmResult.Failure` grows two new cases** (`OnDeviceUnavailable` in Epic 4,
   `ContentRejected` in Epic 5) — both are shared sealed-interface changes requiring an
   exhaustive-`when` grep-and-fix pass; flagged explicitly in both epics rather than left
   implicit.
4. **Credential migration is one-shot, uses a synchronous durable write before clearing the
   plaintext source (`storeBlocking()`/`commit()`, not `apply()` — Story 2.1 Task 2.1d,
   Story 2.3), and never leaves a permanent dual-read path** — `VoiceSettings`' plaintext
   accessors are deleted (Epic 2 Story 2.4), not deprecated-and-kept, once Epic 8 **and**
   Epic 6 Story 6.6 (`VoiceCaptureSettings.kt`) confirm zero remaining call sites.
5. **In-memory-only suggestion inbox** — no SQLDelight table, no `MigrationRunner` entry, no
   `@DirectSqlWrite` surface added by this plan; nothing is written to the graph until
   accept, so a lost-on-restart pending suggestion is not a data-loss bug.
6. **Every accepted write goes through `GraphWriter.savePage()`/`queueSave()`**, never
   `DatabaseWriteActor` directly — confirmed against the actual current signatures
   (`suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String)`, returns
   `Unit`, not `Either` — the plan does not invent an `Either` wrapper `GraphWriter` doesn't
   have).
7. **Synthesis proposal generation is bounded by construction** — `LlmSynthesisContextBuilder`
   has its own regression test (Task 7.6b) asserting bounded query counts against a
   large-graph fixture, mirroring `QueryPlanAuditTest`/`LargeGraphWarmStartCrashTest`.
8. **On-device providers are excluded from the synthesis feature by a `supportsLongFormOutput`
   flag on `LlmProvider`**, not a hardcoded feature-specific check — set once per provider
   in Epics 4/5, consumed generically by `LlmProviderRegistry.availableForFeature()` in
   Epic 1/7.
9. **Existing-install default-behavior changes are guarded explicitly**, not left as an
   implicit side effect of shipping the new fallback logic — both the `voice.use_device_llm`
   flag (Story 8.1) and tag suggestion's new on-device default (Story 8.2) have dedicated
   one-shot migration steps and regression tests distinguishing fresh installs from
   upgrades, using the `Settings.containsKey()` method added in Epic 1 Story 1.7 (the
   `Settings` interface has no presence check otherwise — typed getters with defaults
   cannot distinguish "key absent" from "key present and equal to the default").
10. **Sequencing**: Epics 1+2 first (either order) → Epics 3/4/5/6/7 in parallel → Epic 8
    last, with voice → tag suggestion → `ClaudeTopicEnricher` order within Epic 8, and Epic
    2's final cleanup (Story 2.4) gated on Epic 8's completion.

---

## Flagged for ADR (written in parallel by a separate agent)

1. **Consolidate credential storage onto `CredentialStore`, remove `PlatformSettings`-backed
   secrets** — security-relevant, fixes a live plaintext-on-desktop/WASM gap, involves a
   no-fallback one-shot migration with real data-loss risk if done wrong (Epic 2).
2. **Flat `LlmProviderRegistry` over N simultaneously-available providers, not a
   `RepositoryFactory`-style single-active-backend enum** — a genuine divergence from the
   codebase's one established multi-implementation pattern (`GraphBackend`); worth recording
   why the existing pattern doesn't fit (Epic 1).
3. **`LlmSuggestionInbox` is in-memory only for v1, not a persisted SQLDelight table** —
   directly trades off against `CLAUDE.md`'s mandatory-migration-machinery for new tables;
   worth recording the reasoning (nothing written until accept, matches `PendingConflict`
   precedent) in case a future iteration needs to revisit it (Epic 7).
4. **Swift shim + Kotlin/Native cinterop for iOS Foundation Models, implemented in v1 (not
   deferred)** — the largest net-new engineering surface in the whole plan (revised
   estimate: 5 stories/~17 tasks, including first-ever Xcode project scaffolding and a
   first-ever hand-authored cinterop `.def`, per the adversarial review's B3 finding),
   with no CI lane exercising the real call path today (Gradle #17559 +
   K2/Compose-Multiplatform klib incompatibility, documented in `ci-ios.yml` — not
   "Linux-only CI," which was the original, incorrect framing); worth recording the
   decision to ship it now given the requirements' explicit "v1, not architected-for-later"
   resolution (Epic 5). **This ADR's own CI-framing text should be corrected to match** —
   it should not repeat the "this project's CI is Bazel/Gradle on Linux" claim, which is
   false (macOS `macos-latest` runners already exist in this repo's workflows).
5. **Generic OpenAI-compatible provider relaxes HTTPS-only for loopback hosts only; legacy
   Azure deployment-path auth is explicitly out of scope** — a security-boundary decision
   (which hosts may receive plaintext HTTP) bundled with a scope-boundary decision (Azure
   legacy needs its own provider type, not a variant of this one); both worth recording
   together since they were decided in the same research pass (Epic 3).
