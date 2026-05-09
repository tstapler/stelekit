# Paranoid Mode — Implementation Plan

## Status: Draft
## Target Platforms: Desktop JVM + Web/WASM (v1)
## Requirements: `../requirements.md`
## Research: `../research/`

---

## 1. Technology Validation

### Confirmed Stack

| Function | JVM | Web/WASM | Notes |
|---|---|---|---|
| ChaCha20-Poly1305 AEAD | `javax.crypto` (JDK 11+, SunJCE provider) | `libsodium.js` WASM via Kotlin `external` interop | WebCrypto does NOT support ChaCha20 — libsodium.js is mandatory for WASM |
| Argon2id KDF | `bcprov-jdk18on:1.80` (BouncyCastle) | `libsodium.js` `crypto_pwhash` (single-dep option) | BouncyCastle is pure JVM; no JNI required |
| HKDF-SHA256 subkeys | BouncyCastle `HKDFBytesGenerator` | `libsodium.js` `crypto_kdf_hkdf_*` | Per-file subkeys from HKDF eliminate per-key nonce domain blowup |
| Secure random | `java.security.SecureRandom` | `crypto.getRandomValues()` via JS interop | Never counters or timestamps — critical for nonce safety |
| OS keychain | `java.security.KeyStore` (platform-backed) | `SubtleCrypto.generateKey({extractable:false})` + IndexedDB | Linux JVM fallback: PKCS#12 file in `~/.local/share/stelekit/` |
| Abstraction | `CryptoEngine` interface (commonMain) | `JvmCryptoEngine` / `WasmCryptoEngine` actuals | Interface injection pattern preferred over `expect`/`actual` for crypto surface |

### Key Dependency to Add

```kotlin
// jvmMain only
implementation("org.bouncycastle:bcprov-jdk18on:1.80")
```

No new commonMain dependencies required. The WASM build requires JS interop to `libsodium.js` (loaded via npm / webpack; not a Gradle dep).

### Validated Decisions

- **PKCS#11 cannot be used for ChaCha20-Poly1305 on JVM** (JDK-8255410 bug). Must use default SunJCE provider. Keystore operations (OS keychain slot) use PKCS#11/KeychainStore; cipher operations always use SunJCE.
- **Argon2id at 64 MiB / 3 iterations** benchmarks at ~350–450 ms in browser WASM and <1 s on desktop JVM. Both are within NFR-6 (≤ 5 s). Parameters must be calibrated at vault creation and stored in the keyslot header.
- **`cryptography-kotlin` (whyoleg)** is not adopted for v1. It lacks Argon2id, requiring BouncyCastle anyway, and adds an abstraction layer of uncertain WASM-JS stability. Raw `CryptoEngine` interface is simpler and better understood.

---

## 2. File Format Specification

### 2a. Per-File Encrypted Format (`.stek` extension)

Each encrypted graph file on disk:

```
Offset  Size    Field
0       4       Magic: 0x53 0x54 0x45 0x4B  ("STEK")
4       1       Version: 0x01
5       12      Nonce: cryptographically random per write (96-bit)
17      N       Ciphertext: ChaCha20-Poly1305(subkey, nonce, plaintext, aad=file_path_utf8)
17+N    16      Poly1305 auth tag (appended by JDK/libsodium inside ciphertext block)
```

**Subkey derivation**: `HKDF-SHA256(ikm=DEK, salt=file_path_utf8, info="stelekit-file-v1", len=32)`

- The file path (relative to graph root) is both the HKDF salt and the AEAD additional data (AAD). This binds the ciphertext to its location — moving a file and re-reading it will fail authentication, preventing silent relocation attacks.
- On-disk filename: `{original_name}.md.stek` (preserves directory structure; extension signals format to the app).

### 2b. Vault Header File (`.stele-vault`)

Stored at `{graph_root}/.stele-vault`. Always present for paranoid-mode graphs.

```
Offset  Size     Field
0       4        Magic: 0x53 0x4B 0x56 0x54  ("SKVT")
4       1        Format version: 0x01
5       8        Random padding (8 random bytes — prevents zero-length fingerprinting)
13      2048     Keyslot array: 8 slots × 256 bytes each
2061    512      Reserved random area (future extensibility / backup header / hidden namespace metadata)
2573    32       Header MAC: HMAC-SHA256(key=header_mac_key, data=bytes[0..2572])
                 header_mac_key = HKDF-SHA256(DEK, salt="vault-header-mac", info="v1")
                 Authenticated on every unlock after DEK is recovered.
```

**Keyslot layout (256 bytes each)**:

```
Offset  Size    Field
0       16      Per-slot random salt (Argon2id input)
16      4       Argon2id memory cost (KiB, LE uint32)
20      2       Argon2id iterations (LE uint16)
22      2       Argon2id parallelism (LE uint16)
24      48      Encrypted DEK blob: ChaCha20-Poly1305(keyslot_key, nonce=slot_nonce, DEK || namespace_tag)
                  namespace_tag: 0x00 = outer, 0x01 = hidden (single byte, part of plaintext)
72      12      slot_nonce (12 random bytes for the DEK-wrapping AEAD)
84      1       Provider type hint (0x00=passphrase, 0x01=keyfile, 0x02=os_keychain, 0xFF=unused)
                  NOTE: unused slots fill ALL 256 bytes with random — provider type field is also random for unused slots
85      171     Reserved / random filler
```

**All 8 slots have identical binary length regardless of state.** Unused slots are indistinguishable from active ones (random bytes throughout). The MAC on the encrypted DEK blob is the only oracle for "is this slot active?"

**Hidden volume namespace design**: Slots 0–3 are tried for the outer namespace; slots 4–7 for the hidden namespace. However, this division is not written anywhere in the header — it is a protocol convention. The app tries all 8 slots and infers namespace from the `namespace_tag` byte inside the successfully decrypted DEK blob. Because an adversary cannot decrypt any slot without the passphrase, the namespace assignment is not observable.

**Timing safety**: All 8 slots are always attempted in constant time regardless of which slot(s) succeed. Results are collected, then the valid one is selected post-loop. MAC comparison uses constant-time routines (`MessageDigest.isEqual()` on JVM; `crypto.subtle.verify()` on WASM).

### 2c. Hidden Volume Storage

The `_hidden_reserve/` directory inside the graph root acts as the hidden volume's file store. At vault creation time, this directory is filled with a single random-byte blob of fixed size (configured at creation; default 50 MB). Hidden-volume ciphertext blocks overwrite this random blob from the beginning.

The outer graph's `GraphWriter` must never write into `_hidden_reserve/`. This is enforced by the `CryptoLayer` path filter at write time.

For v1, the hidden volume is stored as a single encrypted archive (`.stek` file) within `_hidden_reserve/`. This "virtual archive" approach avoids per-file OS metadata leakage from the hidden volume and eliminates the outer-volume-overwrite-protection complexity.

---

## 3. Architectural Decisions

### 3a. New Components

```
kmp/src/commonMain/kotlin/dev/stapler/stelekit/vault/
  CryptoEngine.kt             — interface (encrypt, decrypt, hkdf, argon2id, secureRandom)
  CryptoLayer.kt              — wraps CryptoEngine; per-file encrypt/decrypt with STEK header
  VaultManager.kt             — keyslot management, unlock, lock, key rotation
  VaultHeader.kt              — data class for parsed .stele-vault header
  VaultError.kt               — sealed class for DomainError subtypes
  KeyslotProvider.kt          — sealed class: Passphrase, KeyFile, OsKeychain
  HiddenVolumeManager.kt      — outer/hidden namespace routing, _hidden_reserve/ lifecycle

kmp/src/jvmMain/kotlin/dev/stapler/stelekit/vault/
  JvmCryptoEngine.kt          — javax.crypto + BouncyCastle implementation

kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/vault/
  WasmCryptoEngine.kt         — libsodium.js JS interop implementation

kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/
  VaultUnlockScreen.kt        — unlock dialog (passphrase + key-file toggle)
  VaultSettingsScreen.kt      — per-graph paranoid mode settings, provider management
```

### 3b. Integration with Existing Architecture

**GraphLoader** receives an optional `CryptoLayer?` parameter. When non-null, every `fileSystem.readFile(path)` result is passed through `cryptoLayer.decrypt(path, bytes)` before parsing. The `ExternalFileChange` flow also passes raw bytes through decryption — if the `CryptoLayer` is null (graph locked), the change is queued and emitted only after unlock.

**GraphWriter** receives an optional `CryptoLayer?` parameter. Inside `savePageInternal`, the `content` string is encrypted via `cryptoLayer.encrypt(filePath, content.toByteArray())` before calling `fileSystem.writeFile(filePath, encryptedBytes)`. The existing Arrow Saga rollback path restores the old encrypted bytes on failure.

**GraphManager.addGraph/switchGraph** detects the `.stele-vault` file at the graph root. If present, it sets `GraphInfo.isParanoidMode = true` and emits a `VaultLocked` state on `activeRepositorySet` before constructing the `RepositorySet`. The `StelekitViewModel` observes this and pushes the `VaultUnlockScreen`.

**VaultManager** owns the in-memory DEK as a `ByteArray`. It is injected into `CryptoLayer` at unlock time. On lock, `VaultManager.lock()` zero-fills the DEK array, clears the `CryptoLayer` reference in `GraphLoader` and `GraphWriter`, and emits a lock event to the ViewModel.

**Error handling**: All vault operations return `Either<VaultError, T>` where `VaultError` is a new sealed subtype of `DomainError`. Existing call sites use `.onLeft`, `.fold`, or `.getOrNull()` — no existing error handling patterns change.

**DatabaseWriteActor**: No changes required. The vault layer intercepts file I/O below the DB layer — the actor serializes DB writes as before and is unaware of encryption.

**PlatformDispatcher**: Argon2id derivation is CPU-intensive (64 MiB / 3 iterations ≈ 350–1000 ms). It must run on `PlatformDispatcher.Default` (not `DB`), wrapped in `withContext(Dispatchers.Default)` inside `VaultManager.unlock()`. File encrypt/decrypt is fast (<5 ms/file per NFR-7) and may run on whichever dispatcher is calling `GraphLoader`/`GraphWriter`.

### 3c. `rememberCoroutineScope` Safety

`VaultManager` owns its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)` internally. It is not passed a scope from composition. The idle-timeout auto-lock coroutine is managed by this internal scope.

---

## 4. Epics, Stories, and Tasks

### Epic 1 — Crypto Foundation (commonMain + platform actuals)
*Goal: Working CryptoEngine on JVM and WASM with unit tests.*

**Story 1.1 — CryptoEngine interface**
- T1.1.1: Define `CryptoEngine` interface in `commonMain` (`encryptAEAD`, `decryptAEAD`, `hkdfSha256`, `argon2id`, `secureRandom`)
- T1.1.2: Define `VaultError` sealed class extending `DomainError`
- T1.1.3: Add `bcprov-jdk18on:1.80` to `jvmMain` dependencies in `kmp/build.gradle.kts`

**Story 1.2 — JVM implementation**
- T1.2.1: Implement `JvmCryptoEngine.encryptAEAD` using `javax.crypto.Cipher("ChaCha20-Poly1305")` with SunJCE provider; fresh `Cipher` instance per call
- T1.2.2: Implement `JvmCryptoEngine.decryptAEAD` — verify Poly1305 tag, propagate `BadPaddingException` as `VaultError.AuthenticationFailed`
- T1.2.3: Implement `JvmCryptoEngine.hkdfSha256` using BouncyCastle `HKDFBytesGenerator`
- T1.2.4: Implement `JvmCryptoEngine.argon2id` using `Argon2BytesGenerator`; accept `memory`, `iterations`, `parallelism` params
- T1.2.5: Implement `JvmCryptoEngine.secureRandom` using `java.security.SecureRandom`

**Story 1.3 — WASM implementation**
- T1.3.1: Add `libsodium.js` npm dependency; write Kotlin `external` declarations for `crypto_aead_chacha20poly1305_ietf_encrypt`, `_decrypt`, `crypto_pwhash` (Argon2id), `crypto_kdf_hkdf_sha256_*`
- T1.3.2: Implement `WasmCryptoEngine.encryptAEAD` and `decryptAEAD` via libsodium.js
- T1.3.3: Implement `WasmCryptoEngine.hkdfSha256` via libsodium.js
- T1.3.4: Implement `WasmCryptoEngine.argon2id` via libsodium.js `crypto_pwhash`
- T1.3.5: Implement `WasmCryptoEngine.secureRandom` via `crypto.getRandomValues()`

**Story 1.4 — Unit tests**
- T1.4.1: JVM round-trip test: encrypt → decrypt → verify plaintext equality
- T1.4.2: JVM nonce-uniqueness test: 1000 successive encryptions of the same plaintext produce distinct nonces
- T1.4.3: JVM authentication-failure test: modified ciphertext byte throws `VaultError.AuthenticationFailed`
- T1.4.4: JVM HKDF test: same inputs produce same 32-byte key; different file paths produce different keys
- T1.4.5: JVM Argon2id test: output length 32 bytes; known test vector from RFC 9106

---

### Epic 2 — Vault Header and Keyslot System
*Goal: Create, read, and update `.stele-vault` headers with multi-provider keyslots.*

**Story 2.1 — Data models**
- T2.1.1: Define `VaultHeader` data class (magic, version, keyslots, reserved, headerMac)
- T2.1.2: Define `Keyslot` data class (salt, argon2Params, encryptedDekBlob, slotNonce, providerType)
- T2.1.3: Define `Argon2Params` data class (memory, iterations, parallelism)
- T2.1.4: Define `KeyslotProvider` sealed class (Passphrase, KeyFile, OsKeychain) with `deriveSecret(engine: CryptoEngine): ByteArray` method

**Story 2.2 — VaultHeader serialization**
- T2.2.1: Implement binary serializer for `VaultHeader` (fixed-offset layout, no reflection)
- T2.2.2: Implement binary deserializer with magic check and version guard
- T2.2.3: Add backup header round-trip logic (primary and secondary copies at fixed offsets within reserved area)

**Story 2.3 — VaultManager core**
- T2.3.1: Implement `VaultManager.createVault(path, initialProvider)`: generate DEK, wrap in first keyslot, write `.stele-vault`
- T2.3.2: Implement `VaultManager.unlock(path, provider)`: read header, iterate all 8 keyslots in constant time, return `Either<VaultError, DEK>`; verify header MAC after DEK recovery
- T2.3.3: Implement `VaultManager.lock()`: zero-fill DEK array, emit `VaultLocked` event, clear `CryptoLayer` reference
- T2.3.4: Implement `VaultManager.addKeyslot(provider, dek)`: wrap DEK with new provider, write to empty slot
- T2.3.5: Implement `VaultManager.removeKeyslot(slotIndex)`: overwrite slot bytes with random, rewrite header
- T2.3.6: Implement `VaultManager.rotateKeyslots(newProviders, dek)`: re-wrap DEK in all specified providers atomically

**Story 2.4 — Key rotation (FR-8)**
- T2.4.1: Implement provider-rotation (passphrase change): derive new keyslot_key, re-encrypt DEK in that slot only
- T2.4.2: Implement full DEK rotation: generate new DEK, re-encrypt all vault files, wrap in all current keyslots, update header atomically

**Story 2.5 — VaultManager tests**
- T2.5.1: Create vault, unlock with passphrase → success
- T2.5.2: Wrong passphrase → `VaultError.InvalidCredential`
- T2.5.3: Add second keyslot (key file), unlock with key file → success, original passphrase still works
- T2.5.4: Remove keyslot → remaining providers still unlock; removed provider returns error
- T2.5.5: Tampered header bytes → `VaultError.HeaderTampered`
- T2.5.6: All 8 keyslots tried even if slot 0 succeeds (constant-time guarantee)

---

### Epic 3 — CryptoLayer and Transparent I/O
*Goal: GraphLoader and GraphWriter transparently encrypt/decrypt without structural changes.*

**Story 3.1 — CryptoLayer implementation**
- T3.1.1: Implement `CryptoLayer.encrypt(filePath: String, plaintext: ByteArray): ByteArray` — HKDF subkey, random nonce, STEK header prepend
- T3.1.2: Implement `CryptoLayer.decrypt(filePath: String, raw: ByteArray): Either<VaultError, ByteArray>` — magic check, nonce extract, AEAD decrypt
- T3.1.3: Handle non-STEK files gracefully: if magic does not match, return `VaultError.NotEncrypted` (allows reading plaintext graphs alongside encrypted ones during migration)
- T3.1.4: Add `DomainError.FileSystemError.CorruptedFile` for tampered/corrupted AEAD failures

**Story 3.2 — GraphLoader integration**
- T3.2.1: Add optional `cryptoLayer: CryptoLayer?` constructor parameter to `GraphLoader`
- T3.2.2: In `loadPageByName`, `loadFullPage`, and `loadDirectory` — after `fileSystem.readFile(path)`, pass bytes through `cryptoLayer?.decrypt(path, bytes)` before `parseAndSavePage`
- T3.2.3: Queue `ExternalFileChange` events when `cryptoLayer` is null (locked state); replay on unlock
- T3.2.4: In `sanitizeDirectory`, skip `.stele-vault` and `_hidden_reserve/`

**Story 3.3 — GraphWriter integration**
- T3.3.1: Add optional `cryptoLayer: CryptoLayer?` constructor parameter to `GraphWriter`
- T3.3.2: In `savePageInternal` Saga Step 1, if `cryptoLayer` is non-null: encrypt `content.toByteArray()` before `fileSystem.writeFile`; Saga compensation restores old encrypted bytes
- T3.3.3: Override `getPageFilePath` to append `.stek` extension for paranoid-mode graphs
- T3.3.4: Block writes to `_hidden_reserve/` path prefix from the outer graph (throw `VaultError.HiddenAreaWriteDenied`)

**Story 3.4 — CryptoLayer tests**
- T3.4.1: Encrypt file, write to disk, read, decrypt → original content
- T3.4.2: Modified ciphertext byte → `VaultError.AuthenticationFailed` propagated as `DomainError`
- T3.4.3: File path change invalidates decryption (AAD mismatch) — prevents silent relocation attacks
- T3.4.4: Non-STEK file returns `VaultError.NotEncrypted` (migration compatibility)

---

### Epic 4 — GraphManager and ViewModel Integration
*Goal: Paranoid-mode graphs trigger unlock flow at startup; lock/unlock lifecycle is correct.*

**Story 4.1 — GraphManager vault detection**
- T4.1.1: Add `isParanoidMode: Boolean` to `GraphInfo` (serialized in `GraphRegistry`)
- T4.1.2: In `GraphManager.addGraph`: detect `.stele-vault` at graph root, set `isParanoidMode = true`
- T4.1.3: In `GraphManager.switchGraph`: if `isParanoidMode`, emit `VaultState.Locked` before constructing `RepositorySet`; hold `RepositorySet` construction until unlock succeeds
- T4.1.4: Wire `VaultManager` lifecycle to `GraphManager` graph scope (`activeGraphJobs`)

**Story 4.2 — StelekitViewModel unlock flow**
- T4.2.1: Add `VaultState` sealed class (`Locked`, `Unlocking`, `Unlocked`, `Error`) to `AppState`
- T4.2.2: Observe `VaultState` in `StelekitViewModel`; on `Locked`, push `VaultUnlockScreen`
- T4.2.3: On unlock success: inject `CryptoLayer` into `GraphLoader` and `GraphWriter`, proceed with `loadGraph`
- T4.2.4: On unlock failure: surface error, remain on unlock screen (no rate limiting — Argon2id is the rate limiter)
- T4.2.5: Expose `lockGraph()` action: calls `VaultManager.lock()`, clears DEK, navigates to unlock screen

**Story 4.3 — Auto-lock idle timeout**
- T4.3.1: Implement idle-timeout countdown in `VaultManager` internal scope
- T4.3.2: Reset countdown on any user interaction event (key press, pointer move)
- T4.3.3: On timeout: call `lock()` flow, emit `VaultLocked` to ViewModel
- T4.3.4: Expose idle-timeout setting in `VaultSettingsScreen` (default: never)

**Story 4.4 — ViewModel tests**
- T4.4.1: Paranoid-mode graph open → VaultUnlockScreen shown
- T4.4.2: Correct passphrase → graph loads; unlock screen dismissed
- T4.4.3: Wrong passphrase → error shown; unlock screen persists
- T4.4.4: Lock action → DEK zeroed, unlock screen shown again

---

### Epic 5 — Unlock UI
*Goal: Clean unlock UX matching NFR-8 (editing UX unchanged after unlock).*

**Story 5.1 — VaultUnlockScreen**
- T5.1.1: Build `VaultUnlockScreen` composable: passphrase field (`CharArray`-backed, not `String`), "Use key file" toggle, unlock button
- T5.1.2: Show Argon2id progress indicator during derivation (non-blocking — derivation on `Dispatchers.Default`)
- T5.1.3: Error states: `InvalidCredential`, `HeaderTampered`, `VaultCorrupted`
- T5.1.4: "Open as hidden graph" — non-prominent secondary action (keyboard shortcut or settings link); triggers hidden-namespace unlock

**Story 5.2 — VaultSettingsScreen**
- T5.2.1: Display per-graph paranoid-mode status (enabled/disabled, algorithm, format version)
- T5.2.2: List registered providers with add/remove actions
- T5.2.3: Add provider flow: passphrase (confirm field), key file picker, OS keychain (auto)
- T5.2.4: Key rotation action: "Change passphrase" (provider rotation) and "Re-encrypt all files" (DEK rotation)
- T5.2.5: Idle-timeout picker (never / 5 / 15 / 30 / 60 minutes)
- T5.2.6: Warning banner when only one provider is registered

---

### Epic 6 — Hidden Volume
*Goal: Two-passphrase plausible deniability with no UI indication of hidden volume existence.*

**Story 6.1 — HiddenVolumeManager**
- T6.1.1: Implement `HiddenVolumeManager.createHiddenVolume(path, hiddenProvider, reserveSizeMb)`: pre-fill `_hidden_reserve/` with CSPRNG bytes, create hidden-namespace keyslots (slots 4–7)
- T6.1.2: Implement hidden volume unlock path: `VaultManager.unlock` detects `namespace_tag = 0x01`, routes graph load to hidden-volume virtual archive
- T6.1.3: Implement hidden-volume virtual archive: single encrypted `.stek` archive within `_hidden_reserve/`; in-memory virtual filesystem for hidden pages/blocks
- T6.1.4: Ensure no UI text ever exposes "outer" or "hidden" — graph name and all UI elements are identical regardless of namespace

**Story 6.2 — Hidden volume tests**
- T6.2.1: Outer passphrase → outer graph loads; hidden passphrase → hidden graph loads; contents differ
- T6.2.2: Adversary with outer passphrase: cannot decrypt any hidden-namespace keyslot
- T6.2.3: Outer writes do not overwrite `_hidden_reserve/` contents
- T6.2.4: Hidden volume creation fills reserve with random bytes (no plaintext zeros)

---

### Epic 7 — Security Hardening and Pitfall Mitigations
*Goal: Address known attack vectors documented in `research/pitfalls.md`.*

**Story 7.1 — Memory safety**
- T7.1.1: Use `CharArray` (not `String`) for passphrase input throughout; zero-fill after use
- T7.1.2: Zero-fill DEK `ByteArray` in `VaultManager.lock()`; nullify all references
- T7.1.3: Do not log DEK bytes, subkey bytes, or passphrase characters in any log level; add detekt rule
- T7.1.4: Document JVM GC limitation: zero-on-use is best-effort; recommend OS-level full-disk encryption for defense-in-depth

**Story 7.2 — Nonce safety**
- T7.2.1: Enforce `SecureRandom` / `crypto.getRandomValues()` for all nonce generation (never timestamps, counters, or `kotlin.random.Random`)
- T7.2.2: Never reuse a `Cipher` instance across multiple encryptions — create fresh instance per call
- T7.2.3: Add `jvmTest` asserting that 10,000 `secureRandom(12)` calls produce no collisions (probabilistic; documents intent)

**Story 7.3 — Vault header integrity**
- T7.3.1: Include all header bytes (bytes 0–2572) as input to the HMAC-SHA256 header MAC
- T7.3.2: Verify header MAC immediately after DEK recovery; fail with `VaultError.HeaderTampered` on mismatch
- T7.3.3: Document evil-maid attack vector in user-facing help: recommend secure boot + OS FDE for full protection

**Story 7.4 — Filesystem metadata mitigations (partial)**
- T7.4.1: Set fixed modification timestamp (epoch zero) on all `.stek` files after write to reduce timestamp fingerprinting
- T7.4.2: Pad plaintext to nearest 4 KB boundary before encryption to eliminate exact-size leakage (optional in v1 — flag as configurable)

**Story 7.5 — OS Keychain edge cases**
- T7.5.1: JVM/macOS: use `KeychainStore`; JVM/Windows: use `Windows-MY`; JVM/Linux: use PKCS#12 at `~/.local/share/stelekit/vault-{graphId}.p12` with warning
- T7.5.2: WASM: warn if OS-keychain is the only registered provider (IndexedDB deletion = permanent lockout)
- T7.5.3: Always require at least one passphrase or key-file slot; block removing the last non-keychain slot

---

### Epic 8 — Vault Creation Flow and Format Migration
*Goal: Users can create paranoid-mode graphs and the format is version-gated.*

**Story 8.1 — Vault creation UX**
- T8.1.1: "New Graph" dialog: add "Enable paranoid mode" checkbox; when checked, show passphrase setup step
- T8.1.2: Argon2id parameter benchmarking at vault creation: measure 64 MiB / 3 iter timing; offer "Fast" (lower memory) / "Standard" / "Strong" presets
- T8.1.3: Random pre-fill of graph directory with dummy data before first file write (prevents distinguishing encrypted from unencrypted graphs by file count)

**Story 8.2 — Format versioning**
- T8.2.1: Format version field (byte 4 of vault header) guards the deserializer; unknown version → `VaultError.UnsupportedVersion`
- T8.2.2: Migration path for v1→v2: decrypt all files with old DEK → re-encrypt with new algorithm → update header version field; write new files before updating header (transaction-safe)

---

## 5. Implementation Sequence

The tasks above must be implemented in the following phase order to respect dependencies:

| Phase | Epics | Gate condition |
|---|---|---|
| Phase 1 | Epic 1 (CryptoEngine) | Unit tests pass for JVM; WASM stubs compile |
| Phase 2 | Epic 2 (VaultManager) | VaultManager tests pass; .stele-vault round-trips correctly |
| Phase 3 | Epic 3 (CryptoLayer + I/O integration) | GraphLoader/GraphWriter integration tests pass; no plaintext on disk |
| Phase 4 | Epic 4 (GraphManager + ViewModel) | Unlock flow works end-to-end on desktop JVM |
| Phase 5 | Epic 5 (Unlock UI) | VaultUnlockScreen and VaultSettingsScreen render and function |
| Phase 6 | Epic 7 (Hardening) | Memory-safety, nonce, and header-MAC tests pass |
| Phase 7 | Epic 8 (Creation UX + Versioning) | New paranoid-mode graph creation flow works |
| Phase 8 | Epic 6 (Hidden Volume) | Hidden volume creation and dual-unlock tests pass |

Hidden volume (Epic 6) is deferred to the final phase because it builds on all prior infrastructure and has the highest complexity-to-value ratio. It can be shipped as a follow-up if time pressure warrants.

---

## 6. Flagged Risks and Open Questions

### RISK-1 (High): WASM Kotlin interop with libsodium.js
Kotlin/WASM JS interop (`external` declarations, `@JsModule`, `@JsExport`) is available but immature compared to Kotlin/JS. libsodium.js WASM initialization is async (`sodium.ready` promise) — the `CryptoEngine` interface must either be `suspend`-initialized or wrap the init in a Deferred. This is a breaking constraint on the `CryptoEngine` interface design.

**Resolution needed before Phase 1**: Prototype WASM interop with libsodium.js in an isolated test module. If initialization proves unwieldy, fall back to `argon2-browser` (Argon2id only) plus a thin Rust WASM bundle (`chacha-poly1305-wasm`) for the AEAD. Two separate WASM bundles are worse for bundle size but may be easier to interop.

### RISK-2 (Medium): JVM `SecretKeySpec.destroy()` does not guarantee memory zeroing
`SecretKeySpec.destroy()` is documented as a best-effort operation. On OpenJDK 11–21, the backing byte array is zeroed by the implementation, but this is not guaranteed by the spec. Zeroing the input `ByteArray` before passing to `SecretKeySpec` is the primary mitigation (T7.1.2).

**Accepted risk**: JVM process memory dumps remain a theoretical vector while the vault is unlocked. This is documented in the user-facing threat model.

### RISK-3 (Medium): ChaCha20-Poly1305 on JDK — tag position
The JDK `ChaCha20-Poly1305` implementation appends the 16-byte Poly1305 tag to the ciphertext in a single `doFinal` call — there is no separate `getOutputSize` for tag-only. The STEK file format assumes this layout. Verify this against the JDK implementation before finalizing the binary format spec.

### RISK-4 (Medium): Argon2id benchmark at vault creation
The benchmark must run on the user's device at vault creation time (NFR-6: ≤ 5 s unlock). On constrained hardware (2 GB RAM, slow IO), the default 64 MiB / 3 iterations might exceed 5 s on WASM. The "Fast" preset must be validated on representative low-end hardware before shipping.

### RISK-5 (Low): Linux OS keychain (JVM)
There is no universal Linux keystore. `org.freedesktop.secrets` (GNOME Keyring) requires D-Bus and a running secrets daemon — not available on all desktop environments (KDE, XFCE, bare WM users). The PKCS#12 fallback (T7.5.1) is cryptographically weaker (machine-derived key). Document clearly.

### RISK-6 (Low): `sanitizeDirectory` renames `.md` to `.md.stek` on load
`GraphLoader.sanitizeDirectory` renames files to normalize filenames. In paranoid mode, file extensions are `.md.stek`. The sanitize logic must exclude encrypted files from its `.md`-only filter to prevent renaming encrypted files to `.md` (corrupting them). (T3.2.4 covers this but must be explicitly tested.)

### RISK-7 (Low): Hidden volume virtual archive max size
The single-archive approach for v1 means the hidden volume has a fixed maximum size (set at creation). If the user fills the hidden volume, writes silently fail. A capacity warning in `HiddenVolumeManager` before writes reach 90% of capacity is required.

### OPEN-1: Per-file path stored as AAD — absolute vs. relative path
The file path used as HKDF salt and AAD must be stable across graph moves. Using the absolute path means moving the graph directory breaks decryption of all files. Using a graph-root-relative path (e.g., `pages/MyNote.md.stek`) is strongly preferred. This must be confirmed as the canonical choice before Phase 3 implementation.

**Decision**: Use graph-root-relative path (relative to `.stele-vault` parent directory). Record this in an ADR.

### OPEN-2: Vault creation for existing plaintext graphs
The requirements state plaintext-to-paranoid conversion is out of scope for v1 (user must export and re-import). However, the UX for "convert this graph" is a common request. A migration wizard (decrypt all, re-encrypt, update header) should be prototyped early as a settings action, even if not shipping in v1.

### OPEN-3: iOS/Android exclusion scope confirmation
Requirements mark iOS/macOS/Android as out of scope for v1. The `CryptoEngine` interface should still be structured to accommodate `iosMain` and `androidMain` actuals in v2 without interface changes. Argon2id on Android would use BouncyCastle (same as JVM); iOS would use CommonCrypto + a Swift Argon2id binding. No implementation work now, but interface design should not preclude it.

---

## 7. Summary

| Dimension | Count |
|---|---|
| Epics | 8 |
| Stories | 25 |
| Tasks | ~85 |
| New source files (estimate) | ~18 in `vault/` package + UI screens |
| New Gradle dependencies | 1 (`bcprov-jdk18on:1.80` in `jvmMain`) |
| Existing files modified | `GraphLoader.kt`, `GraphWriter.kt`, `GraphManager.kt`, `GraphInfo.kt`, `AppState.kt`, `StelekitViewModel.kt`, `DomainError.kt`, `kmp/build.gradle.kts` |
| Flagged risks | 7 (1 High, 4 Medium, 2 Low) |
| Open questions requiring decision | 3 |

The single highest-priority action before coding begins: **prototype WASM/libsodium.js interop** (RISK-1) to confirm the `CryptoEngine` interface design is achievable on the WASM target without architectural revision.
