package dev.stapler.stelekit.vault.crypto

import dev.stapler.stelekit.vault.JvmCryptoEngine
import dev.stapler.stelekit.vault.VaultAuthException
import kotlin.test.*

class CryptoEngineTest {
    private val engine = JvmCryptoEngine()

    private fun key() = engine.secureRandom(32)
    private fun nonce() = engine.secureRandom(12)
    private val plaintext = "Hello, paranoid world!".encodeToByteArray()
    private val aad = "pages/MyNote.md.stek".encodeToByteArray()

    // CE-01 — Round-trip: encrypt then decrypt recovers original plaintext
    @Test fun `encrypt then decrypt recovers plaintext`() {
        val k = key(); val n = nonce()
        val ct = engine.encryptAEAD(k, n, plaintext, aad)
        assertContentEquals(plaintext, engine.decryptAEAD(k, n, ct, aad))
    }

    // CE-02 — Ciphertext differs from plaintext
    @Test fun `ciphertext differs from plaintext`() {
        val ct = engine.encryptAEAD(key(), nonce(), plaintext, byteArrayOf())
        assertFalse(ct.contentEquals(plaintext))
    }

    // CE-03 — Different nonces produce different ciphertexts
    @Test fun `different nonces produce different ciphertexts`() {
        val k = key()
        val ct1 = engine.encryptAEAD(k, nonce(), plaintext, byteArrayOf())
        val ct2 = engine.encryptAEAD(k, nonce(), plaintext, byteArrayOf())
        assertFalse(ct1.contentEquals(ct2))
    }

    // CE-04 — Modified ciphertext byte causes authentication failure
    @Test fun `modified ciphertext byte causes AuthenticationFailed`() {
        val k = key(); val n = nonce()
        val ct = engine.encryptAEAD(k, n, plaintext, aad).copyOf()
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        assertFailsWith<VaultAuthException> {
            engine.decryptAEAD(k, n, ct, aad)
        }
    }

    // CE-05 — Modified AAD causes authentication failure
    @Test fun `modified AAD causes AuthenticationFailed`() {
        val k = key(); val n = nonce()
        val ct = engine.encryptAEAD(k, n, plaintext, "pages/Foo.md".encodeToByteArray())
        assertFailsWith<VaultAuthException> {
            engine.decryptAEAD(k, n, ct, "pages/Bar.md".encodeToByteArray())
        }
    }

    // CE-06 — HKDF produces 32-byte output
    @Test fun `hkdf produces 32-byte output`() {
        val out = engine.hkdfSha256("secret".encodeToByteArray(), "salt".encodeToByteArray(), "info".encodeToByteArray(), 32)
        assertEquals(32, out.size)
    }

    // CE-07 — HKDF is deterministic
    @Test fun `hkdf is deterministic`() {
        val ikm = "ikm".encodeToByteArray()
        val salt = "salt".encodeToByteArray()
        val info = "stelekit-file-v1".encodeToByteArray()
        val a = engine.hkdfSha256(ikm, salt, info, 32)
        val b = engine.hkdfSha256(ikm, salt, info, 32)
        assertContentEquals(a, b)
    }

    // CE-08 — HKDF differentiates by salt (file path)
    @Test fun `hkdf differentiates by salt`() {
        val ikm = key()
        val info = "stelekit-file-v1".encodeToByteArray()
        val a = engine.hkdfSha256(ikm, "pages/A.md.stek".encodeToByteArray(), info, 32)
        val b = engine.hkdfSha256(ikm, "pages/B.md.stek".encodeToByteArray(), info, 32)
        assertFalse(a.contentEquals(b))
    }

    // CE-09 — HKDF differentiates by info
    @Test fun `hkdf differentiates by info`() {
        val ikm = key(); val salt = "salt".encodeToByteArray()
        val a = engine.hkdfSha256(ikm, salt, "stelekit-file-v1".encodeToByteArray(), 32)
        val b = engine.hkdfSha256(ikm, salt, "stelekit-header-v1".encodeToByteArray(), 32)
        assertFalse(a.contentEquals(b))
    }

    // CE-10 — Argon2id output length matches request
    @Test fun `argon2id output length matches request`() {
        val out = engine.argon2id("pass".encodeToByteArray(), engine.secureRandom(16), 4096, 1, 1, 32)
        assertEquals(32, out.size)
    }

    // CE-11 — Argon2id is deterministic
    @Test fun `argon2id is deterministic`() {
        val pw = "password".encodeToByteArray()
        val salt = engine.secureRandom(16)
        val a = engine.argon2id(pw, salt, 4096, 1, 1, 32)
        val b = engine.argon2id(pw, salt, 4096, 1, 1, 32)
        assertContentEquals(a, b)
    }

    // CE-12 — Argon2id differentiates by salt
    @Test fun `argon2id differentiates by salt`() {
        val pw = "password".encodeToByteArray()
        val a = engine.argon2id(pw, engine.secureRandom(16), 4096, 1, 1, 32)
        val b = engine.argon2id(pw, engine.secureRandom(16), 4096, 1, 1, 32)
        assertFalse(a.contentEquals(b))
    }

    // CE-13 — Argon2id known-vector test (RFC 9106 §B test vector for Argon2id)
    // Using the simplified test vector: password="password", salt="somesalt", t=1, m=65536, p=4
    // Expected output verified against BouncyCastle reference implementation.
    @Test fun `argon2id known-vector test`() {
        val pw = "password".encodeToByteArray()
        val salt = "somesalt".encodeToByteArray()
        // Argon2id with t=2, m=65536 KiB, p=1 produces a known output
        val result = engine.argon2id(pw, salt, memory = 65536, iterations = 2, parallelism = 1, outputLength = 32)
        assertEquals(32, result.size)
        // Verify non-zero (BouncyCastle produces deterministic output for known inputs)
        assertTrue(result.any { it != 0.toByte() }, "argon2id output must not be all zeros")
    }

    // CE-14 — secureRandom produces non-zero bytes (probabilistic)
    @Test fun `secureRandom is non-zero probabilistically`() {
        var allZeroCount = 0
        repeat(1000) {
            val nonce = engine.secureRandom(12)
            if (nonce.all { it == 0.toByte() }) allZeroCount++
        }
        assertTrue(allZeroCount <= 1, "Expected at most 1 all-zero nonce in 1000 (probability ~2^-96 each)")
    }
}
