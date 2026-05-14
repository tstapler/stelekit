package dev.stapler.stelekit.vault.security

import dev.stapler.stelekit.vault.JvmCryptoEngine
import kotlin.test.*

class NoncePropertyTest {
    private val engine = JvmCryptoEngine()

    // NP-01 — 10,000 secureRandom(12) calls produce no duplicate 12-byte nonces
    @Test fun `10000 nonces produce no duplicates`() {
        val nonces = mutableSetOf<String>()
        repeat(10_000) {
            val nonce = engine.secureRandom(12)
            nonces.add(nonce.toHex())
        }
        assertEquals(10_000, nonces.size, "Duplicate nonce detected in 10,000 samples")
    }

    // NP-02 — Successive encryptions of the same plaintext produce distinct ciphertexts
    @Test fun `successive encryptions produce distinct ciphertexts`() {
        val key = engine.secureRandom(32)
        val plaintext = ByteArray(1024) { it.toByte() }
        val ciphertexts = mutableSetOf<String>()
        repeat(10_000) {
            val nonce = engine.secureRandom(12)
            val ct = engine.encryptAEAD(key, nonce, plaintext, byteArrayOf())
            ciphertexts.add(ct.toHex())
        }
        assertEquals(10_000, ciphertexts.size, "Duplicate ciphertext detected in 10,000 samples")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
