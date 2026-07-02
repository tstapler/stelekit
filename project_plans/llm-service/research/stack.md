# Stack Research: Unified Multi-Provider LLM Abstraction

Scope: language/library/API choices for `project_plans/llm-service`. This supersedes
`project_plans/llm-provider/research/stack.md` in breadth (that pass only covered the
narrower tag-suggestion feature and Claude/OpenAI remote providers); this one covers the
full v1 provider set — Anthropic, generic OpenAI-compatible, Gemini, Android on-device,
iOS on-device, plus credential storage and wiring pattern for all of it.

## 0. Existing code inventory (do not re-invent)

| File | What it already does |
|---|---|
| `kmp/src/commonMain/.../voice/LlmFormatterProvider.kt` | `fun interface LlmFormatterProvider { suspend fun format(transcript, systemPrompt): LlmResult }`, sealed `LlmResult` (Success / Failure.ApiError / Failure.NetworkError), `NoOpLlmFormatterProvider`. This is the shape to **evolve**, not replace — it's a reasonable minimal contract but has no notion of provider identity, capability (on-device vs remote), or availability check. |
| `kmp/src/commonMain/.../voice/ClaudeLlmFormatterProvider.kt` | Full Anthropic Messages API client: Ktor `HttpClient` injected, `x-api-key`/`anthropic-version` headers, Arrow `CircuitBreaker` (3 failures → open, 30s reset, 2x backoff to 5min), `claude-haiku-4-5-20251001`. |
| `kmp/src/commonMain/.../voice/OpenAiLlmFormatterProvider.kt` | Generic OpenAI chat-completions client, **already parameterized by `baseUrl`** (defaults `https://api.openai.com`), enforces `require(baseUrl.startsWith("https://"))`, posts to `$baseUrl/v1/chat/completions`, `Authorization: Bearer` header. **This is already 90% of the "generic OpenAI-compatible custom endpoint" provider required in scope** — it just needs the HTTPS-only constraint relaxed for `http://localhost` (Ollama/LM Studio) and a model-name field added (currently hardcoded `gpt-4o-mini`). |
| `kmp/src/androidMain/.../voice/MlKitLlmFormatterProvider.kt` | Android on-device via `com.google.mlkit:genai-prompt:1.0.0-beta2` (Gemini Nano/AICore). `checkEligible()` checks `FeatureStatus` (AVAILABLE/DOWNLOADABLE/DOWNLOADING). Already implements `LlmFormatterProvider`. |
| `kmp/src/commonMain/.../voice/LlmProviderSupport.kt` | Shared `Json { ignoreUnknownKeys = true }`, `estimateMaxTokens`, `detectTruncation`, `mapHttpError` (401→"Invalid API key", 429→"Rate limit exceeded"). Reuse for all new providers. |
| `kmp/src/commonMain/.../domain/ClaudeTopicEnricher.kt` | A **second, independent** Claude client (own DTOs, own retry logic, no circuit breaker) used for topic/tag enrichment. Duplicates `ClaudeLlmFormatterProvider`'s HTTP plumbing. Per requirements, this should be migrated onto the unified abstraction, collapsing the duplication. |
| `kmp/src/commonMain/.../tags/LlmTagProvider.kt` | Consumes a single injected `LlmFormatterProvider` — has no provider-selection logic itself. The gap is entirely in `App.kt`'s `buildLlmFormatterForTags`. |
| `kmp/src/commonMain/.../ui/App.kt:1647` (`buildLlmFormatterForTags`) | Hand-checks `voiceSettings.getAnthropicKey()` then `getOpenAiKey()`, falls back to `null` — **never** checks Android on-device eligibility. This is the concrete bug requirements calls out (no on-device fallback for tagging). |
| `kmp/src/commonMain/.../voice/VoicePipelineFactory.kt` | The **only** existing call site with on-device-aware selection logic: `if (deviceLlmProvider != null && settings.getUseDeviceLlm()) deviceLlmProvider else <remote lookup>`. This precedence pattern (explicit on-device opt-in flag, then remote key fallback) is the closest existing analogue to what a provider registry should do generically. |
| `kmp/src/commonMain/.../voice/VoiceSettings.kt` | Plain `Settings`-backed (non-credential-store) key/value getters for Anthropic/OpenAI/Whisper keys plus `use_device_llm` flag. Per requirements this storage should be migrated into the new unified credential storage. |

## 1. HTTP client — Ktor, already fully wired for JVM/Android/iOS; WASM/JS needs one new dependency

**Ktor 3.1.3** is the established HTTP client (`kmp/build.gradle.kts`):

```
commonMain: io.ktor:ktor-client-core:3.1.3, ktor-client-content-negotiation:3.1.3, ktor-serialization-kotlinx-json:3.1.3
jvmMain/androidMain: io.ktor:ktor-client-okhttp:3.1.3   (OkHttp engine)
iosMain: io.ktor:ktor-client-darwin:3.1.3               (Darwin/NSURLSession engine)
jvmTest: io.ktor:ktor-client-mock:3.1.3
```

**Gap**: no Ktor engine is declared for `wasmJsMain`. `HttpClient { }` in `commonMain` resolves
its engine from whatever platform source set contributes one at link time — WASM/JS currently
has none, so any commonMain provider code (Claude/OpenAI/Gemini/generic clients) will fail to
link on the `wasmJs` target until an engine is added there.

Recommendation: add `io.ktor:ktor-client-js:3.1.3` (fetch-API-based; per Ktor's engine matrix
this engine now targets both `js` and `wasmJs`, published as `ktor-client-js-wasm-js` artifacts
on Maven Central) to the `wasmJsMain` dependency block. Confirm during planning whether the web
target is expected to make direct browser-side calls to Anthropic/OpenAI/Gemini at all — CORS
will block calls to `api.anthropic.com` from a browser origin unless Anthropic allows it (they
generally do not for direct browser calls), so the web/WASM story for **remote** providers may
need to degrade to "credentials configured but feature disabled" rather than a working HTTP
path. This is a planning-phase decision, not a stack question, but the engine gap is a stack
fact to record.

All engines (OkHttp, Darwin, JS/CIO) support the plain `HttpClient { install(ContentNegotiation) { json(...) } }` construction with no platform-specific code in `commonMain` — the existing `ClaudeLlmFormatterProvider.withDefaults()` / `OpenAiLlmFormatterProvider.withDefaults()` pattern (no explicit engine class referenced in commonMain) is correct and should be reused for Gemini and the generic-OpenAI-compatible providers.

## 2. Google Gemini API — raw Ktor REST calls, not a third-party KMP SDK

**No official Google KMP SDK exists.** Google's official Kotlin SDKs (`com.google.ai.client.generativeai`, Firebase AI Logic SDK) are Android/JVM-only. The one community KMP wrapper found —
[`PatilShreyas/generative-ai-kmp`](https://github.com/PatilShreyas/generative-ai-kmp) (`dev.shreyaspatil.generativeai:generativeai-google`) — does support Android/iOS/JVM/JS/Wasm, but:

- It's an unofficial community fork of `google/generative-ai-android`, not Google-maintained.
- Latest release `v0.9.0-1.1.0` is from April 2025 — over a year stale relative to Gemini API
  churn (model names, `v1beta` endpoint changes).
- It repackages Google's classes under `dev.shreyaspatil.*`, adding an extra layer of
  translation risk if Google changes the underlying response schema.

**Recommendation: raw Ktor REST calls**, matching the pattern already used for Claude and
OpenAI. This keeps the dependency surface, DTO ownership, and error-mapping behavior
consistent across all remote providers, and avoids taking on an externally-maintained
translation layer for a fast-moving API.

Endpoint (confirmed current, `v1beta`):
```
POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
Header: x-goog-api-key: <API_KEY>
```
This is a single-shot (non-streaming) endpoint — same request/response shape class as
`ClaudeLlmFormatterProvider`'s `/v1/messages` call, so the existing DTO/`LlmResult` mapping
pattern transfers directly. Model id to start with: `gemini-2.0-flash` (fast/cheap tier,
appropriate for the transcript-formatting/tagging use cases already in the codebase).

## 3. iOS on-device LLM — Apple Foundation Models framework requires a Swift shim; no direct Kotlin/Native path

**Framework**: `FoundationModels` (iOS 26 / iPadOS 26 / macOS 26+), giving Swift-native access
to Apple's on-device ~3B-parameter model — the same model backing Apple Intelligence features.

**Availability constraints** (hard gate, must be checked at runtime, not just OS version):
- OS: iOS 26 / iPadOS 26 / macOS 26 or later.
- Hardware: A17 Pro, M1, or newer chip with Neural Engine (rules out iPhone 14 and earlier,
  most iPads before the M-series/A17 generation).
- Apple Intelligence must be **enabled by the user** in Settings (separate from OS version —
  a supported device with AI disabled reports unavailable).
- The on-device model must have finished its background download (there's an intermediate
  "not yet downloaded" state, analogous to ML Kit's `DOWNLOADABLE`/`DOWNLOADING` on Android).
- Regional/language eligibility restrictions apply (Apple Intelligence rollout is gated by
  region and device language settings) — must be treated as a possible "unavailable" outcome
  the UI surfaces gracefully, mirroring `MlKitLlmFormatterProvider.checkEligible()`.

**No Kotlin/Native interop path exists.** `FoundationModels` is Swift-only:
- Kotlin/Native's Objective-C interop (`cinterop`) can only bind against Objective-C-visible
  APIs. Pure Swift types not annotated `@objc`/`@objcMembers` are invisible to Kotlin/Native.
- `FoundationModels`'s actual API surface (`LanguageModelSession`, the `@Generable` macro for
  structured output, Swift structured-concurrency `async`/`await` calls) is built on Swift
  macros and generics that have no Objective-C representation — they cannot be exposed via
  `@objc` even if someone tried; macro-generated conformances and generic session types don't
  bridge.

**Recommended architecture**: a small **Swift shim module**, not a direct interop binding:
1. New small Swift package/target (e.g. `iosApp/FoundationModelsShim`) exposing a minimal,
   Objective-C-compatible surface: a class with `@objc` methods like
   `func format(transcript: String, systemPrompt: String, completion: @escaping (String?, NSError?) -> Void)`
   (or a synchronous variant, given the existing `suspend fun format(...)` contract already
   crosses a suspend boundary in Kotlin — the shim can use a completion handler and let Kotlin
   wrap it in `suspendCancellableCoroutine`). This shim internally does the Swift-only
   `LanguageModelSession` calls, availability checks (`SystemLanguageModel.default.availability`),
   and converts results/errors to plain strings/`NSError` before crossing the bridge.
2. Build the shim as an Objective-C-header-emitting framework/module the Xcode project already
   links (the repo's iOS app target is Gradle-driven per `CLAUDE.md` — "Gradle is kept only for
   iOS"). Use a `.def` file + generated header + `cinterop` block in `iosMain`'s Kotlin/Native
   target, following the standard "Kotlin/Native as an Apple framework" bridging pattern (a
   `.def` pointing at the shim's Obj-C header, `cinterop` generates the Kotlin bindings).
3. `iosMain` implements `LlmFormatterProvider`/the new abstraction by calling the generated
   cinterop bindings — analogous to how `IosCredentialStore.kt` already calls Security-framework
   C APIs directly via `cinterop` (see `kmp/src/iosMain/.../git/IosCredentialStore.kt`), except
   here the C surface is hand-authored (the shim) rather than a system framework's existing
   Obj-C headers.

**Consequence for scope**: this is real net-new engineering (a Swift module + cinterop wiring),
not a drop-in like Gemini's REST client. It should be sized accordingly in planning — likely the
largest single unit of work in the provider set, comparable to but distinct from the Android
ML Kit integration (which required no cross-language bridge since ML Kit ships a Kotlin/Java
API directly).

## 4. Secure credential storage per platform — reuse `CredentialStore`, do not add multiplatform-settings

The project already has a working `expect/actual CredentialStore` (`kmp/src/commonMain/.../git/CredentialStore.kt`) used today for git credentials:

| Platform | File | Backend |
|---|---|---|
| Android | `androidMain/.../git/AndroidCredentialStore.kt` | `EncryptedSharedPreferences` (`androidx.security:security-crypto:1.1.0-alpha06`), AES-256-SIV/GCM keys backed by Android Keystore |
| iOS | `iosMain/.../git/IosCredentialStore.kt` | Keychain Services (`SecItemAdd`/`SecItemCopyMatching`/`SecItemDelete`) via direct `cinterop` against `Security` framework — no external library |
| JVM (Desktop) | `jvmMain/.../git/JvmCredentialStore.kt` | AES-256-GCM file at `~/.config/stelekit/credentials.enc`, key via PBKDF2-HMAC-SHA256 from machine identity + salt |
| WASM/JS | `wasmJsMain/.../git/CredentialStore.kt` | Stub (browser has no durable secure-storage primitive equivalent to Keystore/Keychain) |

This is a strictly better fit for provider API keys than `VoiceSettings`' current mechanism.
**Gap found**: `VoiceSettings` today stores Anthropic/OpenAI/Whisper keys via `PlatformSettings`
(a *separate* `expect/actual`, not `CredentialStore`), and on JVM `PlatformSettings` is backed
by a **plaintext** `java.util.Properties` file at `~/.stelekit/prefs.properties` — i.e. today's
voice-feature API keys are stored unencrypted on desktop. This matches the prior
`llm-provider` research pass's finding and is exactly what the requirements doc's "migrate
existing voice-settings LLM key storage into the new credential storage" line calls out fixing.

**Second gap found (new, not in the prior research pass)**: `PlatformSettings`
(`kmp/src/commonMain/.../platform/PlatformSettings.kt`) has **no iOS `actual` implementation** —
only Android (`platform/PlatformSettings.android.kt`), JVM (`jvmMain/.../platform/PlatformSettings.kt`), and WASM/JS
(`wasmJsMain/.../platform/PlatformSettings.kt`) exist. `CredentialStore` *does* have a full iOS
Keychain implementation already. This reinforces routing all provider credential/config storage
(API keys, custom base URLs, model names) through `CredentialStore` rather than `PlatformSettings`
— it sidesteps a pre-existing iOS gap rather than requiring a new `IosPlatformSettings` to be
built as a prerequisite.

**Recommendation**:
- Do **not** add `multiplatform-settings` (Touchlab) or any third-party encrypted-storage
  library (Kissme, KVault, KSafe) — the project already has hand-rolled `expect/actual`
  implementations per platform with the correct backend (Keystore/Keychain/AES-GCM file) and
  zero extra dependencies. Adding a library here would duplicate existing, working,
  already-audited code for no capability gain.
- Extend `CredentialStore`'s key namespace (or add a small typed wrapper analogous to
  `VoiceSettings`, e.g. `LlmProviderCredentialStore`) to hold: per-provider API key, and for
  the generic OpenAI-compatible provider, base URL + optional model name (non-secret
  config — could live alongside the key under a namespaced key, e.g.
  `llm.provider.<id>.api_key`, `llm.provider.<id>.base_url`, `llm.provider.<id>.model`).
  `CredentialStore`'s interface is a flat `store(key, value)`/`retrieve(key)`/`delete(key)`
  string map, so structured config either gets JSON-serialized into one value per provider or
  split into multiple flat keys — a plan-phase decision, not a stack question.
- JVM's `JvmCredentialStore` (AES-256-GCM file) is available today and should be the desktop
  backend for the new provider credentials — this closes the plaintext gap `VoiceSettings`
  currently has, as a side effect of migrating onto `CredentialStore`.

## 5. Generic OpenAI-compatible custom endpoint — target `/v1/chat/completions`, relax HTTPS-only, treat Azure's legacy deployment-path form as out of scope

The de facto interoperability contract across the v1 target vendor list is the **OpenAI Chat
Completions request/response schema**, posted to `{baseUrl}/v1/chat/completions` with
`Authorization: Bearer <key>`:

| Vendor | Endpoint form | Notes |
|---|---|---|
| Ollama | `http://localhost:11434/v1/chat/completions` | Documented "OpenAI compatibility" mode; no API key required (any placeholder string accepted) |
| LM Studio | `http://localhost:1234/v1/chat/completions` | Same schema; also no real key required |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` | Schema is OpenAI-compatible with minor response normalization across backing models; needs a real Bearer key |
| Azure OpenAI (v1 GA API, current) | `https://{resource}.openai.azure.com/openai/v1/chat/completions` | As of the v1 GA surface, Azure dropped the mandatory `?api-version=` query param and deployment-name path segment, aligning with the standard OpenAI path — a `baseUrl` of `https://{resource}.openai.azure.com/openai` reaches this cleanly |
| Azure OpenAI (legacy, pre-v1) | `https://{resource}.openai.azure.com/openai/deployments/{deployment}/chat/completions?api-version=2024-06-01` | **Not** compatible with a plain `{baseUrl}/v1/chat/completions` pattern — needs the deployment name in path and a query param, plus an `api-key` header instead of `Authorization: Bearer` |

**Recommendation**: target the modern, path-uniform contract (`{baseUrl}/v1/chat/completions`,
`Authorization: Bearer`) for the generic provider, matching what `OpenAiLlmFormatterProvider`
already does. This covers Ollama, LM Studio, OpenRouter, and current-generation Azure OpenAI
(v1 GA) with zero vendor-specific branching. Legacy Azure deployment-path API versions are out
of scope for the generic provider — if a user needs an old Azure resource that hasn't been
migrated to the v1 GA surface, that's a distinct "Azure legacy" provider type, not a variation
of the generic one; flag this as an open question for planning rather than silently
under-supporting it.

**Two changes needed to `OpenAiLlmFormatterProvider`** (not new code, an evolution of it):
1. `require(baseUrl.startsWith("https://"))` currently rejects `http://localhost:11434`
   (Ollama/LM Studio default). This must become configurable — HTTPS-required for the
   "OpenAI" and "Azure" presets, HTTP-allowed for a "custom endpoint" preset (with a UI warning
   about sending keys over plaintext HTTP for non-loopback hosts, which is a Settings-UI
   design question for planning).
2. The model name (`gpt-4o-mini`) is hardcoded as a `private const val`. The generic provider
   needs this as a constructor/config parameter, since Ollama/LM Studio users select arbitrary
   locally-installed model names.

**Validation of endpoint reachability before saving** (open question #2 in requirements):
no special protocol exists for this — the standard approach is a lightweight probe request
(e.g. `GET {baseUrl}/v1/models`, which OpenAI-compatible servers including Ollama and LM Studio
implement) fired from the Settings UI's "Test connection" action, reusing the same `HttpClient`
construction path as the real provider. This is a UI/UX flow decision for planning, not a new
library need.

## 6. Provider wiring pattern — no DI framework; continue the existing factory-function + plain-object style

The codebase has **no DI framework** (no Koin, Dagger/Hilt, Kodein). The two existing patterns:

1. **`RepositoryFactoryImpl`** (`kmp/src/commonMain/.../repository/RepositoryFactory.kt`): a
   plain class implementing a factory interface, with an internal `instances: MutableMap<String, Any>`
   used as a manual per-key singleton cache (`getOrCreateInstance`). A companion `object Repositories`
   exposes a process-wide singleton entry point (`Repositories.initialize(...)`, `Repositories.block()`, etc.) for callers that don't want to thread a factory instance through.
2. **`buildVoicePipeline(...)` / `buildLlmFormatterForTags(...)`**: plain top-level functions
   that take explicit dependencies (`VoiceSettings`, an optional platform-supplied
   `deviceLlmProvider: LlmFormatterProvider?`) and return a fully-wired object — no registry,
   no reflection, no annotation processing. Platform-specific providers (Android's
   `MlKitLlmFormatterProvider`) are constructed in platform code (`App.kt`'s Android-specific
   wiring, not shown here but implied by `deviceLlmProvider` being nullable/injected) and
   passed in as a parameter — i.e. **constructor/parameter injection from platform entry
   points into commonMain factory functions**, not a service locator that reaches out to
   platform code itself.

**Recommendation**: the new provider registry should follow pattern 2, extended slightly
toward pattern 1's per-key caching:
- A `commonMain` `LlmProviderRegistry` (plain class, not an `object`, so it can be scoped
  per-`GraphManager`/test-injectable like `RepositoryFactoryImpl` is) that takes constructor
  dependencies: `CredentialStore` (or the new credential wrapper), and an optional
  platform-supplied on-device provider list (mirroring `deviceLlmProvider` today) —
  since Android's ML Kit provider and iOS's Foundation Models shim are both
  platform-constructible-only (ML Kit needs no cinterop but does need Android context/no
  common constructor; the Foundation Models shim is iosMain-only by construction), platform
  `expect`/`actual` factory functions (`expect fun platformOnDeviceProvider(): LlmFormatterProvider?`) are the natural mechanism — consistent with how `GraphManager`/`DriverFactory` already use `expect`/`actual` for platform-specific construction elsewhere in the codebase.
- `LlmProviderRegistry.availableProviders(): List<LlmProviderDescriptor>` (id, display name,
  on-device vs remote, credential-configured vs not, eligibility-checked-if-on-device) replaces
  the `App.kt` hand-rolled `buildLlmFormatterForTags` key-sniffing, and both `VoicePipelineFactory`
  and `LlmTagProvider`'s call site converge on calling the registry instead of duplicating
  precedence logic.
- No new dependency required for this — it's an application of patterns already in the
  codebase, not a stack/library decision.

## Summary of library additions needed

| Addition | Where | Why |
|---|---|---|
| `io.ktor:ktor-client-js:3.1.3` (or `ktor-client-js-wasm-js` artifact) | `wasmJsMain` deps | No Ktor engine currently declared for WASM/JS — needed if any remote provider is expected to make HTTP calls from the web target (subject to CORS caveats noted above) |
| None for Gemini | — | Raw Ktor REST, following existing Claude/OpenAI pattern; no official or trustworthy third-party KMP SDK exists |
| None for credential storage | — | `CredentialStore` expect/actual already covers Android Keystore, iOS Keychain, JVM AES-GCM file |
| A new Swift shim module + cinterop `.def` | iOS app target (Xcode/Gradle-managed) + `iosMain` cinterop config | `FoundationModels` is Swift-only; no direct Kotlin/Native binding is possible |
| None for the generic OpenAI-compatible provider | — | Reuses/evolves `OpenAiLlmFormatterProvider` (relax HTTPS-only constraint, parameterize model name) |
