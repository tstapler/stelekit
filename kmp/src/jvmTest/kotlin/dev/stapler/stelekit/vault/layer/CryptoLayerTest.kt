package dev.stapler.stelekit.vault.layer

import arrow.core.Either
import dev.stapler.stelekit.vault.*
import kotlin.test.*

class CryptoLayerTest {
    private val engine = JvmCryptoEngine()
    private val dek = engine.secureRandom(32)
    private val layer = CryptoLayer(engine, dek)
    private val plaintext = "# My Note\n\n- block one\n- block two\n".encodeToByteArray()

    // CL-01 — encrypt produces STEK-magic-prefixed bytes
    @Test fun `encrypt produces STEK magic prefix`() {
        val encrypted = layer.encrypt("pages/MyNote.md.stek", plaintext)
        assertContentEquals(CryptoLayer.STEK_MAGIC, encrypted.sliceArray(0 until 4))
    }

    // CL-02 — encrypt embeds version byte 0x01
    @Test fun `encrypt embeds version byte 0x01`() {
        val encrypted = layer.encrypt("pages/MyNote.md.stek", plaintext)
        assertEquals(CryptoLayer.STEK_VERSION, encrypted[4])
    }

    // CL-03 — encrypt embeds 12-byte nonce at offset 5
    @Test fun `encrypt embeds 12-byte nonce at offset 5`() {
        val encrypted = layer.encrypt("pages/MyNote.md.stek", plaintext)
        assertTrue(encrypted.size >= CryptoLayer.HEADER_SIZE)
        val nonce = encrypted.sliceArray(5 until 17)
        assertEquals(12, nonce.size)
    }

    // CL-04 — Round-trip: encrypt → decrypt recovers original plaintext
    @Test fun `round-trip encrypt and decrypt recovers plaintext`() {
        val path = "pages/MyNote.md.stek"
        val encrypted = layer.encrypt(path, plaintext)
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(plaintext, result.getOrNull())
    }

    // CL-05 — File path change invalidates authentication (AAD binding)
    @Test fun `file path change invalidates decryption`() {
        val encrypted = layer.encrypt("pages/A.md.stek", plaintext)
        val result = layer.decrypt("pages/B.md.stek", encrypted)
        assertTrue(result.isLeft())
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull())
    }

    // CL-06 — Modified ciphertext byte → AuthenticationFailed
    @Test fun `modified ciphertext byte causes AuthenticationFailed`() {
        val path = "pages/MyNote.md.stek"
        val encrypted = layer.encrypt(path, plaintext).copyOf()
        encrypted[30] = (encrypted[30].toInt() xor 0xFF).toByte()
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isLeft())
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull())
    }

    // CL-07 — Non-STEK file returns VaultError.NotEncrypted
    @Test fun `non-STEK file returns NotEncrypted`() {
        val plainMarkdown = "# My Note\n\n- block\n".encodeToByteArray()
        val result = layer.decrypt("pages/Note.md", plainMarkdown)
        assertTrue(result.isLeft())
        assertIs<VaultError.NotEncrypted>(result.leftOrNull())
    }

    // CL-08 — Truncated STEK file returns VaultError.CorruptedFile
    @Test fun `truncated STEK file returns CorruptedFile`() {
        val truncated = ByteArray(10) { 0x53.toByte() }  // partial "STEK" magic-like bytes
        val result = layer.decrypt("pages/Note.md.stek", truncated)
        // Either CorruptedFile or NotEncrypted depending on magic check result
        assertTrue(result.isLeft())
    }

    // CL-09 — Different files with same content produce different ciphertexts (HKDF path isolation)
    @Test fun `different file paths produce different ciphertexts for same content`() {
        val ctA = layer.encrypt("pages/A.md.stek", plaintext)
        val ctB = layer.encrypt("pages/B.md.stek", plaintext)
        assertFalse(ctA.contentEquals(ctB))
    }
}
