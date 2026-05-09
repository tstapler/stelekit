# Paranoid Mode — Requirements

## Overview

Paranoid Mode is an opt-in security feature for SteleKit that encrypts all graph files on disk, ensuring that a user's notes cannot be read even if the device is lost, seized, or compromised. Files are always ciphertext at rest; the app transparently decrypts on read and encrypts on write while the graph is unlocked.

---

## Goals

1. **At-rest confidentiality** — every markdown file and asset in a paranoid-mode graph is encrypted on disk at all times.
2. **Multi-provider key management** — a LUKS2-style keyslot system: a single master Data Encryption Key (DEK) is wrapped by one or more independent key providers. Any registered provider can unlock the graph; removing a provider doesn't require re-encrypting content.
3. **Plausible deniability** — a hidden-volume scheme (VeraCrypt-style): two different passphrases open two distinct, independent graphs stored in the same encrypted container. An adversary with one passphrase cannot prove a second graph exists.
4. **Transparent I/O** — decryption happens on read; encryption on write. No explicit "lock" step; locking is equivalent to closing the graph.
5. **Algorithm agility** — ChaCha20-Poly1305 as the default AEAD cipher for all file content. Key wrapping uses a separate KDF + AEAD per keyslot.

## Non-Goals (v1)

- iOS / macOS platform support (future work)
- Re-encryption of existing plaintext graphs in place (user must export and re-import)
- Encrypted sync / cloud backup integration
- Search over ciphertext (all search operates on the in-memory decrypted state)

---

## Functional Requirements

### FR-1 Paranoid Mode Toggle

- A graph can be created in paranoid mode or converted to paranoid mode at creation time.
- A paranoid-mode graph is identified by a `.stele-vault` metadata header file in the graph root that stores keyslot headers and vault parameters (never the DEK).

### FR-2 Encrypted File Format

- Each file in a paranoid-mode graph is individually encrypted.
- Format per file:
  - 4-byte magic: `STEK` (SteleKit Encrypted Key)
  - 1-byte version field
  - 12-byte random nonce (per-file, per-write)
  - Ciphertext (ChaCha20-Poly1305, keyed with per-file subkey derived from DEK + file path)
  - 16-byte Poly1305 auth tag
- Subkey derivation: `HKDF-SHA256(DEK, salt=file_path_utf8, info="file")` → 32-byte subkey per file.

### FR-3 LUKS2-style Keyslot System

- The vault header stores N keyslots (N ≥ 1, soft max 8).
- Each keyslot stores: `Argon2id(provider_secret) XOR DEK`, plus the Argon2id parameters.
- Supported provider types:
  - **Passphrase** — user-entered text; KDF is Argon2id with tunable memory/time params.
  - **Key file** — arbitrary file; its SHA-256 hash is used as the provider secret.
  - **OS keychain** — the DEK is wrapped using the platform's native secure storage (JVM: OS keychain via java.security; WASM: Web Crypto API / SubtleCrypto with non-extractable key).
- Providers can be added and removed independently without re-encrypting graph content.

### FR-4 Plausible Deniability / Hidden Volume

- The vault supports two independent DEKs occupying two separate keyslot namespaces: **outer** and **hidden**.
- The outer namespace is the decoy; the hidden namespace is the sensitive data.
- On unlock, the app tries each registered provider against both namespaces; whichever namespace yields a valid MAC authenticates as that graph.
- The hidden graph's files are stored interleaved with (and indistinguishable from) outer graph ciphertext.
- The header gives no indication of how many namespaces exist or which keyslots belong to which namespace.
- **UX**: "Open as hidden graph" is a separate, non-prominent action (e.g., keyboard shortcut or advanced settings).

### FR-5 Unlock Flow (Desktop JVM)

1. User opens a paranoid-mode graph directory.
2. App presents an unlock dialog: passphrase field + optional "Use key file" toggle.
3. App derives the provider secret, iterates keyslots, attempts to unwrap DEK.
4. On success: graph is loaded into memory; on-disk files remain encrypted.
5. On failure after N attempts: app shows error; does not rate-limit at app level (Argon2id's memory cost is the rate-limiter).

### FR-6 Unlock Flow (Web/WASM)

- Same logical flow; key derivation uses WebCrypto PBKDF2 or Argon2id WASM port.
- OS keychain provider uses IndexedDB-backed non-extractable Web Crypto key.
- Hidden volume support included (same logic as desktop).

### FR-7 Lock / Session Expiry

- Locking a graph: clears the in-memory DEK and cached plaintext. Equivalent to closing the graph.
- Optional: auto-lock after configurable idle timeout (default: never).
- App settings surface: per-graph paranoid-mode status, registered providers, idle timeout.

### FR-8 Key Rotation

- A user can re-wrap the DEK with a new set of providers without re-encrypting any file content (only the keyslot headers change).
- A full re-encryption option is available in settings to change the DEK itself (re-encrypts all files).

---

## Non-Functional Requirements

| ID | Category | Requirement |
|----|----------|-------------|
| NFR-1 | Security | ChaCha20-Poly1305 AEAD for content; nonces are random 96-bit values, never reused. |
| NFR-2 | Security | Argon2id parameters: ≥ 64 MiB memory, ≥ 3 iterations, parallelism ≥ 1 (tunable). |
| NFR-3 | Security | DEK lives only in memory; never written to disk in plaintext. Cleared on lock. |
| NFR-4 | Security | Vault header is authenticated (AEAD over header bytes) to detect tampering. |
| NFR-5 | Security | Hidden-volume header segment is indistinguishable from random bytes to an observer. |
| NFR-6 | Performance | Unlock time ≤ 5 s on low-end hardware (Argon2id parameters must be benchmarked at vault creation). |
| NFR-7 | Performance | Per-file encrypt/decrypt adds ≤ 5 ms overhead for files ≤ 100 KB. |
| NFR-8 | Usability | Paranoid mode must not change the editing UX once the graph is unlocked. |
| NFR-9 | Compatibility | Encrypted graph format is versioned; future format changes must include a migration path. |
| NFR-10 | Platforms | Desktop JVM + Web/WASM required for v1. |

---

## Platform Notes

### Desktop JVM
- Crypto: `javax.crypto` (ChaCha20-Poly1305 available since Java 11) + BouncyCastle for Argon2id.
- Key storage (OS keychain slot): `java.security.KeyStore` with OS-backed keystore (`KeychainStore` on macOS, `PKCS11` on Linux, `Windows-MY` on Windows).

### Web/WASM
- Crypto: `SubtleCrypto` (AES-GCM available natively; ChaCha20 via WASM port e.g. `libsodium.js` or `tink-wasm`).
- Argon2id: `argon2-browser` WASM port.
- OS keychain slot: `CryptoKey` with `extractable: false` stored in IndexedDB.
- Hidden volume: full support (same protocol).

---

## Acceptance Criteria

1. A new graph created in paranoid mode has no plaintext files on disk after the first save.
2. Any registered provider (passphrase, key file, OS keychain) can independently unlock the graph.
3. Adding or removing a provider does not re-encrypt graph content.
4. Opening the graph with the hidden-graph passphrase shows different content than the outer passphrase.
5. An adversary with only the outer passphrase cannot detect the hidden volume's existence.
6. Locking the graph clears the DEK from memory within 1 second.
7. All acceptance criteria pass on Desktop JVM and Web/WASM.

---

## Out of Scope / Future

- iOS / macOS / Android
- Encrypted search index
- Cloud sync of encrypted graphs
- Hardware token (FIDO2 / YubiKey PIV) as a key provider
- Multi-user shared encrypted graphs
