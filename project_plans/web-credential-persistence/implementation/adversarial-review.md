# Adversarial Review: web-credential-persistence
**Date**: 2026-09-21
**Verdict**: CONCERNS

## Blockers

(none — both original blockers verified resolved; see return summary for detail)

## Concerns

- [ ] **Migration idempotency (Task 1.4.2a) still depends on `preload()` being a direct suspend call rather than `scope.launch{}`-wrapped — a dependency stated only in prose, with no explicit warning at the task that would prevent the mistake, and no test.** `main()` already contains sibling fire-and-forget `scope.launch{}` blocks nearby (`Main.kt:133`, `199`), so an implementer wiring in `CredentialStore.preload()` per Task 1.2.2a's current wording ("add `CredentialStore.preload()` immediately after...") could plausibly wrap it the same way, leaving `migrateLegacyGitCredential()`'s idempotency check (`CredentialStore().retrieve(targetKey) != null`) to run against a still-cold cache. This was flagged in the prior review and was not addressed by this repair round — grep confirms no "direct suspend call, not `scope.launch{}`" warning was added anywhere in Task 1.2.2a or elsewhere in plan.md.
  — **Recommendation**: add an explicit "must be a direct suspend call, not `scope.launch{}`" warning to Task 1.2.2a, and/or add a Phase 2 test that exercises the boot ordering itself rather than just the two functions' individual behavior.

## Minors

- requirements.md's "Correction to the triage source item" section still cites `GitCredentialConnectionStore.kt:60` for `credentialStore.retrieve(...)`; verified via grep the actual call site is line 48 (inside `getSecret()`). Pre-existing citation drift, untouched by this repair round since it only corrected the `storeBlocking()` success-metric wording.
- `isAvailable()` (Task 1.3.2a) is still deliberately not gated on `cryptoKey`/preload-completion state, and now that Task 1.2.1b's top-level `try`/`catch` genuinely prevents a `preload()` failure from crashing boot (Blocker #1, resolved), the residual documented-but-unaddressed symptom is real: `isAvailable()` can report `true` while `cryptoKey` is permanently `null` and writes silently never persist for that session. Still no one-line KDoc caveat added at Task 1.3.2a noting this.
- Task 1.2.1b's per-entry decrypt-failure log ("not fatal") still doesn't distinguish "wrong/rotated key" from "corrupted storage" — observability nuance only, Observability Plan already declares metrics out of scope.
- The Dependency Visualization ASCII diagram's arrow flow is ambiguous after the new Task 1.4.3b block was inserted: the `| v` leading into `Epic 1.5` is left-aligned under the `1.3.1a` column rather than under the newly-inserted `Task 1.4.3b` box, making it visually unclear whether Epic 1.5 depends on Epic 1.4.3/1.4.3b or is a leftover arrow from Epic 1.3. Cosmetic only — no task text depends on the diagram for sequencing (the prose Migration Plan/Risk Control sections state ordering explicitly).
