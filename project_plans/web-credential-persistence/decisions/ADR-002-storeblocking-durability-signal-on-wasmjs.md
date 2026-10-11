# ADR-002: `storeBlocking()` Durability Signal on wasmJs

**Status**: Accepted — requirements.md's success-metric wording was corrected during Phase 3
review to match this decision (see requirements.md's Success Metrics section, "Correction during
planning"); no reviewer sign-off gate remains.
**Date**: 2026-09-21
**Context**: project_plans/web-credential-persistence

## Decision

The wasmJs `CredentialStore.storeBlocking(key, value)` override **always returns `false`**,
never claiming durability, with a KDoc explaining precisely why — matching
`CredentialAccess.storeBlocking()`'s own documented contract: "correctly reports `false` for a
no-op/non-persisting backend ... rather than falsely claiming durability for a write that never
actually happened" (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/security/CredentialAccess.kt:25-39`).

## Context

`WebCrypto`'s `SubtleCrypto.encrypt` is unconditionally `Promise`-based — there is no synchronous
variant anywhere in the spec. Kotlin/Wasm's browser target is single-threaded with no primitive to
block the calling function on a `Promise`'s resolution (no `Atomics.wait`-on-`SharedArrayBuffer`
available in a plain, non-cross-origin-isolated tab). `CredentialAccess.storeBlocking()` is a
plain, non-`suspend` function — by the interface's own contract (which this project's Constraints
section says must not change shape). **There is therefore no way to implement a literally
synchronous, durable-before-return write using real AES-GCM encryption on wasmJs within the
current interface signature.** This is a structural limitation of the platform, not an
implementation gap to engineer around.

`LlmCredentialMigration.migrateKey` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmCredentialMigration.kt:116-153`)
is the one real caller: it calls `storeBlocking`, and **only if it returns `true`** does it then
irreversibly delete the one plaintext copy of a legacy API key (`clearPlaintext()`). A false
positive here — reporting `true` before the async encrypt-and-persist has actually landed — would
let this migration destroy the only copy of a user's key while believing the write succeeded,
exactly the bug `AndroidCredentialStore`/`JvmCredentialStore` already override `storeBlocking()`
to avoid (see their kdocs).

## Alternatives Considered

| Alternative | Rejected because |
|---|---|
| Default interface delegation (`store()` then `retrieve() == value`) | Reports `true` immediately after `store()` updates the in-memory cache — before the async WebCrypto-encrypt-and-`localStorage`-persist has actually landed. This is precisely the false-positive-durability trap research/pitfalls.md §4/§5 warns against. |
| A second, synchronous-only cipher (e.g. XOR obfuscation using already-resident raw key bytes) used only for `storeBlocking`, bypassing WebCrypto entirely for this one call path | Technically genuinely synchronous (localStorage.setItem is itself synchronous) and so could legitimately report `true` — but introduces a second, weaker, undocumented crypto scheme purely to serve a migration path that (per research/features.md §2) is currently a no-op on web with no live user data at risk. Judged disproportionate: adds a real security-review surface (a hand-rolled stream cipher, exactly what build-vs-buy.md says never to reimplement) to protect a code path that does nothing today. |
| Widen `CredentialAccess.storeBlocking` to `suspend` | Out of scope per requirements.md's Constraints (`CredentialAccess`'s contract must not change shape), and would not even fully solve the problem — several other `CredentialAccess` call sites (Compose `remember{}`, `GraphManager.removeGraph`) structurally cannot suspend regardless (research/architecture.md §1). |
| **Chosen: always return `false`, with explanatory KDoc** | Honest, matches the interface's own documented "no-op backend" carve-out, and is safe: `LlmCredentialMigration` will retry (log a warn) on every launch but will never destroy the plaintext source, since `clearPlaintext()` only runs after a confirmed `true`. |

## Consequences

- `LlmCredentialMigration` effectively never completes on wasmJs — it retries indefinitely,
  logging a warning each launch, but never loses data. Acceptable because this migration path is
  presently unreachable/no-op on web (legacy `VoiceSettings`/Anthropic/OpenAI plaintext keys are a
  JVM/Android-era concern — research/features.md §2 confirms no evidence of live data at risk here
  today).
- This was originally in tension with a literal reading of requirements.md's success metric ("now
  reports `true` on wasmJs for a successful write instead of `false`"). **Resolved during Phase 3
  review**: requirements.md's Success Metrics section was corrected to state explicitly that
  wasmJs's honest `false` is the accepted, correct behavior — the original wording assumed
  durability could be verified before returning, which WebCrypto's unconditionally `Promise`-based
  API and Kotlin/Wasm's single-threaded runtime make structurally impossible. This ADR's decision
  is therefore the authoritative, final answer, not a pending proposal.
- If a future project needs `LlmCredentialMigration` to actually complete on web, the honest fix is
  widening `storeBlocking` to `suspend` (a larger, separately-scoped interface migration) — not a
  synchronous workaround on this platform.

## Reviewer Sign-off

Resolved — no longer needed. requirements.md's success-metric wording was corrected during Phase
3 review (see plan.md's "Resolved During Planning" section), removing the tension this section
originally flagged. Task 1.3.3a (see plan.md) ships this decision with no further gate.
