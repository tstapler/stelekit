# ADR-011: Consolidate LLM/Voice Credential Storage onto `CredentialStore`

**Date**: 2026-07-01
**Status**: Accepted
**Deciders**: Tyler Stapler
**Context**: llm-service Phase 3 (planning)

---

## Context

LLM and voice API key storage is currently split across three different, inconsistent
mechanisms, none of which is the project's existing secure secret store:

| Platform | Current mechanism | Encrypted? |
|---|---|---|
| Android | `VoiceSettings` → `PlatformSettings` → `EncryptedSharedPreferences`, with a **silent fallback to plaintext `SharedPreferences`** if Keystore init throws (`GeneralSecurityException` etc., caught and only `Log.w`'d) | Usually, but not guaranteed — failure is invisible to the caller |
| JVM Desktop | `VoiceSettings` → `PlatformSettings` → plain `java.util.Properties` at `~/.stelekit/prefs.properties` | No — plaintext on disk today |
| WASM/JS | `VoiceSettings` → `PlatformSettings` → `kotlinx.browser.localStorage` | No — plaintext, XSS-exposed, no eviction |

Meanwhile, `CredentialStore` (`kmp/src/commonMain/.../git/CredentialStore.kt`, `expect/actual`)
already exists, is used in production for git credentials, and is correctly encrypted on
every platform that matters:

| Platform | Backend |
|---|---|
| Android | `EncryptedSharedPreferences`, AES-256-GCM, backed by Android Keystore — and unlike `PlatformSettings`, throws on Keystore init failure rather than silently degrading to plaintext |
| iOS | Keychain Services (`Security` framework) via direct `cinterop` |
| JVM Desktop | AES-256-GCM file at `~/.config/stelekit/credentials.enc`, key derived via PBKDF2-HMAC-SHA256 from machine identity + salt |
| WASM/JS | No-op stub (browser has no durable secure-storage primitive equivalent to Keystore/Keychain) |

`requirements.md`'s constraint is explicit: "Secure per-platform credential storage...
not new plaintext preferences." The current `VoiceSettings`/`PlatformSettings` path already
violates this constraint in production on Desktop and Web. `PlatformSettings` also has no
iOS `actual` at all, whereas `CredentialStore` does — building a fix on top of
`PlatformSettings` would require closing that gap as a prerequisite; routing through
`CredentialStore` sidesteps it entirely.

The generic OpenAI-compatible provider (Ollama/LM Studio/Azure OpenAI/OpenRouter/custom)
also introduces a new credential *shape* — base URL, optional API key, optional model name,
and (for Azure) deployment name + API version — that a single `apiKey` string field cannot
express without either an ad-hoc JSON blob (which reintroduces the plaintext-on-JVM/WASM gap
if stored via `Settings.putString`) or a provider-typed key schema.

## Decision

Migrate **all** LLM/voice provider credentials (Anthropic, OpenAI, Gemini, generic
OpenAI-compatible custom endpoints, and any future remote provider) onto the existing
`CredentialStore` expect/actual, using namespaced flat keys consistent with the existing
`git_https_token_$id` convention:

```
llm.anthropic.api_key
llm.openai.api_key
llm.gemini.api_key
llm.custom.<providerId>.api_key
```

Non-secret provider configuration (custom base URL, model name, Azure deployment
name/API version) stays in a new `Settings`-backed `LlmSettings` class, not
`CredentialStore` — mirroring how `git/model/GitConfig` already separates non-secret
config from `CredentialStore`-held secrets. `CredentialStore`/`CredentialAccess` is
relocated from `git/` to a `platform/` (or `platform/security/`) package so LLM code
does not import from the `git` package — a rename + import update across the expect
class and its 4 platform actuals, no behavior change.

Migration is **one-shot, on first launch after upgrade**, structurally analogous to
existing one-shot migrations (`db/UuidMigration.kt`, `MigrationRunner`) but operating
over `Settings`/`CredentialStore` rather than SQL:

1. On `LlmSettings`/credential-store initialization, check whether
   `VoiceSettings.getAnthropicKey()` (resp. `getOpenAiKey()`) returns non-null **and**
   the corresponding `llm.anthropic.api_key` (resp. `llm.openai.api_key`) entry is
   absent from `CredentialStore`.
2. If so, write the value to `CredentialStore` using a **synchronous** write
   (`commit()`, not Android's asynchronous `apply()`) so the write is durable before
   proceeding.
3. Verify via `retrieve()` that the write landed, then clear the old plaintext
   `VoiceSettings`/`PlatformSettings` key.
4. Record that migration has run (either implicitly, by the old key now being absent,
   or with an explicit `llm.migration.voice_settings_v1_done` marker if idempotency
   needs to be independently verifiable) so re-running the check on every launch is
   cheap and safe.

After migration, `buildLlmFormatterForTags` (`App.kt` L1647-1653) and any direct
`voiceSettings.getAnthropicKey()`/`getOpenAiKey()` call sites read exclusively through
the registry/credential store. **There is no permanent dual-read or plaintext-fallback
path** — once the one-shot migration has run for a given install, `VoiceSettings`'
Anthropic/OpenAI key getters are no longer consulted.

Any write path reachable from provider credential entry must propagate a Keystore/Keychain
init failure as a typed error (`Either<DomainError.CredentialError.SecureStorageUnavailable, Unit>`
in this codebase's idiom) rather than degrading to plaintext — this is `CredentialStore`'s
existing behavior on Android (throws from `by lazy` on Keystore init failure) and must be
preserved, not weakened, when unifying.

## Consequences

**Positive**:
- Closes a live security gap: Desktop and Web currently store LLM API keys in plaintext;
  after migration they use the same AES-256-GCM (JVM) / no-op-with-no-secrets-persisted (WASM)
  backends already proven for git credentials.
- Eliminates the iOS `PlatformSettings` gap as a prerequisite — `CredentialStore` already
  has a full iOS Keychain implementation.
- Provider-typed key schema from day one (namespaced keys) avoids a future migration when
  the generic OpenAI-compatible / Azure provider needs additional non-secret fields.
- No new dependency — reuses an already-audited, already-shipped abstraction.

**Negative / risks**:
- One-time migration code must be written and tested carefully (see pitfalls research
  §1.1): silent data loss if migration only writes forward without verifying, and a
  migrate-then-delete race if the write isn't confirmed durable before the old value is
  cleared. `VoiceSettingsTest` assertions describing the old plaintext-storage behavior
  must be replaced with migration-path tests (seed old location → assert new location
  reads correctly), not simply deleted.
- `VoiceSettings.getUseDeviceLlm()` (an existing, unrelated boolean flag) is out of scope
  for this migration — it stays in `Settings`, since it is not a secret. Its relationship
  to the new per-feature provider-selection model (`LlmSettings.getSelectedProviderId`) is
  a separate decision (see ADR-014 and architecture research §4).
- `CredentialStore`'s relocation from `git/` to `platform/` touches 5 files (expect + 4
  actuals) plus every existing git-credential call site's import — low-risk but must be
  done as a discrete, reviewable step so it doesn't get conflated with the credential
  *migration* logic itself.
- Users upgrading with an existing Anthropic/OpenAI key configured must not experience
  the key silently disappearing (indistinguishable at the type level from "never
  configured") — the migration must complete, and be verified to complete, before any
  code path treats `CredentialStore.retrieve()` returning `null` as "not configured."

## Alternatives Considered

### Leave a permanent compatibility shim (read old plaintext key if new key missing)

Rejected. This leaves a live plaintext-key read/write path in the codebase indefinitely —
exactly the gap this ADR exists to close. Per pitfalls research §1.1/§6.2, "do not keep a
'read old key if new key missing' runtime fallback indefinitely — that's a second live
plaintext-key code path that never goes away." A one-shot migration achieves the same
zero-data-loss guarantee (verified write before delete) without the permanent maintenance
and security liability of a dual-read path.

### Build a brand-new credential abstraction purpose-built for LLM providers

Rejected. `CredentialStore` already solves exactly this problem — a keyed secure string
store with platform-appropriate backends — and is already proven in production for git
credentials (`GraphManager.removeGraph()` already constructs `CredentialStore()` directly
and uses namespaced keys for non-git values, establishing precedent for general-purpose
reuse). Building a second abstraction would duplicate four platform-specific secure-storage
implementations (Keystore, Keychain, PBKDF2+AES-GCM file) for zero capability gain, directly
contradicting the stack research's explicit recommendation not to add a third-party
encrypted-storage library "for no capability gain" — the same reasoning applies even more
strongly to writing a second hand-rolled one.

### Keep `VoiceSettings`/`PlatformSettings` as-is and only fix the Android silent-fallback bug

Rejected. This would leave Desktop and Web credential storage plaintext permanently, which
directly violates the requirements constraint. It also does nothing for the generic
OpenAI-compatible provider's more complex credential shape, and does not close the iOS
`PlatformSettings` gap that would otherwise need to be built as a prerequisite for iOS
voice-settings parity.
