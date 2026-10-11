# ADR-001: Web Credential Encryption Key Material and Threat Model

**Status**: Proposed (flagged for reviewer sign-off, per requirements.md's "state explicitly rather than overclaiming security")
**Date**: 2026-09-21
**Context**: project_plans/web-credential-persistence

## Decision

The wasmJs `CredentialStore` actual encrypts values at rest with AES-256-GCM using a key that is:

- generated once via `crypto.getRandomValues()` (32 random bytes),
- imported as a `CryptoKey` via `crypto.subtle.importKey('raw', ...)`,
- persisted **in the same `localStorage` origin as the ciphertext**, under a dedicated key
  (`stelekit_credential_key`).

## Context

Unlike JVM (`JvmCredentialStore`, `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/security/JvmCredentialStore.kt:52-60`),
which derives its AES key from machine-bound entropy (`user.name` + `os.name`) so the key
material never has to be written anywhere, a browser sandbox has no durable, non-guessable
per-device identifier a script can read that an attacker with the same storage access couldn't
also read. Any key we persist for reuse across reloads has to live somewhere the app itself can
read back — and anywhere it can read from, an attacker with the same storage access can too.

## Alternatives Considered

| Alternative | Rejected because |
|---|---|
| Derive key from a `sessionStorage`-scoped identifier | Does not survive a reload/tab close — defeats success metric #1 (persist across reload) outright. |
| No encryption — rely on browser-origin sandboxing alone | Materially weaker than "encrypted at rest"; fails the stated success metric ("values unreadable by casually inspecting browser storage in devtools" — plaintext is readable). Also the status quo `persistWebGitCredentials` already ships, which this project exists to fix. |
| Non-extractable `CryptoKey` persisted as a handle in IndexedDB (no raw key bytes ever readable from JS) | Real, narrower mitigation against storage-dump/backup-file/malicious-extension-with-storage-read-only attackers (see research/pitfalls.md §1) — but does **not** defend against live XSS in this app's own page (non-extractability blocks *exporting* the key, not *using* it), and requires IndexedDB, which research/stack.md §3 already rejected as the storage backend (async-only API, no usable Kotlin bindings, no payload-size benefit at credential-store scale). Introducing IndexedDB solely to get a marginally narrower threat-model win, on top of the localStorage backend already chosen, was judged disproportionate for a "casual snooping, not determined attacker" bar this project explicitly targets. |
| **Chosen: random key generated once, persisted alongside ciphertext in `localStorage`** | Provides near-zero protection against an attacker who already has `localStorage` read access (they read the key next to the ciphertext) — but this is **not materially weaker than JVM's own scheme**: `JvmCredentialStore`'s key is derived from `user.name`/`os.name`, both trivially readable by anyone with local access to the same machine, i.e. exactly the attacker class JVM's own KDoc already excludes. Both schemes defend the same threat: a plaintext-in-devtools/plaintext-in-a-backup-file casual read, not a co-resident/same-origin-script attacker. |

## Consequences

- The new `CredentialStore`'s class KDoc must state this ceiling explicitly, mirroring
  `JvmCredentialStore.kt:28-31`'s self-disclaimer verbatim in spirit: *"Protects against casual
  snooping (plaintext-in-devtools, plaintext in a browser data export/backup) but not against a
  determined attacker with script execution in the same origin (XSS) or direct `localStorage`
  file access — that attacker can read the key next to the ciphertext. This matches, not
  exceeds, JVM's own documented threat model."*
- `GitSetupScreen.kt:1246`'s existing "Token is encrypted on disk using device-specific keys"
  copy is **not accurate** for this scheme (there is no "device-specific" binding on web — the
  key is random, not derived from any device identifier) and must be corrected as part of this
  project (see plan Epic 1.5).
- If a future reviewer wants a stronger web threat model (non-extractable `CryptoKey` + IndexedDB),
  that is a separate, larger follow-up — not silently folded into this project's medium-appetite
  scope.

## Reviewer Sign-off

This is a security-posture choice, not purely an engineering one — flagging per requirements.md's
own instruction ("state this explicitly rather than overclaiming security") for explicit owner
acknowledgment before implementation starts.
