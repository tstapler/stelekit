# Features Research — LUKS2-style Keyslot & DEK-Wrapping Patterns

## Summary

This document covers the LUKS2 keyslot architecture, DEK-wrapping patterns, and how they map to SteleKit's multi-provider key management requirements.

---

## 1. LUKS2 Architecture Overview

LUKS2 (Linux Unified Key Setup v2) is the de-facto standard for at-rest encryption with multi-slot key management. Its design is directly applicable to Paranoid Mode.

### Header Layout

```
+------------------+  sector 0
| Binary Header 1  |  4 KiB — magic, version, offsets, checksums
+------------------+
| JSON Metadata 1  |  variable (12 KiB–4092 KiB)
+------------------+
| Binary Header 2  |  4 KiB (redundant copy)
+------------------+
| JSON Metadata 2  |  (redundant copy)
+------------------+
| Keyslot Area     |  encrypted key material per slot
+------------------+
| Data Segment     |  actual encrypted data
+------------------+
```

### JSON Metadata Structure (relevant fields)

```json
{
  "keyslots": {
    "0": {
      "type": "luks2",
      "key_size": 32,
      "af": { "type": "luks1", "stripes": 4000, "hash": "sha256" },
      "area": { "offset": "32768", "size": "131072" },
      "kdf": {
        "type": "argon2id",
        "time": 3,
        "memory": 65536,
        "cpus": 1,
        "salt": "<base64>",
        "hash": "sha256"
      }
    }
  },
  "digests": {
    "0": {
      "type": "pbkdf2",
      "keyslots": ["0"],
      "segments": ["0"],
      "salt": "<base64>",
      "digest": "<base64>"
    }
  }
}
```

### Key insight: The AF (Anti-Forensic Splitter)

LUKS2 uses an Anti-Forensic information splitter: the master key is split into `stripes` strips, each XORed with the KDF output, so that recovering the key requires reading every strip without error. For SteleKit, a simpler approach suffices: `KDF_output XOR DEK` (no stripe splitting), which is already close to LUKS2's reduced-AF mode.

---

## 2. DEK-Wrapping Pattern

The canonical pattern (used in LUKS2, age, and similar systems):

```
passphrase → Argon2id(passphrase, salt, m, t, p) → keyslot_key (32 bytes)
keyslot_key XOR DEK → keyslot_blob (stored in vault header)
```

To unlock:
```
passphrase → Argon2id(passphrase, same salt, m, t, p) → keyslot_key
keyslot_key XOR keyslot_blob → DEK_candidate
verify DEK_candidate against vault MAC
```

### Verification Without Exposing DEK

LUKS2 maintains a `digest` object: `PBKDF2(DEK, salt) → fingerprint`. On unlock, derive the fingerprint from the candidate DEK and compare. If they match, the DEK is valid.

For SteleKit, prefer an AEAD-verified approach: store `Encrypt(keyslot_key, DEK || namespace_tag)` so the MAC itself is the verification step, not a separate digest. This eliminates the digest object and simplifies the design.

---

## 3. Keyslot Provider Types

### Passphrase (Argon2id)

```
provider_secret = Argon2id(passphrase, per_slot_salt, m=64MiB, t=3, p=1)
keyslot_blob = ChaCha20-Poly1305-Encrypt(provider_secret, DEK, aad=slot_id)
```

- Per-slot salt (16 bytes) is stored in the vault header
- Per-slot Argon2id parameters (m/t/p) are stored alongside the salt
- Allows different slots to have different KDF cost without re-encrypting content

### Key File

```
provider_secret = SHA-256(key_file_bytes)
keyslot_blob = ChaCha20-Poly1305-Encrypt(provider_secret, DEK, aad=slot_id)
```

- Key file can be any file; its 32-byte SHA-256 hash is the secret
- Losing the key file = losing that slot (other slots unaffected)
- Consider truncating key files to first 1 MiB to prevent DoS on giant files

### OS Keychain

**JVM**: Store the DEK (or a random wrapper key) in `java.security.KeyStore` using the platform backend. The keystore entry is retrieved by alias at unlock time.

**WASM**: Generate a non-extractable `CryptoKey` via `SubtleCrypto.generateKey()`, wrap the DEK with it using `SubtleCrypto.wrapKey()`, store the wrapped blob in the vault header and the non-extractable key in IndexedDB. On unlock, call `SubtleCrypto.unwrapKey()`.

---

## 4. Hidden Volume DEK Namespaces

### Two Namespace Design

```
Vault header:
  - Keyslot section A (outer namespace): 4 slots max
  - Keyslot section B (hidden namespace): 4 slots max
  - No label distinguishing which section is which
  - Both sections look like random bytes when not decrypted
```

On unlock:
1. Try each keyslot in section A and section B against the supplied credential
2. First successful MAC verification determines which namespace (outer vs. hidden) is active
3. The app loads the corresponding graph from the data area

The header must not reveal which slots belong to which namespace. One approach: allocate all 8 slots with identical binary layout; on unlock, try all 8 and note which slot succeeded. The namespace is inferred from which DEK is produced (each DEK encrypts a different set of files).

### File Storage Interleaving

Hidden volume files are stored in the "free" area of the outer volume. Two approaches from the literature:

1. **VeraCrypt approach**: Outer volume is created first; hidden volume files grow from the end of the container inward. The outer volume must never be filled to the point that it overwrites the hidden area.

2. **Shufflecake approach (CCS 2023)**: All file data blocks are scrambled across the device; each volume's block locations are encoded in keys known only to that volume. This provides stronger deniability but is much more complex to implement.

For SteleKit v1, the VeraCrypt approach is simpler: a `.stele-vault` directory contains the outer graph files and an encrypted hidden-volume segment (fixed-size reserved area at the end of the vault container). The reserved area's size gives no information about whether it is used.

---

## 5. Key Rotation (FR-8)

**Changing a provider (passphrase change)**:
1. Derive new `keyslot_key` from new passphrase
2. Re-encrypt the existing DEK with the new `keyslot_key`
3. Replace the old keyslot blob in the header
4. Zero the old keyslot area
5. No file content re-encryption needed

**Full DEK rotation**:
1. Generate new DEK
2. Decrypt all vault files with old DEK
3. Encrypt all vault files with new DEK
4. Wrap new DEK in all registered keyslots
5. Replace vault header

**Remove a provider**:
1. Overwrite keyslot blob with random bytes (or zeros)
2. Update keyslot count in header
3. No re-encryption

---

## 6. Industry Feature Landscape

### Standard Notes (open source, cross-platform)

Uses a similar envelope-encryption model: 1 master key per account, per-note key derived from master + note UUID. Passphrase change only rewrites the master key wrapper.

### age (Rust/Go encryption tool)

Uses recipient-based wrapping: each recipient's key wraps the file key. The vault header is the list of wrapped file keys. Directly analogous to LUKS2 keyslots without a KDF layer.

### 1Password (app)

DEK sealed by account key + SRP-derived session key. Multiple keyslots not a public feature, but same envelope pattern. Emergency Kit = printed copy of account key.

### Obsidian (community plugin: Meld Encrypt)

AES-256-GCM, per-block password, no keyslot system — user must remember password per note block. No plausible deniability. Shows the UX gap that Paranoid Mode fills.

---

## 7. User-Facing Edge Cases

- **Forgotten passphrase with no backup provider**: user is locked out permanently (no recovery path)
- **Corrupted vault header**: all files become unrecoverable — backup strategy critical
- **Adding a provider when already unlocked**: must re-authenticate before adding new providers (prevents unauthorized provider addition)
- **Mixing hidden and outer writes simultaneously**: the outer-volume write must not overwrite hidden-volume blocks — need a reserved-area map in memory during outer-volume writes
- **First-time vault creation**: must pre-fill the outer volume with random data before writing any ciphertext, to make outer and hidden file areas indistinguishable

---

## Sources

- [LUKS2 On-Disk Format Spec v1.1.4](https://fossies.org/linux/cryptsetup/docs/on-disk-format-luks2.pdf)
- [Linux Unified Key Setup — Wikipedia](https://en.wikipedia.org/wiki/Linux_Unified_Key_Setup)
- [Argon2id in LUKS2 — ElcomSoft analysis](https://blog.elcomsoft.com/2022/08/probing-linux-disk-encryption-luks2-argon-2-and-gpu-acceleration/)
- [mjg59 — Upgrade your LUKS KDF](https://mjg59.dreamwidth.org/66429.html)
- [HKDF RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869)
- [Trail of Bits — Best practices for key derivation (2025)](https://blog.trailofbits.com/2025/01/28/best-practices-for-key-derivation/)
- [VeraCrypt Hidden Volume documentation](https://veracrypt.io/en/Hidden%20Volume.html)
- [Shufflecake: CCS 2023](https://eprint.iacr.org/2023/1529.pdf)
- [Obsidian Meld Encrypt forum](https://forum.obsidian.md/t/alternative-ways-to-encrypt-note-and-file/89983)
