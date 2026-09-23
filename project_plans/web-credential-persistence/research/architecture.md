# Research: Architecture — web-credential-persistence

Agent 3 of SDD Phase 2 fan-out. Scope: architectural patterns, integration points, data-flow and
consistency requirements for a real `wasmJs` `CredentialStore` actual.

## 1. Integration points — every `CredentialAccess`/`CredentialStore` consumer in commonMain

Found via `grep -rl "CredentialStore\|CredentialAccess" kmp/src/commonMain`:

| Consumer | File | Call context | Suspend or sync? |
|---|---|---|---|
| `GitCredentialConnectionStore.getSecret/saveConnection/deleteConnection` | `git/GitCredentialConnectionStore.kt:48,73,81` | Plain methods, no `suspend` | **Sync** — caller must not need to await |
| `LlmCredentialStore.getApiKey/setApiKey/deleteApiKey/setApiKeyBlocking` | `llm/LlmCredentialStore.kt:21,23,25,36` | Plain methods, no `suspend` | **Sync** |
| `VaultCredentialStore.migrateFrom(source: CredentialAccess, keys)` | `git/VaultCredentialStore.kt:88-95` | Plain method; calls `source.retrieve`/`source.delete` in a loop | **Sync** |
| `GraphManager.removeGraph()` — deletes `git_https_token_$id`/`git_ssh_passphrase_$id` | `db/GraphManager.kt:497,564-566` | `fun removeGraph` — **not** `suspend` (confirmed: `fun removeGraph(id: GraphId): Boolean` at line 497, next to `suspend fun updateGraphPath` at 631 which *is* suspend — the asymmetry is deliberate) | **Sync** |
| `GraphManager.updateGraphPath()` — moves `git_https_token_*`/`git_ssh_passphrase_*` between old/new graph IDs | `db/GraphManager.kt:631,720-727` | `suspend fun updateGraphPath` | Caller is suspend, but still calls the plain sync `CredentialAccess` methods directly (no `withContext`/await needed today) |
| `GraphManager.llmProviderRegistry` (`by lazy { buildLlmProviderRegistry(LlmCredentialStore(CredentialStore()), llmSettings) }`) | `db/GraphManager.kt:190` | Plain `by lazy` property, evaluated on first access, not inside any coroutine | **Sync**, and **first-touch timing matters** (see §5) |
| `App.kt` — `remember { LlmCredentialStore(CredentialStore()) }`, `credentialAccessProvider = { vaultCredentialStore ?: CredentialStore() }`, `vaultCredentialStore?.migrateFrom(source = CredentialStore(), ...)` | `ui/App.kt:466,662,1008` etc. | Composable `remember{}` bodies and plain lambdas — **cannot suspend** (Compose forbids suspending inside `remember{}`) | **Sync**, hard constraint |
| `GitSetupScreen.kt` — `resolveHttpsTokenKey`/`resolveOauthTokenKey`/`persistWebGitCredentials` call `credentialStore.store(...)` | `ui/screens/git/GitSetupScreen.kt:592,597,616,628,654,673` (via `resolveHttpsTokenKey`/`resolveOauthTokenKey`, defined at 1581/1616) | These call sites are inside `scope.launch { ... }` blocks (confirmed by adjacent `return@launch` at line 636) — i.e. **the caller happens to already be in a suspend context**, even though the functions themselves take a plain (non-suspend) `credentialStore: CredentialStore` parameter | Caller context is suspend-capable, but the signature is still sync |
| `GitSyncService` | `git/GitSyncService.kt:22,55` | `credentialAccessProvider: (() -> CredentialAccess)?` — plain, non-suspend supplier lambda, invoked to check `isAvailable()` before sync | **Sync** |

**Conclusion on integration points**: every consumer treats `CredentialAccess` as synchronous by
construction — several call sites (`App.kt`'s `remember{}` blocks, `GraphManager.removeGraph`, the
`llmProviderRegistry by lazy`) are in contexts that **structurally cannot** await a suspend call
(Compose `remember{}` forbids it; `removeGraph`/`llmProviderRegistry` are plain functions/properties
with no coroutine scope threaded through). The two/three call sites that do happen to run inside a
`launch{}` (`GitSetupScreen`'s save handlers, `updateGraphPath`) are the exception, not the rule, and
even those pass `CredentialStore`/`CredentialAccess` through a plain synchronous parameter type — so
there is no clean subset of "suspend-friendly" callers big enough to special-case. **A real fix must
make `store`/`retrieve`/`delete` genuinely synchronous from every caller's point of view**, not push
async-ness onto callers.

## 2. Synchronous-interface vs. async-browser-API tension

WebCrypto's `SubtleCrypto.encrypt/decrypt/importKey/deriveKey/digest` are **Promise-only** — there is
no synchronous variant anywhere in the spec (deliberately, to avoid blocking the event loop), and
wasmJs is single-threaded so there is no way to block-wait on a Promise inline the way a JVM thread
can block on a `Future`. So a synchronous `retrieve()` that internally calls SubtleCrypto per-call is
not possible, full stop — this must be resolved architecturally, not worked around.

Evaluating the three options the requirements doc names:

- **(a) In-memory decrypted cache + async background persist** — recommended. Mirrors
  `VaultCredentialStore`'s existing `cache: MutableMap<String,String>` + `cryptoLayer` pattern
  exactly (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/VaultCredentialStore.kt:42-81`):
  `retrieve()` reads the in-memory map (sync, instant); `store()`/`delete()` write the map
  synchronously *and* fire an async encrypt+`localStorage.setItem` in the background
  (`CredentialAccess`'s own KDoc at `platform/security/CredentialAccess.kt:34-35` explicitly blesses
  this: "`store`... fine to be fire-and-forget"). The one-time async work — deriving/loading the
  AES-GCM key and decrypting whatever's already in `localStorage` into the cache — happens once, at
  boot, via a `suspend` init step.
- **(b) Widen `CredentialAccess` to `suspend`** — out of scope per requirements.md, and §1 above
  shows why it wouldn't even help: several call sites (Compose `remember{}`, `GraphManager.removeGraph`)
  cannot suspend at all regardless of the interface shape.
- **(c) Synchronous-only browser primitive (plain `localStorage` + XOR/obfuscation, no SubtleCrypto)**
  — genuinely viable given the stated threat model ("protects against casual snooping, not a
  determined attacker" — see §3). Simpler: no cache-population race to reason about, no boot-time
  init ordering constraint, `CredentialStore()` stays as trivially stateless/re-constructible as the
  JVM actual already is. The tradeoff is it's a visibly weaker scheme than "AES-GCM" by name, even
  though the *effective* protection level against the stated threat (someone reading raw
  `localStorage` in devtools) is comparable to (a) once you account for §3's key-storage problem —
  both ultimately reduce to "obfuscated, not cryptographically secret against a co-resident attacker."

**Recommendation for planning**: prefer (a), because it's the direction requirements.md's own Rabbit
Holes section anticipates ("in-memory-cache-plus-async-flush pattern... mirroring
`VaultCredentialStore`") and keeps the code honestly labeled ("AES-GCM" is what it says and does),
but flag that (c) is a legitimate, much-lower-complexity fallback if research/planning decides the
cache-population race (see §4/§5 timing analysis below) is too fragile. Whichever is chosen, the
*cache* must be a **shared singleton**, not per-instance state — see the next paragraph.

**Critical structural requirement uncovered by §1**: `CredentialStore()` is constructed ad hoc at
many call sites (`GraphManager.kt:564,720`, `App.kt:466,662,1008`, `GitSetupScreen.kt` parameters) —
never held as one long-lived instance the way `VaultCredentialStore` is (`App.kt:530`,
`remember(activeGraphPath, isParanoidMode, cryptoEngine) { ... }`, one instance per active graph).
If the decrypted cache lived on the `CredentialStore` instance itself, every one of those ad hoc
`CredentialStore()` constructions would see an **empty, freshly-initialized cache** — `retrieve()`
would return `null` for values a *different* `CredentialStore()` instance wrote moments earlier. The
cache (and the derived `CryptoKey`, and the pending-write queue) must live in a `companion object` /
top-level singleton, exactly like `EphemeralSettingsMode.store` already does for
`PlatformSettings` (`platform/PlatformSettings.kt:24`) for precisely the same reason (`PlatformSettings()`
is also constructed ad hoc everywhere). `CredentialStore`'s constructor (`actual constructor()`) can
stay a thin, stateless wrapper around a shared object, matching the existing pattern.

## 3. Where does the encryption key come from?

There is no analog to JVM's `username + os.name` entropy in a browser sandbox — no stable
cross-session identifier a script can read that an attacker with the same storage access couldn't
also read. Evaluating the requirements doc's three options:

- **Random key via `crypto.getRandomValues()`, persisted in the same `localStorage` origin.**
  Provides near-zero protection against an attacker who already has `localStorage` read access
  (they read the key next to the ciphertext) — but this is **not materially weaker than JVM's own
  scheme**: `JvmCredentialStore`'s key is derived from `System.getProperty("user.name")` +
  `os.name`, both trivially readable by *anyone with local access to the same machine* (i.e. exactly
  the attacker class JVM's own KDoc already excludes: "protects against casual snooping but not
  against a determined local attacker", `platform/security/JvmCredentialStore.kt:28-31`). Both
  schemes defend the same threat: a values-are-plaintext-in-devtools/plaintext-in-a-backup-file
  casual read, not a co-resident/same-origin-script attacker. **This is the recommended option** —
  it's honest about being obfuscation, is trivial to implement, and its ceiling matches JVM's
  already-accepted, already-documented bar rather than silently overclaiming.
- **Derive from `sessionStorage`-scoped ID.** Rejected outright — `sessionStorage` does not survive
  a reload (tab close/reopen), which defeats the entire point of this project (persistence across
  reload is success metric #1 in requirements.md).
- **Leave values unencrypted, rely on browser-origin sandboxing alone.** Strictly better than
  today's plaintext-in-`localStorage`-with-no-even-origin-story only in the sense that it's the
  status quo `persistWebGitCredentials` already ships (see §5) — but it is a **materially different,
  weaker posture** than "encrypted at rest" (fails the success metric: "encrypted at rest (not
  plaintext)... values unreadable by casually inspecting browser storage in devtools" — plaintext
  *is* readable in devtools, defeating that bar outright). Do not silently fall back to this; if
  research/planning ever considers it, it needs explicit owner sign-off since it's a regression
  relative to the stated success metric, not a lateral tradeoff.

**Recommendation**: random key via `crypto.getRandomValues()`, persisted alongside the ciphertext in
`localStorage` (a dedicated key like `stelekit_credential_key`), generated once on first use. State
explicitly in the new actual's KDoc — mirroring `JvmCredentialStore`'s own doc comment pattern
verbatim — something like: *"Protects against casual snooping (plaintext-in-devtools, plaintext in a
browser data export/backup) but not against a determined attacker with script execution in the same
origin (XSS) or direct `localStorage` file access — that attacker can read the key next to the
ciphertext. This matches, not exceeds, JVM's own documented threat model."*

## 4. `EphemeralSettingsMode` integration

Read `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt` in full. The
pattern: `EphemeralSettingsMode` is a global object with `active: Boolean` (set once via `enable()`,
never reset — a session-lifetime flag) and its own `store: MutableMap<String,String>`. Every
`PlatformSettings` method checks `EphemeralSettingsMode.active` and redirects to the in-memory map
instead of real `localStorage` when true — chosen specifically because `PlatformSettings()` is
constructed ad hoc everywhere with "no injection seam" (its own KDoc, lines 6-13), so gating inside
the class itself is the only way to make every call site automatically ephemeral-safe.

**`CredentialStore` is in the exact same shape** — ad hoc-constructed everywhere (§1), no injection
seam — so it should follow the identical pattern, checking `EphemeralSettingsMode.active` itself
rather than being told externally. Concretely:

- When `active`, the async persist step (encrypt + `localStorage.setItem`) must **not** touch real
  `localStorage` — write into `EphemeralSettingsMode.store` instead (or a parallel in-memory-only
  map, but reusing `EphemeralSettingsMode.store` with a distinct key prefix, e.g.
  `credential_enc.<key>`, keeps a single "this session is ephemeral" bucket rather than two).
- The random encryption key (§3) should also be session-only during an ephemeral session — never
  read or write the persisted `stelekit_credential_key` from real `localStorage`; generate a fresh
  in-memory-only key for the session. This falls out for free if the key read/write goes through the
  same `EphemeralSettingsMode`-gated path as any other persisted value.
- **Should credentials be *more* ephemeral-safe than generic settings, not just equally?** Argued no
  extra mechanism is needed: the in-memory cache (§2) already means `retrieve()` never touches
  `localStorage` at all mid-session regardless of ephemeral mode — the only place real persistence
  (and thus real leakage risk) happens is the async write-through, which is exactly the one place
  the `EphemeralSettingsMode.active` check needs to sit. No separate/stricter flag is warranted;
  reusing the existing flag keeps one single "is this session ephemeral" source of truth rather than
  risking the two flags drifting (e.g. a future session that's ephemeral for settings but not for
  credentials by an oversight).
- Verify with a `wasmJsTest`: start an ephemeral session (`EphemeralSettingsMode.enable()`), call
  `CredentialStore().store(...)`, assert real `localStorage` (or a fake matching its shape) was never
  written to — mirroring how `EphemeralSettingsMode`-non-leakage is presumably already tested for
  `PlatformSettings` (worth checking for an existing test to pattern-match against during planning).

## 5. `persistWebGitCredentials` reconciliation

Read `GitSetupScreen.kt`'s `persistWebGitCredentials` (`ui/screens/git/GitSetupScreen.kt:1554-1568`)
and both call sites (lines 616, 673, both inside `scope.launch{}` blocks per the KDoc comments right
above each call, e.g. line 611-615: *"the wasmJs configResolver (browser/Main.kt) reads credentials
from PlatformSettings, not from CredentialStore (a no-op on web) — populate it here so a real web
user's saved PAT is actually reachable"*). It writes `githubOwner`/`githubRepo`/`githubBranch`/
`githubToken` as **plaintext** `PlatformSettings` keys, bypassing `CredentialAccess` entirely,
specifically because `CredentialStore` is currently a no-op.

Read `browser/Main.kt`'s two `configResolver` definitions (lines 184-190 and 415-420, one for the
normal boot path, one for `runEphemeralSession()`). Key finding: **`configResolver` is typed
`suspend (GitConfig) -> GitHostConfig?`** — it is *not* forced to go through the synchronous
`CredentialAccess` interface at all; it's a wasmJs-local lambda free to `await`/suspend internally.
Today it doesn't actually suspend on anything credential-related — it just reads
`PlatformFileSystem.githubToken`, a plain companion-object `var` populated **once, synchronously, at
boot** (`main()`'s top-level `scope.launch{}`, lines 163-171, *before* `GraphManager` is constructed
at line 269 and *before* `ComposeViewport` mounts at line 302 — i.e. before any UI can possibly run).

This is the architecturally important fact for the reconciliation question: **the entire
credential-wiring step at boot already runs inside a suspend coroutine, ahead of any UI mount.**
That means the async cache-population init this project needs anyway (§2) can slot into that exact
same spot — right where `ghSettings.getString("githubToken", ...)` is read today — with no new
suspend-context plumbing required. Concretely:

- `configResolver` should read from `CredentialStore`'s (now-real, in-memory-cache-backed) `retrieve`
  instead of `PlatformFileSystem.githubToken`, once the same boot sequence also seeds
  `CredentialStore`'s cache. Since `configResolver` is already `suspend`, it could even call a
  `CredentialStore`-internal suspend accessor directly (bypassing `CredentialAccess`'s sync
  constraint entirely for this one call site) — but reusing the plain sync `retrieve()` against the
  already-populated cache is simpler and keeps one code path instead of two.
- **`persistWebGitCredentials`'s workaround should be removed, not kept**, once `CredentialStore`
  genuinely persists and `configResolver` is repointed at it: its entire reason for existing
  (`CredentialStore` being a no-op) goes away, and keeping both means `GitCredentialConnectionStore`'s
  registry and the raw `PlatformSettings` values can still silently disagree (the exact problem
  named in requirements.md's Problem Statement item 2). Per requirements.md's Risk Control section,
  sequence the removal **last**, after the new store is verified working — during the transition,
  `persistWebGitCredentials` continuing to write plaintext `PlatformSettings` is the rollback safety
  net, not a permanent fixture.
- **Migration of the one existing plaintext value**: no bulk migration mechanism is needed —
  requirements.md's Risk Control section already states the no-op backend "never stored anything to
  migrate." The one real carryover case is a user who already has a plaintext `githubToken` in
  `localStorage` from `persistWebGitCredentials` pre-fix. Recommended one-time reconciliation: at the
  same boot-time step, if `CredentialStore` has no value for the relevant `git_https_token_<graphId>`
  key but legacy `PlatformSettings.getString("githubToken", "")` is non-blank, read it once, write it
  into `CredentialStore`, and leave (or clear — planning's call) the legacy plaintext key. This is a
  small, boot-local reconciliation, not a general migration framework — scope it as literally that:
  one `if (credentialStore.retrieve(key) == null) { legacy value if present -> store it }` check at
  the same point `ghSettings` is read today.
- **Timing regression check**: `persistWebGitCredentials`'s own KDoc flags "only takes effect after a
  page reload" as a known rough edge (because `Main.kt` reads `PlatformSettings` once at startup).
  The new `CredentialStore`-backed path has the **same** characteristic if its cache is also
  populated once at boot (§2/§4) — this is not a regression, but also not an improvement; note it
  explicitly in the plan rather than implying the reload requirement goes away. If planning wants to
  remove the reload requirement, that's a separate, larger change (e.g. re-running the credential-init
  + `configResolver`-rebuild step reactively whenever `GitSetupScreen` saves) — out of scope unless
  trivial.
- **`GITHUB_OAUTH` question (from Open Questions)**: `persistWebGitCredentials` explicitly early-returns
  for any `authType != GitAuthType.HTTPS_TOKEN` (`GitSetupScreen.kt:1560`), so it never covered OAuth
  regardless. `resolveOauthTokenKey` (`GitSetupScreen.kt:1616-1632`) *does* call
  `credentialStore.store(key, secret)` for the OAuth case already — that write already goes through
  `CredentialAccess`, not the `PlatformSettings` workaround. Once `CredentialStore` is real, that
  write starts actually persisting "for free" — but `configResolver` would still need to resolve
  `GitConfig.oauthTokenKey` (not just the hardcoded `githubToken` var) to actually *use* it, and
  `configResolver` today only reads `PlatformFileSystem.githubToken`, not
  `CredentialStore.retrieve(config.oauthTokenKey)`. So fixing `CredentialStore` alone makes the OAuth
  token *storable and retrievable via `GitCredentialConnectionStore`* (success metric #2), but does
  **not** by itself make `configResolver` use it for an actual git operation — that's the pre-existing,
  separately-tracked gap requirements.md's Out of Scope section already names. Confirm this
  distinction explicitly in planning so "fixed CredentialStore" isn't conflated with "OAuth git sync
  now works end-to-end" (it doesn't, per Out of Scope).

## Summary of architectural recommendations for planning

1. **Shared singleton cache**, not per-instance state — `CredentialStore()`'s stateless constructor
   stays, but the decrypted in-memory cache, derived `CryptoKey`, and any pending-write bookkeeping
   live in a `companion object` (mirrors `EphemeralSettingsMode.store`'s reason for existing).
2. **One boot-time async init**, slotted into `browser/Main.kt`'s existing credential-wiring step
   (both the normal path at line ~163 and the `runEphemeralSession()` path), before `GraphManager` is
   constructed and before `ComposeViewport` mounts — reuses proven timing, no new suspend plumbing.
3. **Key = `crypto.getRandomValues()`, persisted alongside ciphertext in `localStorage`** — matches
   JVM's own accepted threat-model bar, stated explicitly in the new actual's KDoc.
4. **Gate the async persist (and the key's own read/write) on `EphemeralSettingsMode.active`**,
   reusing the existing flag/store rather than adding a second one.
5. **Repoint `configResolver` at `CredentialStore`**, do a one-time boot-local legacy-plaintext
   reconciliation for `persistWebGitCredentials`'s existing `githubToken` key, then remove
   `persistWebGitCredentials` last (after the new store is verified) per the existing rollback plan.
6. **`GITHUB_OAUTH` git-sync usage stays out of scope** — `CredentialStore` becoming real fixes
   storage/retrieval (and thus `GitCredentialConnectionStore`), not `configResolver`'s consumption of
   `oauthTokenKey`, which is a separate, already-tracked gap.
