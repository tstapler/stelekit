package dev.stapler.stelekit.vault

/**
 * Platform-agnostic cryptographic primitives for paranoid-mode vault operations.
 *
 * All implementations must use cryptographically secure random sources (never counters or
 * timestamps) and must not share mutable cipher/key state across concurrent calls.
 *
 * JVM (Desktop): JvmCryptoEngine (javax.crypto ChaCha20-Poly1305 + BouncyCastle Argon2id/HKDF)
 * Android: TODO — AndroidCryptoEngine using javax.crypto (same APIs, different BouncyCastle setup)
 * WASM: TODO — WasmCryptoEngine via libsodium.js interop (out of scope for v1)
 */
interface CryptoEngine {
    /**
     * Encrypt [plaintext] using ChaCha20-Poly1305 AEAD.
     * Returns ciphertext with the 16-byte Poly1305 tag appended.
     * [aad] is authenticated but not encrypted.
     */
    fun encryptAEAD(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /**
     * Decrypt [ciphertext] (which includes the trailing Poly1305 tag) using ChaCha20-Poly1305.
     * Throws [VaultError.AuthenticationFailed] if the tag does not verify.
     */
    fun decryptAEAD(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    /**
     * HKDF-SHA256 key derivation.
     * Returns [length] bytes derived from [ikm] using [salt] and [info].
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /**
     * Argon2id password-based key derivation.
     * Returns [outputLength] bytes.
     */
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memory: Int,
        iterations: Int,
        parallelism: Int,
        outputLength: Int,
    ): ByteArray

    /**
     * Generate [length] cryptographically random bytes.
     * Must use SecureRandom / crypto.getRandomValues() — never counters or timestamps.
     */
    fun secureRandom(length: Int): ByteArray

    /** HMAC-SHA256: returns 32-byte MAC of [data] under [key]. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /**
     * Constant-time byte array comparison. Default is a pure-Kotlin XOR-fold;
     * platform implementations may delegate to a native constant-time primitive.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /**
     * Zero-fill [bytes] in place to reduce window for memory dumps.
     * Best-effort on JVM (GC may have copied the array).
     */
    fun clearBytes(bytes: ByteArray) {
        bytes.fill(0)
    }
}

/** Argon2id tuning parameters stored per-keyslot in the vault header. */
data class Argon2Params(
    val memory: Int,
    val iterations: Int,
    val parallelism: Int,
)

/**
 * Thrown by [CryptoEngine.decryptAEAD] when the Poly1305 authentication tag fails.
 * Caught at CryptoLayer and VaultManager boundaries and mapped to [VaultError.AuthenticationFailed].
 */
class VaultAuthException(message: String) : Exception(message)

/** Fast parameters for tests — do NOT use in production. */
val TEST_ARGON2_PARAMS = Argon2Params(memory = 4096, iterations = 1, parallelism = 1)

/** Production defaults: 64 MiB / 3 iterations / 1 thread. */
val DEFAULT_ARGON2_PARAMS = Argon2Params(memory = 64 * 1024, iterations = 3, parallelism = 1)
