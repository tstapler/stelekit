# Architecture Review: web-credential-persistence
**Date**: 2026-09-21
**Verdict**: CONCERNS

## Constitution Violations
- [ ] None — no `docs/adr/ADR-000-architecture-constitution.md` found in the repository
  (`docs/adr/` contains ADR-001 through ADR-019 with no ADR-000; confirmed by directory listing).

## Blockers
None. The design is a straightforward `actual` fill-in behind a stable, unchanged interface, and
the two flagged ADRs are honestly self-aware about their tradeoffs. Nothing here is structurally
unfixable after the fact — the items below should be resolved before/during implementation, not
before the plan can proceed.

## Concerns

- **Task 1.3.3a / ADR-002 — `LlmCredentialMigration` is wired on web and will retry-and-log
  forever if any legacy plaintext key exists, with no gating to detect or short-circuit that.**
  `LlmCredentialMigration.runIfNeeded()` **is** called from `App.kt:489` inside commonMain
  (confirmed — `grep -rl "LlmCredentialMigration("` finds only `LlmCredentialMigration.kt` and
  `App.kt`; `App.kt` is not platform-gated, so this runs on wasmJs too), contradicting the plan's
  and ADR-002's framing that this migration path is merely "presently unreachable/no-op on web."
  It is reachable: `VoiceSettings.getAnthropicKey()`/`getOpenAiKey()` read through the
  platform-agnostic `Settings` interface (`PlatformSettings` on web, i.e. `localStorage`), so any
  web user who set a legacy voice-provider key before `LlmCredentialStore` existed has live data
  here. With `storeBlocking()` hard-coded to `false` (Task 1.3.3a), `migrateKey()` will return
  `false` for that user on *every single app launch, forever* — `migrateCredentialsIfNeeded()`
  never sets `KEY_MIGRATED`, so `runIfNeeded()` reattempts and logs a warning on every boot,
  permanently, with no path to resolution. This isn't just "migration never completes" (which
  ADR-002 does disclose) — it's an unbounded per-launch retry+log with no rate limiting or
  escape hatch, and the ADR's own "no live data at risk" justification is unverified (it should
  be a one-line grep/data check, not an assumption, per this project's own evidence-before-claims
  standard).
  **Remediation**: either (a) verify via a data check whether any real web install can have a
  legacy `VoiceSettings` plaintext key (if truly impossible on web — e.g. the feature predates the
  wasmJs target — state that as a verified fact in ADR-002, not an inference), or (b) add a
  platform capability check so `migrateCredentialsIfNeeded()` marks itself resolved once instead
  of retrying indefinitely when `storeBlocking` is structurally guaranteed to always return
  `false` on this platform (e.g. an `isDurabilityGuaranteed: Boolean` capability flag on
  `CredentialAccess`, defaulting `true`, overridden `false` on wasmJs, that
  `LlmCredentialMigration` checks to fall back to a "best-effort store + mark done anyway" path on
  platforms that can never honestly report `true`).

- **Task 1.4.1a / Epic 1.4.3 — the dual credential-resolution path in `configResolver` is not
  actually cleaned up by the "remove last" step.** Task 1.4.1a repoints `configResolver` to:
  `CredentialStore().retrieve(it) ?: PlatformFileSystem.githubToken ?: ""`. Task 1.4.3a removes
  `persistWebGitCredentials` (the *writer* of the legacy `PlatformSettings` `githubToken` plaintext
  channel, confirmed at `GitSetupScreen.kt:1554-1568`) and its call sites, but nothing in the plan
  ever removes the `?: PlatformFileSystem.githubToken` fallback expression itself, nor the
  boot-time read that populates `PlatformFileSystem.githubToken` from `PlatformSettings`
  (`Main.kt:163-176`, confirmed present). After 1.4.3a ships, that fallback becomes permanent dead
  weight (nothing but the one-shot migration, Task 1.4.2a, ever writes `githubToken` again, and
  migration immediately clears it after copying) — but it's still live code, still a second
  credential-resolution path a future reader has to reason about, and the Tech Debt Disposition
  table's "isolate via seam, then refactor out (remove last)" framing implies the seam itself goes
  away, which it doesn't. This matches the reviewer's own flagged tension (plan's Unresolved
  Questions doesn't list it, but the "Extending Bad Architecture" triage in this skill would call
  this an incomplete Strangler Fig — the strangler vine isn't fully cut).
  **Remediation**: add a Task 1.4.3b (or fold into 1.4.3a) that removes the
  `?: PlatformFileSystem.githubToken` fallback and the now-dead `PlatformFileSystem.githubOwner/
  githubRepo/githubToken` companion-field wiring in `Main.kt:163-176`, sequenced after 1.4.2a's
  migration is verified to have run for all users (or explicitly justify keeping the fallback
  permanently, e.g. as a documented escape hatch for `STELEKIT_GIT_TOKEN`-equivalent manual
  override — but state that explicitly rather than leaving it as unexplained legacy debris).

- **Task 1.3.1a — `persistCredential`'s `cryptoKey!!` is not structurally guarded against being
  called before `preload()` completes, and the failure mode is a silent, permanent data-loss
  footgun.** The plan's boot-sequencing (Task 1.2.2a: `preload()` before `ComposeViewport` mounts
  on both boot paths) protects the two blessed entry points, but nothing in `CredentialStore`
  itself prevents a future call site — a Web Worker, a Service Worker background sync, a test that
  forgets to call `preload()`/`resetForTest()` in the right order, or simply a call reached before
  the awaited `preload()` in `main()` finishes if that ordering ever regresses — from calling
  `store()` before `cryptoKey` is set. `store()` itself never throws (it writes to `decryptedCache`
  synchronously and fires-and-forgets the persist), so `retrieve()` will happily return the value
  from cache, giving every appearance of success — but `persistCredential`'s `cryptoKey!!` will
  NPE, get caught by the task's own `try/catch (e: Throwable)`, and silently swallow the failure.
  The credential then exists only in memory and vanishes on next reload with no error surfaced
  anywhere. This is exactly the type of illegal state the `type-driven-design` lens flags:
  `cryptoKey: JsAny?` plus a bare `!!` lets "cache is being written to but the key was never
  loaded" become a reachable, silently-wrong runtime state instead of a compile-time
  impossibility.
  **Remediation**: have `persistCredential` (or a shared `ensureCryptoKey()` suspend helper it
  calls first) lazily await `preload()`'s completion — e.g. `preload()` itself sets a
  `CompletableDeferred<JsAny>` for `cryptoKey` that `persistCredential` awaits — so a call before
  boot-sequenced `preload()` finishes queues rather than NPEs. This turns "some ad hoc call site
  forgot the ordering" from a silent data-loss bug into a harmless, self-healing wait.

## Nitpicks
- Primitive-obsession-by-string-key (`"git_https_token_$graphId"`, `"llm.<providerId>.api_key"`)
  is pre-existing codebase convention (also used unchanged by `JvmCredentialStore`,
  `VaultCredentialStore`) — the plan doesn't worsen it, and introducing a typed key wrapper here
  alone (without touching the other two actuals) would create inconsistency rather than reduce it.
  Fine to leave as-is for this project's scope; a candidate for a separate cross-platform
  type-driven-design pass if ever revisited.
- The companion object accumulates several responsibilities (key bootstrap, cache, crypto
  orchestration, ephemeral gating, localStorage I/O) — consistent with `JvmCredentialStore`'s and
  `VaultCredentialStore`'s existing shape for the same interface, so this is parity, not a new
  violation. Worth a shared observation only: all three `CredentialAccess` actuals have this same
  SRP looseness, and a future consolidation (e.g. extracting a shared
  `EncryptedKeyValueCache<K>` helper) could reduce duplication across all three at once — out of
  scope for this project.
- Test-only backdoors (`resetForTest()`, `flushForTest()`) exposed directly on the production
  companion object are a pre-existing pattern in this codebase (plan cites
  `HostDirectorySyncConstructionTest.kt` as precedent) — consistent with convention, not a new
  smell introduced here.
- `storeBlocking()`'s always-`false` KDoc (Task 1.3.3a) should explicitly cross-reference ADR-002
  by ID/path in the code comment (the plan's task description already quotes the reasoning, but
  make sure the shipped KDoc keeps the pointer to the ADR file, not just a paraphrase, so a future
  reader can find the full alternatives-considered table).
