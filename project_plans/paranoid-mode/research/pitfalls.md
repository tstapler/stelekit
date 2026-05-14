# Pitfalls Research — At-Rest Encryption Attack Surface for a Note-Taking App

## Summary

This document catalogs the known failure modes, attack vectors, and design traps for at-rest encryption in an application like SteleKit, with specific attention to the ChaCha20-Poly1305 + LUKS2-style + hidden-volume design.

---

## 1. Nonce Reuse (Critical)

### The Problem

ChaCha20-Poly1305 is an AEAD cipher with a 96-bit (12-byte) nonce. If the same (key, nonce) pair is ever reused to encrypt two different plaintexts:

- The XOR of the two ciphertexts equals the XOR of the two plaintexts — keystream is revealed
- The Poly1305 authentication key is exposed, allowing forgery of arbitrary messages
- All past and future messages encrypted with that key are compromised

This is catastrophically worse than AES-CBC nonce reuse.

### Attack Scenarios for SteleKit

1. **Crash mid-write**: if the app generates a nonce, begins writing, crashes, and on restart re-uses the same nonce (from an in-memory state that was not persisted to disk), the next write reuses it.
2. **Backup restore**: user restores a file from backup. The backup has an older nonce+ciphertext. The app writes the same file with the same DEK subkey — if nonce generation is time-based or counter-based and was reset by the restore, collision is possible.
3. **Parallel writers**: two coroutines both generating nonces at the "same instant" from a poorly seeded `Random` instead of `SecureRandom`.

### Mitigations

- Always use `SecureRandom` (JVM) or `crypto.getRandomValues()` (WASM) for nonce generation — never counters or timestamps
- Use `KotlinCrypto/random` in commonMain for platform-safe CSPRNG
- The 96-bit nonce with random generation gives 2^48 safety margin before birthday collision probability reaches 1% (at 2^32 encryptions per key, per NIST SP 800-38D). Use per-file subkeys from HKDF to further reduce per-key nonce exposure.
- **Never reuse a `Cipher` instance** for a second encryption — always re-initialize with a freshly generated nonce (JDK documentation explicitly states this for ChaCha20-Poly1305)

---

## 2. DEK Persistence in JVM Memory

### The Problem

The JVM heap is managed by the garbage collector. When a `ByteArray` holding the DEK goes out of scope, the GC may not zero the memory before reuse. Sensitive bytes can linger in the heap for the process lifetime and may appear in:

- JVM crash dumps (hs_err_pid*.log)
- Operating system swap files / pagefile.sys
- Hibernation files (Windows hiberfil.sys, Linux /swapfile)
- Memory dumps via cold boot attack or forensic tools

Additionally, Java `String` objects are immutable and interned — if a passphrase is stored as a `String` at any point, it cannot be zeroed.

### Mitigations

- Use `CharArray` for passphrase input (not `String`), and call `charArray.fill(0.toChar())` after use
- Use `ByteArray` for DEK and subkeys; call `byteArray.fill(0)` after lock
- `SecretKeySpec.destroy()` exists but is not guaranteed to zero the backing array on all JVM implementations — zero the input `ByteArray` before passing to `SecretKeySpec`
- For critical paths, use `com.sun.jna.Memory` (JNA-pinned native memory) for the DEK, which is not subject to GC relocation. This is complex but provides stronger guarantees on JVM.
- **On lock**: zero DEK ByteArray, nullify all references, and call `System.gc()` (hint only — not a guarantee). Best-effort is acceptable; document this limitation clearly.
- Do not log passphrase, DEK bytes, or subkey bytes — disable debug logging for the vault layer in production builds

---

## 3. Cold Boot and Memory Dump Attacks

### The Problem

While the vault is unlocked, the DEK lives in RAM. A physical attacker with access to the running machine can:

1. Freeze RAM with liquid nitrogen and remove DIMMs (cold boot attack — DRAM remanence lasts seconds to minutes at low temperature)
2. Dump process memory via `/proc/PID/mem` (Linux, requires root or ptrace), `Task Manager → create dump`, or forensic tools
3. Read hibernation or swap files that captured RAM while the vault was unlocked

The swap file concern is particularly acute: the JVM can swap DEK-containing heap pages to disk at any time.

### Mitigations

- **JVM memory locking**: `java.lang.ProcessBuilder` + platform `mlock()` via JNI or JNA can pin pages. Complex but feasible for a security-focused app. `mlockall(MCL_CURRENT|MCL_FUTURE)` prevents swapping of the entire process. (**High complexity — consider as v2**)
- **Minimum unlock time**: keep the DEK in memory only while actively in use; lock as soon as the user closes the graph or the idle timeout fires (FR-7)
- **Threat model documentation**: document clearly that cold boot attacks and root-level memory dumps defeat at-rest encryption by definition. Paranoid Mode protects against theft of a *locked* device, not a *running* device under physical control.
- Auto-lock on screen lock / session suspend via OS hook (JVM: `java.awt.Toolkit.addAWTEventListener` for focus loss; WASM: `document.addEventListener('visibilitychange')`)

---

## 4. Vault Header Tampering (Evil Maid Attack)

### The Problem

If the `.stele-vault` header file is not authenticated, an adversary with brief physical access can:

1. Replace the vault header with a modified version that weakens Argon2id parameters (lower memory/iterations), making brute force much cheaper
2. Swap in a header with keyslots that wrap a known DEK — when the user unlocks, the attacker recovers the passphrase-derived key

### Mitigations (NFR-4)

- The vault header must be wrapped in an AEAD MAC covering all header bytes
- The MAC key can be derived from a fixed well-known secret (not ideal) or from a hardware-backed key (better). On JVM, use `java.security.KeyStore` with a machine-specific key to sign the header. On WASM, use a non-extractable `CryptoKey`.
- **Simpler approach**: include the header in the AEAD associated data (AAD) of the first keyslot's encrypted DEK. If the header is tampered, the MAC check fails and unlock fails. This requires that the DEK-wrapping AEAD uses the header bytes as AAD.
- For v1, document the evil maid attack vector and rely on the user's threat model — if they are concerned about evil maid, they should use secure boot and full-disk encryption at the OS level.

---

## 5. Deniability Breaks

### VeraCrypt-Style UI Leaks

VeraCrypt itself shows `type: Outer` in the volume info dialog when an outer volume with a hidden counterpart is mounted. This directly betrays the existence of a hidden volume.

**SteleKit must never expose any "outer/hidden" distinction in the UI** — the graph just looks like a normal graph regardless of which namespace is unlocked.

### Filesystem Metadata Leaks

Even if file content is encrypted, the following metadata may be observable:

- **File names** (`.stek` extension reveals encryption, but is required for format detection)
- **File sizes** (size of ciphertext reveals approximate plaintext size)
- **Timestamps** (last-modified on the container directory)
- **Directory structure** (number of files, hierarchy)
- **Access patterns** (if an adversary has ongoing access, they can see which files are read/written)

**Mitigations**:
- File sizes: pad plaintexts to the nearest 4 KB boundary before encryption (adds modest overhead but eliminates exact-size leakage)
- File names: store all file paths encrypted within a single file manifest; use opaque UUIDs as on-disk filenames. **High complexity for v1** — consider as v2.
- Timestamps: use fixed timestamps (1970-01-01T00:00:00Z) for all vault files. Easy to implement.

### Journal and System Log Leaks

The OS filesystem journal may record file names and modification events. On Linux:

```bash
# Ext4 journal may contain:
grep -i ".stek" /dev/sda1  # (raw disk access)
```

**Mitigation**: Use a single large encrypted container file rather than individual per-file `.stek` files. All file content is stored as blobs within the container, eliminating per-file OS metadata. **Architecturally significant trade-off** — the current per-file design is simpler and allows standard file sync tools to work.

---

## 6. Key File and OS Keychain Pitfalls

### Key File Pitfalls

- **Key file stored on same device as vault**: provides no additional security over a second passphrase
- **Key file in iCloud / Dropbox sync**: cloud provider can see the key file
- **Git-tracked key file**: accidentally committed to version control
- **SHA-256 of key file as secret**: if the key file is large and has predictable structure (e.g., a known PDF), an attacker can hash common documents and match against the keyslot
- **Mitigation**: Recommend storing key files on a separate physical device (USB). Document risks clearly. Consider using only the first 1 MiB of the key file to prevent DoS on large files.

### OS Keychain Pitfalls (JVM)

- `java.security.KeyStore` with `KeychainStore` on macOS is accessible to any process running as the same user (no per-app sandbox by default)
- On Linux, there is no universal system keystore — `org.freedesktop.secrets` (GNOME Keyring) requires D-Bus and is not universally available
- On Windows, `Windows-MY` keystore is per-user — same-user malware can access it
- **Mitigation**: Label keychain entries clearly ("SteleKit — [graph name]"). On Linux, fall back to a PKCS#12 file in `~/.local/share/stelekit/` with a machine-derived key (imperfect but practical).

### WASM Non-Extractable Key Pitfalls

- Non-extractable `CryptoKey` in IndexedDB is browser-profile-specific — clearing browser data deletes it. If this is the only registered keyslot, the vault is permanently locked.
- In Chromium, IndexedDB keys are tied to the browser profile and origin — cross-origin access is prevented by design.
- **Mitigation**: Always require at least one passphrase or key-file slot alongside an OS-keychain slot. Show a warning if the OS-keychain slot is the only registered provider.

---

## 7. Hidden Volume Overwrite Risk

### The Problem

If the user mounts the outer volume and writes enough data to fill it, outer-volume files can overwrite hidden-volume data. This is the fundamental tension in VeraCrypt-style hidden volumes: the outer volume cannot know where hidden data starts.

### Mitigations

- Reserve a fixed block at the end of the vault (the `_hidden_reserve/` area in the proposed architecture). Never allow outer-volume writes into this reserved area.
- The `GraphWriter`, when operating on an outer volume, must check that no file write targets the reserved block. This requires the `CryptoLayer` to be aware of the vault's reserved-area boundaries.
- Document the constraint clearly in the UX: "Outer volume is limited to [X] MB to protect the hidden volume."
- For the simpler single-archive hidden volume approach (v1 recommendation), the hidden archive has a fixed maximum size defined at creation time — overwrite is a non-issue.

---

## 8. Brute-Force and Timing Attacks

### Keyslot Iteration Timing

If the unlock loop tries each keyslot in order and short-circuits on the first valid MAC, timing differences can reveal which slot index is "active" — leaking whether the outer or hidden namespace was unlocked.

**Mitigation**: Always try all 8 keyslots and record all results; select the valid one after all attempts complete. Use constant-time MAC comparison (`MessageDigest.isEqual()` on JVM; `crypto.subtle.verify()` on WASM).

### Argon2id Parameters as Rate Limiter

The requirements correctly note that Argon2id's memory cost is the brute-force rate limiter (no app-level rate limiting needed — FR-5). However, if the parameters are benchmarked at vault creation and stored in the header, an adversary can read the parameters and calibrate their attack resources accordingly.

**Mitigation**: Do not expose Argon2id parameters in any UI. Consider slightly randomizing the parameter values (e.g., memory ± 5%) so that the exact parameters are not trivially predictable, though the stored values are still readable from the header.

---

## 9. Format Versioning and Migration Risks

If the encrypted file format must be migrated (e.g., algorithm change in v2), the migration requires:

1. Decrypt all files with old DEK + old algorithm
2. Re-encrypt with new algorithm + new nonce
3. Update vault header

During migration, files exist in both old and new formats — risk of partial migration on crash. **Mitigation**: Use a transaction-like approach — write all new files first, then atomically update the vault header's format version field. If the app restarts mid-migration and sees a mismatched version, it resumes from the point of failure.

---

## 10. OWASP Top 10 Cryptographic Failures (A04:2025)

The following OWASP A04 anti-patterns directly apply:

| Anti-Pattern | Mitigation |
|---|---|
| Weak random number generation | Use `SecureRandom` / `crypto.getRandomValues()` everywhere |
| Hard-coded encryption keys | Never — all keys derived from user-supplied secrets |
| Reusing keys across multiple places | Per-file HKDF subkeys prevent DEK reuse across files |
| Not rotating keys | Key rotation path required (FR-8) |
| Storing keys in plaintext config files | DEK never on disk; keyslots stored as ciphertext |
| Missing AAD / AEAD misuse | File path as AAD binds ciphertext to location — prevents moving attacks |

---

## Sources

- [CWE-323: Reusing a Nonce/Key Pair in Encryption](https://cwe.mitre.org/data/definitions/323.html)
- [OWASP A04:2025 Cryptographic Failures](https://owasp.org/Top10/2025/A04_2025-Cryptographic_Failures/)
- [10 Cryptography Mistakes You're Probably Making — AppSec Engineer](https://www.appsecengineer.com/blog/10-cryptography-mistakes-youre-probably-making)
- [Encryption at Rest: Whose Threat Model Is It Anyway? — Scott ARC Blog](https://scottarc.blog/2024/06/02/encryption-at-rest-whose-threat-model-is-it-anyway/)
- [Protecting strings in JVM Memory — Alan Evans / Medium](https://westonal.medium.com/protecting-strings-in-jvm-memory-84c365f8f01c)
- [One key to rule them all: Recovering master key from RAM (Android FBE) — ScienceDirect](https://www.sciencedirect.com/article/pii/S266628172100007X)
- [Cold Boot Attack — Wikipedia](https://en.wikipedia.org/wiki/Cold_boot_attack)
- [Defeating PD of VeraCrypt Hidden OS — Springer](https://link.springer.com/chapter/10.1007/978-981-10-5421-1_1)
- [VeraCrypt "Outer" type leak — GitHub issue #1328](https://github.com/veracrypt/VeraCrypt/issues/1328)
- [VeraCrypt Hidden Volume protection](https://veracrypt.io/en/Protection%20of%20Hidden%20Volumes.html)
- [Shufflecake CCS 2023](https://eprint.iacr.org/2023/1529.pdf)
- [Trail of Bits — Best practices for key derivation (2025)](https://blog.trailofbits.com/2025/01/28/best-practices-for-key-derivation/)
- [dys2p — LUKS security analysis](https://dys2p.com/en/2023-05-luks-security.html)
