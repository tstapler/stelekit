# Implementation Plan: web-credential-persistence

**Feature**: Replace the wasmJs `CredentialStore` no-op stub with a real, encrypted-at-rest
actual (WebCrypto AES-GCM + `localStorage`), fixing `GitCredentialConnectionStore.getSecret()`
and LLM provider API key persistence on web, and reconciling the plaintext
`persistWebGitCredentials` workaround.
**Date**: 2026-09-21
**Status**: Ready for implementation
**ADRs**: ADR-001 (key material/threat model), ADR-002 (`storeBlocking()` durability signal on wasmJs)

---

## System Type

This is a platform `actual` implementation behind an existing `expect`/`actual` interface
(`CredentialAccess`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialAccess.kt`),
plus call-site reconciliation of one duplicate credential channel (`persistWebGitCredentials`).
It is **not** a new system: `JvmCredentialStore`/`AndroidCredentialStore` already define the
contract's shape and behavior; this project only fills in the missing `wasmJs` actual and
repoints one boot-time consumer (`configResolver` in `browser/Main.kt`) at it.

---

## Domain Glossary
| Term | Definition | Notes |
|------|-----------|-------|
| `CredentialCryptoInterop.kt` | New file: hand-rolled `external`/`js()` bindings over `crypto.subtle`/`crypto.getRandomValues`, mirroring `OpfsInterop.kt`'s idiom. | `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialCryptoInterop.kt` |
| `subtleCryptoAvailable()` | Synchronous feature-detection (`typeof crypto?.subtle !== 'undefined'`), backs `isAvailable()`. | New function in `CredentialCryptoInterop.kt` |
| `randomBytes(length)` | Synchronous wrapper over `crypto.getRandomValues(new Uint8Array(length))`, used for the AES key and each encryption's IV. | New function in `CredentialCryptoInterop.kt`; reuses `OpfsInterop.kt`'s `JsAny.toKotlinByteArray()` for marshalling. |
| `importAesKey(rawBytes)` | Suspend wrapper over `crypto.subtle.importKey('raw', ..., {name:'AES-GCM'}, false, ['encrypt','decrypt'])`. | New function in `CredentialCryptoInterop.kt` |
| `subtleEncrypt`/`subtleDecrypt` | Suspend wrappers over `crypto.subtle.encrypt`/`.decrypt`, `Promise<JsAny>` + `.await()`, matching `OpfsInterop.kt`'s `*Promise()` + `suspend fun` pairing. | New functions in `CredentialCryptoInterop.kt` |
| `CredentialStore.Companion` (cache) | Shared singleton state — `decryptedCache: MutableMap<String,String>`, the imported `CryptoKey` (`JsAny?`), an internally-owned `CoroutineScope`. Lives in the `actual class CredentialStore`'s own `companion object`, not instance state. | Rewritten `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`; mirrors `EphemeralSettingsMode.store`'s reason for being a singleton (§2 of research/architecture.md). |
| `preload()` | Suspend, boot-time-only function: loads-or-generates the AES key from `localStorage["stelekit_credential_key"]`, then decrypts every `credential_enc.*` entry into `decryptedCache`. Must complete before any UI-driven `retrieve()` call. | New method on `CredentialStore.Companion` |
| `CREDENTIAL_KEY_STORAGE_KEY` | `"stelekit_credential_key"` — the `localStorage` key holding the base64-encoded raw AES key bytes. | Constant in `CredentialStore.kt` |
| `CIPHERTEXT_KEY_PREFIX` | `"credential_enc."` — prefix applied to every `CredentialAccess` key before writing ciphertext to `localStorage`, e.g. `credential_enc.git_https_token_abc123`. | Constant in `CredentialStore.kt` |
| `persistCredential(key, value)` | Internal, fire-and-forget suspend function launched from `store()`: encrypts `value` via `subtleEncrypt` and writes `localStorage[CIPHERTEXT_KEY_PREFIX + key]`. | New method on `CredentialStore.Companion` |
| `migrateLegacyGitCredential()` | One-time, boot-local reconciliation function: if `CredentialStore` has no value for `git_https_token_$graphId` and legacy `PlatformSettings().getString("githubToken", "")` is non-blank, copies it into `CredentialStore` and clears the legacy plaintext keys. | New function in `browser/Main.kt` |

---

## Pattern Decisions
| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| `CredentialStore` actual + cache | In-memory read cache + async write-behind (PoEAA-style write-behind cache; mirrors `VaultCredentialStore`'s `cache`/`cryptoLayer` shape) | research/architecture.md §2, research/features.md §1 | (b) widen `CredentialAccess` to `suspend`; (c) synchronous-only plain-`localStorage` obfuscation with no `SubtleCrypto` | (b) is explicitly out of scope, and several callers (Compose `remember{}`, `GraphManager.removeGraph`) structurally cannot suspend regardless of interface shape (architecture.md §1). (c) is a legitimate, much-simpler fallback but ships a visibly weaker, dishonestly-labeled scheme ("AES-GCM" would not be what it does) for no real gain over (a) once you account for the key-storage problem (§3) — kept as documented fallback if (a)'s cache-population race proves too fragile in implementation, not chosen up front. |
| WebCrypto interop layer | Hand-rolled `external`/`js()` Adapter (GoF Adapter), `Promise<JsAny>` + `suspend fun ... .await()` pairing | research/build-vs-buy.md, research/stack.md §1 | `whyoleg/cryptography-kotlin` library | Viable but doesn't remove the sync/async tension (its WebCrypto-backed ops are still `suspend`) — only changes who writes the `external`/`js()` glue. Costs a new dependency for a WebCrypto surface (~6 functions) this codebase has already hand-rolled twice (`OpfsInterop.kt`, `WebLock.kt`) with no third-party library. |
| Cache/key-material singleton | Companion-object Singleton (GoF Singleton, scoped to the class) | research/features.md §1, research/architecture.md §2 | Instance-held cache field on `CredentialStore` | `CredentialStore()` is constructed ad hoc at every call site (`GraphManager.kt:190,564,720`; `App.kt:466,662,916,961,1003,1008`) — an instance-held cache would be empty on every one of those constructions, so `retrieve()` would return `null` for values a *different* instance wrote moments earlier. |
| `persistWebGitCredentials` reconciliation | Strangler Fig (incremental replace, then remove) | research/features.md §3-4, requirements.md Risk Control | Big-bang delete + force re-entry (clean cutover) | Requirements' Risk Control section explicitly sequences "verify new store works, *then* remove the workaround, not before" for rollback safety; features.md §4 shows real, live plaintext data (`githubToken`) exists today that a clean cutover would silently discard, forcing users to re-paste a PAT — the whole point of this project is to stop requiring that. |
| `storeBlocking()` on wasmJs | Explicit override returning honest `false` (matches `AndroidCredentialStore`/`JvmCredentialStore`'s "don't trust the default delegation" precedent) | ADR-002, research/pitfalls.md §4-5 | Default interface delegation (`store()`+`retrieve()` round-trip); a second synchronous-only weaker cipher (XOR) used only for this call path | Default delegation reports `true` the instant the in-memory cache is updated, before the async WebCrypto-encrypt-and-persist has actually landed — a false positive that would let `LlmCredentialMigration` destroy the only plaintext copy of a key on a subsequent flush failure. A synchronous XOR fallback would genuinely allow `true`, but introduces a second, undocumented, weaker crypto scheme to protect a migration path research confirms is currently a no-op on web. |

---

## Tech Debt Disposition
| Area | Existing Issue | Disposition | Justification |
|------|----------------|--------------|----------------|
| `persistWebGitCredentials`'s plaintext-`localStorage` channel | A second, disagreeing credential representation for `HTTPS_TOKEN` git PATs, bypassing `CredentialAccess` entirely (`GitSetupScreen.kt:1554-1568`). | **Isolate via seam, then refactor out (remove last) — but the strangler vine is only partially cut by this project.** | Cannot be deleted first without regressing the PR #239 "BLOCKER" fix it exists to close (requirements.md Risk Control). Epic 1.4 repoints `configResolver` at the new `CredentialStore` (Task 1.4.1a), migrates the one legacy plaintext value (Task 1.4.2a), and removes the writer function and its call sites (Task 1.4.3a) — the isolate-then-remove sequence is itself the rollback safety net. **This project does not, however, remove `configResolver`'s `?: PlatformFileSystem.githubToken` fallback expression or the boot-time `PlatformFileSystem.githubToken` wiring (`Main.kt:163-176`)** — per architecture-review.md, those survive Task 1.4.3a as permanent dead weight. Explicitly deferred to Task 1.4.3b, a follow-up not executed in this pass (see Epic 1.4). |
| `GitCredentialConnectionStore.deleteConnection()` — untested, never wired to any UI, on any platform | Pre-existing gap (confirmed via repo-wide grep in research/ux.md §0) — a saved connection can be created and selected but never deleted from the UI on JVM/Android either, today. | **Explicitly out of scope.** | Not caused by, or specific to, this project — it predates the wasmJs `CredentialStore` fix and affects every platform equally. Making web credentials genuinely durable (this project's core deliverable) makes the gap *more visible* (UX research §3 flags it as a natural follow-on), but closing it is a UI-affordance feature (new delete button + confirmation dialog) sized as its own separately-scoped task, not a 2-5 minute slice of this backend-focused project. Flagged here so it isn't silently forgotten, not silently bundled in. |
| `GitSetupScreen.kt:1246` — "Token is encrypted on disk using device-specific keys" | Currently false on web (no-op store today; plaintext `persistWebGitCredentials` workaround as the closest thing that happens). Per ADR-001, the new scheme is also not "device-specific" (the key is random, not device-derived) — so the string needs to be corrected, not just "made true by association." | **Refactor first (in scope, Epic 1.5) — flagged as a scope addition, not pre-approved scope.** | A full-text search of requirements.md for "1246", "device-specific", "trust signal", and "encrypted on disk" returns zero matches — this fix is **not** named in requirements.md's Scope section. It originates entirely from UX research (research/ux.md §0, §5), which names it the single highest-value trust fix. Including it here is a deliberate planning-phase addition — this project's own actual store change would otherwise leave a newly-false claim on screen — flagged explicitly here (see Epic 1.5) rather than silently bundled as if it had always been in scope. |

---

## Migration Plan

This project has one small, real migration — **not** a DB schema migration, but a one-time
boot-local reconciliation of `persistWebGitCredentials`'s existing plaintext `githubToken`
`PlatformSettings` value into the new encrypted `CredentialStore`.

- **What**: any web user who has ever configured `HTTPS_TOKEN` git sync since
  `persistWebGitCredentials` shipped has a real plaintext token sitting in
  `localStorage["githubToken"]` today (confirmed live in `main`, research/features.md §4).
- **When**: runs once per boot, in `browser/Main.kt`'s normal boot sequence, **after**
  `CredentialStore.preload()` completes (Task 1.2.2a) and **before** `configResolver` is
  built/used (Task 1.4.1a) — same point `ghSettings.getString("githubToken", ...)` is already
  read today (`Main.kt:167`).
- **Idempotency**: check-before-write — if `CredentialStore().retrieve("git_https_token_$graphId")`
  is already non-null, skip; otherwise, if legacy `PlatformSettings().getString("githubToken", "")`
  is non-blank, `store()` it under `git_https_token_$graphId` and then clear the four legacy
  plaintext keys (`githubOwner`/`githubRepo`/`githubBranch`/`githubToken`) via
  `PlatformSettings().putString(key, "")`. No separate "already migrated" flag is needed — the
  check-before-write on the target key itself is naturally idempotent (mirrors
  `LlmCredentialMigration`'s pattern one level simpler, since there's only one legacy source key
  here, not per-provider fan-out).
- **`EphemeralSettingsMode` gating**: the migration function must run after
  `EphemeralSettingsMode.enable()` on the ephemeral boot path (it does — `runEphemeralSession()`
  calls `enable()` first, at `Main.kt:408`) and, like `PlatformSettings`/`CredentialStore`
  themselves, transparently no-ops correctly in that mode: both the read (`PlatformSettings().getString`)
  and write sides already route through `EphemeralSettingsMode.store` per-call, so no
  migration-specific ephemeral check is needed beyond using the same `PlatformSettings`/
  `CredentialStore` instances everywhere else does.
- **Rollback**: no rollback mechanism needed beyond reverting the code — the migration only ever
  *copies* a value (never the sole copy of anything, since the legacy plaintext key remains
  readable by `persistWebGitCredentials`'s own consumers until Task 1.4.3a removes that function).

---

## Observability Plan
- **Logs**: `CredentialStore`'s `store`/`retrieve`/`delete`/`preload`/migration attempts and
  outcomes are logged via `println("[SteleKit] ...")` (matching this codebase's existing wasmJs
  logging convention — see `OpfsInterop.kt`, `Main.kt`) at `warn`-equivalent granularity on
  failure only (key name, not value — e.g. `"[SteleKit] CredentialStore encrypt+persist failed
  for key 'git_https_token_abc123': <reason>"`), mirroring `JvmCredentialStore.logger.warn`'s
  discipline (`JvmCredentialStore.kt:68,91,129`). **Never log the secret value itself.**
- **Metrics**: None — not complexity ≥ 3 per requirements.md's Observability Requirements section.
- **Alerts**: None.

## Risk Control
- **Feature flag**: None — no build-flag system is in play for this project (requirements.md:
  "Not complexity ≥ 3"). The staged rollout below is the risk control instead.
- **Rollback procedure**: Revert the new `CredentialStore` actual to the no-op stub
  (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`, one
  file). `persistWebGitCredentials` continues to cover the `HTTPS_TOKEN` happy path during
  rollback **as long as Task 1.4.3a (its removal) has not yet shipped** — this is why removal is
  sequenced strictly last, after Tasks 1.3.x/1.4.1a/1.4.2a are verified working end-to-end.
- **Staged rollout**: Implementation order *is* the staged rollout — interop layer → cache/actual
  → boot wiring → `configResolver` repoint → legacy migration → **only then** remove
  `persistWebGitCredentials`. Each stage is independently verifiable (tests in Phase 2) before the
  next stage begins.

## Resolved During Planning
- **`storeBlocking()` always-`false` on wasmJs (ADR-002).** requirements.md's success-metric
  wording was corrected during Phase 3 review: wasmJs's honest `false` *is* the accepted, correct
  behavior, not a gap requiring `true` — WebCrypto is unconditionally `Promise`-based and
  Kotlin/Wasm's single-threaded runtime cannot block-wait on a `Promise` inside a non-`suspend`
  function, so a literally durable-before-return synchronous write is not implementable on this
  platform (see requirements.md's Success Metrics section, "Correction during planning," and
  ADR-002). This is no longer an open question — Task 1.3.3a ships the always-`false` design with
  no further sign-off gate.

## Unresolved Questions
- [ ] `GitSetupScreen.kt:1246`'s corrected copy wording (generic "encrypted in this browser" vs.
      a platform-conditional string vs. softening to omit an at-rest claim entirely) — blocks
      Task 1.5.1a — owner: Tyler/UX, per research/ux.md §5's own framing that this is "a
      copy-accuracy question for planning to resolve once the actual encryption scheme is chosen."
- [ ] Whether the trivial `GitConfig.oauthTokenKey` resolution inside `configResolver` (would make
      OAuth-saved connections actually usable for git operations, not just storable/retrievable)
      should be folded into Task 1.4.1a as a one-line bonus, or left strictly out of scope per
      requirements.md — blocks Task 1.4.1a — owner: Tyler. Plan default: leave out of scope (see
      Task 1.4.1a's notes) to avoid conflating "CredentialStore fixed" with "OAuth git sync now
      works end-to-end," per research/architecture.md §5's explicit warning against that conflation.

## Dependency Visualization

```
Epic 1.1 (WebCrypto interop)
        |
        v
Epic 1.2 (cache singleton + preload) ----------------+
        |                                             |
        v                                             v
Epic 1.3 (real CredentialStore actual)      Epic 1.6 (BUG-005 doc fix, independent)
   |        |         |
   v        v         v
1.3.1a   1.3.2a    1.3.3a (ADR-002 resolved during planning, no sign-off gate)
   |
   v
Epic 1.4.1 (repoint configResolver) --> Epic 1.4.2 (legacy migration) --> Epic 1.4.3 (remove persistWebGitCredentials, LAST)
                                                                                  |
                                                                                  v
                                                    Task 1.4.3b (remove configResolver fallback +
                                                    Main.kt:163-176 wiring — deferred, one release
                                                    cycle after 1.4.2a ships, NOT in this pass)
   |
   v
Epic 1.5 (GitSetupScreen.kt:1246 copy fix, needs ADR-001 wording settled)

Phase 2 (tests) depends on: Epic 1.2 + 1.3 (2.1.1a), Epic 1.3 + 1.4.1/1.4.2 (2.1.2a), Epic 1.3 (2.1.3a)
```

---

## Phase 1: Real wasmJs Credential Persistence

### Epic 1.1: WebCrypto Interop Layer
**Goal**: Hand-rolled `external`/`js()` bindings over `crypto.subtle`/`crypto.getRandomValues`,
matching `OpfsInterop.kt`/`WebLock.kt`'s established idiom — no new dependency.

#### Story 1.1.1: `crypto.subtle` bindings
**As a** `CredentialStore` implementer, **I want** suspend-friendly Kotlin wrappers over
`crypto.subtle.importKey`/`.encrypt`/`.decrypt` and a synchronous `crypto.getRandomValues`
wrapper, **so that** the actual class can encrypt/decrypt without any Kotlin/Wasm interop
boilerplate leaking into its business logic.

**Acceptance Criteria**:
- `subtleCryptoAvailable()` returns `true` in a normal browser/karma-headless test environment.
  - *Given* a test running under the project's karma/headless-browser wasmJsTest harness,
    *When* `subtleCryptoAvailable()` is called, *Then* it returns `true` (HTTPS/localhost is a
    secure context, so `crypto.subtle` is defined).
- `randomBytes(32)` returns 32 distinct bytes, and calling it twice returns different values.
  - *Given* no prior state, *When* `randomBytes(32)` is called twice, *Then* the two returned
    `ByteArray`s are not equal (overwhelmingly likely with a CSPRNG).
- `importAesKey`/`subtleEncrypt`/`subtleDecrypt` round-trip a value correctly.
  - *Given* a 32-byte random key imported via `importAesKey`, and plaintext `"ghp_abc123"`
    encoded as UTF-8 bytes, *When* `subtleEncrypt(key, iv, plaintextBytes)` is awaited and the
    result is passed to `subtleDecrypt(key, iv, ciphertextBytes)`, *Then* the decrypted bytes,
    decoded as UTF-8, equal `"ghp_abc123"`.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialCryptoInterop.kt` (new)

##### Task 1.1.1a: Create `CredentialCryptoInterop.kt` (~5 min)
- New file, package `dev.stapler.stelekit.platform.security`. Add:
  - `internal fun subtleCryptoAvailable(): Boolean = js("typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined'")`
  - `private fun randomBytesJs(length: Int): JsAny = js("crypto.getRandomValues(new Uint8Array(length))")`
    and `internal fun randomBytes(length: Int): ByteArray` wrapping it, reusing
    `dev.stapler.stelekit.platform.toKotlinByteArray()` (imported from `OpfsInterop.kt`, same
    module, `internal` visibility) for marshalling.
  - `private fun importAesKeyPromise(rawKey: JsAny): kotlin.js.Promise<JsAny> = js("crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt'])")`
    + `internal suspend fun importAesKey(rawKeyBytes: ByteArray): JsAny` using
    `ByteArray.toJsArrayBuffer()` (reused from `OpfsInterop.kt`) and `.await()`.
  - `private fun subtleEncryptPromise(key: JsAny, iv: JsAny, data: JsAny): kotlin.js.Promise<JsAny> = js("crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, data)")`
    + `internal suspend fun subtleEncrypt(key: JsAny, iv: ByteArray, data: ByteArray): ByteArray`
    (marshals `iv`/`data` to `ArrayBuffer` via `toJsArrayBuffer()`, awaits, marshals result back
    via `toKotlinByteArray()`).
  - Mirror the above for `subtleDecrypt`.
  - Every Promise created here must be `.await()`ed inside a `try`/`catch` at the call site (not
    left detached) — per research/pitfalls.md §2's warning that an unawaited rejected Promise
    surfaces as an uncaught JS console error, not a Kotlin exception.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialCryptoInterop.kt`

---

### Epic 1.2: Boot-Time Credential Cache
**Goal**: A shared singleton cache (companion object on `CredentialStore`) that `preload()`s
itself once at boot, so every ad hoc `CredentialStore()` construction sees the same warm,
synchronously-readable state.

#### Story 1.2.1: Cache scaffold and key bootstrap
**As a** `CredentialStore` consumer (`GitCredentialConnectionStore`, `LlmCredentialStore`),
**I want** `retrieve()` to be backed by an always-warm in-memory cache, **so that** every ad hoc
`CredentialStore()` instance sees data written by any other instance in the same session.

**Acceptance Criteria**:
- `preload()` generates a key on first-ever run and reuses it on subsequent runs.
  - *Given* an empty `localStorage` (`localStorage["stelekit_credential_key"]` unset),
    *When* `CredentialStore.preload()` runs, *Then* `localStorage["stelekit_credential_key"]`
    is set to a non-blank base64 string, and a second `preload()` call in the same session
    reuses the same key bytes rather than regenerating.
- `preload()` decrypts pre-existing ciphertext into the cache.
  - *Given* `localStorage["credential_enc.llm.anthropic.api_key"]` already holds a
    previously-encrypted value (written by an earlier session using the same key), *When*
    `preload()` runs, *Then* `CredentialStore().retrieve("llm.anthropic.api_key")` returns the
    original plaintext synchronously, with no further `preload()` call needed.
- `preload()` degrades gracefully instead of crashing app boot when key generation/import fails.
  - *Given* `crypto.subtle.importKey` throws/rejects (e.g. unavailable `SubtleCrypto`, malformed
    base64 in a corrupted `stelekit_credential_key` value, quota/private-mode error), *When*
    `preload()` runs, *Then* the exception does not propagate out of `preload()` — it is caught,
    logged, `cryptoKey` is left `null`, `decryptedCache` is left empty, and app boot continues
    (`ComposeViewport` still mounts). Every `retrieve()` call for the rest of the session then
    returns `null`, indistinguishable from "nothing saved yet" — not a crash.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

##### Task 1.2.1a: Cache scaffold and constants (~4 min)
- Rewrite `CredentialStore.kt`: `actual class CredentialStore actual constructor() :
  CredentialAccess` stays a thin stateless wrapper. Add `companion object` with:
  `CREDENTIAL_KEY_STORAGE_KEY = "stelekit_credential_key"`, `CIPHERTEXT_KEY_PREFIX =
  "credential_enc."`, `private val decryptedCache = mutableMapOf<String, String>()`,
  `private var cryptoKey: JsAny? = null`,
  `private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)`
  (internally owned, per this repo's coroutine-scope-ownership rule — never a caller-supplied
  `rememberCoroutineScope()`).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

##### Task 1.2.1b: Implement `preload()` (~5 min)
- Add `internal suspend fun preload()` to the companion object. **Wrap the entire body — key
  generation/import (steps 1-2) as well as the ciphertext-enumeration/decrypt loop (steps 3-4) —
  in a single top-level `try { ... } catch (e: Throwable) { ... }`.** This is a regression-prevention
  requirement, not optional hardening: before this project, a no-op `CredentialStore` could never
  fail boot; wrapping only the per-entry decrypt loop (step 4) and leaving key
  generation/import (steps 1-2) unguarded would let a `crypto.subtle.importKey` failure propagate
  uncaught into `Main.kt`'s top-level `CoroutineExceptionHandler`, which only logs — crashing the
  entire app boot (blank page), not just the credentials feature. On any failure caught by the
  outer `try`, log via `println("[SteleKit] CredentialStore: preload failed, credentials
  unavailable this session: ${e.message}")`, leave `cryptoKey = null` and `decryptedCache` empty,
  and return normally (do not rethrow) — this is safe because `isAvailable()` (Task 1.3.2a) only
  checks `subtleCryptoAvailable()`, not `cryptoKey != null`, so the app continues to boot with
  credentials simply unavailable for the rest of the session; every `retrieve()` call returns
  `null`, the same UX as "nothing saved yet," not a crash.
  1. Read `localStorage[CREDENTIAL_KEY_STORAGE_KEY]`; if present, base64-decode to raw key bytes;
     if absent, generate via `randomBytes(32)` and write the base64 encoding back
     (**skip the `localStorage` read/write entirely when `EphemeralSettingsMode.active` —
     generate a fresh in-memory-only key for the session instead**, per Story 1.3.2's gating).
  2. `cryptoKey = importAesKey(rawKeyBytes)`.
  3. Enumerate existing ciphertext entries — **but skip this step entirely when
     `EphemeralSettingsMode.active` is true**: an ephemeral boot generates an in-memory-only key
     and must never read real `localStorage` at all (not even to attempt-and-fail a decrypt),
     matching Story 1.3.1's "ephemeral sessions never touch real `localStorage`" acceptance
     criterion, which this step previously violated by enumerating unconditionally. When not
     ephemeral: `for (i in 0 until localStorage.length) { val k = localStorage.key(i) ?: continue;
     if (k.startsWith(CIPHERTEXT_KEY_PREFIX)) { ... } }` (the standard `org.w3c.dom.Storage` API
     already used by `PlatformSettings.kt` — no new `js()` needed for enumeration).
  4. For each, decode the stored `iv:ciphertext` payload (base64, `:`-joined), call
     `subtleDecrypt`, decode UTF-8, and put into `decryptedCache[k.removePrefix(CIPHERTEXT_KEY_PREFIX)]`.
     On any per-entry decrypt failure, log via `println("[SteleKit] CredentialStore: decrypt
     failed for stored key '$k', skipping (not fatal)")` and continue — never throw, never log
     the raw ciphertext or key material. (This per-entry catch is still needed *inside* the loop
     so one corrupted entry doesn't abort decrypting the rest; the outer `try`/`catch` above is a
     separate, coarser guard against steps 1-2 failing.)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

#### Story 1.2.2: Boot sequence wiring
**As a** SteleKit web user, **I want** my saved credentials to already be resolvable by the time
the app UI mounts, **so that** `LlmProviderRegistryFactory`'s synchronous registry-build read and
`GitSetupScreen`'s field-population reads don't race a cold cache.

**Acceptance Criteria**:
- `preload()` completes before `ComposeViewport` mounts, on both boot paths.
  - *Given* a returning user with a saved `llm.anthropic.api_key` value, *When* the app loads
    (normal boot path), *Then* `LlmProviderRegistryFactory`'s first build (which runs before any
    UI paints) already sees the key as configured — no "flash of unconfigured provider."
  - *Given* a user launches an "open temporarily" (ephemeral) session, *When*
    `runEphemeralSession()` runs, *Then* `CredentialStore.preload()` still runs (as a no-op-ish
    fresh-key generation, since `EphemeralSettingsMode.active` is already `true` by then) before
    `ComposeViewport` mounts.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 1.2.2a: Wire `preload()` into both boot paths (~4 min)
- **MUST be a direct, awaited suspend call on the boot coroutine — `CredentialStore.preload()`
  called and completed via normal sequential suspend execution, NOT wrapped in a detached
  `scope.launch { CredentialStore.preload() }` or any other fire-and-forget dispatch.** Both
  `main()` and `runEphemeralSession()` are already `suspend`-context blocks at the exact insertion
  points below, so a plain `CredentialStore.preload()` call suspends the boot sequence correctly
  with no extra plumbing needed. (Pre-mortem P1: a detached launch here means `preload()` may not
  have finished populating the cache by the time `LlmProviderRegistryFactory`'s first build or
  `GitSetupScreen`'s field-population reads run — both are plain synchronous reads against the
  companion cache, not suspend-aware — producing a hard-to-repro, non-deterministic "my saved
  credential looks empty" regression instead of a clean, consistent one.)
- In `main()`'s normal boot branch: add `CredentialStore.preload()` immediately after the
  existing `ghSettings`/`PlatformFileSystem.github*` wiring block (`Main.kt:163-176`), before
  `configResolver` is defined (`Main.kt:184`).
- In `runEphemeralSession()`: add `CredentialStore.preload()` immediately after
  `EphemeralSettingsMode.enable()` (`Main.kt:408`), before `configResolver` is defined
  (`Main.kt:415`).
- Add `import dev.stapler.stelekit.platform.security.CredentialStore` to `Main.kt`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

---

### Epic 1.3: Real `CredentialStore` Actual
**Goal**: `store`/`retrieve`/`delete`/`isAvailable`/`storeBlocking` genuinely implement
`CredentialAccess` against the warm cache from Epic 1.2, respecting `EphemeralSettingsMode`.

#### Story 1.3.1: Synchronous cache-backed store/retrieve/delete
**As a** `GitCredentialConnectionStore`/`LlmCredentialStore` caller, **I want** `store()` to
return instantly and `retrieve()` to see the value immediately afterward, **so that** the <100ms
NFR budget (requirements.md) is met even though the underlying encrypt+persist is async.

**Acceptance Criteria**:
- Read-after-write within the same tab works instantly.
  - *Given* a fresh `CredentialStore()`, *When* `store("git_https_token_abc123", "ghp_xyz")` is
    called and immediately followed by `retrieve("git_https_token_abc123")` (same call stack, no
    await), *Then* it returns `"ghp_xyz"`.
- `delete()` on a never-set key is a safe no-op.
  - *Given* no value was ever stored under `"git_ssh_passphrase_neverset"`, *When*
    `delete("git_ssh_passphrase_neverset")` is called, *Then* it does not throw (matches
    `GraphManager.removeGraph`'s `try/catch`-wrapped expectation, research/features.md §2).
- The value genuinely persists to `localStorage` in the background.
  - *Given* `store("llm.anthropic.api_key", "sk-ant-abc123")` was called, *When* the background
    `persistCredential` coroutine is awaited to completion (test-only hook), *Then*
    `localStorage["credential_enc.llm.anthropic.api_key"]` is a non-blank ciphertext string that
    does **not** contain the substring `"sk-ant-abc123"`.
- Ephemeral sessions never touch real `localStorage`.
  - *Given* `EphemeralSettingsMode.enable()` has been called, *When*
    `CredentialStore().store("git_https_token_abc123", "ghp_xyz")` runs (including its background
    persist step) to completion, *Then* `localStorage["credential_enc.git_https_token_abc123"]`
    remains unset, and `retrieve("git_https_token_abc123")` still returns `"ghp_xyz"` (served
    from the in-memory-only path).

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

##### Task 1.3.1a: Implement `store`/`retrieve`/`delete` (~5 min)
- `actual override fun retrieve(key: String): String? = Companion.decryptedCache[key]`.
- `actual override fun store(key: String, value: String)`: `decryptedCache[key] = value`
  synchronously, then `scope.launch { persistCredential(key, value) }` (fire-and-forget, per
  `CredentialAccess.kt:34-35`'s own KDoc blessing this).
- `actual override fun delete(key: String)`: `decryptedCache.remove(key)` synchronously, then
  `scope.launch { try { if (!EphemeralSettingsMode.active) localStorage.removeItem(CIPHERTEXT_KEY_PREFIX + key) } catch (e: Throwable) { println("[SteleKit] CredentialStore delete failed for key '$key': ${e.message}") } }`.
- `private suspend fun persistCredential(key: String, value: String)` in the companion object:
  gate on `EphemeralSettingsMode.active` (if active, do nothing — the cache write already
  happened synchronously and that's the full ephemeral contract); otherwise generate a fresh IV
  via `randomBytes(12)`, `subtleEncrypt(cryptoKey!!, iv, value.encodeToByteArray())`, base64-encode
  `iv` and ciphertext joined by `:`, write to `localStorage[CIPHERTEXT_KEY_PREFIX + key]`. Wrap
  the whole body in `try/catch (e: Throwable)`, log-and-swallow on failure (never throw from a
  fire-and-forget coroutine — an uncaught throw here would propagate to the scope's
  unhandled-exception path).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

#### Story 1.3.2: `isAvailable()` and `storeBlocking()` durability signals
**As a** `SyncStatusBadge`/`LlmCredentialMigration` caller, **I want** `isAvailable()` and
`storeBlocking()` to report accurate, non-overclaiming signals, **so that** the UI/migration code
never silently treats a failed operation as a success.

**Acceptance Criteria**:
- `isAvailable()` reflects real `SubtleCrypto` presence.
  - *Given* `subtleCryptoAvailable()` returns `true` (normal browser), *When*
    `CredentialStore().isAvailable()` is called, *Then* it returns `true`.
- `storeBlocking()` never falsely claims durability (ADR-002).
  - *Given* any `key`/`value` pair, e.g. `storeBlocking("llm.openai.api_key", "sk-openai-abc")`,
    *When* the call returns, *Then* it returns `false` — always, regardless of whether the
    in-memory cache write succeeded — per ADR-002's honest-failure decision.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

##### Task 1.3.2a: `isAvailable()` (~3 min)
- `actual override fun isAvailable(): Boolean = subtleCryptoAvailable()`. (Not gated on
  `cryptoKey != null`/preload-completion — per research/pitfalls.md §4, a not-yet-preloaded cache
  should look like "no credential found," not "backend unavailable," since the former is what
  every consumer already handles gracefully via a `null` `retrieve()`.)
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

##### Task 1.3.3a: `storeBlocking()` override (~3 min)
- `override fun storeBlocking(key: String, value: String): Boolean { store(key, value); return false }`
  with KDoc quoting ADR-002's reasoning (WebCrypto is unconditionally `Promise`-based; Kotlin/Wasm
  is single-threaded with no block-on-`Promise` primitive; a non-`suspend` signature cannot
  genuinely verify durability before returning on this platform) — still calls `store()` so the
  value is at least cached and best-effort persisted, honestly reporting `false` rather than a
  possibly-false `true`. This is settled, not conditional: requirements.md's success-metric
  wording was corrected during Phase 3 review to state that wasmJs's honest `false` is the
  accepted, correct behavior (see "Resolved During Planning" above and ADR-002) — no reviewer
  sign-off gate remains on this task.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/security/CredentialStore.kt`

**Known consequence — `LlmCredentialMigration` never completes on wasmJs.** Because
`storeBlocking()` always returns `false` here, `LlmCredentialMigration.migrateKey()`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigration.kt:116-153`) will
never see the `true` it requires before calling `clearPlaintext()` — `runIfNeeded()` (wired
unconditionally at `App.kt:489`, confirmed by architecture-review.md, so it does run on wasmJs)
will retry and log a warning on every single launch, indefinitely, with no path to completion.
This is accepted as a permanent, load-bearing consequence of ADR-002, not a bug to fix here: it
never destroys data (that's the entire point of the always-`false` design — `clearPlaintext()`
simply never runs on web), and it's bounded to a per-launch warn-level log line. Whether any web
user actually has live legacy `VoiceSettings` plaintext data for this to retry against is
**unverified** — `VoiceSettings` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/voice/VoiceSettings.kt`)
reads through the platform-agnostic `Settings` interface, so it is reachable on web in principle,
and architecture-review.md flags research's "no live data at risk" framing (research/features.md
§2 point 4) as an inference, not a confirmed data check. A structural fix (e.g. an
`isDurabilityGuaranteed` capability flag on `CredentialAccess` that lets
`LlmCredentialMigration` mark itself resolved on platforms that can never honestly report `true`)
would require modifying `LlmCredentialMigration.kt` itself — outside this project's scope (it
touches a shared commonMain migration class, not the wasmJs `CredentialStore` actual) and not a
trivial 2-5 minute addition. **Deferred as a follow-up project**, not silently scope-crept into
this one; tracked here so it isn't lost.

---

### Epic 1.4: `persistWebGitCredentials` Reconciliation
**Goal**: Collapse the plaintext `PlatformSettings` git-credential channel into the new
`CredentialStore`, in the Strangler Fig order Risk Control requires: repoint → migrate → remove.

#### Story 1.4.1: Repoint `configResolver` at `CredentialStore`
**As a** web user with a saved `HTTPS_TOKEN` connection, **I want** `configResolver` to resolve
my token from the real encrypted store, **so that** git sync auth no longer depends on the
plaintext `persistWebGitCredentials` side channel.

**Acceptance Criteria**:
- `configResolver` resolves the token via `GitConfig.httpsTokenKey` through `CredentialStore`.
  - *Given* `CredentialStore().retrieve("git_https_token_myGraphId")` returns `"ghp_realtoken"`
    and a `GitConfig(httpsTokenKey = "git_https_token_myGraphId", ...)` is passed in, *When*
    `configResolver(config)` runs, *Then* `GitHostAdapter.resolve` is called with
    `"ghp_realtoken"` as the token — not `PlatformFileSystem.githubToken`.
  - *Given* `CredentialStore().retrieve(config.httpsTokenKey)` returns `null` (nothing migrated
    yet, see Story 1.4.2), *When* `configResolver(config)` runs, *Then* it falls back to
    `PlatformFileSystem.githubToken ?: ""` (unchanged legacy behavior) — no regression during the
    transition window.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 1.4.1a: Repoint the normal-boot `configResolver` (~4 min)
- In `Main.kt`'s `configResolver` lambda (`:184-190`), change the token resolution to:
  `val token = config.httpsTokenKey?.let { CredentialStore().retrieve(it) } ?: PlatformFileSystem.githubToken ?: ""`.
  Leave `GitConfig.oauthTokenKey` unresolved here — see Unresolved Questions (OAuth git-sync
  consumption stays explicitly out of scope per requirements.md, to avoid conflating "storage
  fixed" with "OAuth sync now works end-to-end").
  Apply the identical change in `runEphemeralSession()`'s `configResolver` (`:415-421`).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

#### Story 1.4.2: One-time legacy plaintext migration
**As a** returning web user who configured git sync before this fix shipped, **I want** my
existing plaintext PAT migrated into the encrypted store automatically, **so that** I don't have
to re-paste it.

**Acceptance Criteria**:
- A legacy plaintext token is migrated exactly once.
  - *Given* `localStorage["githubToken"] == "ghp_legacy456"` and
    `CredentialStore().retrieve("git_https_token_default")` is `null`, *When* the boot-time
    migration step runs, *Then* `CredentialStore().retrieve("git_https_token_default")` returns
    `"ghp_legacy456"`, and `localStorage["githubToken"]` is cleared (empty string) afterward.
  - *Given* the migration already ran once (target key now non-null), *When* the app reloads and
    the migration step runs again, *Then* it is a no-op (check-before-write skips it) — no
    duplicate writes, no error.

**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 1.4.2a: Implement `migrateLegacyGitCredential()` (~5 min)
- Add a private suspend function in `Main.kt`:
  ```kotlin
  private suspend fun migrateLegacyGitCredential(graphId: String) {
      val targetKey = "git_https_token_$graphId"
      if (CredentialStore().retrieve(targetKey) != null) return
      val legacy = PlatformSettings().getString("githubToken", "")
      if (legacy.isBlank()) return
      CredentialStore().store(targetKey, legacy)
      val settings = PlatformSettings()
      settings.putString("githubOwner", "")
      settings.putString("githubRepo", "")
      settings.putString("githubBranch", "")
      settings.putString("githubToken", "")
  }
  ```
  Call it from `main()`'s normal boot branch right after `CredentialStore.preload()` (Task
  1.2.2a), passing the boot-time `graphId` local (`Main.kt:155`). Do **not** call it from
  `runEphemeralSession()` — an ephemeral session's `PlatformSettings`/`CredentialStore` reads/
  writes are already routed to the in-memory ephemeral map, so there is no real legacy plaintext
  to migrate there, and running it would be a harmless but pointless no-op.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

#### Story 1.4.3: Remove `persistWebGitCredentials` (sequenced last)
**As a** maintainer, **I want** the plaintext git-credential channel deleted once the real store
is proven, **so that** `GitCredentialConnectionStore`'s registry and the raw settings can no
longer silently disagree (requirements.md Problem Statement item 2).

**Acceptance Criteria**:
- No plaintext git credential channel remains.
  - *Given* Stories 1.4.1 and 1.4.2 are verified working (Phase 2 tests green), *When*
    `persistWebGitCredentials` and its two call sites are removed, *Then* saving a new
    `HTTPS_TOKEN` connection in `GitSetupScreen` still results in `configResolver` resolving the
    token correctly on the next load, via `CredentialStore` alone (no `PlatformSettings`
    `githubToken` write at all).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`,
`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreenCredentialPersistenceTest.kt`

##### Task 1.4.3a: Remove `persistWebGitCredentials` and its call sites (~5 min, run LAST — after Epics 1.1-1.4.2 and Phase 2 are verified)
- Delete `persistWebGitCredentials` (`GitSetupScreen.kt:1554-1568`) and its two call sites
  (`:616`, `:673`, inside the `scope.launch{}` blocks per research/architecture.md §1).
- Update `GitSetupScreenCredentialPersistenceTest.kt`: remove/rewrite the assertions that exercise
  `persistWebGitCredentials` directly; if the file has no remaining test bodies once that's done,
  delete it rather than leaving an empty test class (confirm by reading its current contents
  before editing — this task assumes but does not pre-verify the file's full structure).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`,
  `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreenCredentialPersistenceTest.kt`

##### Task 1.4.3b: Remove the `configResolver` legacy fallback and boot-time wiring (deferred follow-up — **not** part of this implementation pass)
- **What Task 1.4.3a does not clean up**: Task 1.4.1a's `configResolver` change
  (`CredentialStore().retrieve(it) ?: PlatformFileSystem.githubToken ?: ""`) leaves the
  `?: PlatformFileSystem.githubToken` fallback expression in place permanently, and nothing in
  this plan removes the boot-time wiring that populates `PlatformFileSystem.githubToken` from
  `PlatformSettings` (`Main.kt:163-176`). After Task 1.4.3a ships, nothing writes `githubToken`
  again except Task 1.4.2a's one-shot migration (which immediately clears it after copying), so
  the fallback becomes permanent dead weight — a second credential-resolution path a future
  reader still has to reason about, not a fully-cut Strangler Fig vine.
- **Why this is a separate, deferred task rather than folded into Task 1.4.3a**: removing the
  fallback safely requires confirming Task 1.4.2a's migration has actually run for all users with
  legacy data first — that can't be verified within a single implementation pass, only after real
  deployment. Condition to remove: **one release cycle after Task 1.4.2a ships**, once there is no
  remaining evidence (support reports, telemetry, or a direct check) of users still depending on
  the `PlatformFileSystem.githubToken` fallback path.
- **Scope when eventually executed**: delete the `?: PlatformFileSystem.githubToken` fallback in
  both `configResolver` definitions (normal boot and `runEphemeralSession()`), and remove the
  now-dead `PlatformFileSystem.githubOwner`/`githubRepo`/`githubToken` companion-field wiring at
  `Main.kt:163-176`.
- Files (when executed): `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

---

### Epic 1.5: Trust Copy Accuracy
**Goal**: `GitSetupScreen.kt:1246`'s existing security claim becomes true (or is corrected),
closing UX research's single highest-value flagged fix. **Scope note**: this epic is a
planning-phase scope addition, not something requirements.md names — see the Tech Debt
Disposition table above for the full justification and citation check.

#### Story 1.5.1: Correct the encryption claim string
**As a** web user entering a git PAT, **I want** the on-screen claim about encryption to be
accurate for my platform, **so that** the trust signal SteleKit already displays isn't false.

**Acceptance Criteria**:
- The copy no longer claims a "device-specific" binding that doesn't exist on web.
  - *Given* the `authType == HTTPS_TOKEN` branch renders on any platform, *When* a user reads the
    helper text below the token field, *Then* the string does not claim device-specific key
    derivation unless it is actually true for the running platform (JVM: true, per
    `JvmCredentialStore`'s `user.name`+`os.name` derivation; web: not true, per ADR-001's random
    key).

**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

##### Task 1.5.1a: Fix `GitSetupScreen.kt:1246`'s copy (~4 min, resolve Unresolved Question first)
- Replace `"Token is encrypted on disk using device-specific keys. For stronger protection, use
  SSH key auth."` with wording accurate on every platform without a per-platform branch — e.g.
  `"Token is encrypted at rest. For stronger protection, use SSH key auth."` (drops the
  "device-specific" claim that's only true on JVM, stays true on web per ADR-001's "encrypted at
  rest" framing without overclaiming the key-derivation mechanism). If a platform-conditional
  string is preferred instead (see Unresolved Questions), branch on `expect`/`actual` platform
  detection already available elsewhere in this file.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt`

---

### Epic 1.6: Documentation Correction
**Goal**: `docs/bugs/fixed/BUG-005-...md`'s §5 no longer claims something false.

#### Story 1.6.1: Correct BUG-005 §5
**As a** future reader of `docs/bugs/fixed/`, **I want** the doc to accurately describe what
shipped, **so that** I don't trust a closed-bug claim that was never actually true.

**Acceptance Criteria**:
- §5's claim matches reality.
  - *Given* this project ships the real `CredentialStore` actual, *When* §5 ("Fix Approach (as
    shipped)", item 5) is read, *Then* it correctly attributes PAT persistence to
    *this* project (with a reference/date), not to the original `bb063efe`/PR #239 fix.

**Files**: `docs/bugs/fixed/BUG-005-wasm-git-manager-credential-store-stubs.md`

##### Task 1.6.1a: Correct §5 (~3 min)
- Replace item 5's current text ("the `wasmJs` actual ... persists PATs, closing the second half
  of this bug's title") with a corrected statement: PR #239 left `CredentialStore` as a no-op
  stub; the wasmJs actual was implemented separately by the `web-credential-persistence` project
  (link `project_plans/web-credential-persistence/`), which is what actually closes the second
  half of this bug's title.
- Files: `docs/bugs/fixed/BUG-005-wasm-git-manager-credential-store-stubs.md`

---

## Phase 2: Test Coverage

### Epic 2.1: `wasmJsTest` Suite
**Goal**: Prove persist-across-reload, `EphemeralSettingsMode` non-leakage, and every consumer
integration point named in requirements.md's Success Metrics — not by inspection.

#### Story 2.1.1: Core `CredentialStore` behavior
**As a** maintainer, **I want** automated proof that `CredentialStore` persists across a
simulated reload and never leaks during an ephemeral session, **so that** this isn't just
"verified by reading the code."

**Acceptance Criteria**:
- Persist-across-reload.
  - *Given* `CredentialStore().store("git_https_token_abc123", "ghp_xyz")` was called and its
    background persist awaited to completion, *When* the in-memory cache is cleared and
    `CredentialStore.preload()` is re-run (simulating a fresh page load against the same
    `localStorage`), *Then* `CredentialStore().retrieve("git_https_token_abc123")` returns
    `"ghp_xyz"`.
- Ephemeral non-leakage.
  - *Given* `EphemeralSettingsMode.enable()` was called, *When* `CredentialStore().store("llm.anthropic.api_key", "sk-ant-secret")` runs to completion, *Then* `localStorage["credential_enc.llm.anthropic.api_key"]` is never set.
- `storeBlocking()` honesty.
  - *Given* any key/value pair, *When* `storeBlocking(...)` is called, *Then* it returns `false`.
- Decrypt-failure graceful fallback.
  - *Given* `localStorage["credential_enc.git_ssh_passphrase_corrupt"]` holds a malformed/
    non-decryptable payload, *When* `CredentialStore.preload()` runs, *Then* it does not throw,
    and `CredentialStore().retrieve("git_ssh_passphrase_corrupt")` returns `null` (treated as
    "not found," not "error").

**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/security/CredentialStoreTest.kt` (new)

##### Task 2.1.1a: Write `CredentialStoreTest.kt` (~5 min)
- New file covering the four Given-When-Then cases above. Needs a test-only way to reset the
  companion cache/`cryptoKey` between test cases (add an `internal fun resetForTest()` to the
  companion object, gated with a comment that it's test-only). **`resetForTest()` and
  `flushForTest()` are new, project-specific test hooks — there is no existing precedent to
  follow.** (A prior draft of this plan cited `HostDirectorySyncConstructionTest.kt` as an
  existing companion-object-reset pattern; that was checked and is wrong — that file uses a
  fully-injected fake, `HostDirectorySync.CacheAccess`, not a singleton reset, and a repo-wide
  grep for `resetForTest`/`fun reset(` across `kmp/src/wasmJsTest` and `kmp/src/wasmJsMain`
  returns zero hits.) `resetForTest()` must clear **both** the in-memory state (`decryptedCache`,
  `cryptoKey`) **and** the real `localStorage` entries it wrote (`stelekit_credential_key`, every
  `credential_enc.*` key) — since the companion object is a process-wide singleton, its state
  (including whatever it persisted to `localStorage`) otherwise leaks across test cases within the
  same test file/suite run, and this file plus `GitCredentialConnectionStoreWasmJsTest.kt`
  (Task 2.1.2a) and `LlmCredentialStoreWasmJsTest.kt` (Task 2.1.3a) all exercise the same shared
  companion cache and real `localStorage` in the same browser session. Also expose a way to await
  the background persist coroutine (`internal suspend fun
  flushForTest()` that joins all in-flight persist jobs, analogous to `PlatformFileSystem`'s
  `flushPendingWrites()`).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/security/CredentialStoreTest.kt`

#### Story 2.1.2: `GitCredentialConnectionStore` end-to-end reuse
**As a** maintainer, **I want** proof that the "reuse a saved connection" flow works end-to-end
on web, **so that** requirements.md's success metric #2 is actually verified.

**Acceptance Criteria**:
- A saved connection's secret round-trips.
  - *Given* `GitCredentialConnectionStore.saveConnection(host = "github.com", accountLabel =
    "octocat", authType = GitAuthType.HTTPS_TOKEN, secret = "ghp_reuseme", createdAt = 1L)` was
    called (backed by the real wasmJs `CredentialStore`, not a fake), *When*
    `getSecret(connection)` is called for the returned connection on a second graph's
    `GitCredentialConnectionStore` instance (same browser session), *Then* it returns
    `"ghp_reuseme"`.

**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/GitCredentialConnectionStoreWasmJsTest.kt` (new)

##### Task 2.1.2a: Write `GitCredentialConnectionStoreWasmJsTest.kt` (~5 min)
- New file, constructs `GitCredentialConnectionStore(PlatformSettings(), CredentialStore())`
  (real wasmJs backends), exercises the Given-When-Then above plus a `deleteConnection` round-trip
  (save → delete → `getSecret` returns `null`), confirming Task 1.3.1a's delete path works for
  this consumer specifically (closing the loop on requirements.md's Problem Statement item 1).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/GitCredentialConnectionStoreWasmJsTest.kt`

#### Story 2.1.3: `LlmCredentialStore` persistence
**As a** maintainer, **I want** proof that LLM provider API keys persist on web via the real
store, **so that** requirements.md's success metric #3 is actually verified.

**Acceptance Criteria**:
- An LLM API key round-trips through the real store.
  - *Given* `LlmCredentialStore(CredentialStore()).setApiKey("anthropic", "sk-ant-realkey")` was
    called and its background persist awaited, *When* a fresh
    `LlmCredentialStore(CredentialStore())` instance calls `getApiKey("anthropic")`, *Then* it
    returns `"sk-ant-realkey"`.

**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/llm/LlmCredentialStoreWasmJsTest.kt` (new)

##### Task 2.1.3a: Write `LlmCredentialStoreWasmJsTest.kt` (~4 min)
- New file, exercises the Given-When-Then above using `flushForTest()` (from Task 2.1.1a) to
  await the background persist before asserting.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/llm/LlmCredentialStoreWasmJsTest.kt`
