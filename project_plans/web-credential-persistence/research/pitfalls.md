# Research: Pitfalls — web-credential-persistence

Agent 4 of SDD Phase 2 research fan-out. Scope: what commonly goes wrong building an
encrypted-at-rest `CredentialStore` for `wasmJs`, and what this project should explicitly design
against.

## 1. Client-side "encryption" theater

**The pitfall.** Encrypting a secret with a key that is itself readable from the same
browser-storage origin (localStorage, IndexedDB, or derived synchronously from browser-only
state like `navigator.userAgent`) defends against nothing an attacker capable of reading that
storage couldn't already do — the ciphertext and the key are co-located, so decryption is a
one-liner away. It is real, well-documented UX/security anti-pattern: "LocalStorage is not
effective against XSS attacks... JS that an attacker injects via XSS runs on the same origin as
the rest of the browser application code," and "any script that runs in your origin can read
localStorage... one missed output-escape, compromised NPM dependency, or misconfigured
third-party and your tokens are gone." ([Why localStorage Is Unsafe for Tokens and Secrets](https://www.trevorlasn.com/blog/the-problem-with-local-storage),
[Auth0: Secure Browser Storage, The Facts](https://auth0.com/blog/secure-browser-storage-the-facts/))
A GitHub issue against a trading-bot project makes the exact false-confidence complaint this
project must avoid: its title states plainly that the project "overstates security of Web
LocalStorage as a way to 'protect secrets'"
([fintechees/Expert-Advisor-Studio#14](https://github.com/fintechees/Expert-Advisor-Studio/issues/14)).

**What actually changes the threat model (partially).** WebCrypto's non-extractable key flag
is a real, narrower mitigation, not theater — if a `CryptoKey` is generated with
`extractable: false` and persisted as a key *handle* in IndexedDB (the spec's documented
pattern — "developers will use the IndexedDB API, storing CryptoKey objects against some key
string identifier"), raw key bytes never exist in JS-readable form; a dump of IndexedDB records
(devtools export, disk forensics, a browser-profile backup/sync leak, a malicious extension
that only has storage-read APIs rather than page-script execution) yields an opaque handle, not
a key. ([MDN SubtleCrypto](https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto),
[gist: Saving Web Crypto Keys using indexedDB](https://gist.github.com/saulshanabrook/b74984677bccd08b028b30d9968623f5))
**But this does not defend against live XSS in the same page** — injected script running in-page
can still call `crypto.subtle.decrypt()` using the same non-extractable key handle, because
non-extractability only blocks *exporting* the key, not *using* it from the same origin. State
this distinction precisely rather than either overclaiming ("your secrets are encrypted, safe
from attackers") or underclaiming (treating non-extractable keys as pointless because XSS still
works).

**What this project's docs/comments should say**, mirroring `JvmCredentialStore.kt`'s existing
self-disclaimer style (`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/security/JvmCredentialStore.kt:28-31`,
"protects against casual snooping but not against a determined local attacker"):

> Web (wasmJs) credentials are encrypted at rest using a non-extractable WebCrypto key stored in
> IndexedDB. This defends against casual inspection of exported storage dumps, browser-profile
> backups, or a storage-read-only malicious extension — it does **not** defend against an active
> XSS vulnerability in this app's own page, which runs with the same origin privileges as this
> code and can call the same decrypt APIs. There is no browser-sandbox equivalent to an OS
> keychain; this is not a hardened secret store.

This directly matches the requirements doc's own Rabbit Holes section framing ("the goal is
parity with 'don't leave PATs in cleartext for casual devtools/backup inspection,' not a
hardened secret store" — `project_plans/web-credential-persistence/requirements.md:201-202`) and
should be stated in the wasmJs `CredentialStore`'s class KDoc, not just in this research doc,
so the disclaimer travels with the code the way JVM's already does.

## 2. WebCrypto `SubtleCrypto` pitfalls

- **Secure-context requirement.** `crypto.subtle` is only defined in a secure context (HTTPS or
  `localhost`) — in an insecure context `window.crypto.subtle` is `undefined`, and calling into it
  throws a plain JS `TypeError`, not a domain-specific error. Confirmed this project's wasmJs
  deployment target is always HTTPS: `.github/workflows/pages.yml` deploys to GitHub Pages
  (`README.md:98` references `https://tstapler.github.io/stelekit/...`), which serves everything
  over HTTPS — so `crypto.subtle` availability is not a *deployment* risk here. It **is** a local
  dev-server risk if anyone ever runs the wasmJs dev build over plain `http://<lan-ip>` instead of
  `localhost` — worth a defensive `isAvailable()` check (`typeof crypto?.subtle !== 'undefined'`)
  rather than assuming presence, since `CredentialAccess.isAvailable()` already exists as the
  contract's designated "backend temporarily unusable" signal
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialAccess.kt:22`).
- **Non-extractable-key generation/import quirks.** Some browsers historically rejected certain
  algorithm/usage combinations for non-extractable keys inconsistently (Node's `webcrypto`
  implementation, e.g., has open issues specifically about non-extractable key storage —
  [nodejs/webcrypto#13](https://github.com/nodejs/webcrypto/issues/13)) — test key
  generation/import against real Chrome/Firefox/Safari, not just assume MDN's documented shape
  works everywhere identically.
- **Kotlin/Wasm↔JS Promise interop.** This codebase already has an established hand-rolled
  pattern for exactly this class of interop in
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` — `js("new
  Promise(...)")` returning `kotlin.js.Promise<JsAny>`, consumed via `kotlinx.coroutines.await`
  (`HostDirectoryInterop.kt:51-92`), explicitly commented as "matching OpfsInterop.kt's
  established idiom." A `crypto.subtle.encrypt/decrypt/generateKey` wrapper should follow the
  same idiom rather than inventing a new one. The known failure mode: if a `.then()`/`.await()`
  chain is missing a `.catch` equivalent (i.e., a rejected Promise is never awaited/observed —
  e.g. a fire-and-forget `store()` that kicks off the encrypt+write Promise chain without
  awaiting or attaching an error handler), the rejection surfaces as an **uncaught JS
  exception / unhandled promise rejection in the browser console**, not as a Kotlin exception or
  an `Either.Left` the caller can observe — silently breaking the "never throws" contract
  `CredentialAccess.storeBlocking`'s KDoc requires ("Never throws for a failed write — callers
  must check the return value",
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialAccess.kt:38`).
  Kotlin/Wasm's official interop guidance confirms this is a live, only-partially-solved area:
  unhandled rejections need explicit `window.addEventListener("unhandledrejection", ...)` wiring
  to be observable from Kotlin at all
  ([Kotlin docs: Interoperability with JavaScript](https://kotlinlang.org/docs/wasm-js-interop.html)).
  **Every Promise this implementation creates must be awaited and wrapped in try/catch inside
  Kotlin** — never left to run detached — or a failed encrypt/write becomes an invisible failure
  that neither `store()`'s fire-and-forget contract nor `storeBlocking()`'s durability contract
  can report correctly.

## 3. localStorage / IndexedDB pitfalls

- **Private-browsing support is not uniform.** Firefox has no IndexedDB support at all in Private
  Browsing — "Firefox is the only major browser without support for IndexedDB in Private
  Browsing mode, throwing an error when attempting to open an IndexedDB database." Chrome/Edge
  support it in Incognito but with a reduced quota that is wiped on window close. Safari iOS
  Private Browsing rejects writes in some configurations.
  ([diragb.dev: IndexedDB vs LocalStorage vs Cookies (2026)](https://diragb.dev/blog/indexeddb-vs-localstorage-vs-cookies/),
  [MDN: Storage quotas and eviction criteria](https://developer.mozilla.org/en-US/docs/Web/API/Storage_API/Storage_quotas_and_eviction_criteria))
  **Implication for this project:** if `CredentialStore` chooses IndexedDB, `store()`/`retrieve()`
  must handle `indexedDB.open()` throwing/rejecting outright (Firefox private mode) as a normal,
  expected failure path — not a bug — and degrade to `isAvailable() == false` rather than crash.
  This mirrors `AndroidCredentialStore`'s designed-for "vault is locked" unavailability signal
  already documented on `CredentialAccess.isAvailable()`.
- **Storage clearing / eviction.** Both localStorage and IndexedDB are cleared by "Clear browsing
  data" and by LRU per-origin eviction when device storage is under pressure (Safari specifically
  "may evict data after approximately 7 days of inactivity" for some configurations). A user
  returning after a long gap may find their saved git PAT / LLM key silently gone — this is a
  **correct, not-a-bug** outcome given the stated threat-model ceiling (no durable OS-keychain
  equivalent exists per the requirements doc), but the UI/UX consuming `retrieve() == null` must
  treat it as "credential not found, re-enter" rather than surfacing a raw error, matching how
  `GitCredentialConnectionStore.getSecret()` already treats a `null` return today
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitCredentialConnectionStore.kt:48`).
- **Per-origin isolation / multi-tab.** Both storages are strictly per-origin — no cross-origin
  leak risk — but *within* an origin, multiple tabs of the same SteleKit deployment share the same
  localStorage/IndexedDB. This codebase already has cross-tab test coverage for a similar
  IndexedDB-backed mechanism (`kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt`)
  — the new `CredentialStore` should get equivalent cross-tab coverage if two tabs writing the
  same credential key concurrently is a realistic scenario (e.g. two `GitSetupScreen` instances
  open in different tabs against the same graph's connection).
- **Async transactions vs. a synchronous call site.** IndexedDB transactions are asynchronous by
  design (`onsuccess`/`onerror`/`oncomplete` callbacks, or Promise-wrapped as this codebase already
  does in `HostDirectoryInterop.kt`) while `CredentialAccess.store/retrieve/delete` are
  **non-suspend, synchronous-signature functions** — this is the core "Rabbit Hole" the
  requirements doc already flags (`requirements.md:203-207`) and is not new information from this
  research pass, but it compounds with pitfall #5 below: any in-memory-cache-plus-async-flush
  design used to satisfy the sync signature must be internally consistent about what "durable"
  means for `storeBlocking()` specifically (see #4).

## 4. Failure-handling parity with the established convention

`CredentialAccess.storeBlocking()`'s KDoc (`CredentialAccess.kt:25-43`) sets the contract this
implementation must honor: default delegates to `store()` then `retrieve()` round-trip-check,
"correctly reports `false` for a no-op/non-persisting backend... rather than falsely claiming
durability for a write that never actually happened," and states plainly "Never throws for a
failed write — callers must check the return value." `JvmCredentialStore` overrides the default
specifically because the default would discard `saveProperties()`'s success/failure signal
(`JvmCredentialStore.kt:186-197`, referencing its own internal "MA13" note) — i.e., **the
established pattern in this codebase is: don't trust the default delegation once your backend can
fail a write asynchronously or silently; override `storeBlocking()` explicitly to observe the
real durability signal.**

wasmJs-specific failure modes that need equivalent handling, none of which JVM's file-based
backend has to consider:

| Failure mode | JVM equivalent handled today | wasmJs handling needed |
|---|---|---|
| Salt/key-material write fails | `generateAndSaveSalt` catches, logs `logger.warn`, falls back to in-memory salt for the session (`JvmCredentialStore.kt:75-94`) | IndexedDB `put` for the wrapped `CryptoKey` can reject (quota, private-mode `open()` failure) — same fallback shape: log + in-memory-only key for this tab's lifetime, `isAvailable()` still `true` for the session but nothing survives reload |
| Decrypt of an existing value fails | `decrypt()` catches, returns `null` — "start fresh" (`JvmCredentialStore.kt:144-155`) | Ciphertext from a *prior app version* with a different key-derivation/algorithm choice must decrypt-fail gracefully to `null`, not throw — same discipline, but web additionally needs a **schema/version tag** on stored ciphertext (JVM doesn't need this since its key derivation has been stable) so a future algorithm change can distinguish "wrong key" from "old format" without guessing |
| Quota exceeded on write | N/A (disk, not quota-bounded in practice) | `localStorage.setItem` throws `QuotaExceededError` synchronously; IndexedDB `put` rejects the transaction. `store()` (fire-and-forget) must catch and log via `logger.warn`, mirroring `JvmCredentialStore`'s `saveProperties` catch-and-warn discipline (`JvmCredentialStore.kt:128-131`) — never propagate as an uncaught exception per pitfall #2 |
| Backend genuinely unavailable (SubtleCrypto missing, IndexedDB `open()` throws) | N/A — JVM file I/O essentially always available | Must surface via `isAvailable() == false`, the contract's existing designated signal (`CredentialAccess.kt:22`, currently only meaningfully used by the vault-locked case) — not a crash, not a silently-always-null `retrieve()` (which would look identical to "no credential saved" and defeat the whole point of this project) |

**Never-log-the-secret discipline** (requirements.md's Non-functional Requirements section)
already has a concrete pattern to copy: every `JvmCredentialStore.logger.warn` call site logs the
key/path/exception message, never the value (`JvmCredentialStore.kt:68,91,129`). Apply the same
rule to every new wasmJs log site.

## 5. Concurrency / race pitfalls

**Call-site check.** `GitCredentialConnectionStore` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitCredentialConnectionStore.kt`)
and `LlmCredentialStore` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialStore.kt`)
both call `credentialStore.store/retrieve/delete` as plain synchronous calls from Compose UI
event handlers (button `onClick` / form-save `launch {}` blocks) — no lock, no explicit
coroutine-safety wrapper on either side today. On JS's single-threaded event loop this is safe
*as long as the implementation genuinely completes synchronously before returning* — matching
today's no-op stub and JVM's synchronous file I/O. **The risk is entirely self-inflicted by the
implementation choice**, not pre-existing: if `store()` is implemented as "update an in-memory
cache synchronously, then kick off an async IndexedDB flush in the background" (a reasonable
design to satisfy the sync signature per requirements.md's suggested pattern,
`requirements.md:126-127`), a read-after-write within the *same* tab is fine as long as
`retrieve()` always reads the in-memory cache first (not IndexedDB) — but:

- **`storeBlocking()`'s round-trip check becomes meaningless** if it's still using the *default*
  interface delegation (`store()` then `retrieve()` compares against the in-memory cache) —
  it would report `true` even though the async IndexedDB flush could still fail afterward. Per
  §4 above, `storeBlocking()` must be overridden to *await* the actual flush (not just check the
  cache) before returning — this is exactly the JVM precedent (override because the default
  can't observe true durability), applied to a genuinely-async backend for the first time in this
  codebase.
- **Cross-tab races become newly visible.** Two tabs each holding their own in-memory cache can
  diverge: tab A calls `store()` (cache updated, async flush queued), tab B calls `retrieve()`
  moments later on its *own* stale in-memory cache (or, if `retrieve()` always reads IndexedDB
  directly to avoid this, tab B could read a still-in-flight/partial write from tab A). This
  wasn't a concern with the old no-op stub (nothing persisted, so nothing to race) and needs
  explicit test coverage — this codebase already has a cross-tab IndexedDB test to model after:
  `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt`.
- **Page unload before flush completes** is the sharper version of the same race: a background
  async flush that hasn't completed when the tab closes/reloads loses the write entirely, with no
  error ever surfaced to the caller (the synchronous `store()` already returned `Unit`
  successfully). If this design is chosen, `beforeunload`/`visibilitychange` handling (this
  codebase already has `jsVisibilityHiddenPromise`-style precedent per
  `HostDirectoryInterop.kt:35`) or accepting the loss as a documented limitation are the two
  options — pick one explicitly in planning, don't let it fall out of the implementation by
  accident.

## 6. Regression risk to `EphemeralSettingsMode`

`EphemeralSettingsMode` (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt:15-25`)
is a global flag flipped once at boot by the "open temporarily" session path
(`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt:408`, `runEphemeralSession()`),
and it is checked **inside `PlatformSettings`'s own methods**, not at each call site — explicitly
designed this way "because every current and future `PlatformSettings()` usage is automatically
ephemeral-aware with no call-site changes" (`PlatformSettings.kt:9-13`).

**Today, `CredentialStore` is a separate class from `PlatformSettings` with no relationship to
this flag at all** (it's a no-op, so the question has never come up). If the new wasmJs
`CredentialStore` backs its ciphertext with `localStorage` or IndexedDB *without* adding its own
`EphemeralSettingsMode.active` check, the failure mode is exactly the scenario this flag exists
to prevent: **a user who explicitly chose "open temporarily" enters a real git PAT or LLM API key
during that session, and it is written to permanent browser storage anyway** — surviving the tab
close the user believed would erase everything, contradicting the session's entire premise and
constituting a real credential leak, not a cosmetic bug. The comment at `Main.kt:395-399` already
names this exact risk generically ("keeps a temporary session's credentials out of permanent
localStorage rather than requiring every credential-persistence call site to know about ephemeral
mode individually") — but that guarantee currently only covers `PlatformSettings`-routed data
(the git-connection *registry* metadata), not `CredentialAccess`-routed *secrets* themselves,
because `CredentialStore` was a no-op when that comment was written and secrets never reached any
storage layer to leak from.

**Required design response:** the new wasmJs `CredentialStore` must check
`EphemeralSettingsMode.active` itself (mirroring `PlatformSettings`'s own per-method check
pattern) and, when active, route through the same in-memory-only `EphemeralSettingsMode.store`
map (or an equivalent private in-memory map scoped to the `CredentialStore` instance) instead of
IndexedDB/localStorage — never touching durable storage for the encrypt-and-persist path while
ephemeral mode is on. This needs explicit test coverage (`wasmJsTest`) asserting that a `store()`
call while `EphemeralSettingsMode.active == true` leaves the durable backing store completely
untouched — not just "eventually cleared," since IndexedDB/localStorage writes that happen and
are later deleted still constitute a leak window (visible to a concurrent tab, to a backup
snapshot taken mid-session, or to any process reading storage before cleanup).

## Summary of design-level guardrails to carry into planning

1. State the XSS-does-not-defend-against-itself limitation explicitly in the new `CredentialStore`'s
   KDoc, mirroring `JvmCredentialStore.kt`'s existing self-disclaimer — use a non-extractable
   `CryptoKey` (not a raw key value) as the real, narrower mitigation it actually is.
2. Never leave a `crypto.subtle`/IndexedDB Promise chain unawaited in Kotlin — unhandled
   rejections become uncaught JS console errors, not Kotlin exceptions or `Either.Left`, silently
   breaking `storeBlocking()`'s "never throws" contract.
3. Treat `indexedDB.open()` throwing (Firefox private mode) and quota-exceeded writes as expected,
   `isAvailable() == false` / logged-and-swallowed failure paths — not crashes — matching
   `JvmCredentialStore`'s existing catch-log-continue discipline, never logging secret values.
4. If `store()` does async background work, `storeBlocking()` must be overridden (not left as the
   default delegation) to await genuine flush completion — the same reason `JvmCredentialStore`
   overrides it today, newly applicable because this is the first genuinely-async backend in the
   codebase; add cross-tab and page-unload-before-flush test coverage since neither was a risk
   under the old no-op stub.
5. `CredentialStore` must check `EphemeralSettingsMode.active` itself and route to an in-memory-only
   map when active — today only `PlatformSettings` honors this flag, and `CredentialStore` secrets
   are a distinct, currently-unguarded leak path into permanent storage.
