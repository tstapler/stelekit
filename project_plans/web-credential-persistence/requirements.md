# Requirements: web-credential-persistence

**Date**: 2026-09-21
**Type**: bug fix (with a small feature component — real encrypted storage where none exists today)
**Complexity**: 2 — focused feature

## Correction to the triage source item

The backlog item (`2f9f7b89-90bc-40ed-ba48-471e71832573`, title "git features unavailable on
web — GitManager and CredentialStore are stubs") describes the pre-fix state of **both**
`GitManager` and `CredentialStore` on `wasmJs`. Investigation of the current `main` branch
(this worktree, `62c1bc25`, v0.87.0) found:

- **`GitManager`/`JsGitManager` (`kmp/src/wasmJsMain/.../platform/GitManager.kt`) is already
  fully implemented and shipped.** `bb063efe` ("feat(git): implement WASM git write-back for
  web sync (closes BUG-005 Phase 2)", PR #239) delivered `WasmGitWriteService` (1222 lines —
  GitHub Git Data API 5-step sequence, GitLab single-call commit API, conflict detection,
  dirty-file tracking) and `WasmGitRepository` (274 lines, the real `GitRepository` actual per
  `ADR-017`), both under test (`WasmGitWriteServiceMockedIntegrationTest`,
  `WasmGitWriteServiceLiveTest`, `WasmGitRepositoryTest`, `GitHostAdapterTest`). `commit`/`push`/
  `pull`/`status` are real operations against GitHub/GitLab REST APIs, not stubs. This part of
  the backlog item is **stale — already fixed, no further action needed.**
- **`CredentialStore` (`kmp/src/wasmJsMain/.../platform/security/CredentialStore.kt`) is
  genuinely still a no-op stub** — confirmed by reading the file directly (`store`/`delete` do
  nothing, `retrieve` always returns `null`) and by `git log` showing its only commit
  (`a13cb7ea`) is a pure file-move, never an implementation. This half of the backlog item is
  **still accurate and is the real remaining scope of this project.**
- `docs/bugs/fixed/BUG-005-wasm-git-manager-credential-store-stubs.md` **incorrectly claims**
  (§"Fix Approach (as shipped)", item 5) that "the `wasmJs` actual ... persists PATs, closing the
  second half of this bug's title." This is false against the code as it stands today. Correcting
  that doc is in scope (see `docs` task below) — closing a bug that wasn't actually closed is
  itself a data-integrity problem for anyone trusting `docs/bugs/fixed/`.

## Problem Statement

Web (`wasmJs`) users have no real secret storage. `CredentialStore`'s no-op actual means:

1. **`GitCredentialConnectionStore.getSecret()` always returns `null` on web** — the "reuse a
   saved git connection across graphs" UI (`GitSetupScreen`, backed by
   `GitCredentialConnectionStore`) lets a user select a previously-saved connection, but the
   secret behind it can never actually be retrieved. Confirmed by reading
   `GitCredentialConnectionStore.kt:60` (`credentialStore.retrieve(...)`) against the wasmJs
   stub.
2. **A parallel, undocumented-to-users workaround exists and has its own gaps.**
   `GitSetupScreen.kt`'s `persistWebGitCredentials()` (added post-PR-#239 as a "BLOCKER"
   regression fix, per its own doc comment) writes the git PAT directly into `PlatformSettings`
   (`localStorage`, **plaintext**, keys `githubOwner`/`githubRepo`/`githubBranch`/`githubToken`)
   instead of through `CredentialStore`, specifically *because* `CredentialStore` is a no-op on
   web. This unblocks the git-sync happy path for `HTTPS_TOKEN` auth only, but:
   - Stores the PAT **unencrypted** in `localStorage` — every other platform (JVM: AES-256-GCM
     with a PBKDF2-derived key; Android: `EncryptedSharedPreferences`) encrypts at rest. Web is
     the only platform where a saved git PAT is plaintext-readable via devtools or any XSS.
   - Only takes effect after a page reload (`Main.kt` reads `PlatformSettings` once at startup)
     — a documented rough edge in the function's own KDoc.
   - Does not cover `GitAuthType.GITHUB_OAUTH` (device-flow token) at all — explicitly flagged
     as a known, unaddressed follow-up in the same KDoc.
   - Bypasses `GitCredentialConnectionStore` entirely, so the "reusable connection" list and the
     direct-`PlatformSettings` path can silently disagree about what credential is actually live.
3. **LLM provider API keys never persist on web at all.** `LlmCredentialStore` wraps the same
   `CredentialAccess` interface (`llm.<providerId>.api_key` keys) with no web-specific
   workaround — unlike git, nothing routes around the no-op store for this case. Every web
   session requires re-entering LLM API keys (confirmed: `LlmCredentialStore` has no wasmJs
   branch or override; it always calls through the injected `CredentialAccess`, which on web is
   the no-op `CredentialStore`).

## Baseline

Today, a web user who wants persistent git or LLM credentials gets one of three outcomes
depending on the surface:
- Git PAT (`HTTPS_TOKEN`) entered via `GitSetupScreen`: persists, but in plaintext, and only
  takes effect next reload.
- Git PAT (`GITHUB_OAUTH`) or a "reuse saved connection" selection: silently does not persist /
  does not resolve (secret is `null`).
- LLM provider API key: never persists; re-entry required every session.

No user-visible error explains any of this — each surface just behaves as if credential entry
"didn't take" on next load, or exposes a saved-looking connection that doesn't actually work.

## Users / Consumers

Web (`wasmJs`) target users of SteleKit who configure git sync (`GitSetupScreen`) and/or an LLM
provider (`LlmProviderSettings`/`SettingsDialog`). Desktop (JVM) and Android are unaffected —
both already have real encrypted `CredentialStore` actuals.

## Success Metrics

- A real `CredentialStore` actual exists for `wasmJs`: `store`/`retrieve`/`delete` genuinely
  persist across a page reload, encrypted at rest (not plaintext), matching the security bar the
  JVM/Android actuals already set (`isAvailable() == true`, values unreadable by casually
  inspecting browser storage in devtools).
- `GitCredentialConnectionStore.getSecret()` returns the correct secret on web after a reload —
  the "reuse a saved connection" flow works end-to-end, verified by a `wasmJsTest`.
- LLM provider API keys entered via `LlmProviderSettings` on web persist across a reload,
  verified by a `wasmJsTest` exercising `LlmCredentialStore` against the real wasmJs
  `CredentialStore` actual (not a fake).
- `persistWebGitCredentials`'s plaintext-`PlatformSettings` workaround is either removed (now
  that `CredentialStore` genuinely works) or explicitly justified as a distinct
  fast-path-for-`configResolver`-only concern if planning finds a reason it must stay — not left
  as an undocumented duplicate of the new mechanism.
- `docs/bugs/fixed/BUG-005-wasm-git-manager-credential-store-stubs.md`'s §5 is corrected to
  match reality (or a new bug/ADR entry supersedes it) once this ships.
- `CredentialAccess.storeBlocking()` on wasmJs makes an honest, technically-grounded durability
  claim. **Correction during planning** (found by Phase 3's architecture review, confirmed by
  research/architecture.md §2 and ADR-002): the original wording here — "now reports `true`...
  instead of `false`" — assumed durability could be verified before returning, the same as
  JVM/Android. It cannot: WebCrypto's `encrypt`/`decrypt`/`importKey` are unconditionally
  `Promise`-based, and Kotlin/Wasm's single-threaded runtime has no mechanism to block-wait on a
  `Promise` inside a non-`suspend` function — this is a platform constraint, not an
  implementation gap. The corrected success criterion: `storeBlocking()` on wasmJs always
  returns `false` (an honest "cannot verify" signal, never a false-positive `true`), documented
  in ADR-002, so `LlmCredentialMigration` (the one caller that treats `storeBlocking() == true`
  as a license to delete the sole plaintext copy of a legacy key) never mistakes an unverified
  write for a durable one. This is a real, accepted platform asymmetry versus JVM/Android
  (matching, not regressing, the honesty bar `CredentialAccess.storeBlocking`'s own KDoc already
  sets: "correctly reports `false` for a no-op/non-persisting backend... rather than falsely
  claiming durability"), not a defect to keep chasing.

## Appetite
Medium (1–2 weeks)
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

## Constraints

- Must implement the `expect`/`actual` `CredentialStore` contract
  (`kmp/src/commonMain/.../platform/security/CredentialStore.kt`) without changing its shape —
  `store(key, value)`, `retrieve(key)`, `delete(key)`, `isAvailable()`, `storeBlocking(key,
  value)` — since `CredentialAccess` is consumed generically by `GitCredentialConnectionStore`,
  `LlmCredentialStore`, and `VaultCredentialStore.migrateFrom()` across all platforms.
- No system keychain exists in a browser sandbox — the realistic ceiling here matches JVM's own
  documented threat model ("protects against casual snooping but not a determined local
  attacker"), not Android's OS-backed `EncryptedSharedPreferences` guarantee. State this
  explicitly rather than overclaiming security.
- Browser storage (`localStorage`/`IndexedDB`) is synchronous-or-async per API — `store`/
  `retrieve`/`delete` in the `CredentialAccess` interface are synchronous, non-suspend functions
  today (confirmed: no `suspend` modifier). Any storage backend chosen must fit that synchronous
  contract, or planning must address the mismatch explicitly (e.g. an in-memory cache backed by
  async-flushed durable storage, mirroring how `VaultCredentialStore` already keeps an in-memory
  `cache` synced from an async-capable backing file).
- Must not regress `EphemeralSettingsMode` (`PlatformSettings.kt`) — an "open temporarily" web
  session must not leak real credentials into permanent storage. If the new `CredentialStore`
  backend shares a storage layer with `PlatformSettings` (e.g. `localStorage`), it must respect
  the same ephemeral-mode gate.
- Must not weaken `AndroidCredentialStore`'s and `JvmCredentialStore`'s existing guarantees —
  this project only touches `wasmJsMain`.
- `WasmGitWriteService`/`WasmGitRepository`/`GitManager.kt` (already fixed, see Correction above)
  are explicitly **out of scope for changes** beyond wiring `configResolver` to read from the new
  `CredentialStore` if that turns out to be the right fix for `persistWebGitCredentials`'s
  duplication (a planning-phase decision, not pre-decided here).

## Non-functional Requirements
- **Performance SLO**: Credential read/write is on the critical path of interactive UI actions
  (saving git setup, saving an LLM key) — must complete well under 100ms; no network round-trip
  should be required for `store`/`retrieve`/`delete` themselves.
- **Scalability**: Not applicable — credential count per user is small (a handful of connections/
  keys), no pagination or bulk-read concern.
- **Security classification**: Internal/local, but sensitivity-equivalent to the JVM/Android
  `CredentialStore` actuals — this handles git PATs and LLM provider API keys, both
  bearer-token-equivalent secrets. Must never log values (mirrors the existing JVM/Android
  actuals' logging discipline — see `JvmCredentialStore`'s warn-log call sites, none of which
  include the secret itself).
- **Data residency**: Not applicable — client-local browser storage only, no new network
  destination.

## Scope
### In Scope
- A real `wasmJs` `CredentialStore` actual implementing `CredentialAccess`, encrypted at rest
  using a browser-available primitive (planning/research to select — e.g. WebCrypto
  `SubtleCrypto` AES-GCM with a device-bound or session-derived key, mirroring `JvmCredentialStore`'s
  PBKDF2-derived-key pattern) backed by durable browser storage (`localStorage` or `IndexedDB` —
  planning/research to select, given the sync-API constraint above).
- Respecting `EphemeralSettingsMode` the same way `PlatformSettings.kt` already does, if the
  chosen backend shares storage with it.
- Fixing `GitCredentialConnectionStore.getSecret()` on web as a consequence of the above (no
  code change needed there if `CredentialAccess`'s contract is honored correctly by the new
  actual — verify with a test, don't assume).
- Wiring LLM provider API key persistence on web (`LlmCredentialStore` needs no code change if
  the new actual is correct — verify with a test).
- A `storeBlocking()` override (or confirmation the default is now correct) so
  `LlmCredentialMigration`'s durability contract holds on web.
- Reconciling `persistWebGitCredentials()`'s plaintext-`PlatformSettings` workaround with the new
  real `CredentialStore` — remove the duplication if `configResolver` can be pointed at the new
  store instead, or document why both must coexist.
- Correcting `docs/bugs/fixed/BUG-005-wasm-git-manager-credential-store-stubs.md`'s inaccurate
  §5 claim.
- Test coverage: `wasmJsTest` (or `commonTest` for any newly-extracted pure logic) proving
  persist-across-reload behavior, `EphemeralSettingsMode` non-leakage, and the
  `GitCredentialConnectionStore`/`LlmCredentialStore` integration points above.

### Out of Scope
- Any change to `WasmGitWriteService`, `WasmGitRepository`, or the GitHub/GitLab REST write-back
  mechanics — already shipped and working (see Correction above).
- `GitAuthType.GITHUB_OAUTH` device-flow token persistence — this project only needs
  `CredentialStore` itself to work; whether `GitSetupScreen`'s OAuth flow calls it is a
  pre-existing, separately-tracked gap (per `persistWebGitCredentials`'s own KDoc) and is not
  re-opened here unless trivially covered by the same fix.
- Changing `CredentialAccess`'s interface shape (e.g. making it `suspend`) across all platforms —
  a cross-platform interface change is a larger, separately-scoped migration if research
  concludes the sync-API constraint truly can't be satisfied otherwise.
- OS-keychain-equivalent security guarantees for web — not achievable in a browser sandbox; do
  not scope-creep into WebAuthn/passkey-backed schemes.
- Multi-tab/multi-origin credential sync — single-browser-profile `localStorage`/`IndexedDB`
  scoping is sufficient, matching every other browser-storage mechanism already in this codebase
  (`PlatformSettings`, OPFS).

## Rabbit Holes
- **Key material for at-rest encryption**: unlike JVM (machine-bound entropy: username + OS
  name) there is no durable, non-guessable per-device identifier readily available in a browser
  sandbox that survives a cache clear the same way a `~/.config` file does. A naive
  `crypto.getRandomValues()`-generated key stored alongside the ciphertext in the same
  `localStorage` origin provides no real protection (attacker with `localStorage` read access
  gets both). Research must resolve what threat this realistically defends against (XSS is out
  of reach regardless; the goal is parity with "don't leave PATs in cleartext for casual
  devtools/backup inspection," not a hardened secret store) before implementation starts.
- **Sync interface vs. async browser APIs**: if research recommends `IndexedDB` (properly async)
  over synchronous `localStorage`, the `store`/`retrieve`/`delete` signatures being non-suspend
  becomes a real blocker requiring either an in-memory-cache-plus-async-flush pattern (like
  `VaultCredentialStore`) or accepting `localStorage`'s sync-but-more-limited API. Don't let this
  get decided implicitly mid-implementation.
- **`persistWebGitCredentials` reconciliation**: removing it outright could regress the BLOCKER
  fix it was added for if the new `CredentialStore` doesn't correctly wire into
  `configResolver`'s startup-read timing (`Main.kt` reads `PlatformSettings` once at startup —
  the new store may have the same "needs reload" characteristic, or may not). Verify actual
  behavior, don't assume parity.

## Alternatives Considered
- **Do nothing / leave as-is**: rejected — silently-broken "reuse connection" UI and
  never-persisting LLM keys are real, user-visible defects with a known root cause and a clear
  fix pattern (every other platform already has one).
- **Route everything through `persistWebGitCredentials`'s plaintext pattern instead of fixing
  `CredentialStore`**: rejected — worse security posture than the fix, and doesn't address the
  LLM-key gap at all (that surface has no equivalent workaround today).

## Feasibility Risks
- WebCrypto `SubtleCrypto` API availability/behavior across target browsers needs confirming
  (research phase) — expected to be universally available in evergreen browsers but must be
  verified for whatever minimum-supported-browser bar this project already targets.
- `localStorage` has a per-origin size cap (typically ~5-10MB) — not a real risk given credential
  volume, but worth a one-line confirmation in research rather than an assumption.

## Observability Requirements
Not complexity ≥ 3 — standard logging discipline (no new alerting). Log store/retrieve/delete
*attempts and outcomes* the same way `JvmCredentialStore` does (`logger.warn` on failure), never
logging the secret value itself.

## Risk Control
Not complexity ≥ 3, but stated for completeness: this is additive (a real actual replacing a
no-op one) — no migration of existing data is needed since the no-op backend never stored
anything to migrate. Rollback = revert the new `CredentialStore` actual to the no-op stub;
`persistWebGitCredentials`'s plaintext path continues to cover the `HTTPS_TOKEN` happy path
during rollback if the reconciliation step (see Scope) hasn't removed it yet — sequence that
removal last, after the new store is verified working, precisely so rollback stays safe.

## Open Questions
- Exact storage backend and at-rest encryption scheme (`localStorage` + WebCrypto AES-GCM vs.
  `IndexedDB` vs. another pattern already used elsewhere in `kmp/src/wasmJsMain`) — for research.
- Whether `persistWebGitCredentials` should be deleted outright or kept as a documented
  `configResolver`-specific fast path — for planning, after research clarifies the new store's
  reload-timing behavior.
- Whether `GITHUB_OAUTH` token persistence should be folded into this project's test coverage
  even though it's declared out of scope for new functionality (i.e., does fixing
  `CredentialStore` alone accidentally also fix it) — for research to check, not to build against.
