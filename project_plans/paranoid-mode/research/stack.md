# Stack Research — Paranoid Mode Crypto Libraries

## Summary

This document covers the concrete libraries, versions, and platform-specific patterns needed to implement Paranoid Mode on Desktop JVM and Web/WASM.

---

## 1. JVM (Desktop)

### ChaCha20-Poly1305

Java 11+ natively supports ChaCha20-Poly1305 via `javax.crypto.Cipher`:

```kotlin
val cipher = Cipher.getInstance("ChaCha20-Poly1305")
val paramSpec = IvParameterSpec(nonce)
cipher.init(Cipher.ENCRYPT_MODE, secretKey, paramSpec)
val ciphertext = cipher.doFinal(plaintext)
```

- **Transformation string**: `"ChaCha20-Poly1305"`
- **Nonce**: 12 bytes (96-bit), must be fresh per encryption; re-using nonce with same key breaks security
- **Key**: 32-byte (`SecretKeySpec(bytes, "ChaCha20")`)
- **Tag**: 16 bytes appended by the JDK implementation
- **Caveat**: PKCS#11 provider does not support ChaCha20-Poly1305 (OpenJDK bug JDK-8255410). Must use the default `SunJCE` provider, not PKCS#11.
- After each encryption/decryption, the cipher must be re-initialized with a new nonce — do not reuse the `Cipher` instance across operations.

### Argon2id (Key Derivation)

BouncyCastle ≥ 1.70 provides `Argon2BytesGenerator` (no native libraries required):

```kotlin
// Gradle
implementation("org.bouncycastle:bcprov-jdk18on:1.80")

// Usage
val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
    .withSalt(salt)
    .withMemoryAsKB(65536)   // 64 MiB
    .withIterations(3)
    .withParallelism(1)
    .build()
val gen = Argon2BytesGenerator()
gen.init(params)
val key = ByteArray(32)
gen.generateBytes(passphrase.toByteArray(Charsets.UTF_8), key)
```

- **Current stable**: BouncyCastle 1.80 (bcprov-jdk18on artifact)
- **Kotlin DSL**: `bcgit/bc-kotlin` provides a Kotlin-idiomatic wrapper; optional but reduces boilerplate
- **Pure JVM**: no JNI/native dependency — safe for all desktop JVM targets

### HKDF-SHA256 (Per-File Subkey)

BouncyCastle includes `HKDFBytesGenerator`:

```kotlin
val hkdf = HKDFBytesGenerator(SHA256Digest())
hkdf.init(HKDFParameters(dek, filePath.toByteArray(), "file".toByteArray()))
val subkey = ByteArray(32)
hkdf.generateBytes(subkey, 0, 32)
```

Alternatively `javax.crypto.Mac` + `HMAC-SHA256` can implement HKDF manually per RFC 5869 without BouncyCastle.

### OS Keychain (JVM)

`java.security.KeyStore` with platform keystores:
- macOS: `KeychainStore` (JCEKS-backed)
- Windows: `Windows-MY`
- Linux: no universal keystore; fallback to file-based keystore or PKCS#12 at user-chosen path

---

## 2. Web / WASM

### ChaCha20-Poly1305

**WebCrypto does not support ChaCha20-Poly1305** — only AES-GCM, AES-CTR, AES-CBC, and RSA/ECDSA families are in the W3C WebCrypto Level 2 spec (as of 2025). ChaCha20 support request has been open since 2014 (w3c/webcrypto issue #223) with no timeline.

Options for WASM:

| Option | Notes |
|--------|-------|
| `libsodium.js` (WASM build of libsodium) | Supports `crypto_aead_chacha20poly1305_ietf_*`; mature, widely used |
| `chacha-poly1305-wasm` (npm) | Rust ChaCha20Poly1305 exposed via WASM; smaller bundle |
| `@dugrema/wasm-xchacha20poly1305` | XChaCha20-Poly1305 variant; 192-bit nonce (safer for random nonce gen) |
| Kotlin/WASM interop with libsodium.js | Call JS crypto via `@JsModule`/`external` declarations |

**Recommended**: Use `libsodium.js` (WASM build) via Kotlin/WASM `external` JS interop. Provides both ChaCha20-Poly1305 and Argon2id.

### Argon2id (WASM)

- `argon2-browser` (github: antelle/argon2-browser) — mature, npm-published, ~320–400 ms per hash at 64 MiB/3 iterations in Chrome/Firefox WASM
- `argon2ian` — smaller footprint (valpackett/argon2ian), uses Monocypher internals, ~372–393 ms/iter
- `libsodium.js` includes `crypto_pwhash` (Argon2id) — single-dependency option

**Performance note**: 64 MiB / 3 iterations ≈ 350–450 ms in browser WASM (well under the 5 s NFR-6 ceiling). The benchmark at vault creation must measure on the user's device and tune parameters downward if needed.

### OS Keychain (WASM)

- Use `SubtleCrypto.generateKey()` with `{ extractable: false }` — the key is hardware-bound in the browser's secure enclave where available
- Persist via `IndexedDB` using the `CryptoKey` object directly (structured clone serializes non-extractable keys in most browsers since 2021)
- On mobile browsers this maps to TPM or Secure Enclave via the browser runtime

---

## 3. KMP Abstraction Layer

The recommended pattern is **interface + `expect`/`actual`** for low-level crypto shims:

```kotlin
// commonMain
interface CryptoEngine {
    fun encryptAEAD(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun decryptAEAD(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray
    fun argon2id(password: ByteArray, salt: ByteArray, memory: Int, iterations: Int, parallelism: Int): ByteArray
    fun secureRandom(size: Int): ByteArray
}

// jvmMain: JvmCryptoEngine — uses javax.crypto + BouncyCastle
// wasmJsMain: WasmCryptoEngine — uses libsodium.js via JS interop
```

This avoids `expect`/`actual` for the entire crypto surface and keeps the common code free from platform references. The `CryptoEngine` instance is injected into the vault layer via constructor.

### `cryptography-kotlin` (whyoleg)

- GitHub: `whyoleg/cryptography-kotlin`
- Supports ChaCha20-Poly1305 and HKDF in commonMain with provider backends
- WASM-JS support via WebCrypto was added in 0.3.0 (Feb 2024)
- **Gap**: Does not include Argon2id — BouncyCastle (JVM) and libsodium.js/argon2-browser (WASM) are still needed for KDF
- Can be used as the AEAD layer with manual Argon2id wrapping; evaluate against custom `CryptoEngine` interface

### `KotlinCrypto/random`

- Provides `CryptographicallySecureRandom` in commonMain — use instead of platform `SecureRandom` for nonce generation

---

## 4. Dependency Summary

| Function | JVM | WASM/JS |
|----------|-----|---------|
| ChaCha20-Poly1305 AEAD | `javax.crypto` (JDK 11+) | `libsodium.js` (WASM) |
| Argon2id KDF | `bcprov-jdk18on:1.80` (BouncyCastle) | `argon2-browser` or `libsodium.js` |
| HKDF-SHA256 | BouncyCastle `HKDFBytesGenerator` | `libsodium.js` `crypto_kdf_hkdf_*` |
| Secure random | `java.security.SecureRandom` | `crypto.getRandomValues()` via JS |
| OS keychain | `java.security.KeyStore` | `SubtleCrypto` + `IndexedDB` |

---

## Sources

- [cryptography-kotlin — whyoleg](https://github.com/whyoleg/cryptography-kotlin)
- [Java 11 ChaCha20-Poly1305 examples — mkyong](https://mkyong.com/java/java-11-chacha20-poly1305-encryption-examples/)
- [BouncyCastle Kotlin API](https://docs.keyfactor.com/bouncycastle/latest/how-to-use-the-bouncy-castle-kotlin-api)
- [WebCrypto ChaCha20 issue #223](https://github.com/w3c/webcrypto/issues/223)
- [argon2-browser — antelle](https://github.com/antelle/argon2-browser)
- [libsodium ChaCha20-Poly1305](https://libsodium.gitbook.io/doc/secret-key_cryptography/aead/chacha20-poly1305)
- [KotlinCrypto/random](https://github.com/KotlinCrypto/random)
- [HKDF RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869)
- [JDK-8255410 PKCS11 ChaCha20 bug](https://bugs.openjdk.org/browse/JDK-8255410)
- [Kotlin/WASM Beta — JetBrains blog](https://blog.jetbrains.com/kotlin/2025/08/kmp-roadmap-aug-2025/)
