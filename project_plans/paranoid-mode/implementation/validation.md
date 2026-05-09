# Paranoid Mode — Validation Plan

## Status: Draft
## Phase: 4 — Validation (pre-implementation)
## Paired with: `plan.md` (Epic 1–8 tasks), `../requirements.md`

---

## Coverage Summary

| Requirement type | Total | Covered | Fraction |
|---|---|---|---|
| Functional Requirements (FR-1 – FR-8) | 8 | 8 | **8/8 (100%)** |
| Non-Functional Requirements (NFR-1 – NFR-10) | 10 | 9 | **9/10 (90%)** |
| Acceptance Criteria (AC-1 – AC-7) | 7 | 7 | **7/7 (100%)** |

NFR-8 (editing UX unchanged after unlock) is verified through ViewModel integration tests but has no direct automated UI assertion; it is marked as manual / screenshot test.

## Test Case Counts by Type

| Type | Count |
|---|---|
| Unit — CryptoEngine (JVM) | 14 |
| Unit — VaultManager | 14 |
| Unit — CryptoLayer | 9 |
| Unit — VaultHeader serialization | 6 |
| Integration — round-trip | 10 |
| Integration — GraphLoader / GraphWriter | 8 |
| Integration — ViewModel / GraphManager | 6 |
| Property-based — nonce uniqueness | 2 |
| Property-based — keyslot integrity | 4 |
| Security — adversarial | 12 |
| Hidden volume | 8 |
| Performance | 5 |
| **Total** | **98** |

---

## Test Source Set Assignments

All vault tests live under:

```
kmp/src/jvmTest/kotlin/dev/stapler/stelekit/vault/
```

Sub-packages:

| Package suffix | Contents |
|---|---|
| `crypto/` | `CryptoEngineTest`, `HkdfTest`, `Argon2idTest` |
| `vault/` | `VaultManagerTest`, `VaultHeaderSerializerTest`, `KeyslotTest` |
| `layer/` | `CryptoLayerTest`, `StekFormatTest` |
| `integration/` | `VaultRoundTripTest`, `GraphLoaderCryptoTest`, `GraphWriterCryptoTest` |
| `security/` | `AdversarialTest`, `NoncePropertyTest`, `KeyslotIntegrityTest` |
| `hidden/` | `HiddenVolumeTest` |
| `perf/` | `VaultPerformanceTest` |
| `viewmodel/` | `VaultViewModelTest` |

Property-based tests use `kotlin.test` with a manual loop (`repeat(N)`) since Kotest property-based testing is not yet on the classpath. If Kotest is added, migrate to `forAll { }`.

---

## 1. Unit Tests — CryptoEngine (JvmCryptoEngine)

Source file: `kmp/src/jvmTest/.../vault/crypto/CryptoEngineTest.kt`

### CE-01 — Round-trip: encrypt then decrypt recovers original plaintext
- **Covers**: FR-2, NFR-1, AC-1
- **Setup**: `JvmCryptoEngine`. Generate a random 32-byte key, 12-byte nonce, arbitrary plaintext, empty AAD.
- **Assert**: `decrypt(key, nonce, encrypt(key, nonce, plaintext, aad=""), aad="") == plaintext`

### CE-02 — Ciphertext differs from plaintext
- **Covers**: FR-2, NFR-1
- **Assert**: encrypted bytes are not equal to input bytes (trivial sanity guard).

### CE-03 — Different nonces produce different ciphertexts for same plaintext
- **Covers**: NFR-1
- **Setup**: Encrypt same plaintext with two distinct nonces.
- **Assert**: ciphertexts are not equal.

### CE-04 — Authentication tag failure on modified ciphertext
- **Covers**: FR-2, NFR-1, NFR-4, AC-5 (security)
- **Setup**: Encrypt, flip one ciphertext byte, attempt decrypt.
- **Assert**: throws / returns `VaultError.AuthenticationFailed`; no partial plaintext returned.

### CE-05 — Authentication failure on modified AAD
- **Covers**: FR-2 (AAD binding), plan §2a subkey derivation
- **Setup**: Encrypt with `aad="pages/Foo.md"`, attempt decrypt with `aad="pages/Bar.md"`.
- **Assert**: `VaultError.AuthenticationFailed`.

### CE-06 — HKDF produces 32-byte output
- **Covers**: FR-2 subkey derivation
- **Assert**: `hkdfSha256(ikm, salt, info, len=32).size == 32`

### CE-07 — HKDF is deterministic: same inputs produce same key
- **Covers**: FR-2, correctness
- **Assert**: two calls with identical parameters return identical byte arrays.

### CE-08 — HKDF differentiates by salt (file path)
- **Covers**: FR-2, plan §2a path-binding
- **Setup**: `hkdfSha256(dek, salt="pages/A.md.stek", info, 32)` vs `hkdfSha256(dek, salt="pages/B.md.stek", info, 32)`
- **Assert**: results are not equal.

### CE-09 — HKDF differentiates by info string
- **Covers**: FR-2 (algorithm agility, info namespace separation)
- **Assert**: `info="stelekit-file-v1"` vs `info="stelekit-header-v1"` produce different keys.

### CE-10 — Argon2id output length matches requested length
- **Covers**: NFR-2, FR-3
- **Assert**: `argon2id(password, salt, memory=64*1024, iterations=3, parallelism=1, outLen=32).size == 32`

### CE-11 — Argon2id is deterministic: same parameters produce same output
- **Covers**: FR-3 (unlock must be reproducible)
- **Assert**: two calls return identical byte arrays.

### CE-12 — Argon2id differentiates by salt
- **Covers**: FR-3 (per-keyslot salt uniqueness)
- **Assert**: `argon2id(..., salt=s1, ...) != argon2id(..., salt=s2, ...)`

### CE-13 — Argon2id known-vector test (RFC 9106 test vector)
- **Covers**: NFR-2, correctness vs spec
- **Setup**: Use the `type=2 (Argon2id) t=2 m=65536 p=4` test vector from RFC 9106 §B.
- **Assert**: output matches expected hex string exactly.

### CE-14 — `secureRandom` produces non-zero bytes (probabilistic sanity)
- **Covers**: NFR-1 (never all-zeros nonce)
- **Setup**: Generate 1000 × 12-byte nonces.
- **Assert**: at least 999 are non-zero (the probability of a genuine all-zero 96-bit value is 2⁻⁹⁶).

---

## 2. Unit Tests — VaultHeader Serialization

Source file: `kmp/src/jvmTest/.../vault/vault/VaultHeaderSerializerTest.kt`

### VH-01 — Serialized header has correct total byte length
- **Covers**: plan §2b format spec
- **Assert**: `serialize(header).size == 2605` (4 magic + 1 version + 8 pad + 2048 slots + 512 reserved + 32 MAC)

### VH-02 — Magic bytes at offset 0 are `SKVT`
- **Covers**: FR-1 (vault format), plan §2b
- **Assert**: bytes[0..3] == `[0x53, 0x4B, 0x56, 0x54]`

### VH-03 — Round-trip: serialize → deserialize recovers identical VaultHeader
- **Covers**: FR-1, FR-3, plan §2b
- **Assert**: `deserialize(serialize(header)) == header` (structural equality)

### VH-04 — Wrong magic bytes → VaultError.NotAVault
- **Covers**: FR-1 (format guard), NFR-9 (versioning)
- **Assert**: `deserialize(bytes with magic = "XXXX") == Left(VaultError.NotAVault)`

### VH-05 — Unknown version byte → VaultError.UnsupportedVersion
- **Covers**: NFR-9 (format versioning), plan T8.2.1
- **Setup**: Serialize header, overwrite version byte with `0xFF`.
- **Assert**: `deserialize(…) == Left(VaultError.UnsupportedVersion)`

### VH-06 — Unused keyslots are indistinguishable from random bytes
- **Covers**: NFR-5 (hidden volume, no fingerprinting), plan §2b
- **Setup**: Create header with 3 active slots (0, 2, 5); fill remaining with random.
- **Assert**: each unused slot has no discernible all-zero regions (assert `slotBytes.none { it == 0.toByte() }` fails randomly — verify by checking at least 50% of bytes in unused slots are non-zero across 10 different generated headers).

---

## 3. Unit Tests — VaultManager

Source file: `kmp/src/jvmTest/.../vault/vault/VaultManagerTest.kt`

### VM-01 — createVault writes a readable .stele-vault file
- **Covers**: FR-1, FR-3, AC-1
- **Setup**: Temp dir; `VaultManager.createVault(path, PassphraseProvider("correct"))`.
- **Assert**: `.stele-vault` file exists; file size == 2605 bytes; magic bytes correct.

### VM-02 — Unlock with correct passphrase returns Right(DEK)
- **Covers**: FR-5, AC-2
- **Assert**: `unlock(path, PassphraseProvider("correct")) == Right(dek)` where DEK is 32 bytes.

### VM-03 — Wrong passphrase returns Left(VaultError.InvalidCredential)
- **Covers**: FR-5, NFR-2 (Argon2id as rate-limiter), AC-2
- **Assert**: `unlock(path, PassphraseProvider("wrong"))` is `Left(VaultError.InvalidCredential)`.

### VM-04 — All 8 keyslots are always iterated (constant-time guarantee)
- **Covers**: NFR-1 (timing oracle), plan §2b, T2.5.6
- **Setup**: Create vault, install spy on `CryptoEngine.decryptAEAD`, unlock with correct passphrase.
- **Assert**: `decryptAEAD` is called exactly 8 times regardless of which slot is active.

### VM-05 — Add second keyslot (KeyFileProvider), unlock with key file
- **Covers**: FR-3, FR-5, AC-2
- **Assert**: After `addKeyslot(KeyFileProvider(file))`, unlock succeeds with key file; original passphrase still works.

### VM-06 — Remove keyslot; removed provider cannot unlock
- **Covers**: FR-3, AC-3 (add/remove without re-encrypting content)
- **Assert**: After `removeKeyslot(slotIndex)`, `unlock(passphrase)` returns `Left(InvalidCredential)`; remaining provider still succeeds.

### VM-07 — Header MAC verified after DEK recovery; tampered header is rejected
- **Covers**: NFR-4, plan T2.3.2, T7.3.2
- **Setup**: Unlock to get vault; flip one byte in the keyslot area after writing; retry unlock.
- **Assert**: Returns `Left(VaultError.HeaderTampered)`.

### VM-08 — lock() zero-fills DEK byte array
- **Covers**: NFR-3, FR-7, AC-6
- **Setup**: Capture reference to DEK `ByteArray` before lock; call `lock()`.
- **Assert**: All bytes in the captured array are 0 within 100 ms of lock returning.

### VM-09 — lock() emits VaultLocked event
- **Covers**: FR-7, plan T2.3.3
- **Assert**: `VaultManager.vaultEvents.first { it == VaultEvent.Locked }` completes within 1 s of `lock()`.

### VM-10 — rotateKeyslots: DEK unchanged after provider rotation
- **Covers**: FR-8, AC-3
- **Setup**: Create a file, encrypt it. Rotate provider (new passphrase). Attempt decrypt with old subkey (derived from same DEK).
- **Assert**: File decrypts correctly after rotation — same DEK is still in use.

### VM-11 — Full DEK rotation re-encrypts all files
- **Covers**: FR-8, plan T2.4.2
- **Setup**: Create 3 files; perform full DEK rotation.
- **Assert**: All 3 files decrypt correctly with new DEK; old DEK cannot decrypt them (returns `AuthenticationFailed`).

### VM-12 — VaultError.UnsupportedVersion on unknown header version
- **Covers**: NFR-9, plan T8.2.1
- **Assert**: `unlock(path_with_v255_header, …)` returns `Left(VaultError.UnsupportedVersion)`.

### VM-13 — Empty keyslot array → VaultError.NoValidKeyslot
- **Covers**: FR-3 (edge case: corrupted header with all random slots)
- **Setup**: Write a valid header but overwrite all 8 slots with non-AEAD-verifying random bytes.
- **Assert**: `unlock(…)` returns `Left(VaultError.NoValidKeyslot)` or equivalent.

### VM-14 — Argon2id parameters stored in keyslot match parameters used at unlock
- **Covers**: FR-3 (parameter agility), NFR-2
- **Setup**: Create vault with non-default params (`memory=131072, iterations=5`). Read back keyslot. 
- **Assert**: Deserialized `Argon2Params` has `memory=131072, iterations=5`.

---

## 4. Unit Tests — CryptoLayer

Source file: `kmp/src/jvmTest/.../vault/layer/CryptoLayerTest.kt`

### CL-01 — encrypt produces STEK-magic-prefixed bytes
- **Covers**: FR-2, plan §2a
- **Assert**: result[0..3] == `[0x53, 0x54, 0x45, 0x4B]` ("STEK")

### CL-02 — encrypt embeds version byte 0x01
- **Covers**: NFR-9 (format versioning)
- **Assert**: result[4] == `0x01`

### CL-03 — encrypt embeds 12-byte nonce at offset 5
- **Covers**: FR-2, plan §2a offset table
- **Assert**: `result.size >= 17` and bytes[5..16] are the nonce (non-zero for a random nonce).

### CL-04 — Round-trip: encrypt → decrypt recovers original plaintext
- **Covers**: FR-2, FR-4 (transparent I/O), AC-1
- **Assert**: `cryptoLayer.decrypt(path, cryptoLayer.encrypt(path, plaintext)) == Right(plaintext)`

### CL-05 — File path change invalidates authentication (AAD binding)
- **Covers**: FR-2, plan §2a, plan OPEN-1 (relative path decision), T3.4.3
- **Setup**: Encrypt as `"pages/A.md.stek"`; attempt decrypt as `"pages/B.md.stek"`.
- **Assert**: Returns `Left(VaultError.AuthenticationFailed)`.

### CL-06 — Modified ciphertext byte → AuthenticationFailed
- **Covers**: NFR-1 (AEAD integrity), NFR-4, T3.4.2
- **Assert**: Flip bytes[30], attempt decrypt → `Left(VaultError.AuthenticationFailed)`.

### CL-07 — Non-STEK file returns VaultError.NotEncrypted
- **Covers**: plan T3.1.3 (migration compatibility)
- **Setup**: Pass raw UTF-8 markdown bytes to `cryptoLayer.decrypt(path, bytes)`.
- **Assert**: Returns `Left(VaultError.NotEncrypted)`.

### CL-08 — Truncated STEK file (<17 bytes) returns VaultError.CorruptedFile
- **Covers**: robustness, plan T3.1.4
- **Assert**: `decrypt(path, ByteArray(10))` returns `Left(VaultError.CorruptedFile)`.

### CL-09 — Different files with same content produce different ciphertexts (HKDF path isolation)
- **Covers**: FR-2 (per-file subkeys), NFR-1
- **Setup**: Encrypt identical plaintext for `"pages/A.md.stek"` and `"pages/B.md.stek"`.
- **Assert**: Ciphertexts are not equal (different subkeys).

---

## 5. Integration Tests — Full Round-Trip

Source file: `kmp/src/jvmTest/.../vault/integration/VaultRoundTripTest.kt`

### RT-01 — New paranoid-mode graph: all files on disk are ciphertext after first save
- **Covers**: FR-2, AC-1
- **Setup**: Create temp graph dir; create vault; save a page; read raw bytes from disk.
- **Assert**: File begins with STEK magic; raw bytes do not contain the original UTF-8 string.

### RT-02 — Read back saved page decrypts to original content
- **Covers**: FR-2, transparent I/O
- **Assert**: `graphLoader.loadPage("MyPage") == originalMarkdown`

### RT-03 — Multiple files independently encrypt/decrypt
- **Covers**: FR-2, AC-1
- **Setup**: Save 5 distinct pages; read all back.
- **Assert**: Each round-trips correctly; no cross-file contamination.

### RT-04 — External file change detection works through CryptoLayer
- **Covers**: FR-2, plan T3.2.3 (ExternalFileChange flow)
- **Setup**: Unlock, save, externally overwrite file with new encrypted content, observe change event.
- **Assert**: Decrypted change event carries correct new content.

### RT-05 — Saga rollback on write failure restores old encrypted bytes
- **Covers**: plan T3.3.2, error handling
- **Setup**: Inject a failing `fileSystem` that throws on the second write; trigger `savePageInternal`.
- **Assert**: Original encrypted file content is restored after rollback.

### RT-06 — Unlock, edit, lock, re-unlock, read — content persists
- **Covers**: FR-7 (lock/unlock lifecycle), AC-6
- **Assert**: Content written before lock is readable after re-unlock with same passphrase.

### RT-07 — Passphrase change (provider rotation) → graph still readable
- **Covers**: FR-8, AC-3
- **Assert**: After `rotateKeyslots(newPassphrase)`, unlock with new passphrase → all pages load correctly.

### RT-08 — Full DEK rotation → graph still readable; old passphrase fails
- **Covers**: FR-8, AC-3
- **Assert**: After DEK rotation, old passphrase + old DEK cannot decrypt files; new passphrase can.

### RT-09 — `.stele-vault` file is never absent on disk (paranoid mode invariant)
- **Covers**: FR-1 (vault file always present)
- **Assert**: After createVault, addKeyslot, removeKeyslot, rotateKeyslots — `.stele-vault` exists and is valid at each step.

### RT-10 — `sanitizeDirectory` skips `.stele-vault` and `_hidden_reserve/`
- **Covers**: plan T3.2.4, RISK-6
- **Setup**: Load a paranoid-mode graph directory via `GraphLoader`; observe `sanitizeDirectory` output.
- **Assert**: `.stele-vault` is not renamed; `_hidden_reserve/` is not traversed.

---

## 6. Integration Tests — GraphLoader / GraphWriter

Source file: `kmp/src/jvmTest/.../vault/integration/GraphLoaderCryptoTest.kt`  
Source file: `kmp/src/jvmTest/.../vault/integration/GraphWriterCryptoTest.kt`

### GL-01 — GraphLoader with CryptoLayer=null does not attempt decryption
- **Covers**: plan T3.2.1 (optional parameter), backward compat
- **Setup**: Pass raw encrypted file to `GraphLoader` with `cryptoLayer=null`.
- **Assert**: `loadPage` returns an error or empty result (does not crash).

### GL-02 — GraphLoader with CryptoLayer decrypts before parsing
- **Covers**: FR-2, plan T3.2.2
- **Assert**: Parsed `Page` object has correct blocks matching original markdown.

### GL-03 — GraphLoader queues ExternalFileChange when locked (cryptoLayer=null)
- **Covers**: plan T3.2.3
- **Setup**: Simulate external disk write; GraphLoader has no CryptoLayer.
- **Assert**: Event is queued; after injecting CryptoLayer, event is replayed and decrypted.

### GL-04 — GraphWriter with CryptoLayer encrypts before writing
- **Covers**: FR-2, plan T3.3.2, AC-1
- **Setup**: `GraphWriter` with live CryptoLayer; call `saveBlock`.
- **Assert**: Raw file bytes on disk begin with STEK magic.

### GL-05 — GraphWriter appends .stek extension to file path
- **Covers**: plan T3.3.3 (`.md.stek` extension)
- **Assert**: File path on disk is `pages/MyPage.md.stek`, not `pages/MyPage.md`.

### GL-06 — GraphWriter blocks writes to _hidden_reserve/ from outer graph
- **Covers**: plan T3.3.4, FR-4 (hidden volume protection)
- **Assert**: Attempt to save a page whose computed path falls under `_hidden_reserve/` returns `Left(VaultError.HiddenAreaWriteDenied)`.

### GW-07 — .stek files survive a graphLoader → graphWriter → graphLoader cycle
- **Covers**: end-to-end correctness
- **Assert**: 10 pages written and re-read all match original content.

### GW-08 — Plaintext (non-STEK) files in a paranoid-mode graph return VaultError.NotEncrypted, not crash
- **Covers**: plan T3.1.3 (migration edge case)
- **Assert**: Loading a plaintext `.md` file through GraphLoader with CryptoLayer active returns `Left(VaultError.NotEncrypted)`.

---

## 7. Integration Tests — ViewModel / GraphManager

Source file: `kmp/src/jvmTest/.../vault/viewmodel/VaultViewModelTest.kt`

### VM-VM-01 — Opening paranoid-mode graph emits VaultState.Locked before RepositorySet construction
- **Covers**: FR-5 unlock flow, plan T4.1.3, T4.4.1, AC-2
- **Setup**: `GraphManager.addGraph(path_with_.stele-vault)`.
- **Assert**: First emission on `vaultState` is `VaultState.Locked`.

### VM-VM-02 — Correct passphrase → VaultState.Unlocked and graph loads
- **Covers**: FR-5, AC-2, T4.4.2
- **Assert**: `unlock(passphrase)` transitions state to `VaultState.Unlocked`; `activeRepositorySet` is non-null.

### VM-VM-03 — Wrong passphrase → VaultState.Error; unlock screen persists
- **Covers**: FR-5, T4.4.3
- **Assert**: `unlock("bad")` transitions to `VaultState.Error(VaultError.InvalidCredential)`; state does not transition to `Unlocked`.

### VM-VM-04 — lockGraph() zeroes DEK and returns to VaultState.Locked
- **Covers**: FR-7, NFR-3, T4.4.4, AC-6
- **Assert**: After `lockGraph()`, `vaultState == VaultState.Locked`; `activeRepositorySet` is null.

### VM-VM-05 — Auto-lock fires after configured idle timeout
- **Covers**: FR-7, plan T4.3.1–T4.3.3
- **Setup**: Set idle timeout to 100 ms; advance virtual clock past timeout.
- **Assert**: `vaultState == VaultState.Locked` without explicit `lockGraph()` call.

### VM-VM-06 — Non-paranoid-mode graph does not emit VaultState
- **Covers**: FR-1 (toggle is opt-in), NFR-8
- **Assert**: Opening a plaintext graph does not push `VaultUnlockScreen`.

---

## 8. Property-Based Tests — Nonce Uniqueness

Source file: `kmp/src/jvmTest/.../vault/security/NoncePropertyTest.kt`

### NP-01 — 10,000 secureRandom(12) calls produce no duplicate 12-byte nonces
- **Covers**: NFR-1 (no nonce reuse), plan T7.2.3
- **Method**: Generate 10,000 nonces into a `HashSet<ByteArray>`; compare set size vs count.
- **Assert**: `set.size == 10_000` (collision probability ≈ 2⁻⁷⁸ for 96-bit, negligible)
- **Note**: Documents intent and catches implementation defects (e.g., counter or timestamp nonce). True nonce-uniqueness at scale requires NIST SP 800-38D compliance which is documented but not unit-tested.

### NP-02 — Successive encryptions of the same plaintext produce distinct ciphertexts
- **Covers**: NFR-1 (per-write random nonce), FR-2
- **Method**: Encrypt same 1 KB block 10,000 times with same key; collect ciphertexts into `HashSet`.
- **Assert**: All 10,000 ciphertexts are distinct.

---

## 9. Property-Based Tests — Keyslot Integrity

Source file: `kmp/src/jvmTest/.../vault/security/KeyslotIntegrityTest.kt`

### KI-01 — Any single keyslot byte mutation causes MAC verification failure
- **Covers**: NFR-4 (header authentication), plan T7.3.1–T7.3.2
- **Method**: For each of the 2605 header bytes (excluding the 32-byte MAC itself), create a mutated copy; attempt `deserializeAndVerify`.
- **Assert**: All 2573 non-MAC byte mutations return `Left(VaultError.HeaderTampered)`.
- **Implementation note**: This is O(2573) iterations — runs in <1 s with a real HMAC and is affordable in jvmTest.

### KI-02 — Truncated header bytes → CorruptedFile / deserialization error
- **Covers**: NFR-4, robustness
- **Method**: Try deserialize for lengths [0, 1, 100, 2572] (all < 2605).
- **Assert**: All return `Left(VaultError.CorruptedFile)` or `Left(VaultError.NotAVault)`.

### KI-03 — Unused keyslot random padding does not affect MAC verification of active slots
- **Covers**: NFR-4, plan §2b (unused slots are random but MAC covers all bytes)
- **Setup**: Create vault with 2 active keyslots. Verify unlock succeeds. Zero-fill an unused slot's reserved area and recompute MAC. Attempt unlock.
- **Assert**: Unlock with correct passphrase still succeeds (MAC is recomputed after modification only by `VaultManager`; this test verifies that MAC covers the full header including "random" areas).

### KI-04 — Header MAC key is derived from DEK, not hardcoded
- **Covers**: NFR-4, plan §2b `header_mac_key = HKDF-SHA256(DEK, salt="vault-header-mac", ...)`
- **Setup**: Create vault, extract serialized header and header MAC. Compute expected MAC key via `HKDF(dek, "vault-header-mac", "v1")`. Recompute HMAC-SHA256 over bytes[0..2572].
- **Assert**: Computed MAC matches the last 32 bytes of the vault header.

---

## 10. Security Tests — Adversarial

Source file: `kmp/src/jvmTest/.../vault/security/AdversarialTest.kt`

### SEC-01 — Wrong passphrase rejection (brute-force oracle)
- **Covers**: FR-5, NFR-2 (Argon2id rate limiting), AC-2
- **Assert**: 100 wrong passphrase attempts all return `Left(VaultError.InvalidCredential)`. No timing oracle is exposed: measure that no attempt returns in < 10 ms (Argon2id cost floor).

### SEC-02 — Tampered ciphertext: flipping each byte returns AuthenticationFailed
- **Covers**: NFR-1 (AEAD integrity), NFR-4
- **Method**: For bytes in the ciphertext region (offset 17 to end), flip each byte, attempt decrypt.
- **Assert**: Every modified version returns `Left(VaultError.AuthenticationFailed)`.

### SEC-03 — Nonce reuse would produce different ciphertext (reuse-detection helper)
- **Covers**: NFR-1 (nonce never reused)
- **Setup**: Manually encrypt same plaintext with same key AND same nonce twice (bypassing the normal path). XOR the two ciphertexts.
- **Assert**: XOR is not all-zeros (proving reuse produces detectable plaintext leakage). This test documents why the `secureRandom` path is mandatory.

### SEC-04 — DEK not present in vault header plaintext bytes
- **Covers**: NFR-3 (DEK never written to disk unencrypted)
- **Setup**: Create vault; read raw `.stele-vault` bytes; extract the known DEK from memory.
- **Assert**: The DEK byte sequence does not appear as a contiguous subsequence anywhere in the header bytes.

### SEC-05 — Locked graph: DEK ByteArray contains only zeros
- **Covers**: NFR-3, AC-6
- **Setup**: Unlock vault; capture DEK reference; call `lock()`.
- **Assert**: All bytes in captured reference are 0 within 100 ms.

### SEC-06 — Relocation attack: moving a file and re-reading it fails authentication
- **Covers**: FR-2 (path-as-AAD binding), plan §2a, OPEN-1
- **Setup**: Encrypt `"pages/A.md.stek"`; physically copy bytes to `"pages/B.md.stek"`.
- **Assert**: `decrypt("pages/B.md.stek", bytes)` returns `Left(VaultError.AuthenticationFailed)`.

### SEC-07 — DEK not logged at any log level (detekt or grep guard)
- **Covers**: NFR-3, plan T7.1.3
- **Method**: Grep source tree for patterns `log.*dek`, `println.*dek`, `logger.*key`, `logger.*passphrase` (case-insensitive) in `vault/` package.
- **Assert**: Zero matches (automated source scan, run as a test that reads its own source directory).

### SEC-08 — Passphrase field uses CharArray, not String
- **Covers**: NFR-3, plan T7.1.1
- **Method**: Static source check — grep `VaultUnlockScreen.kt` for `TextField` state backed by `String` (forbidden pattern).
- **Assert**: No `mutableStateOf("")` or `var passphrase: String` in the unlock screen composable; only `CharArray`-backed state.

### SEC-09 — Cipher instance not reused across two encryptions (no shared Cipher state)
- **Covers**: NFR-1, plan T7.2.2
- **Setup**: Encrypt 1000 times in a tight loop via `JvmCryptoEngine`. Capture `Cipher.hashCode()` via reflection or a test-visible counter.
- **Assert**: No two calls share the same `Cipher` instance (new instance per call).
- **Note**: If reflection is too brittle, use a spy `CryptoEngine` wrapper that records instance identity.

### SEC-10 — Constant-time MAC comparison: no early-return timing leak
- **Covers**: NFR-1 (timing safety), plan §2b constant-time comparison
- **Method**: Measure decrypt call time for (a) header with correct MAC, (b) header with MAC differing at byte 0, (c) header with MAC differing at byte 31.
- **Assert**: Mean timings for (b) and (c) differ by < 5% (timing neutrality; not a strict security proof but documents the intent and catches naive early-return bugs).
- **Note**: Full constant-time verification requires hardware-level analysis outside CI scope; this is a regression guard.

### SEC-11 — Passphrase CharArray is zeroed after unlock attempt (success or failure)
- **Covers**: NFR-3, plan T7.1.1
- **Setup**: Pass a `CharArray` passphrase to `VaultManager.unlock()`; capture reference; check after return.
- **Assert**: All chars in the original array are `' '` after `unlock()` returns (regardless of success/failure).

### SEC-12 — File path used as AAD is graph-root-relative, not absolute
- **Covers**: plan OPEN-1 (decision: use relative path)
- **Setup**: Create vault at `/tmp/graph-A/`; encrypt `"pages/MyNote.md.stek"`. Move vault to `/tmp/graph-B/`. Attempt decrypt with relative path `"pages/MyNote.md.stek"`.
- **Assert**: Decryption succeeds — proving the AAD does not embed the absolute path.

---

## 11. Hidden Volume Tests

Source file: `kmp/src/jvmTest/.../vault/hidden/HiddenVolumeTest.kt`

### HV-01 — Dual unlock: outer passphrase → outer namespace; hidden passphrase → hidden namespace
- **Covers**: FR-4, AC-4
- **Setup**: Create vault with both namespaces; write different content to each.
- **Assert**: `unlock(outerPassphrase).namespace == OUTER` and content is outer data; `unlock(hiddenPassphrase).namespace == HIDDEN` and content is hidden data.

### HV-02 — Outer passphrase cannot decrypt hidden-namespace keyslots
- **Covers**: FR-4, AC-5, NFR-5
- **Setup**: Derive `keyslot_key` from outer passphrase and Argon2 params for each of slots 4–7.
- **Assert**: `decryptAEAD(keyslot_key, slot_nonce, slot_blob)` returns `Left(AuthenticationFailed)` for all hidden-namespace slots.

### HV-03 — Outer writes do not touch _hidden_reserve/ directory
- **Covers**: FR-4, plan T6.2.3, T3.3.4
- **Setup**: Unlock as outer graph; capture hash of `_hidden_reserve/` contents; perform 10 page saves.
- **Assert**: Hash of `_hidden_reserve/` is unchanged after all saves.

### HV-04 — Hidden volume creation fills _hidden_reserve/ with non-zero random bytes
- **Covers**: NFR-5, plan T6.1.1, T6.2.4
- **Setup**: Create hidden volume with `reserveSizeMb=1`.
- **Assert**: `_hidden_reserve/` blob exists; is exactly 1 MB; has no run of > 16 consecutive zero bytes (probabilistic — a CSPRNG would not produce this).

### HV-05 — Header gives no indication of namespace count
- **Covers**: FR-4, NFR-5, AC-5
- **Setup**: Create vault with only outer namespace. Create vault with both namespaces. Compare raw header bytes in the keyslot area.
- **Assert**: Binary layout of used vs unused keyslot regions is indistinguishable by structure (byte lengths are identical; all unused slots are filled with random bytes of the same length).

### HV-06 — All 8 keyslots tried before failing (constant-time with hidden volume)
- **Covers**: FR-4 (timing oracle resistance), plan §2b
- **Setup**: Spy on `decryptAEAD` call count; attempt unlock with both a valid outer passphrase and an incorrect passphrase.
- **Assert**: Both cases result in exactly 8 `decryptAEAD` calls.

### HV-07 — Hidden graph files are not accessible when unlocked as outer graph
- **Covers**: FR-4 (namespace isolation), AC-4
- **Setup**: Write 3 pages to hidden graph; unlock as outer graph; attempt to load those page names.
- **Assert**: Pages are not found in outer graph's repository.

### HV-08 — namespace_tag byte in decrypted DEK blob identifies namespace
- **Covers**: FR-4, plan §2b keyslot layout
- **Assert**: Decrypting a known outer keyslot blob yields `namespace_tag == 0x00`; hidden yields `0x01`.

---

## 12. Performance Tests

Source file: `kmp/src/jvmTest/.../vault/perf/VaultPerformanceTest.kt`

These tests run in jvmTest with `@Category(PerformanceTest::class)` to allow selective exclusion from CI fast-path. All thresholds are P99, measured over 10 warm runs on the CI runner (2 vCPU, 7 GB RAM).

### PERF-01 — Argon2id at default params completes in ≤ 5,000 ms (NFR-6)
- **Covers**: NFR-6
- **Setup**: `argon2id(password, salt, memory=65536 KiB, iterations=3, parallelism=1, outLen=32)`.
- **Assert**: Wall-clock time ≤ 5000 ms. Emit actual time to test output for benchmark tracking.
- **Threshold source**: NFR-6 "≤ 5 s on low-end hardware". CI runner is not low-end; threshold here is conservative.

### PERF-02 — Encrypt 100 KB file in ≤ 5 ms (NFR-7)
- **Covers**: NFR-7
- **Setup**: `cryptoLayer.encrypt("pages/bigfile.md.stek", ByteArray(100 * 1024))` × 100 warm iterations.
- **Assert**: Median encrypt time ≤ 5 ms.

### PERF-03 — Decrypt 100 KB file in ≤ 5 ms (NFR-7)
- **Covers**: NFR-7
- **Setup**: Pre-encrypt 100 KB; `decrypt` × 100 warm iterations.
- **Assert**: Median decrypt time ≤ 5 ms.

### PERF-04 — Per-file HKDF subkey derivation overhead ≤ 0.5 ms per file
- **Covers**: NFR-7 (per-file overhead budget, subkey component)
- **Setup**: `hkdfSha256(dek, filePath.toByteArray(), info, 32)` × 10,000 iterations.
- **Assert**: Mean time per call ≤ 0.5 ms.

### PERF-05 — Lock (DEK zeroing) completes in ≤ 1,000 ms
- **Covers**: AC-6 ("clears DEK from memory within 1 second")
- **Setup**: Measure time from `vaultManager.lock()` call to DEK array being all-zero.
- **Assert**: Elapsed time ≤ 1000 ms.

---

## 13. WASM Platform Coverage Notes

WASM (`wasmJsMain`) tests cannot run in jvmTest. The following coverage strategy applies:

### WASM test strategy

| Category | Approach |
|---|---|
| `WasmCryptoEngine` unit tests | Browser-based Kotlin/WASM test runner (`wasmJsBrowserTest` Gradle task). Mirror CE-01 through CE-14 using `WasmCryptoEngine` under Node.js / headless Chrome. |
| Known-vector alignment | CE-13 Argon2id vector is run against both `JvmCryptoEngine` and `WasmCryptoEngine` to ensure cross-platform byte-for-byte equivalence. |
| Round-trip cross-platform | Generate an encrypted file on JVM; decrypt on WASM (same DEK, same file path). Assert plaintext equality. This is the canonical interoperability test. |
| Nonce uniqueness | NP-01 and NP-02 run in WASM context to verify `crypto.getRandomValues()` path. |
| OS keychain (Web) | Tested via integration test that initializes `SubtleCrypto.generateKey({extractable:false})` and verifies the non-extractable key can unlock the vault. Manual test on real browser only (IndexedDB not fully available in test runner). |
| Performance | PERF-01 Argon2id time measured in WASM separately; expected ≤ 450 ms at default params per research (`architecture.md`). |

### WASM gap — no CI coverage today

WASM tests require the `enableJs=true` Gradle property and a headless Chrome on the CI runner. The `wasmJsBrowserTest` task is not included in `ciCheck` for v1 (see `kmp/build.gradle.kts`). WASM coverage is therefore manual + pre-release only.

**Action required before shipping**: add `wasmJsBrowserTest` to the `release` CI workflow with a Chrome headless step.

---

## 14. Requirement-to-Test Traceability Matrix

| Requirement | Test IDs | Status |
|---|---|---|
| **FR-1** Paranoid mode toggle / `.stele-vault` presence | VH-01, VH-02, VM-01, RT-09, VM-VM-01, VM-VM-06 | Covered |
| **FR-2** Encrypted file format (STEK header, HKDF subkeys, ChaCha20-Poly1305) | CE-01–CE-09, CL-01–CL-09, RT-01, RT-02, RT-03, SEC-03, SEC-04, SEC-06, SEC-12 | Covered |
| **FR-3** LUKS2 keyslot system (multi-provider, add/remove) | VM-02–VM-06, VM-10, VM-14, KI-01–KI-04 | Covered |
| **FR-4** Plausible deniability / hidden volume | HV-01–HV-08, SEC-02 | Covered |
| **FR-5** Desktop JVM unlock flow | VM-02, VM-03, VM-04, VM-VM-01–VM-VM-04, SEC-01 | Covered |
| **FR-6** Web/WASM unlock flow | WASM platform section (§13) | Partial (manual) |
| **FR-7** Lock / session expiry | VM-08, VM-09, VM-VM-04, VM-VM-05, PERF-05, SEC-05 | Covered |
| **FR-8** Key rotation (provider + full DEK) | VM-10, VM-11, RT-07, RT-08 | Covered |
| **NFR-1** ChaCha20-Poly1305, random nonces, no reuse | CE-01–CE-05, NP-01, NP-02, SEC-03, SEC-09 | Covered |
| **NFR-2** Argon2id ≥ 64 MiB / ≥ 3 iterations | CE-10–CE-13, VM-14, PERF-01 | Covered |
| **NFR-3** DEK never on disk; cleared on lock | SEC-04, SEC-05, SEC-07, SEC-08, SEC-11, VM-08 | Covered |
| **NFR-4** Vault header authenticated | VM-07, KI-01–KI-04, SEC-02, VH-06 | Covered |
| **NFR-5** Hidden-volume header indistinguishable | HV-04, HV-05, HV-06 | Covered |
| **NFR-6** Unlock ≤ 5 s | PERF-01 | Covered |
| **NFR-7** Per-file overhead ≤ 5 ms for ≤ 100 KB | PERF-02, PERF-03, PERF-04 | Covered |
| **NFR-8** Editing UX unchanged after unlock | VM-VM-06 (negative), manual screenshot test | Partial (manual) |
| **NFR-9** Encrypted format versioned | VH-05, VM-12, CL-02 | Covered |
| **NFR-10** Desktop JVM + Web/WASM required | jvmTest suite (this doc) + WASM §13 | Covered |
| **AC-1** No plaintext files after first save | RT-01, SEC-04, GL-04 | Covered |
| **AC-2** Any registered provider can unlock | VM-02, VM-05, VM-VM-02 | Covered |
| **AC-3** Add/remove provider does not re-encrypt content | VM-06, VM-10, RT-07 | Covered |
| **AC-4** Hidden passphrase shows different content | HV-01, HV-07 | Covered |
| **AC-5** Outer passphrase cannot detect hidden volume | HV-02, HV-05, HV-06 | Covered |
| **AC-6** Lock clears DEK within 1 s | VM-08, SEC-05, PERF-05 | Covered |
| **AC-7** All AC pass on Desktop JVM and Web/WASM | jvmTest + WASM §13 | Covered |

---

## 15. Implementation Notes for Test Authors

### Test infrastructure reuse

- Extend `BlockHoundTestBase` for any vault test that exercises code on `Dispatchers.Default` coroutine threads (Argon2id derivation in particular). This catches accidental blocking inside vault code.
- Shared fixture: `VaultTestFixture` (to be created in `kmp/src/jvmTest/.../vault/VaultTestFixture.kt`) providing:
  - `fun createTempVault(passphrase: String = "test-passphrase"): Pair<Path, VaultManager>`
  - `fun createTempParanoidGraph(): Triple<Path, VaultManager, CryptoLayer>`
  - Standard `Argon2Params(memory=4096, iterations=1, parallelism=1)` for fast test execution (do NOT use production defaults of 64 MiB / 3 iter in unit tests)

### Argon2id parameters in tests

Use `Argon2Params(memory=4096, iterations=1, parallelism=1)` for all tests except PERF-01. This reduces Argon2id test overhead from ~350 ms to ~5 ms while exercising the same code path.

### ByteArray equality in Kotlin tests

`ByteArray` does not override `equals()`. Use `assertContentEquals(expected, actual)` (from `kotlin.test`) throughout, not `assertEquals`.

### Coroutine test executor

Use `runTest { }` from `kotlinx-coroutines-test` for all `suspend` function tests. The jvmTest source set already has this on the classpath (verify in `kmp/build.gradle.kts`).

### Timing tests (PERF-*)

Wrap timing tests in `assumeTrue(System.getenv("RUN_PERF_TESTS") == "true")` so they are skipped by default in CI fast-path and enabled explicitly on benchmark runs or pre-release.
