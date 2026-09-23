# llm-service — Pitfalls Research

Research date: 2026-07-01
Codebase snapshot: `stelekit-llm-service` branch

Scope: this document covers failure modes **new to the broader llm-service scope**
(multi-provider abstraction, unified credential storage, iOS on-device, the
approval-gated graph-edit workflow). It does not repeat
`project_plans/llm-provider/research/pitfalls.md`, which already covers local
tag-matching, Ktor/KMP HTTP plumbing, and the narrower Claude/OpenAI-only
tag-suggestion feature — that research is still valid and should be read
alongside this one. Where this document's findings sharpen or correct a claim
made there (credential storage in particular), it says so explicitly.

---

## 0. Ground truth from the current codebase (read before the rest of this doc)

These are verified facts about what exists today, not speculation — they change
the shape of the migration risk in section 6.

- **Two parallel, inconsistent secret-storage systems already exist.**
  - `CredentialStore` (`kmp/src/commonMain/.../git/CredentialStore.kt`, expect/actual)
    is used only for git credentials today. It is properly encrypted on every
    platform: Android (`EncryptedSharedPreferences`, AES-256-GCM), iOS (Keychain
    via `Security.framework`), JVM (`JvmCredentialStore` — AES-256-GCM with a
    PBKDF2-derived key, `~/.config/stelekit/credentials.enc`, salt file
    `chmod 600` where POSIX permissions are available).
  - `VoiceSettings` (`kmp/src/commonMain/.../voice/VoiceSettings.kt`) stores the
    Anthropic and OpenAI API keys (`KEY_ANTHROPIC`, `KEY_OPENAI`) through the
    generic `Settings`/`PlatformSettings` abstraction — the same store used for
    booleans like `voice.llm_enabled`. This is **not** the secure `CredentialStore`.
- **`PlatformSettings` is encrypted on Android only.**
  - Android (`PlatformSettings.android.kt`): `EncryptedSharedPreferences`, with a
    silent fallback to **plaintext** `SharedPreferences` if the Keystore throws
    (`GeneralSecurityException` etc.) — the fallback is logged but not surfaced to
    the caller, so a caller cannot distinguish "stored encrypted" from "stored
    plaintext because Keystore failed."
  - JVM Desktop (`PlatformSettings.kt` in `jvmMain`): plain `java.util.Properties`
    written unencrypted to `~/.stelekit/prefs.properties`. **No encryption, no file
    permission hardening.** Any Anthropic/OpenAI key a desktop user has already
    entered via Voice Settings is sitting in plaintext on disk today.
  - WASM/JS (`PlatformSettings.kt` in `wasmJsMain`): `kotlinx.browser.localStorage`
    — plaintext, readable by any script with page access (XSS-exposed), and
    persisted indefinitely with no eviction.
  - This means: **the credential-security constraint in requirements.md
    ("platform-appropriate secure storage... not new plaintext preferences") is
    already violated by the shipped desktop and web builds for the two existing
    remote LLM providers.** The new unified credential store is not just adding a
    feature — it is fixing a live security gap, and migration must decide whether
    to proactively re-encrypt/migrate the existing plaintext values or just stop
    writing new plaintext going forward (see §6).
- **ML Kit on-device provider exists but is completely unwired from tag
  suggestion.** `buildLlmFormatterForTags()` in `App.kt` only checks
  `voiceSettings.getAnthropicKey()` / `getOpenAiKey()`; `MlKitLlmFormatterProvider`
  has zero call sites outside the voice pipeline. This matches
  requirements.md's stated gap #2 exactly — confirmed, not just assumed.
- **`ClaudeTopicEnricher`** (`kmp/src/commonMain/.../domain/ClaudeTopicEnricher.kt`)
  is a *third*, independent Claude HTTP client — separate from
  `ClaudeLlmFormatterProvider` — with its own duplicated request/response models,
  its own ad-hoc one-shot retry-on-429 (`delay(2_000)` then retry once, no circuit
  breaker, no `Retry-After` header read), and silent swallow-to-fallback on any
  non-2xx response (`if (!response.status.isSuccess()) return localSuggestions`).
  It takes `apiKey: String` as a raw constructor argument with no documented
  source — grep its call site before assuming it already reads from
  `VoiceSettings`. This is the third divergent error-handling policy the unified
  abstraction needs to reconcile (see §6).
- **`checkEligible()` on `MlKitLlmFormatterProvider` treats `DOWNLOADABLE` and
  `DOWNLOADING` as "eligible: true"**, but `format()` treats those same two
  statuses as a *failure* ("try again in a few minutes"). A caller that only
  checks `checkEligible()` to decide whether to show the on-device option will
  present it as available, then get an immediate failure on first use if the
  model hasn't finished downloading. The registry/discovery mechanism this
  project adds must not collapse this into a single "available" boolean —it
  needs a tri-state (ready / needs-download / unsupported) that features can
  render correctly.

---

## 1. Credential security

### 1.1 Consolidating two storage systems is itself a migration hazard, not just a code change

The natural move is "unify onto `CredentialStore` since it's already correct
everywhere." Concrete risks in doing that:

- **Silent data loss if the migration only writes forward.** If v1 of the unified
  store reads exclusively from the new `CredentialStore`-backed location, a user
  upgrading with an existing `VoiceSettings`-stored Anthropic/OpenAI key loses
  their configured provider with no error — the app just behaves as if no
  provider is configured. `VoiceSettingsTest` and any test asserting "voice
  formatting works when a key is present" must be joined by a one-time-migration
  test that seeds the old location and asserts the new store surfaces the value
  post-migration.
  - Failure mode within failure mode: `CredentialStore.retrieve()` returning
    `null` (key never migrated) is indistinguishable at the type level from
    `null` meaning "user never configured this provider." The migration must
    run and complete (or explicitly record "already migrated" / "nothing to
    migrate") before any code path treats absence as "not configured."
- **Migrate-then-delete race.** If migration reads the old plaintext value, writes
  it to the new encrypted store, then deletes the plaintext original, a crash
  between write and delete either leaves both copies (acceptable, just re-run
  idempotently) or — if delete fires but write's `apply()` hadn't yet flushed
  (Android `SharedPreferences.apply()` is asynchronous) — loses the key entirely.
  Use `commit()` (synchronous) for the migration write specifically, or verify
  via `retrieve()` before deleting the source.
- **The Android Keystore fallback-to-plaintext path must not silently succeed for
  credential writes.** The existing `PlatformSettings.android.kt` catches
  `Exception` around `EncryptedSharedPreferences.create(...)` and falls back to
  plain `SharedPreferences` with only a `Log.w`. The prior pitfalls doc already
  flagged this in the abstract; concretely, for the new unified store, any write
  path reachable from provider credential entry must propagate that failure as a
  typed error (`Either<DomainError.CredentialError.SecureStorageUnavailable, Unit>`
  in this codebase's idiom) rather than degrading to plaintext. `CredentialStore`
  as currently implemented on Android does *not* have this fallback (it throws
  from the `by lazy` if Keystore init fails) — that's the correct behavior to
  preserve when unifying, not the `PlatformSettings` behavior.

### 1.2 Generic OpenAI-compatible provider adds new credential shapes

Existing storage is one string (API key) per provider. The new custom-endpoint
provider type (Ollama/LM Studio/Azure OpenAI/OpenRouter/generic) needs to store a
**tuple**: base URL, optional API key (Ollama typically has none — a placeholder
string is still required by most client libraries, see §4), optional model name,
and for Azure OpenAI specifically a deployment name *and* API version, which are
distinct concepts from "model name" in the other providers. A single
`CredentialStore.store(providerId, apiKey)` shape is insufficient; the schema
needs to be provider-typed from day one or every future field addition becomes a
migration. Do not store this as an ad-hoc JSON blob inside `Settings.putString` —
that reintroduces the plaintext-on-JVM/WASM gap from §0 for exactly the
credential this project is supposed to be securing.

### 1.3 Logging / crash-report leakage — net-new surface area vs. the prior pitfalls doc

The prior `llm-provider` pitfalls doc flagged this for Claude/OpenAI only. Two
providers unique to this broader scope add new leak vectors:

- **Generic OpenAI-compatible provider**: since the base URL is user-supplied,
  any error-logging path that includes the *request URL* (common in Ktor
  `HttpClient` logging, OkHttp interceptors, or a generic "failed to reach
  {url}" error string surfaced in the Settings UI) can leak a self-hosted
  endpoint's internal hostname/IP into logs shipped off-device (crash reporting,
  bug reports attached to GitHub issues). Less severe than key leakage but still
  an internal-network topology leak for self-hosted users.
- **On-device providers (ML Kit, Foundation Models) do not need network egress
  logging at all** — but if the unified abstraction logs "calling provider X with
  system prompt: ..." at a shared logging call site for observability, the prompt
  itself may contain the user's raw note content (voice transcript, block text).
  This is a content-privacy leak, not a credential leak, but it is new: the
  existing per-provider implementations only log lengths (`Log.d(TAG, "...
  (${transcript.length} chars input)")`), never content. Any shared/generic
  logging added at the abstraction layer must preserve that "log length/status,
  never content" discipline across all providers, including the new ones —
  it's easy to regress this when refactoring three call sites into one.

### 1.4 Key rotation / revocation UX

Not addressed anywhere in the current codebase (no provider has an "update key"
flow beyond overwrite-on-save). New pitfalls specific to a multi-provider
Settings UI:

- If a user rotates an Anthropic key in the Anthropic console (revoking the old
  one) and updates it in SteleKit's Settings, any **in-flight or cached**
  provider instance holding the old key (e.g., a `ClaudeLlmFormatterProvider`
  constructed once at app-startup and reused, as the current
  `buildLlmFormatterForTags()` pattern does) keeps using the stale key until the
  provider registry rebuilds it. The registry/discovery mechanism this project
  introduces needs to either be stateless-per-call (fetch credential fresh each
  time) or explicitly invalidate cached provider instances on credential update.
- No UX today confirms a key actually works before it's saved (see Open Question
  #2 in requirements.md) — for on-device providers this doesn't apply, but for
  every remote provider (5 of the 8 in v1 scope) a "Test connection" action
  before persisting the credential materially reduces the failure mode of "user
  saves a typo'd key, gets silent `NetworkError` on next use with no obvious
  cause."

---

## 2. On-device LLM pitfalls

### 2.1 Android ML Kit GenAI / AICore

Verified against current Google documentation and a reported GitHub issue
(googlesamples/mlkit#985), current as of mid-2026:

- **Foreground-only inference.** AICore permits GenAI API inference **only when
  the calling app is the top foreground application**; calling from a background
  context returns `ErrorCode.BACKGROUND_USE_BLOCKED`. This is a hard constraint
  on the approval-gated synthesis workflow (§5) if any part of graph-wide
  synthesis is designed to run as a background job (e.g., triggered by
  `WorkManager`, following the pattern the prior pitfalls doc recommends for the
  voice share-widget pipeline) — that pattern **cannot** use the on-device Android
  provider. A background-triggered synthesis job must either fall back to a
  remote provider or defer the on-device call until the app is foregrounded and
  the user is actively looking at the resulting proposal.
- **Per-app quota, `ErrorCode.BUSY`.** AICore enforces an inference quota per
  calling app; issuing GenAI API requests faster than the quota allows returns
  `BUSY` rather than queuing. Pixel devices can bypass the quota; non-Pixel OEM
  devices cannot. A graph-wide synthesis feature that fires many prompts in a
  tight loop (e.g., one on-device call per candidate page) will hit this on
  non-Pixel hardware — batch/rate-limit on-device calls the same way remote
  providers are already rate-limited, do not assume "no network = no rate limit."
- **Post-reset initialization window.** Immediately after a device reset or an
  AICore app data-clear, AICore may not have finished downloading its latest
  server config, and `checkStatus()`/`checkFeatureStatus()` can report incorrect
  eligibility during that window. There is no documented way to distinguish
  "genuinely unsupported" from "still initializing" other than retrying
  `checkStatus()` later — the Settings UI eligibility indicator (required by
  requirements.md) should poll/retry rather than caching a single negative result
  permanently for the session.
- **Unlocked bootloader devices are unsupported outright** — `checkStatus()` will
  correctly report unavailable, but this is worth documenting explicitly in the
  Settings UI copy ("on-device AI requires a locked bootloader") since it's a
  common state for developer/enthusiast devices that will otherwise look like an
  unexplained failure.
- **Known error-code opacity**: field reports (e.g., "Feature 636 is not
  available", `PREPARATION_ERROR` / `FEATURE_NOT_FOUND` on Pixel 6 Pro / Pixel 8
  Pro) show that even nominally-eligible devices intermittently fail with
  numeric error codes that map to no actionable user-facing message. The
  existing `MlKitLlmFormatterProvider.format()` already collapses all
  `Exception`s to a generic `"On-device LLM error: ${e.message}"` — acceptable
  for now, but the unified abstraction's error taxonomy needs an
  `OnDeviceUnavailable` case distinct from `NetworkError`/`ApiError` so the UI
  doesn't tell an on-device user to "check your internet connection."
- **Beta API stability**: `com.google.mlkit:genai-prompt` is still `1.0.0-beta2`
  as of this research date (not GA). Google's own guidance for the *preview*
  release channel is to fall back to `ModelReleaseStage.STABLE` if
  `checkStatus()` for a preview config returns unavailable — but the Prompt API
  itself (not just a model release stage) is beta, meaning binary-incompatible
  method signature changes remain possible between beta releases. Pin the exact
  version and add a build-breakage smoke test (already implicit via
  `androidTest`/`assembleDebug` in `ciCheck`) rather than a floating version
  range.
- **Output cap confirmed by existing code comment**: "Output hard-capped at 256
  tokens by the on-device model." This directly collides with the graph-edit /
  synthesis workflow in scope for this project — a synthesized note or
  multi-block edit proposal will very likely exceed 256 tokens. The unified
  abstraction cannot assume a uniform max-output-tokens contract across
  providers; any feature that needs long-form output (synthesis) must either
  chunk the request into multiple on-device calls and stitch results, or
  explicitly exclude the on-device Android provider from that feature's
  available-provider list and communicate why in the Settings UI ("on-device
  models don't support synthesis — showing remote providers only for this
  feature").
- **Storage**: the Gemini Nano model itself (~1GB) is shared across apps via
  AICore, not duplicated per-app, and downloads in the background — so SteleKit
  does not need to bundle or manage model storage directly, but the download can
  take 15–30 minutes on Wi-Fi, during which `checkStatus()` returns
  `DOWNLOADABLE`/`DOWNLOADING` (see §0's `checkEligible()`/`format()` inconsistency
  above — this is exactly the window that inconsistency falls into).

### 2.2 iOS Foundation Models / Apple Intelligence

- **Region gating is a live, moving target, not a one-time check.** Apple
  Intelligence features (and by extension on-device Foundation Models
  availability, since the framework requires "Apple Intelligence-enabled
  device... in supported regions") have been repeatedly delayed/restricted in
  the EU under the Digital Markets Act — most recently reported as an ongoing
  Siri-AI-specific delay for iOS 27/iPadOS 27 with "no timeline" as of mid-2026.
  Do not hardcode a region allowlist/denylist at build time; the availability
  check must be a runtime API query (`SystemLanguageModel.availability`, per
  Apple's framework) evaluated fresh each session, not cached across app
  versions — a user's region-eligibility can change between SteleKit releases
  without a SteleKit code change.
- **Guardrails can reject the SteleKit-authored prompt itself, not just user
  content.** Foundation Models applies a default content-safety guardrail to
  both input and output. Because SteleKit constructs the system prompt
  programmatically (tag vocabulary injection, block content injection,
  synthesis instructions spanning multiple pages), there is a real risk that a
  legitimate prompt is refused by the guardrail for reasons opaque to both
  SteleKit and the user (e.g., a block of user notes discussing a sensitive
  medical or legal topic, or a large pasted vocabulary list that pattern-matches
  something the guardrail flags). Unlike a remote provider's 4xx, a guardrail
  rejection is not a "your API key is wrong" or "rate limited" class of error —
  it needs its own `LlmResult.Failure.ContentRejected` (or similar) case so the
  UI can say "this couldn't be processed on-device" without implying a
  configuration problem, and ideally offer "try a remote provider instead" as
  the recovery path (which the unified provider-selection UI naturally
  supports, if wired up).
- **Simulator testing is contingent on host macOS version**, not just Xcode/iOS
  simulator version: the simulator uses the *same* on-device models shipped with
  the host Mac's macOS, and requires macOS 26+ with Apple Intelligence enabled
  on the host — a CI runner or contributor machine on an older macOS, or a Mac
  without Apple Intelligence hardware eligibility (M1 or later required), cannot
  exercise this path in simulator at all. This project's CI is Bazel/Gradle on
  Linux for JVM/Android; **no CI lane can currently test the iOS on-device
  provider at all** — it can only be manually verified on physical/simulator
  hardware a contributor owns. This is a testing-strategy gap, not just a
  pitfall to code around (see §3 and §6).
- **~3B parameter model — capability ceiling below server-class remote models**,
  same category of constraint as Gemini Nano's 256-token cap but not identically
  shaped: Apple's on-device model is tuned for summarization, extraction, short
  dialog, and refinement rather than open-ended synthesis, and Apple explicitly
  positions it as not comparable to frontier server models. Treat it the same
  way as the Android on-device cap in §2.1 for purposes of the
  synthesis/graph-edit feature — assume it needs the same "not eligible for
  every feature" carve-out in the provider registry.

---

## 3. Multi-provider abstraction pitfalls

### 3.1 Inconsistent error semantics are already present across only *two* providers — the problem gets worse, not better, at eight

`ClaudeLlmFormatterProvider`, `OpenAiLlmFormatterProvider`, and
`MlKitLlmFormatterProvider` already disagree on error handling today:

| | Circuit breaker | Retry policy | Empty-response handling | Timeout source |
|---|---|---|---|---|
| Claude | Yes (3 failures / 30s) | None (breaker only) | `NetworkError` | none installed at HTTP client level (relies on caller `withTimeout`, e.g. `LlmTagProvider`'s 8s) |
| OpenAI | Yes (3 failures / 30s) | None | `NetworkError` | same as Claude |
| ML Kit (on-device) | None | None | `ApiError(-1, "Empty response...")` | N/A (synchronous local call, but no timeout at all — a stuck on-device call blocks indefinitely unless the caller wraps it) |
| ClaudeTopicEnricher (3rd Claude client, not behind `LlmFormatterProvider`) | None | One hardcoded retry with `delay(2_000)` | Silently falls back to local suggestions | none |

The unified abstraction must pick *one* retry/backoff/circuit-breaker policy and
apply it uniformly, or explicitly document per-provider-category exceptions
(e.g., "on-device providers get no circuit breaker because failures are
local/synchronous, not network-flaky"). Migrating `ClaudeTopicEnricher` onto the
shared abstraction (in scope per requirements.md) means its ad-hoc retry-on-429
logic must be deleted, not preserved as a fourth policy.

### 3.2 Context-length and prompt-portability divergence

- Remote providers (Claude, OpenAI, Gemini, Azure OpenAI) have context windows in
  the 128K–1M token range; the two on-device providers cap output at ~256 tokens
  (Android) and are tuned for short-form tasks (iOS). A single prompt template
  designed against Claude's system-prompt conventions (the existing
  `LlmTagProvider.buildSystemPrompt()` and the voice formatter's system prompts)
  is not guaranteed to produce parseable output on-device — smaller models are
  more sensitive to instruction-following precision (e.g., "output one page name
  per line, no markdown" is a harder constraint for a 3B on-device model to
  reliably satisfy than for Claude Haiku).
  - The existing `LlmTagProvider.parseResponse()` already handles this
    defensively (line-based parsing, tolerant of stray formatting via
    `removePrefix("- ")`), which is good — but that tolerance was tuned against
    Claude/OpenAI output in practice, not validated against on-device output.
    When wiring the on-device provider into tag suggestion (an explicit
    requirement), add fixture-based tests using representative on-device output
    shapes (which may include leading numbering, trailing punctuation, or
    partial-word truncation at the 256-token boundary) — do not assume the
    existing parser generalizes.
- **Prompt-portability risk is highest for the new synthesis/graph-edit feature**,
  not tag suggestion — a system prompt instructing the model to propose
  structured edits (block IDs, page references, edit operations) needs a
  much more precise output contract (likely JSON) than the existing free-text
  tag-list format. JSON-mode / structured-output support is **not** uniform
  across the v1 provider set: Claude and OpenAI support tool-use/JSON-schema
  constrained output; Gemini has its own structured-output mechanism; the
  generic OpenAI-compatible provider's structured-output support depends
  entirely on what the backing server implements (Ollama/LM Studio support
  varies by model and server version); on-device Android/iOS models have no
  documented JSON-schema-constrained generation mode in the APIs referenced
  above. Design the synthesis feature's output contract to be recoverable from
  free-text with a strict parser (similar to `LlmTagProvider.parseResponse()`)
  rather than depending on JSON-mode being available from every provider —
  otherwise the approval workflow (in scope for every provider per
  requirements.md's "always require explicit approval" decision) silently loses
  provider parity for its core feature.

### 3.3 Testing complexity — platform-specific providers can't run where the rest of the suite runs

- `MlKitLlmFormatterProvider` lives in `androidMain` and depends on
  `com.google.mlkit.genai.prompt.GenerativeModel` — it cannot be exercised in
  `jvmTest`/`businessTest` at all; today it appears to have **no test file**
  (only `androidUnitTest`/`androidTest` could reach it, and grep found none).
  The iOS Foundation Models provider will have the same problem, compounded by
  §2.2's finding that even manual verification requires specific host macOS/
  hardware. **Any interface-level abstraction (the new
  `LlmProvider`/registry) must be designed so that `businessTest` can exercise
  discovery, fallback ordering, and error-mapping logic against fakes/mocks of
  the on-device providers**, without ever touching the real ML Kit or Foundation
  Models SDKs — otherwise those code paths (which include the eligibility
  tri-state from §0 and the foreground-only constraint from §2.1) are
  effectively untested in CI for the life of the project. Look at how
  `VoiceCaptureViewModelTest` / `LlmProviderSupportTest` already fake
  `LlmFormatterProvider` via the `fun interface` — that pattern generalizes, but
  the *registry/discovery* layer being added is new surface area with no
  existing test precedent to copy from.
- Mocking 8 provider types for cross-provider fallback-order tests (e.g., "user
  has no remote key configured, device is on-device-eligible, feature should
  route to on-device automatically" — exactly the parity goal in
  requirements.md) is meaningfully more combinatorial than the 2-provider
  fallback the current `buildLlmFormatterForTags()` does. Plan for a
  table-driven test structure (provider-availability matrix × feature ×
  expected-selected-provider) from the start rather than one bespoke test per
  scenario, or this becomes untestable-by-inspection as providers are added.

---

## 4. Generic OpenAI-compatible endpoint pitfalls

Confirmed by current-year documentation/community sources (Ollama docs,
LM Studio docs, BAML's `openai-generic` provider docs):

- **Ollama's OpenAI-compatible endpoint requires *an* API key value be sent, but
  ignores its content.** Most OpenAI-client-shaped code sends no
  `Authorization` header at all when the key is empty/unset (some SDKs skip the
  header entirely if `api_key` is blank). If the generic provider's HTTP layer
  is built by copying `OpenAiLlmFormatterProvider`'s
  `headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }` verbatim, an
  empty-string key produces `Authorization: Bearer ` (a malformed header with a
  trailing space and empty token) rather than omitting the header — some
  servers reject this, others accept it. The generic provider must decide and
  test explicitly: send a well-formed placeholder (e.g., `"ollama"`, `"lm-studio"`,
  matching common convention) when the user leaves the key blank, rather than
  echoing an empty string into the header.
- **Port/base-URL conventions differ by tool and are not auto-discoverable**:
  Ollama defaults to `:11434/v1`, LM Studio to `:1234/v1`. The custom-endpoint
  Settings field (Open Question #2 in requirements.md — "what validation
  confirms the endpoint is reachable/compatible") should ship with quick-pick
  presets for these two common local tools rather than requiring the user to
  know the port, and the "test connection" action mentioned in §1.4 is
  especially valuable here since a wrong port produces a connection-refused
  error indistinguishable (to a non-technical user) from "the model isn't
  running."
- **"OpenAI-compatible" is a compatibility claim about the *request/response
  shape*, not a guarantee of feature parity.** Streaming event framing,
  tool/function-calling support, and which optional fields are accepted vs.
  silently ignored vs. hard-rejected all vary by backing server and even by
  server *version*. Concretely for this codebase: `OpenAiLlmFormatterProvider`
  already sends `max_tokens` (not the newer `max_completion_tokens` some OpenAI
  models now require) — a generic provider pointed at a real Azure OpenAI
  deployment or a newer OpenAI-hosted model behind an OpenRouter passthrough may
  reject or ignore that field depending on the target. Do not assume the
  existing `OpenAiRequest`/`OpenAiResponse` serializable shapes (lines 107–121 of
  `OpenAiLlmFormatterProvider.kt`) are safe to reuse unmodified for the generic
  provider — validate field names against each target's actual current API
  surface at implementation time, and keep unknown-field tolerance
  (`ignoreUnknownKeys` is already used elsewhere in this codebase, e.g.
  `ClaudeTopicEnricher`'s `lenientJson`) on both request *construction*
  (only send fields you're sure of) and response *parsing* (already good
  practice here).
- **Azure OpenAI is a structurally different case, not just "OpenAI with a
  different base URL."** Azure uses `api-key` header auth (not
  `Authorization: Bearer`), a URL path keyed by **deployment name** rather than
  model name (`/openai/deployments/{deployment-id}/chat/completions`), and a
  mandatory `api-version` query parameter. If Azure OpenAI is folded into the
  same "generic OpenAI-compatible" provider type as Ollama/LM Studio/OpenRouter
  (as requirements.md's scope table implies by listing it alongside them under
  one provider concept), the credential schema from §1.2 needs an
  Azure-specific auth-header-name and deployment/api-version field, or Azure
  needs its own provider implementation rather than reusing the generic one
  with a different base URL. Treating Azure as "just another custom base URL"
  will fail at the auth-header level on the very first request.
- **No streaming support exists anywhere in this codebase today** (`.body<T>()`
  blocking response parse, confirmed in all three current HTTP-based providers).
  If a future iteration adds streaming for any provider, streaming response
  framing for self-hosted servers is the least standardized part of the
  "OpenAI-compatible" surface — validate per-target rather than assuming SSE
  chunk format parity with OpenAI's actual API.

---

## 5. Approval-gated write workflow pitfalls

### 5.1 Staleness / race between proposal and approval

The requirements are explicit that this workflow always requires approval
(no auto-apply exception) — which means every proposal has a window, potentially
long (user may leave a suggestion pending across app sessions per Open Question
#3), during which the graph can change underneath it.

- **Anchored edits can point at content that no longer exists.** If a proposal
  references a block UUID that the user has since deleted or edited (directly,
  or via git sync pulling in changes from another device — this codebase has
  external file-change detection via `GraphLoader.externalFileChanges`), naive
  "apply the stored edit" at approval time either (a) silently resurrects
  deleted content, (b) throws/fails at write time with a confusing error, or
  (c), worst case, applies a *stale-content-relative* edit (e.g., "append this
  paragraph after block X") on top of content block X no longer has, producing
  a nonsensical merge. This project already has a conflict-detection primitive
  (`GraphLoader`'s `DiskConflict` emission for external file changes) — the
  approval workflow should reuse that concept: re-validate the target
  block/page's content hash (or `updated_at`) at approval time against what the
  proposal was generated from, and if they differ, surface a conflict UI rather
  than applying blind, the same pattern already established for disk-vs-memory
  conflicts.
- **Deleted-page target.** A synthesized-note proposal that references a page as
  context, where that page is deleted before approval, needs a defined
  behavior — reject the proposal outright with a clear reason, not attempt a
  degraded apply.
- **Multi-graph consideration**: this codebase supports multiple simultaneously
  open graphs (`GraphManager`). A pending proposal must be scoped to the graph
  it was generated against; if the user switches graphs before approving, the
  approval UI must not apply the edit to the *currently active* graph if that's
  no longer the graph the proposal targets. This is a real risk given
  `GraphManager`'s per-graph `RepositorySet`/`CoroutineScope` model — a queue of
  pending suggestions needs a graph-id field from day one, not added later.

### 5.2 Approval fatigue / rubber-stamping

Current UX research (2026) on human-in-the-loop AI review is unambiguous that
this is a design failure mode, not a hypothetical:

- **Review fatigue causes pattern-matching instead of evaluation** once a queue
  passes roughly 10–15 items reviewed in a session — reviewers stop reading
  content and start approving based on the shape/length of the proposal.
  Directly relevant here: if graph-wide synthesis can generate many proposals
  in one pass (e.g., "here are 12 tag corrections across your graph"), queuing
  all 12 as individual approve/reject items risks exactly this. Batch related
  proposals with a shared summary and let the user approve/reject the batch
  with drill-down, rather than presenting 12 independent single-item reviews.
- **Approval rates near 100% are themselves a signal the review UI is broken**,
  not a signal that the AI is highly accurate — if the review surface doesn't
  give the user enough to make a real decision in a few seconds (e.g., no diff
  view against current block content, no "why was this suggested" context), the
  path of least resistance is always "approve." This directly informs Open
  Question #1 in requirements.md (what does the approval UI look like) — a
  diff-style before/after view anchored to the actual current block content
  (not just a description of the change) is a UX prerequisite for keeping
  approval meaningful, not a nice-to-have.
- **Risk-tiering reduces queue volume but is explicitly out of scope here** —
  requirements.md's resolved decision is "no auto-apply exception, even for
  high-confidence output." This is the right call for a v1 given the fatigue
  research above cuts both ways (auto-apply for "obvious" cases reduces queue
  volume, but this codebase's tag-suggestion feature already has a
  separate, shipped local-match auto-apply tier — conflating the two would
  violate the explicit out-of-scope boundary). The mitigation this project
  should invest in instead is *reducing proposal volume at the generation
  source* (don't generate 12 low-value proposals from one synthesis pass) and
  batching-with-drill-down, not weakening the approval gate.

### 5.3 Unbounded-graph-scan risk in synthesis, against this project's own established rules

This is the sharpest, most concrete risk in this section, because CLAUDE.md's
"Graph-scale reads must be paginated, projected, or chunked — never O(graph)"
rule exists precisely because of prior, real incidents (GC thrash, OOM on
Android, per the CLAUDE.md history and `LargeGraphWarmStartCrashTest`). A
graph-wide LLM synthesis feature is the single feature type most likely to
violate that rule if built naively, because "let the LLM see the whole graph"
is the intuitive-but-wrong implementation:

- Any synthesis feature must build its LLM context from **bounded/projected**
  reads only, using the same primitives §0-adjacent code already uses for
  large-graph safety: `getPageNameEntries()` (name+journal-flag projection, not
  full `Page` objects), chunked `IN`-clause lookups (`getPagesByNames`,
  ≤500-chunk per this codebase's documented `SQLITE_MAX_VARIABLE_NUMBER`
  constraint), or the paginated `getAllPagesSnapshot()` suspend method
  explicitly provided for whole-graph one-shot tooling. **Do not read full block
  content for every page into a prompt** — even a mid-size graph (the existing
  benchmark uses an 8,030-page synthetic XLARGE fixture) would blow past every
  provider's context window doing this naively, independent of the OOM risk.
- Combine this with §3.2's context-length divergence: even if the synthesis
  feature is careful to stay bounded on the **database read** side, it must
  independently truncate/chunk on the **token budget** side per selected
  provider, since a remote-provider-sized context budget (hundreds of K tokens)
  used against an on-device provider (256-token output cap, and much smaller
  effective input budget in practice) will simply fail or truncate silently
  unless the prompt-construction code is provider-aware, not just
  graph-scale-aware.
- Recommend: design the synthesis feature's data-gathering step as its own
  bounded, testable unit (mirroring `PageNameIndex`'s pattern) *before* wiring
  it to any provider, and add a regression test in the same spirit as
  `QueryPlanAuditTest` / `LargeGraphWarmStartCrashTest` — assert the synthesis
  context-builder never issues more than N bounded queries against a
  large-graph fixture, the same way those tests assert batch sizes today.

---

## 6. Migration cost

### 6.1 What currently exists and is under test — must keep passing

| Existing coverage | File | What it locks in |
|---|---|---|
| `LlmProviderSupportTest` | `businessTest/.../voice/LlmProviderSupportTest.kt` | Shared helper logic (word count, truncation detection, HTTP-error mapping) used by both `ClaudeLlmFormatterProvider` and `OpenAiLlmFormatterProvider` via `LlmProviderSupport`. Any refactor that moves this logic into the new abstraction must not change these mappings (e.g., which HTTP codes map to which `LlmResult.Failure` case). |
| `VoiceCaptureViewModelTest` | `businessTest/.../voice/VoiceCaptureViewModelTest.kt` | End-to-end voice-capture-to-formatted-block behavior against a fake `LlmFormatterProvider`. Since `LlmFormatterProvider` is a `fun interface`, this test's fakes will keep compiling even if the interface grows — but if the unified abstraction *replaces* `LlmFormatterProvider` rather than extending it, every fake in this file needs updating in the same change, not as follow-up debt. |
| `VoiceSettingsTest` | `businessTest/.../voice/VoiceSettingsTest.kt` | Current `VoiceSettings` getter/setter round-trip behavior (including the plaintext-on-JVM storage path documented in §0). Migrating key storage per requirements.md ("migrate existing voice-settings LLM key storage... into the new credential storage") will make some of these assertions describe *removed* behavior — do not just delete failing tests; replace them with tests asserting the migration path (old-location seed → new-location read) explicitly, so the migration itself is regression-tested, not just the post-migration steady state. |
| `TagSuggestionEngineTest`, `TagSuggestionViewModelTest` | `businessTest/.../tags/*.kt` | Local-match tag suggestion tier — explicitly out of scope to change per requirements.md, but `LlmTagProvider` (which these tests likely exercise indirectly or adjacently) currently hand-checks Anthropic/OpenAI-shaped `LlmFormatterProvider` instances passed in by the caller. Wiring on-device fallback into `buildLlmFormatterForTags()` must not change `LlmTagProvider`'s own contract (`suggestTags(request): Either<DomainError, List<TagSuggestion>>`) — the fallback selection belongs in the caller/registry, not inside `LlmTagProvider` itself, to keep this test surface stable. |
| `ClaudeLlmFormatterProviderTest`, `OpenAiLlmFormatterProviderTest` | `jvmTest/.../voice/*.kt` | Direct HTTP-mock-level tests of the two existing remote providers (circuit breaker behavior, status-code mapping, response parsing). If these providers are refactored to implement a new common interface, these tests validate the *implementation*, not the interface — keep them passing by preserving the providers' externally observable HTTP behavior even as their Kotlin type signature changes. |
| `ClaudeTopicEnricherTest` | `jvmTest/.../domain/ClaudeTopicEnricherTest.kt` | Covers the third, independent Claude client identified in §0/§3.1. Since `ClaudeTopicEnricher` migrating onto the unified abstraction is explicitly in scope, this test needs the most rework of any file in this table — its current assertions likely encode the ad-hoc retry-on-429 behavior that should be *deleted*, not preserved, once it's routed through the shared circuit-breaker policy. Flag this test file specifically during planning as "will be substantially rewritten," not "must pass unchanged." |

### 6.2 Behavioral changes migration must not introduce silently

- **Tag suggestion's LLM tier currently only fires if an API key exists.** Once
  on-device fallback is wired in, Android users with *no* key configured will,
  for the first time, see LLM-tier tag suggestions by default (assuming
  eligibility). This is the intended parity goal, but it's a **default-behavior
  change for existing users**, not just new capability — an existing user who
  deliberately never configured an API key (e.g., privacy-conscious, wanted
  local-match-only suggestions) will start getting on-device LLM suggestions
  without having opted in, unless the feature-level provider selection
  (required by requirements.md's Settings UI scope) defaults new on-device
  availability to **off** for existing installs and **on** only for fresh
  installs, or surfaces a one-time "on-device tag suggestions are now
  available" prompt rather than silently changing behavior on upgrade.
- **`VoiceSettings.getUseDeviceLlm()` already exists and defaults to `false`** —
  this flag predates the unified abstraction and currently gates on-device LLM
  usage in the *voice* pipeline specifically. The migration needs to decide
  whether this flag becomes the shared "prefer on-device" toggle across all
  features (tag suggestion included) or whether tag suggestion gets its own
  independent toggle. Silently reusing `voice.use_device_llm` to also gate tag
  suggestion (implicit if the registry reads the same setting key for both) 
  would mean a user who enabled it for voice notes gets it silently
  enabled for tag suggestion too, or vice versa — the requirements' "select
  which provider a feature uses" per-feature model implies these should be
  independent, which means this existing boolean setting needs to be
  reinterpreted or split, not reused as-is.
- **Removing `ClaudeTopicEnricher`'s duplicate request/response models** in favor
  of the shared abstraction's types must preserve exact wire compatibility with
  the Anthropic Messages API (model name `claude-haiku-4-5-20251001`,
  `max_tokens: 256` for this specific use case) — `TopicEnricher` is a different
  interface (`enhance(rawText, localSuggestions): List<TopicSuggestion>`) from
  `LlmFormatterProvider` (`format(transcript, systemPrompt): LlmResult`), so
  migrating it onto the unified provider abstraction means either adapting
  `TopicEnricher`'s call site to the new interface shape, or keeping
  `TopicEnricher` as a feature-level interface that *internally* delegates to
  the unified provider — the latter is lower-risk for this migration since it
  doesn't require touching `TopicEnricher`'s callers at all.

### 6.3 Sequencing risk

Given the breadth (8 provider types, 2 new platform integrations, a new
credential schema, a new approval-workflow data model), attempting this as one
large refactor risks a long-lived branch that regresses the existing,
shipped, and tested voice/tag features for an extended period. The migration
should be sequenced so that at every intermediate commit, the existing
`VoiceCaptureViewModelTest`/`TagSuggestionEngineTest`/`LlmProviderSupportTest`
suite passes unchanged — i.e., introduce the new abstraction and registry
alongside the existing providers first, migrate one call site at a time
(voice formatting, then tag suggestion, then `ClaudeTopicEnricher` last since
it has the most divergent existing behavior per §3.1), and only remove the old
`LlmFormatterProvider`-direct-construction call sites after each migrated
consumer's tests pass against the new path.
