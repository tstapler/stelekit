# LLM Provider Stack Research

## Summary

All required infrastructure for LLM API calls already exists in the codebase. Ktor 3.1.3 is
in use for HTTP, `kotlinx.serialization` handles JSON, and a fully-wired `expect/actual`
credential store covers Android (Android Keystore via `EncryptedSharedPreferences`), iOS
(Keychain), and JVM (AES-256-GCM file store). The project already ships two working Claude
API clients (`ClaudeLlmFormatterProvider`, `ClaudeTopicEnricher`). The tag suggestion feature
can be built directly on these foundations with zero new library additions.

---

## 1. HTTP Client

### What is used

**Ktor 3.1.3** (`io.ktor`) is the project's HTTP library. It is declared in `commonMain` and
is already in production use for LLM calls, URL fetching, GitHub Device Flow, Google Drive,
and Wayback Machine lookups.

```
commonMain:
  io.ktor:ktor-client-core:3.1.3
  io.ktor:ktor-client-content-negotiation:3.1.3
  io.ktor:ktor-serialization-kotlinx-json:3.1.3

jvmMain / androidMain:
  io.ktor:ktor-client-okhttp:3.1.3        (OkHttp engine)

iosMain:
  io.ktor:ktor-client-darwin:3.1.3        (NSURLSession engine)

jvmTest:
  io.ktor:ktor-client-mock:3.1.3          (MockEngine for unit tests)
```

### Engine matrix

| Platform | Ktor engine | Notes |
|----------|-------------|-------|
| JVM (Desktop) | OkHttp | `HttpClient(OkHttp) { ... }` — used in `UrlFetcherJvm` |
| Android | OkHttp | Same artifact, same engine |
| iOS | Darwin | NSURLSession wrapper; declared in `iosMain` |
| WASM/JS | (not yet configured) | Would need `ktor-client-js` engine |

### Existing LLM client pattern

`ClaudeLlmFormatterProvider` and `ClaudeTopicEnricher` (both in `commonMain`) show the
established pattern:

```kotlin
val client = HttpClient {
    install(ContentNegotiation) { json(lenientJson) }
}
// No explicit engine constructor arg in commonMain — engine is resolved by
// the platform source set at link time (OkHttp on JVM/Android, Darwin on iOS).
```

`HttpClient { }` with no engine argument in `commonMain` uses the engine contributed by
whichever platform source set is being compiled. This is the correct KMP pattern; do not
pass an engine class from `commonMain`.

Tests use `MockEngine` via `ktor-client-mock:3.1.3` (see `UrlFetcherJvmTest`).

---

## 2. Serialization

**`kotlinx.serialization` 1.10.0** (`kotlinx-serialization-json`) is the project's sole
serialization library, declared in `commonMain` and `commonTest`.

The Kotlin Gradle plugin `plugin.serialization` is applied, enabling `@Serializable`
annotation processing across all source sets.

### Existing usage patterns

All LLM request/response DTOs use `@Serializable` data classes:

```kotlin
@Serializable
private data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>,
)
```

`Json { ignoreUnknownKeys = true }` is the standard instance used for LLM responses
(defined as `LlmProviderSupport.voiceLenientJson`). New features should reuse this instance
rather than creating their own.

---

## 3. Credential Storage

The project has a fully implemented `expect/actual` `CredentialStore` pattern across all
platforms. Additionally, a `PlatformSettings` `expect/actual` is used specifically for API
keys (Anthropic, OpenAI, Whisper) via `VoiceSettings`.

### CredentialStore (git credentials, SSH passphrases)

| Platform | Implementation | Backend |
|----------|---------------|---------|
| Android | `AndroidCredentialStore` | `EncryptedSharedPreferences` (AES-256-GCM key via Android Keystore `MasterKey`) |
| iOS | `IosCredentialStore` | Security framework `SecItemAdd`/`SecItemCopyMatching` (Keychain) |
| JVM (Desktop) | `JvmCredentialStore` | AES-256-GCM file encrypt at `~/.config/stelekit/credentials.enc`, key derived via PBKDF2-HMAC-SHA256 from machine identity + random salt |
| WASM/JS | `CredentialStore` stub | (separate wasmJs implementation) |

The JVM store notes in code that OS keychain integration (Libsecret/DPAPI/macOS Keychain) is
a future hardening goal.

### PlatformSettings (API keys for LLM/voice features)

`VoiceSettings` stores Anthropic, OpenAI, and Whisper API keys via the `PlatformSettings`
`expect/actual`:

| Platform | Backend | Security level |
|----------|---------|---------------|
| Android | `EncryptedSharedPreferences` (AES-256-SIV keys, AES-256-GCM values) backed by Android Keystore | High — hardware-backed on API 23+ |
| JVM (Desktop) | Plain `java.util.Properties` file at `~/.stelekit/prefs.properties` | Low — API keys stored in plaintext |
| iOS | (not shown in search results — expect implementation exists) | Expected: Keychain |

**Gap identified**: JVM `PlatformSettings` stores API keys as plaintext. For LLM API key
storage on desktop, routing through `JvmCredentialStore` (which encrypts with AES-256-GCM)
would be materially more secure. This is pre-existing; address as a hardening step separate
from the tag suggestion feature.

---

## 4. What Exists for LLM Calls Specifically

Two production-ready Claude API clients already exist in `commonMain`:

### `ClaudeLlmFormatterProvider`
- Path: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/ClaudeLlmFormatterProvider.kt`
- Endpoint: `https://api.anthropic.com/v1/messages`
- Model: `claude-haiku-4-5-20251001`
- Auth: `x-api-key` header
- Arrow `CircuitBreaker`: 3 failures → open, 30s reset, exponential backoff to 5min
- Wraps errors into `LlmResult` sealed class

### `ClaudeTopicEnricher`
- Path: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/ClaudeTopicEnricher.kt`
- Endpoint: same (`/v1/messages`)
- Model: same
- No circuit breaker; manual 429 retry with 2s delay
- Returns `List<TopicSuggestion>` (term + confidence float)

`ClaudeTopicEnricher` is the closest analogue to a tag suggestion feature. It already accepts
a block of text, sends it to Claude with candidate page names, and returns re-ranked
suggestions with new concepts.

---

## 5. Recommended Approach

### Do not add new libraries

All required infrastructure is present:
- HTTP: Ktor 3.1.3 (all platforms)
- JSON: `kotlinx.serialization` 1.10.0
- Credential storage: `PlatformSettings` / `CredentialStore` (all platforms)
- Arrow `CircuitBreaker` from `arrow-resilience:2.2.1.1` (already in use)
- `MockEngine` for tests (already in jvmTest)

### Extend `ClaudeTopicEnricher` or create a parallel `LlmTagSuggester`

The tag suggestion feature is architecturally identical to `ClaudeTopicEnricher`. Two paths:

1. **Extend**: Add a `suggestTags(blockText: String, existingTags: List<String>): List<TagSuggestion>` method to `ClaudeTopicEnricher` or extract a shared base class.
2. **New class**: Create `LlmTagSuggester` in `commonMain` following the same pattern (`HttpClient` injected, `apiKey: String` param, `CircuitBreaker`, Arrow `Either` returns).

Option 2 is cleaner if tag suggestion has different prompts, confidence thresholds, or
output shapes.

### API key wiring

Reuse `VoiceSettings.getAnthropicKey()` — it already reads from `PlatformSettings` with
the correct per-platform encryption backing. No new key management code is needed.

### Circuit breaker

Reuse `ClaudeLlmFormatterProvider.defaultCircuitBreaker()` or define one in
`LlmProviderSupport` (same file as `voiceLenientJson`) so all LLM providers share the same
resilience policy.

### JSON instance

Reuse `LlmProviderSupport.voiceLenientJson` (`Json { ignoreUnknownKeys = true }`). Claude
API responses are stable but the `ignoreUnknownKeys` guard is valuable.

### Testing

Inject `MockEngine` (already a test dep in `jvmTest`) as the Ktor engine for unit tests,
following the pattern in `UrlFetcherJvmTest` and `ClaudeTopicEnricherTest`.

---

## Appendix: Key File Locations

| File | Role |
|------|------|
| `kmp/build.gradle.kts` | All dependency declarations |
| `kmp/src/commonMain/.../voice/ClaudeLlmFormatterProvider.kt` | Full Claude API client with circuit breaker |
| `kmp/src/commonMain/.../domain/ClaudeTopicEnricher.kt` | Tag/topic enrichment via Claude — closest analogue |
| `kmp/src/commonMain/.../voice/LlmProviderSupport.kt` | Shared JSON instance, token estimator, error mapper |
| `kmp/src/commonMain/.../voice/VoiceSettings.kt` | API key read/write via PlatformSettings |
| `kmp/src/commonMain/.../platform/PlatformSettings.kt` | expect class — cross-platform settings |
| `kmp/src/androidMain/.../platform/PlatformSettings.android.kt` | Android: EncryptedSharedPreferences |
| `kmp/src/jvmMain/.../platform/PlatformSettings.kt` | JVM: plaintext .properties file (hardening gap) |
| `kmp/src/commonMain/.../git/CredentialStore.kt` | expect class — cross-platform secure storage |
| `kmp/src/androidMain/.../git/AndroidCredentialStore.kt` | Android Keystore-backed implementation |
| `kmp/src/iosMain/.../git/IosCredentialStore.kt` | iOS Keychain implementation |
| `kmp/src/jvmMain/.../git/JvmCredentialStore.kt` | JVM AES-256-GCM file-backed implementation |
| `kmp/src/jvmMain/.../domain/UrlFetcherJvm.kt` | Example: explicit `HttpClient(OkHttp)` in jvmMain |
