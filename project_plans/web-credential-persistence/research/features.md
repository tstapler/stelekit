# Research: Features (Agent 2)

Research question: what similar features exist in this codebase or industry, what edge cases
and failure modes should the design handle, what are users' unstated needs?

## 1. Comparable in-codebase patterns

### `VaultCredentialStore` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/VaultCredentialStore.kt`)

The one existing example of a `CredentialAccess` implementation that layers **encryption
requiring an unlock step** on top of a **synchronous interface**:

- Holds a `@Volatile` in-memory `cache: MutableMap<String, String>?` — `retrieve()`/`store()`/
  `delete()` all operate purely against this cache; disk I/O (`fileSystem.readFileBytes`/
  `writeFileBytes`) only happens inside `onVaultUnlocked()` (full decrypt-and-load) and after
  each mutation (`saveCredentials()`, fire-and-forget, logged on failure, never thrown).
- `isAvailable()` reports `cache != null` / `cryptoLayer != null` — i.e., "locked" is a first-
  class state the interface already models, not something new the wasmJs actual invents.
- `migrateFrom(source, keys)` is the existing migration precedent: pull each key from a source
  `CredentialAccess`, `store()` it here, then `source.delete(key)` — same shape
  `LlmCredentialMigration` uses, and a candidate pattern if a `PlatformSettings`→`CredentialStore`
  migration for `githubToken` is ever added (see §4).
- **This is the template for reconciling "encryption needs async/expensive setup" with "the
  interface is synchronous, non-suspend"**: do the expensive part once (here: vault unlock,
  requiring the DEK) into an in-memory cache, then serve every `retrieve()`/`store()` call
  synchronously against that cache. The wasmJs actual has the same shape of problem — WebCrypto
  `SubtleCrypto.encrypt`/`decrypt` are Promise-based — and needs the same solution: pre-warm a
  cache asynchronously, serve `CredentialAccess` calls synchronously against it.

### `GitCredentialConnectionStore` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitCredentialConnectionStore.kt`)

Confirms the key-naming conventions the new store must not break:
- `"git_connection_$id"` — [`GitCredentialConnection.secretKeyFor(id)`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitCredentialConnection.kt:31), used for the "reusable saved connection" registry.
- `"git_https_token_$graphId"` — graph-scoped HTTPS PAT, written by
  [`resolveHttpsTokenKey`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt:1591) and cleaned up by
  [`GraphManager.removeGraph`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:565).
- `"git_github_oauth_$graphId"` — graph-scoped OAuth device-flow token
  ([GitSetupScreen.kt:1623](kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt:1623), delete at [GitSetupScreen.kt:431](kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt:431)).
- `"git_ssh_passphrase_$graphId"` — [GraphManager.kt:566](kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:566).
- `"llm.<providerId>.api_key"` (e.g. `"llm.anthropic.api_key"`, `"llm.custom.<uuid>.api_key"`) —
  [`LlmCredentialStore.keyFor`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialStore.kt:38).

None of these keys carry a platform prefix or need one — the interface is generic flat
`String → String`. The new actual just needs to treat `key` as an opaque namespaced string, same
as every other actual.

**Critical instantiation pattern, confirmed by grep**: `CredentialStore()` and
`LlmCredentialStore(CredentialStore())` are constructed **ad hoc, with no DI/singleton**, at
every call site — [`GraphManager.kt:190`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt:190), `:564`, `:720`, and
[`App.kt:466`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt:466) each create a fresh instance. `PlatformSettings.kt`'s wasmJs actual doc comment
(line 7-9) states this explicitly for `Settings` and the same is true for `CredentialAccess` —
"there is no injection seam." **Implication for the new actual**: any cached key material or
warmed decrypt cache from the async setup described above must live in a `companion object` /
top-level singleton, not an instance field — a fresh `CredentialStore()` is constructed and
thrown away possibly dozens of times per session, and each one must see the same warm cache the
first one built.

## 2. Consumers and their edge cases

Grepped every `credentialStore.{store,retrieve,delete,isAvailable,storeBlocking}` call site under
`kmp/src/commonMain`:

| Consumer | Call sites | Failure mode if new actual behaves wrong |
|---|---|---|
| `GitCredentialConnectionStore` | `getSecret`/`saveConnection`/`deleteConnection` | Already covered in §1 — the actual bug this project fixes. |
| `LlmCredentialStore` | `getApiKey`/`setApiKey`/`deleteApiKey`/`setApiKeyBlocking` | See `LlmCredentialMigration` below. |
| `LlmProviderRegistryFactory` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmProviderRegistryFactory.kt:42,50,58,68`) | `llmCredentialStore.getApiKey(...)` called **synchronously at provider-registry build time** — i.e. at or near app startup, not lazily on first use | If the async warm-up cache (per §1) hasn't finished loading by the time this runs, every provider looks unconfigured even though a key is saved — a **cold-start race**, not a missing feature. This is the concrete, code-level instance of the "reload timing" rabbit hole named in requirements.md. |
| `LlmProviderSettings.kt` (settings UI, lines 131/145/183/212) | `setApiKey`/`getApiKey` from Compose event handlers and `remember{}` initial state | `getApiKey` backing `remember(providerId) { mutableStateOf(...) }` (line 183) is a one-shot synchronous read at composition time — same cold-start race as above, but user-visible as "my key box is empty right now" rather than a silent registry gap. |
| `GitSetupScreen.kt` | `retrieve`/`store`/`delete` at lines 199, 207, 431, 512, 517, 567, 597, 654, 807, 1592, 1628 | Populating text fields on screen entry (199/207/567) is a synchronous read at composition time — same race. |
| `GraphManager.removeGraph` (`:564-566`) | `cs.delete(...)` wrapped in `try/catch` treating failure as "non-critical" | New actual's `delete()` must not throw for a missing/never-set key (matches JVM/Android, which are no-ops on missing keys). |
| `VaultCredentialStore.migrateFrom` | `source.retrieve`/`source.delete` where `source` could in principle be the wasmJs `CredentialStore` | Only relevant if paranoid-mode vault ships on web — not currently wired, but the interface makes it possible; the new actual must behave correctly as a `migrateFrom` **source** (retrieve-then-delete pattern) even though no code path exercises it in-scope today. |

### `LlmCredentialMigration`'s durability reliance (the concrete `storeBlocking` edge case)

[`LlmCredentialMigration.migrateKey`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigration.kt:116-153) is the sharpest edge case for `storeBlocking`:

1. Calls `llmCredentialStore.setApiKeyBlocking(providerId, plaintextValue)` — per
   `CredentialAccess.storeBlocking`'s kdoc, this must be "durable-before-return": the value must
   actually be on persistent storage, not just cached, before the call returns `true`.
2. **Only if `wroteDurably == true`** does it then read back and compare (defense-in-depth against
   corrupted writes), and **only then** calls `clearPlaintext()` — irreversibly deleting the one
   plaintext copy of the (Voice/legacy) API key.
3. If `storeBlocking` reports `true` for a write that is not actually durable — e.g. a naive
   default-interface delegation (`store()` + immediate `retrieve()` against an in-memory cache
   that hasn't flushed to `localStorage`/`IndexedDB` yet) — this migration will **destroy the only
   copy of the user's key while believing it succeeded**, exactly the bug `AndroidCredentialStore`
   and `JvmCredentialStore` each explicitly override `storeBlocking()` to avoid (see their kdocs at
   [`AndroidCredentialStore.kt:74-94`](kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/security/AndroidCredentialStore.kt:74-94) and
   [`JvmCredentialStore.kt:186-197`](kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/security/JvmCredentialStore.kt:186-197)).
4. Today this migration is a **no-op on web anyway** — `VoiceSettings`/legacy Anthropic/OpenAI
   keys are a JVM/Android-era `PlatformSettings` concern; nothing suggests it currently runs
   against wasmJs's no-op `CredentialStore` in a way that has live user data at risk. But the
   requirements doc explicitly calls out making `storeBlocking` report real durability on wasmJs
   as a success metric — this migration is the reason that matters, and the new actual must
   override `storeBlocking()` explicitly (like Android/JVM do) rather than rely on the interface's
   default `store()` + read-back, if the underlying write path is asynchronous in any way (e.g.
   `IndexedDB.put()` inside a `Promise` that isn't awaited before returning).

## 3. The `persistWebGitCredentials` workaround

Read [`GitSetupScreen.kt:1554-1568`](kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreen.kt:1554-1568) (the function) and its kdoc at 1528-1553, plus
[`GitSetupScreenCredentialPersistenceTest.kt:16-27`](kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupScreenCredentialPersistenceTest.kt:16-27) (header doc).

Confirmed facts:
- Writes plaintext `githubOwner`/`githubRepo`/`githubBranch`/`githubToken` into `PlatformSettings`
  (i.e. raw `localStorage` on web) — a **second, parallel credential channel** from
  `CredentialStore`, specifically because `configResolver` in `browser/Main.kt` reads
  `PlatformSettings` once at startup into `PlatformFileSystem`/`WasmSectionSyncService`'s
  companion fields, and (per the kdoc) that's "the credential source the write engine
  (`WasmGitRepository`) actually trusts" — not `CredentialStore`.
- Explicitly no-ops for `authType != HTTPS_TOKEN` — so `GITHUB_OAUTH` tokens are not covered
  by this workaround at all (confirmed in-code, matches requirements.md).
- Own kdoc admits: "this only takes effect for the CURRENT session after a page reload on web,
  because `Main.kt` reads `PlatformSettings` exactly once at startup" — i.e. even the *existing*
  workaround already has the exact "needs reload" characteristic the requirements' Rabbit Holes
  section worries the *new* store might also have. This is useful baseline: if the new
  `CredentialStore` has the same one-reload-lag, that's **parity, not a regression** — but
  planning should still verify whether wiring `configResolver` to read from the new store instead
  of `PlatformSettings` could remove the lag entirely (the requirements' Scope section already
  flags this as the preferred reconciliation path).
- Test file's own header names this a "PR #239 review Finding 1 (BLOCKER) regression" fix —
  i.e. shipping a real `CredentialStore` without also fixing this reconciliation risks
  reintroducing exactly the blocker this function was added to close, if `configResolver` isn't
  repointed correctly. The requirements' Risk Control section already sequences this correctly
  (verify new store works, *then* remove `persistWebGitCredentials`, not before).

Edge cases reconciliation must handle:
1. **Reload timing** — whichever mechanism `configResolver` ends up reading from (new
   `CredentialStore` or still `PlatformSettings`), it must be readable synchronously and
   correctly at the exact point `Main.kt` builds `configResolver`, which per the codebase pattern
   is very early in `main()` — before any async warm-up (per §1) is guaranteed to have completed
   if the store's setup is async. This is the same cold-start race as §2, just at an earlier and
   more startup-critical point (git sync auth entirely fails, not just "field looks empty").
2. **Multiple auth types** — `GITHUB_OAUTH` explicitly isn't handled by the workaround today; if
   the new `CredentialStore` makes `GitCredentialConnectionStore` genuinely work end-to-end on
   web, OAuth-saved connections become retrievable for the first time, but `configResolver` still
   needs *some* path to learn about them if it doesn't read through `CredentialStore` directly.
3. **Existing users with a workaround-era token** — see §4.
4. **Two channels disagreeing** — kdoc for `resolveHttpsTokenKey`/`resolveOauthTokenKey`
   (`GitSetupScreen.kt:1581-1632`) shows `GitCredentialConnectionStore` and the graph-scoped
   `git_https_token_$graphId` key are already two representations of "the same" credential kept
   in sync by the save flow. Adding `persistWebGitCredentials`'s `PlatformSettings` copy as a
   *third* representation (current state) is exactly the "can silently disagree" problem
   requirements.md's Problem Statement §2 names. Reconciliation should collapse to two
   representations (graph-scoped key + connection registry, both backed by the real
   `CredentialStore`) rather than three.

## 4. Migration/compat edge case — existing web users with a workaround-era plaintext token

- Confirmed: `persistWebGitCredentials` is live in `main` today (not gated behind this project),
  so any web user who has configured `HTTPS_TOKEN` git sync since this workaround shipped has a
  real plaintext `githubToken` value sitting in their browser's `localStorage` right now.
- **`EphemeralSettingsMode` makes blind migration unsafe to assume-in-general, but doesn't block
  it for the normal case.** Per [`PlatformSettings.kt:6-25`](kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformSettings.kt:6-25) and its use in
  [`Main.kt:394-408`](kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt:394-408): `EphemeralSettingsMode.enable()` is called *first*, before
  anything else touches settings, on the "open temporarily" boot path, and from that point every
  `PlatformSettings()` instance is transparently redirected to an in-memory map instead of real
  `localStorage`. That means:
  - In an ephemeral session, `PlatformSettings().getString("githubToken", "")` reads the
    **in-memory ephemeral map**, not real persisted data — so a one-shot "read `githubToken` from
    `PlatformSettings` and copy it into the new `CredentialStore`" migration running during an
    ephemeral session would either find nothing (safe, just a no-op) or — if it ran *before*
    `EphemeralSettingsMode.enable()` in some future code path — could read a real persisted token
    and copy it into what's supposed to be a temporary, non-persistent session. **Any migration
    step must run after `EphemeralSettingsMode.enable()` (if applicable) and must itself respect
    the flag** (skip migration or write only to the ephemeral in-memory store) exactly as
    `PlatformSettings` does per-call, not as a one-time boot-order assumption.
  - In the normal (non-ephemeral) case this is a straightforward one-shot migration: read
    `githubToken`/`githubOwner`/`githubRepo`/`githubBranch` once, and if non-blank, write the
    token into the new `CredentialStore` under `"git_https_token_$graphId"` (needs a `graphId` —
    only resolvable once a graph exists, so this can't run at the same generic boot-time point as
    `LlmCredentialMigration`; it's graph-scoped, closer to how `GraphManager.removeGraph` already
    does per-graph credential cleanup at `:564-566`).
- **Recommendation for research→planning handoff**: a real migration (read-once,
  mark-done-per-graph, matching `LlmCredentialMigration`'s idempotent-flag pattern) is preferable
  to a "clean cutover, re-enter credentials" if it's cheap — the whole point of this project is to
  stop making web users re-enter secrets. But it is **not free**: it needs (a) a per-graph
  "already migrated" flag (to stay idempotent — same reasoning as
  `LlmCredentialMigration.KEY_MIGRATED`), (b) to not fire during ephemeral sessions, and (c) to
  plug into the same graph lifecycle point that already deletes `PlatformSettings`'
  `githubToken`-family keys once `persistWebGitCredentials` itself is retired — otherwise a stale
  plaintext copy lingers in `localStorage` even after the "real" copy is safely in the encrypted
  store. This should be scoped explicitly in the plan (in-scope: "Reconciling
  `persistWebGitCredentials`..." per requirements' Scope section), not left implicit.
- A **clean cutover (re-enter once)** is the simpler, lower-risk alternative if the plan finds the
  above migration too fiddly for a medium-appetite project — requirements.md's Risk Control
  section already says "no migration of existing data is needed since the no-op backend never
  stored anything to migrate," which is true for `CredentialStore` itself but glosses over the
  fact that `persistWebGitCredentials`'s **separate** `PlatformSettings` channel *does* have real
  data today. Planning should decide explicitly rather than let this fall through the gap between
  "CredentialStore has nothing to migrate" (true) and "the workaround's plaintext copy has
  something to migrate" (also true, different store).

## 5. Unstated user needs vs. realistic ceiling

Reasonable web-user expectations, and what's actually achievable per the constraints already
locked in requirements.md (no OS keychain, no hardened secret store — explicitly out of scope):

- **"My saved GitHub PAT should survive closing the tab."** — Achievable and is the core ask.
  Matches JVM/Android's bar (survive process restart) as long as `localStorage`/`IndexedDB`
  survive (they do, barring the user clearing site data — same ceiling `PlatformSettings`
  already accepts for every other web-persisted preference).
- **"I shouldn't have to re-enter my Anthropic API key every session."** — Same answer; this is
  the concrete LLM-key gap requirements.md's Problem Statement §3 identifies as currently
  unaddressed even by a workaround (git has one, LLM keys have none).
  `LlmProviderRegistryFactory`'s synchronous read at registry-build time (§2) means this
  expectation is only fully met if the cold-start race is closed — a key that "usually" survives
  reload but occasionally doesn't (racing the async warm-up) would read to users as flaky/
  untrustworthy, worse in perception than a store that's honestly always-empty.
- **"My saved connection should just work when I add a second graph on the same GitHub account."**
  — This is `GitCredentialConnectionStore`'s whole purpose (§1 kdoc: "Configuring git sync on a
  second graph against the same account reuses a saved connection instead of re-entering a PAT or
  redoing the GitHub OAuth device flow") — currently silently broken on web per requirements.md
  Problem Statement §1. Fixing `CredentialStore` is described as sufficient to fix this
  (`GitCredentialConnectionStore` needs no code change) — worth a `wasmJsTest` specifically
  exercising the reuse flow, not just isolated store/retrieve round-trips, since that's the
  actual user-facing feature this unblocks.
- **What users should *not* expect** (and the design must not imply otherwise): protection from
  another script running in the same origin (XSS), from someone with local devtools/profile-
  directory access, or from a shared-computer scenario without OS-level user separation. The
  requirements doc already states this ceiling explicitly ("protects against casual snooping but
  not a determined local attacker," matching JVM's own documented threat model) — worth carrying
  the same one-line caveat into any user-facing copy near the credential-entry UI if planning adds
  one, so the security bar isn't silently overclaimed to end users the way `persistWebGitCredentials`'s
  plaintext-in-`localStorage` implicitly *underclaims* it today.
- **Unstated tension**: users expect "instant" credential entry (typed a PAT, hit save, it's
  saved) — the NFR in requirements.md already sets a <100ms budget with no network round-trip.
  Combined with the async-WebCrypto problem (§1), this means encryption must not be perceived as
  adding latency to the *save* path even though the underlying crypto primitive is Promise-based
  — another argument for the VaultCredentialStore-style pattern (synchronous cache write returns
  immediately to the UI; the async encrypt-and-persist happens in the background, fire-and-forget,
  logged on failure like every other actual already does).

## Summary of design-relevant findings for planning

1. **Sync-vs-async is the central tension**, and `VaultCredentialStore` is the existing in-repo
   template for solving it (in-memory cache, async warm/flush, synchronous reads against cache).
   `LlmProviderRegistryFactory` (registry build) and `GitSetupScreen`'s field-population reads are
   concrete synchronous call sites that will race a cold cache if warm-up isn't complete by the
   time they run — this needs an explicit answer in the plan, not just "cache it."
2. **No DI/singleton exists for `CredentialStore` today** (`CredentialStore()`/
   `LlmCredentialStore(CredentialStore())` constructed ad hoc at every call site, confirmed at
   `GraphManager.kt:190/564/720` and `App.kt:466`) — any warmed cache or derived key material must
   live in a `companion object`/top-level singleton scoped to the wasmJs actual itself, not
   instance state, since instances don't persist across call sites.
3. **`storeBlocking()` needs an explicit override**, not the default `store()`+read-back, if the
   underlying write is at all asynchronous — `LlmCredentialMigration` deletes the only plaintext
   copy of a key immediately after a `true` result, exactly the failure mode Android/JVM already
   guard against with their own overrides.
4. **`persistWebGitCredentials`'s plaintext `PlatformSettings` channel is real, live, user-visible
   data today** — reconciliation is a migration-shaped problem (with an `EphemeralSettingsMode`-aware
   gate), not just a "delete the workaround" cleanup, if planning wants to preserve existing users'
   saved tokens rather than force a one-time re-entry.
5. **Key naming is already fully conventionalized** (`git_https_token_$graphId`,
   `git_github_oauth_$graphId`, `git_ssh_passphrase_$graphId`, `git_connection_$id`,
   `llm.<providerId>.api_key`) — the new actual needs zero special-casing of key shapes; it's a
   pure opaque-string store, matching every other actual.
