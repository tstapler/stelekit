# Architecture Research — Hidden Volume / Plausible Deniability

## Summary

This document covers the architecture of hidden-volume and plausible-deniability systems (VeraCrypt, Shufflecake), the integration points with SteleKit's existing layered architecture, and the data-flow and consistency requirements for transparent encrypted I/O.

---

## 1. VeraCrypt Hidden Volume Architecture

### Core Mechanism

A VeraCrypt hidden volume lives entirely within the free-space area of an outer volume. The container is a single binary blob (or a partition). Both the outer and hidden volume headers are stored at fixed offsets; the hidden header is placed near the end of the container at a position that looks like random data to anyone who decrypts only the outer header.

```
Container layout (VeraCrypt):
+--------------------+  byte 0
| Outer header       |  512 bytes (encrypted with outer passphrase)
+--------------------+  byte 65536 (64 KiB backup)
| Outer backup hdr   |
+--------------------+  ...
| Outer volume data  |  (filled from front)
| ← outer files grow inward →
|                    |
| ← hidden files grow from back →
| Hidden volume data |  (filled from rear)
+--------------------+  near end-of-container
| Hidden header      |  512 bytes (encrypted with hidden passphrase)
+--------------------+  end of container
```

### How Password Determines Which Volume Mounts

VeraCrypt tries the entered password against the outer header first, then the hidden header. The header contains a magic identifier after decryption — if decryption succeeds and the identifier matches, that volume is mounted. If the identifier fails for both, unlock fails.

**SteleKit adaptation**: use AEAD MAC verification as the identifier check — a successful decryption of the keyslot blob (which contains the DEK) proves which namespace is active. No plaintext magic bytes needed.

### Deniability Guarantees and Limits

- **What it provides**: An adversary who can only see disk bytes and knows the outer passphrase cannot distinguish outer ciphertext from "random noise" that happens to be a hidden volume.
- **Known breaks**:
  1. VeraCrypt leaks "Outer" type in the UI when an outer volume with a hidden counterpart is mounted — the UI shows `type: Outer` vs `Normal`. **Mitigation**: SteleKit should never expose any "outer/hidden" distinction in the UI.
  2. Shadow Copy / filesystem journal on the host OS can contain evidence of hidden-volume file activity.
  3. Timestamps on the container file can reveal that the container was modified after the outer passphrase was last used, hinting at a hidden volume.
  4. Cross-drive analysis (comparing two snapshots of the container) can detect hidden-volume writes as changes in areas "claimed" to be free space.

---

## 2. Shufflecake (2023) — Multi-Layer Deniability

Shufflecake (Kudelski Security, CCS 2023) addresses some of VeraCrypt's weaknesses. Key design improvements:

- **Block interleaving**: file data blocks for all volumes are scrambled across the storage medium. Each volume's block map is part of that volume's encrypted metadata.
- **Multiple hidden volumes**: supports a hierarchy of volumes, each with independent keys. Knowing volume N reveals nothing about volume N+1.
- **No fixed header offsets**: headers are stored at pseudo-random locations derived from the passphrase, making even the header locations deniable.

**I/O overhead**: ~2× compared to a plain LUKS volume (vs VeraCrypt's ~1.5×). Shufflecake wastes <1% of disk space.

**Applicability to SteleKit**: the full Shufflecake design requires block-level control (it is a Linux kernel module). SteleKit operates at the file level, not block level. A simplified file-level analogue is feasible for v1, but the full interleaving guarantee cannot be achieved without a custom filesystem or block device.

**Recommended v1 simplification**: Use the VeraCrypt two-namespace approach (outer + one hidden) with random data pre-filling. Document that the outer-volume-must-not-overwrite-hidden-area constraint is the user's responsibility (Paranoid Mode UX warning on outer-volume writes). Full Shufflecake-style interleaving is a v2 consideration.

---

## 3. Integration with SteleKit's Existing Architecture

### Existing Data Flow

```
GraphLoader (reads markdown files) → OutlinerPipeline → Repositories
GraphWriter (writes markdown files) ← BlockStateManager ← BlockEditor
```

### Paranoid Mode Integration Points

```
GraphLoader:
  readFile(path) → CryptoLayer.decrypt(path, bytes) → plaintext

GraphWriter:
  savePage(path, plaintext) → CryptoLayer.encrypt(path, plaintext) → ciphertext → writeFile(path, ciphertext)
```

The `CryptoLayer` is a pure function wrapper around `CryptoEngine`:

```kotlin
class CryptoLayer(
    private val engine: CryptoEngine,
    private val dek: ByteArray            // held in memory while unlocked
) {
    fun encrypt(filePath: String, plaintext: ByteArray): ByteArray {
        val nonce = engine.secureRandom(12)
        val subkey = engine.hkdfSha256(dek, filePath.toUtf8Bytes(), "file".toUtf8Bytes(), 32)
        val aad = filePath.toUtf8Bytes()   // bind ciphertext to path
        val ciphertext = engine.encryptAEAD(subkey, nonce, plaintext, aad)
        return STEK_MAGIC + VERSION_BYTE + nonce + ciphertext  // tag is appended inside ciphertext
    }

    fun decrypt(filePath: String, bytes: ByteArray): ByteArray {
        require(bytes.startsWith(STEK_MAGIC))
        val nonce = bytes.slice(5..16)
        val ciphertext = bytes.drop(17)
        val subkey = engine.hkdfSha256(dek, filePath.toUtf8Bytes(), "file".toUtf8Bytes(), 32)
        val aad = filePath.toUtf8Bytes()
        return engine.decryptAEAD(subkey, nonce, ciphertext, aad)
    }
}
```

### VaultManager (new component)

```
VaultManager
  ├── loadVaultHeader(path) → VaultHeader
  ├── unlock(credential) → Either<VaultError, DEK>
  ├── lock() — zeroes DEK from memory
  ├── addKeyslot(credential, dek) → Either<VaultError, Unit>
  ├── removeKeyslot(slotId) → Either<VaultError, Unit>
  └── rotateKeyslots(newCredentials, dek) → Either<VaultError, Unit>
```

`VaultManager` wraps the `CryptoEngine` and owns the in-memory DEK. It exposes `Either<VaultError, T>` following the existing project convention.

### GraphManager Integration

`GraphManager.addGraph(path)` detects a `.stele-vault` file and triggers the unlock flow before constructing the `RepositorySet`. The unlock dialog is presented by `StelekitViewModel` on a state change. Once unlocked, `GraphManager` injects the `CryptoLayer` into `GraphLoader` and `GraphWriter` for that graph.

### Lock / Session Expiry

On lock:
1. `CryptoLayer` is destroyed; reference is cleared
2. `VaultManager.lock()` overwrites the DEK byte array with zeros (best-effort — JVM GC can relocate arrays, so zero-on-use is more reliable than zero-on-lock; use `SecretKeySpec` with `destroy()` where available)
3. `GraphManager` removes the graph from the active set
4. UI navigates to the unlock screen

Auto-lock: a countdown coroutine in `VaultManager` emits a `lock` event after idle timeout. The `StelekitViewModel` observes this and triggers the lock flow.

---

## 4. Vault Header Format for Plausible Deniability

The `.stele-vault` file must not reveal whether a hidden volume exists. Proposed layout:

```
Bytes 0–3:    Magic: "SKVT" (SteleKit Vault)
Byte  4:      Format version (0x01)
Bytes 5–12:   Random padding (8 bytes)
Bytes 13–N:   Keyslot array (8 fixed-size slots × 256 bytes = 2048 bytes)
              Each slot: salt(16) + argon2_params(12) + encrypted_dek_blob(48) + reserved(180)
              Unused slots are filled with random bytes — identical layout to used slots
Bytes N+1–M:  Reserved random area (fixed 512 bytes — future extensibility or hidden-namespace data)
```

Every slot looks identical whether it is a passphrase slot, key-file slot, OS-keychain slot, or an unused slot. The app tries each slot against the supplied credential and checks if the AEAD tag verifies. No field says "this slot is active" or "this slot belongs to namespace X."

The hidden namespace uses slots 4–7; the outer namespace uses slots 0–3. But nothing in the header marks which slot belongs to which namespace.

---

## 5. File Storage Architecture for Hidden Volume

### Outer Graph Directory

```
graph-root/
  .stele-vault          (vault header — always plaintext-accessible to determine vault status)
  pages/
    page1.md.stek       (encrypted — outer or hidden?)
    page2.md.stek
  assets/
    image.png.stek
  _hidden_reserve/
    (fixed-size block of random bytes, pre-allocated at vault creation)
```

Hidden-volume files are stored within the `_hidden_reserve/` directory as fixed-size ciphertext blocks. They are keyed with the hidden DEK and have no filenames exposed — the file manifest is encrypted within the hidden volume.

**Simpler alternative for v1**: Hidden volume is a separate encrypted archive (single `.stek` file) within `_hidden_reserve/`, decrypted and mounted as a virtual in-memory filesystem when the hidden passphrase is used. Files are written back as a single archive on every save. This trades fine-grained I/O for simplicity.

---

## 6. Data Flow and Consistency Requirements

### Read Path (existing external change detection must still work)

`GraphLoader.externalFileChanges` (SharedFlow) watches for disk changes. When a file changes on disk, it re-reads the ciphertext and decrypts. If the CryptoLayer is locked (DEK not available), it must queue the change notification until unlock, not silently drop it.

### Write Path (GraphWriter)

`GraphWriter.saveBlock()` already writes via debounced 500 ms. The encryption step adds ~1–5 ms per file (well within NFR-7). No structural changes needed; the `CryptoLayer` intercepts at the file I/O boundary.

### Corruption Handling

If `decryptAEAD` throws (bad tag = tampered or corrupted file), the `CryptoLayer` propagates a `DomainError.DatabaseError.CorruptedFile` (new error type). The UI surfaces this as a recoverable error ("This file appears to be corrupted or tampered with").

### Vault Header Corruption

The vault header is authenticated (NFR-4). On corruption of primary header, fall back to the backup header copy (LUKS2 pattern). If both are corrupted, the vault is unrecoverable without backup. The app should strongly recommend exporting vault header backups.

---

## Sources

- [VeraCrypt Hidden Volume architecture](https://veracrypt.io/en/Hidden%20Volume.html)
- [VeraCrypt Plausible Deniability](https://veracrypt.io/en/Plausible%20Deniability.html)
- [Defeating PD of VeraCrypt Hidden OS — Springer](https://link.springer.com/chapter/10.1007/978-981-10-5421-1_1)
- [VeraCrypt "Outer" type leak — GitHub issue #1328](https://github.com/veracrypt/VeraCrypt/issues/1328)
- [Shufflecake: CCS 2023](https://eprint.iacr.org/2023/1529.pdf)
- [Shufflecake — Kudelski Security intro](https://research.kudelskisecurity.com/2022/11/10/introducing-shufflecake-plausible-deniability-for-multiple-hidden-filesystems-on-linux/)
- [LUKS2 On-Disk Format Spec](https://fossies.org/linux/cryptsetup/docs/on-disk-format-luks2.pdf)
- [AEAD — Google Tink documentation](https://developers.google.com/tink/aead)
