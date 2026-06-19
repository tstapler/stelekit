# LLM Tag Suggestion — Failure Modes and Mitigations

Research date: 2026-06-13
Codebase snapshot: stelekit-llm-provider branch

This document covers concrete failure modes for each subsystem of the proposed tag suggestion feature: local matching, remote LLM calls, KMP/Ktor networking, Android share widget, voice pipeline, and API key security. Each section notes what the current codebase already handles and what is still unaddressed.

---

## 1. LLM API Pitfalls

### 1.1 Rate Limiting (429)

**Claude API limits (as of 2025)**
- Free tier: 5 requests/minute, 25 requests/day on the cheapest Haiku model tier.
- Tier 1 (first $5 spent): 50 requests/minute (RPM), 50 000 tokens/minute (TPM).
- Tier 2+: 1 000 RPM and above, depending on tier.

**OpenAI limits**
- gpt-4o-mini Tier 1: 500 RPM, 200 000 TPM, 10 000 RPD (requests per day).
- gpt-4o Tier 1: 500 RPM, 30 000 TPM.

**Current handling**: `ClaudeLlmFormatterProvider` and `OpenAiLlmFormatterProvider` both use an Arrow `CircuitBreaker` (opens after 3 consecutive failures, 30 s reset, exponential backoff up to 5 min). `LlmProviderSupport.mapHttpError` maps HTTP 429 to `LlmResult.Failure.ApiError(429, …)`. The test suite (`ClaudeLlmFormatterProviderTest`) verifies 429 produces `ApiError(429)`.

**Gaps and mitigations**
- The circuit breaker opens on *any* 3 failures, including 429. This is correct — don't retry into a rate limit window. But the UI currently treats `ApiError(429)` identically to auth errors; add a distinct `RateLimited` sealed case so the UI can show "try again shortly" instead of "invalid key."
- For tag suggestion (which is lower-urgency than voice transcription), add a per-session debounce: do not fire a remote call if one is already in-flight for the same block. A `MutableStateFlow<Job?>` in the ViewModel cancels the previous job before starting a new one.
- Do not retry 429 automatically. Surface it to the user and let them re-trigger when ready, or implement a `Retry-After` header reader (Claude returns this header) to schedule a single delayed retry.

### 1.2 Latency

**Typical latencies**
- Claude Haiku 4.5: median 300–800 ms TTFT (time-to-first-token) for short prompts; end-to-end for a 200-token response is ~1–2 s.
- OpenAI gpt-4o-mini: median 400–1 000 ms end-to-end for similar payload sizes.
- Network round-trip from mobile adds 50–300 ms on a good connection, up to 2–5 s on poor 4G.

**Current handling**: Both providers issue a blocking `httpClient.post()` call that returns only after the full response body is received. No streaming.

**Gaps and mitigations**
- Tag suggestion must never block the block editor. Issue the call from a background coroutine (`viewModelScope.launch { }`) and expose results as `StateFlow<TagSuggestionState>`. The `LaunchedEffect` in the block UI collects this and shows a non-blocking suggestion chip row.
- Add `HttpTimeout` on the tag-suggestion client: `connectTimeoutMillis = 5_000`, `requestTimeoutMillis = 10_000`. The existing `UrlFetcherAndroid` uses 10 s / 15 s; for suggestions, 10 s total is the right ceiling — after that, degrade silently.
- Consider streaming (`HttpStatement.execute { response -> response.bodyAsChannel().readUTF8Line() }`) only if the UX design shows incremental chips. For a final "here are your tags" result, streaming adds complexity without benefit.

### 1.3 API Key Security on Mobile

**Risks**
- A key stored in `SharedPreferences` (unencrypted) is readable by any app with root or a backup extraction tool (`adb backup` before Android 9 / `FLAG_ALLOW_BACKUP` not set).
- A key in the APK binary (BuildConfig or strings.xml) is trivially extractable with `apktool` or `strings`.
- A key logged to Logcat is visible to any app with READ_LOGS permission (granted to shell, adb).

**Current codebase pattern**: The `PlatformSettings.android.kt` implementation uses `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore) for all settings including any stored API key. `AndroidCredentialStore` uses the same mechanism for git credentials. The iOS `IosCredentialStore` writes directly to the iOS Keychain via `Security.framework`. Both are the correct storage backends.

**Mitigations**
- Route all API key writes through the existing `PlatformSettings` / `CredentialStore` pattern — do not introduce a second storage path.
- Never put the key in `BuildConfig`, `strings.xml`, or any resource file.
- Never log the key: grep for `apiKey` in log calls before shipping. Add a detekt rule or a `@Sensitive` annotation marker on the field.
- `EncryptedSharedPreferences` has one known pitfall: it can throw `GeneralSecurityException` if the Keystore is unavailable (e.g., after a device wipe without factory reset, or on some emulators). The existing fallback in `PlatformSettings` falls back to plain `SharedPreferences` and logs a warning — this is acceptable for non-sensitive settings but **not** acceptable for API keys. For the API key specifically: if Keystore fails, do not store the key at all; surface an error asking the user to re-enter it.
- Desktop JVM: store in OS keyring (via `java.security.KeyStore` with `PKCS12` or the system `awt.Desktop` keyring abstraction). As a minimum, store in `~/.config/stelekit/credentials.json` with `chmod 600` — not in `~/.stelekit.properties` or any world-readable location.
- Proxy alternative: consider routing API calls through a SteleKit backend proxy that holds a shared key and enforces per-user rate limits. This removes the key from the device entirely, at the cost of requiring a server.

### 1.4 Cost Management

**Risk**: A user with a large graph and aggressive tag suggestion (triggered on every block edit) can accumulate thousands of API calls per session.

**Mitigations**
- Gate remote calls behind an explicit user action (block action menu "Suggest tags") or a settings toggle `autoSuggestTags: Boolean` defaulting to false.
- For auto-suggest mode, debounce: only fire after 2 s of inactivity post-edit, and deduplicate — do not re-fire if the block text hasn't changed since the last call.
- Cap prompt size: truncate block text to 500 characters before sending. Tag suggestion does not need the full block content.
- Limit page-name vocabulary sent to the LLM to the top-N most relevant candidates from local matching (e.g., 50 names max). Sending 8 000 page names per call would cost ~8 000 tokens just for context.
- Expose a per-month call counter in Settings ("API calls this month: 142 / estimated cost: $0.03"). Read from a local counter in `PlatformSettings`; reset on the first call of each calendar month.
- Document that the user's own API key is used and they are billed directly — include a link to their provider's usage dashboard in the Settings screen.

---

## 2. KMP / Ktor Pitfalls

### 2.1 HTTP Engine Per Platform

The correct engine assignments are already established in `kmp/build.gradle.kts` for the Coil image loader. The tag suggestion client must follow the same pattern:

| Platform | Engine | Dependency already in build.gradle.kts |
|---|---|---|
| JVM / Desktop | `OkHttp` | `io.ktor:ktor-client-okhttp:3.1.3` in `jvmMain` |
| Android | `OkHttp` | `io.ktor:ktor-client-okhttp:3.1.3` in `androidMain` |
| iOS | `Darwin` | `io.ktor:ktor-client-darwin:3.1.3` in `iosMain` |
| WASM/JS | `Js` (browser fetch) | not yet declared; add `io.ktor:ktor-client-js` to `wasmJsMain` if JS target is enabled |

**Pitfall**: Creating `HttpClient { }` in `commonMain` without specifying an engine works only when exactly one engine is on the classpath. Since both JVM and Android have `OkHttp` and iOS has `Darwin`, `commonMain` cannot create a client with the no-arg constructor — it will crash at runtime with "No HttpClientEngine" on whichever platform lacks a default. Always create the client in the platform source set (`jvmMain`, `androidMain`, `iosMain`) or use an `expect`/`actual` `HttpClientEngineFactory`.

The existing codebase handles this correctly in `UrlFetcherAndroid` (uses `OkHttp` explicitly) and `UrlFetcherIos` (uses `Darwin` explicitly). The `ClaudeLlmFormatterProvider` is in `commonMain` and creates `HttpClient { }` with no engine — this compiles but is fragile. Refactor to inject the client from platform source sets, as the test-injection pattern already does.

### 2.2 SSL/TLS Issues

- **Android**: OkHttp uses the system trust store. On Android 7+ the system trust store is immutable from user-installed CAs, so custom enterprise CAs require a `network_security_config.xml`. The Anthropic/OpenAI endpoints use publicly trusted certs — no special config needed. However, if a user runs a local proxy (e.g., mitmproxy for debugging), the OkHttp client will refuse the proxy cert. This is the correct behavior for production.
- **iOS Darwin**: NSURLSession uses the iOS system trust store and ATS (App Transport Security). Connections to `api.anthropic.com` and `api.openai.com` are HTTPS and satisfy ATS by default. Do not add `NSAllowsArbitraryLoads` to `Info.plist` — it would disable ATS globally.
- **JVM OkHttp**: Uses the JVM default trust store (`cacerts`). On Linux systems with outdated JDKs, `cacerts` may not include newer root CAs. Pin to Kotlin 1.9+ / JDK 21 (already the toolchain version in this repo) which includes updated root CAs.
- **Certificate pinning**: Not required for MVP, but consider pinning the Anthropic and OpenAI leaf certs in OkHttp for security-conscious users (at the cost of needing an app update when the cert rotates).

### 2.3 ProGuard / R8 Rules for Ktor on Android

The project has no `.pro` rule files yet (none found in the repo). When `minifyEnabled = true` (release builds), R8 will strip:

- Ktor's `ServiceLoader`-based engine discovery (`io.ktor.client.HttpClientEngineContainer`). Mitigation: add `-keep class io.ktor.client.engine.okhttp.** { *; }` and `-keep class io.ktor.client.HttpClientEngineContainer { *; }`.
- `kotlinx.serialization` reflective access for `@Serializable` classes. Mitigation: add `-keep @kotlinx.serialization.Serializable class * { *; }` and `-keepclassmembers class ** { @kotlinx.serialization.* <methods>; }`. The `kotlin("plugin.serialization")` plugin already generates serializers at compile time, but R8 can still strip them.
- Arrow's `Either` and `CircuitBreaker` if accessed only via reflection: unlikely, but add `-keep class arrow.** { *; }` as a safeguard.

Create `kmp/src/androidMain/proguard-rules.pro` and reference it from the `android { buildTypes { release { proguardFiles(...) } } }` block.

### 2.4 Timeout Handling Across Platforms

**Pitfall**: `HttpTimeout` plugin behaviour differs subtly across engines.
- OkHttp (Android/JVM): `connectTimeoutMillis` maps to `OkHttpClient.connectTimeout`; `requestTimeoutMillis` is a Ktor-level cancellation applied via `Job.cancel()` on the coroutine. If the coroutine is cancelled first, OkHttp's underlying call is also cancelled.
- Darwin (iOS): `connectTimeoutMillis` maps to `NSURLSessionConfiguration.timeoutIntervalForRequest`. `requestTimeoutMillis` is also respected via a coroutine timeout wrapper.
- WASM/JS: timeouts are not supported by the browser `fetch` API; `HttpTimeout` silently does nothing on JS. For JS, implement an explicit `withTimeout(10.seconds) { client.post(...) }` wrapper.

Recommended config for tag suggestion:
```kotlin
install(HttpTimeout) {
    connectTimeoutMillis = 5_000
    requestTimeoutMillis = 10_000
    socketTimeoutMillis  = 10_000  // prevents hung reads on flaky connections
}
```

Always install `HttpTimeout` on the client used for LLM calls — without it, a hung server connection blocks the coroutine indefinitely.

---

## 3. Local Matching Pitfalls

### 3.1 Performance with Large Vocabularies

The `PageRepository` architecture deliberately avoids unbounded reads (see `PageRepository` interface doc comment: "There is deliberately NO `getAllPages()` on this interface"). The existing `getPageNameEntries()` projection (`PageNameEntry(name, isJournal)`) returns names only, which is the correct input for local matching.

**Pitfall**: A naïve O(N × M) scan — for each word in the block text, check every page name — is 1 000 × 5 000 = 5 million comparisons on a mid-size graph. On the main thread this causes a measurable jank spike.

**Mitigations**
- Build a `Set<String>` of lowercased page names at graph-load time (or on first tag suggestion request). Store it in the ViewModel alongside the existing page name index.
- For exact-word matching: split block text into tokens, lowercase each, and test `pageNameSet.contains(token)`. This is O(words) with O(1) set lookup.
- For substring matching: use `pageNames.filter { name -> blockText.contains(name, ignoreCase = true) }`. On 1 000 page names and a 500-character block this is ~500 000 character comparisons — fast enough, but only do it off the main thread (`withContext(PlatformDispatcher.Default) { … }`).
- For larger graphs (5 000+ pages), build an inverted index: a `Map<String, List<String>>` from lowercase word → list of page names containing that word. This reduces matching to O(words × average_bucket_size).
- The existing `PageNameIndex` (used in `StelekitViewModel`) is the right place to expose a `matchBlockText(text: String): List<String>` method.

### 3.2 False Positives from Short Page Names

Logseq graphs commonly have pages named with single words, abbreviations, or common English words: "AI", "I", "the", "a", "on", "books", "health".

**Pitfall**: A block like "I am reading a book on AI" would match pages named "I", "a", "on", "AI" — producing four suggestions, most of them noise.

**Mitigations**
- Minimum name length filter: only match page names with 3 or more characters for exact-word matching. Keep 1–2 character names in a separate set for deliberate context (e.g., the user explicitly uses a page named "AI" as a tag — matching "AI" in block text is correct).
- Require word-boundary matching for short names: use `Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)` rather than `contains()` for names under 5 characters. This prevents "in" matching "index".
- Filter out common English stop words from the candidate page names before matching. Use a configurable exclusion list in Settings (default: words like "the", "a", "an", "and", "or", "of", "in", "on", "at", "to").
- Score suggestions by specificity: prefer multi-word page names and longer names. Surface them above single-word matches.

### 3.3 Case Sensitivity, Diacritics, and Special Characters

Logseq page names are case-insensitive by convention but the file system may be case-sensitive (Linux) or case-insensitive (macOS, Windows).

**Pitfall**: A page named "Machine Learning" will not match "machine learning" in block text if comparison is case-sensitive. A page named "naïve" will not match "naive".

**Mitigations**
- Normalize both the page name vocabulary and the block text to lowercase before comparison: `name.lowercase()` and `blockText.lowercase()`.
- For diacritic normalization: use `java.text.Normalizer.normalize(str, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "")` to strip combining characters. This converts "naïve" → "naive" and "café" → "cafe". This is JVM/Android-only; for KMP commonMain, use `String.lowercase()` only and accept diacritic mismatches until a KMP normalization library is available.
- Logseq page names with forward slashes (`A/B Testing`) are treated as namespaces. Strip the namespace prefix for matching: match on both the full name and the leaf component (`B Testing`).
- Page names with brackets (`[[some page]]`) appear in block text as wikilinks — exclude these from candidate matching since they are already linked.

### 3.4 Logseq-Style Page Names with Special Characters

Logseq allows page names containing: `()`, `/`, `&`, `'`, `#`, `@`, and even emoji. The file name on disk uses URL-encoding for some of these.

**Pitfall**: A page named "Q&A" stored on disk as `Q&A.md` is loaded with its raw name. Matching "Q&A" in block text works. But a page named "C++ notes" stored as `C++ notes.md` could have its `+` characters misinterpreted in substring matching if the block text uses `C++` without spaces.

**Mitigations**
- Always use `Regex.escape(pageName)` when constructing regex patterns from page names to prevent regex metacharacters in page names from breaking the pattern.
- For block text preprocessing: do not strip punctuation before matching — keep it, since page names may contain punctuation that should match literally.
- Test with a fixture of pathological page names: `"C++"`, `"50% off"`, `"1 + 1 = 2"`, `"Hello (World)"`, `"A/B"`.

---

## 4. Voice / Share Pipeline Pitfalls

### 4.1 Android Background Process Restrictions

Android 8+ enforces background execution limits: apps cannot start foreground services from the background (since Android 12, even this requires user permission). The share widget (Glance `AppWidget`) runs in the app's process, but the process may not be running when the user triggers the widget.

**Pitfall**: The share widget starts an LLM call; Android kills the process mid-call (OOM, battery saver); the call is lost; the user sees no feedback.

**Mitigations**
- Route LLM calls from the share widget through `WorkManager` (a `OneTimeWorkRequest`), the same pattern used by `GitSyncWorker`. WorkManager is guaranteed to run the work even if the process is killed and restarted.
- Alternatively, start a foreground `Service` with a notification ("Suggesting tags…") so Android does not kill the process during the call. This requires `FOREGROUND_SERVICE_DATA_SYNC` permission (Android 14+).
- The share widget itself (Glance) runs its `update()` on the main thread of the widget process — do not perform network I/O in `GlanceAppWidget.update()`. Dispatch to WorkManager or a coroutine on `Dispatchers.IO` immediately.

### 4.2 LLM Call Failure After Voice Capture

The voice pipeline is: record → transcribe → format via LLM → insert block. If the LLM call fails after transcription, the raw transcript exists but the formatted/tagged result does not.

**Current handling**: `VoiceCaptureViewModel` (inferred from test files) holds the transcript in state. The formatter returns `LlmResult.Failure`.

**Mitigations**
- On LLM failure, fall back to inserting the raw transcript text as a block without tags. Never discard the transcript — that is data loss.
- Show the failure inline: "Tags could not be suggested (network error). Your note was saved." with a "Try again" action that retries only the LLM call, not the entire voice capture.
- Store the raw transcript in a temporary `DraftBlock` repository entry before issuing the LLM call, so a process death between transcription and LLM completion does not lose the data.

### 4.3 User Dismisses Before Suggestions Arrive

**Pitfall**: The user opens the block action "Suggest tags", navigates away, and the LLM response arrives into a ViewModel that no longer has a visible collector.

**Current pattern**: The existing architecture cancels coroutines via `viewModelScope` when the ViewModel is cleared. A tag suggestion job launched in `viewModelScope` is automatically cancelled when the user navigates away — correct behavior.

**Mitigations**
- Do not hold a reference to the UI from the coroutine. Use `StateFlow` in the ViewModel; the UI collects only while visible.
- If the user navigates back within the same session, the in-progress call result should still land if the ViewModel is retained (standard Compose Navigation lifecycle).
- If the call completes after the ViewModel is cleared (e.g., the screen is fully popped), discard the result silently. Do not attempt to modify block state from a background thread with a stale reference.
- For the share widget specifically (which has no ViewModel): use a `Channel` or `SharedFlow` per pending suggestion keyed by block UUID, and deliver the result when the app is foregrounded.

---

## 5. Security Pitfalls

### 5.1 Android: EncryptedSharedPreferences vs. Android Keystore

`EncryptedSharedPreferences` (already used in this codebase for `PlatformSettings` and `CredentialStore`) stores the data key wrapped by an AES-256-GCM key that lives in the Android Keystore. This is the correct mechanism. However:

**Known failure modes**
- `GeneralSecurityException` / `KeyStoreException` on first access if the device's Keystore is corrupted or unavailable. The `PlatformSettings` fallback to plain `SharedPreferences` is appropriate for UI settings (dark mode, etc.) but must NOT apply to API keys.
- On Android < 6 (API 23) the Keystore-backed key generation is absent. The project's `minSdk = 26`, so this is not a concern.
- Backup extraction: `EncryptedSharedPreferences` files are excluded from `adb backup` automatically because they live in `MODE_PRIVATE` storage, but `allowBackup="true"` in the manifest can expose them in a full backup if the backup key is not protected. Set `android:allowBackup="false"` in `AndroidManifest.xml` or use the `BackupAgent` exclusion list.

**Mitigation for API key specifically**
```kotlin
// In PlatformSettings or a dedicated ApiKeyStore:
actual override fun putString(key: String, value: String) {
    if (key == SETTING_LLM_API_KEY && prefs is SharedPreferences && !isEncrypted) {
        // Keystore unavailable — refuse to store the key, not silently downgrade
        throw SecurityException("Cannot store API key: Keystore unavailable")
    }
    prefs.edit().putString(key, value).apply()
}
```

### 5.2 iOS: UserDefaults vs. Keychain

`UserDefaults` is accessible via iTunes/Finder backup and from any app in the same app group. **Never** store API keys in `UserDefaults`.

The existing `IosCredentialStore` uses `SecItemAdd` / `SecItemCopyMatching` from `Security.framework` — the iOS Keychain. This is the correct backend. Ensure:
- `kSecAttrAccessible` is set to `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (not `kSecAttrAccessibleAlways`, which permits access on locked devices and across device transfers). Add this attribute to `IosCredentialStore.baseQuery()`.
- The `kSecAttrService` is set to a stable, app-specific identifier (`dev.stapler.stelekit.credentials` — already done) to prevent key collisions with other apps.

### 5.3 JVM Desktop: Key Storage

The JVM desktop target has no existing credential storage. Options in order of security:

1. **OS keyring via `java.awt.Desktop` or a KMP-compatible library** (e.g., `com.github.javakeyring:java-keyring`): delegates to macOS Keychain / Windows Credential Manager / libsecret on Linux. Best option.
2. **Encrypted file in `~/.config/stelekit/`**: encrypt with a key derived from a device identifier (machine UUID from `/etc/machine-id` on Linux or `wmic csproduct get UUID` on Windows) using HKDF-SHA256. The existing `bcprov-jdk18on` dependency (BouncyCastle) in `jvmMain` already provides both Argon2id and HKDF. Use `chmod 600` / `File.setReadable(false, false); File.setReadable(true, true)` after writing.
3. **JVM `KeyStore` (PKCS12)** protected by a password derived from the device: only viable if the user accepts a password prompt at first launch.

Do not store the key in system properties (`System.setProperty`) or environment variables — both are visible to any code in the same JVM process.

### 5.4 General: Key Leakage Vectors

- **Logcat / logging**: The `Logger` calls in `commonMain` should never log `apiKey`. Add a detekt custom rule or a `@Sensitive` marker.
- **Crash reporting**: If a crash reporter (e.g., Firebase Crashlytics) is added later, scrub the API key from all `Throwable` messages and stack frames before upload. The key may appear in `IllegalArgumentException` messages if validation throws with the key value.
- **Network request interception in debug builds**: When `BuildConfig.DEBUG` is true, OkHttp's `HttpLoggingInterceptor` at `HEADERS` level will log the `Authorization` / `x-api-key` header to Logcat. Guard the interceptor: `if (BuildConfig.DEBUG) client.addInterceptor(logging)` — but even in debug, consider redacting the key header: `logging.redactHeader("x-api-key"); logging.redactHeader("Authorization")`.
- **Serialization**: Do not include the `ApiKey` in any data class that is serialized to JSON or Parcel — a misplaced `@Serializable` on a settings data class could write the key to disk in plaintext.

---

## 6. Cross-Cutting: What the Current Codebase Already Gets Right

The following patterns are already established and the tag suggestion feature must not break them:

- `Either<DomainError, T>` for all service boundaries — LLM results should follow the same pattern at the repository level even though they currently use `LlmResult` (a parallel sealed class). Consider aligning `LlmResult` with `DomainError` or bridging at the call site.
- `CircuitBreaker` from `arrow.resilience` — already on the LLM providers; do not add raw retry loops.
- `CancellationException` must always be rethrown — both `ClaudeLlmFormatterProvider` and `OpenAiLlmFormatterProvider` already do this correctly. Any new network call site must follow the same pattern.
- `PlatformDispatcher.IO` for non-database network calls — the `UrlFetcher` implementations use this. The LLM client should follow the same dispatcher rule.
- `EncryptedSharedPreferences` (Android) and Keychain (iOS) for secret storage — established in `CredentialStore` and `PlatformSettings`; do not introduce a second storage mechanism.
- Bounded page queries — `PageRepository` has no `getAllPages()`; the local matching vocabulary must come from `getPageNameEntries()` which returns a lightweight projection, not full `Page` objects.
