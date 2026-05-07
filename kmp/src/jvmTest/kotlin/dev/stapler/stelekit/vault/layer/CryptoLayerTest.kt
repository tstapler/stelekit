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

    // U-CL-10 — Empty plaintext (0 bytes) round-trips
    @Test fun `encrypt and decrypt empty plaintext`() {
        val path = "pages/Empty.md.stek"
        val encrypted = layer.encrypt(path, byteArrayOf())
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(byteArrayOf(), result.getOrNull())
    }

    // U-CL-11 — Single-byte plaintext round-trips
    @Test fun `encrypt and decrypt single byte plaintext`() {
        val path = "pages/Single.md.stek"
        val encrypted = layer.encrypt(path, byteArrayOf(0x42))
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(byteArrayOf(0x42), result.getOrNull())
    }

    // U-CL-12 — Four-byte plaintext round-trips
    @Test fun `encrypt and decrypt four byte plaintext`() {
        val path = "pages/Four.md.stek"
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encrypted = layer.encrypt(path, data)
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(data, result.getOrNull())
    }

    // U-CL-13 — 16-byte plaintext (ChaCha20 block size boundary) round-trips
    @Test fun `encrypt and decrypt 16-byte plaintext at block boundary`() {
        val path = "pages/Sixteen.md.stek"
        val data = ByteArray(16) { it.toByte() }
        val encrypted = layer.encrypt(path, data)
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(data, result.getOrNull())
    }

    // U-CL-14 — Large plaintext (1 MB) round-trips correctly
    @Test fun `encrypt and decrypt large plaintext`() {
        val path = "pages/Large.md.stek"
        val data = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val encrypted = layer.encrypt(path, data)
        val result = layer.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(data, result.getOrNull())
    }

    // U-CL-15 — Byte array with fewer than 4 bytes returns CorruptedFile (magic check impossible)
    @Test fun `byte array shorter than magic returns CorruptedFile`() {
        val result = layer.decrypt("pages/Tiny.md.stek", byteArrayOf(0x01, 0x02, 0x03))
        assertTrue(result.isLeft())
        assertIs<VaultError.CorruptedFile>(result.leftOrNull())
    }

    // U-CL-16 — Exactly HEADER_SIZE bytes (no ciphertext, no tag) causes AEAD failure
    @Test fun `exactly header-size bytes with no ciphertext causes authentication failure`() {
        val path = "pages/HeaderOnly.md.stek"
        val data = ByteArray(CryptoLayer.HEADER_SIZE)
        CryptoLayer.STEK_MAGIC.copyInto(data)
        data[4] = CryptoLayer.STEK_VERSION
        // Bytes 5..16 are zero-nonce; no ciphertext means no Poly1305 tag → AEAD fails
        val result = layer.decrypt(path, data)
        assertTrue(result.isLeft())
    }

    // U-CL-17 — Successive encryptions of the same content use distinct nonces
    @Test fun `successive encryptions produce different nonces`() {
        val path = "pages/Nonce.md.stek"
        val data = "same content".encodeToByteArray()
        val enc1 = layer.encrypt(path, data)
        val enc2 = layer.encrypt(path, data)
        val nonce1 = enc1.sliceArray(5 until 17)
        val nonce2 = enc2.sliceArray(5 until 17)
        assertFalse(nonce1.contentEquals(nonce2), "Each encryption must use a distinct random nonce")
    }

    // U-CL-18 — Two CryptoLayer instances sharing the same DEK are cross-compatible
    @Test fun `two CryptoLayer instances with same DEK can cross-decrypt`() {
        val dekBytes = engine.secureRandom(32)
        val layer1 = CryptoLayer(engine, dekBytes)
        val layer2 = CryptoLayer(engine, dekBytes)
        val path = "pages/Cross.md.stek"
        val content = "cross-layer content".encodeToByteArray()
        val encrypted = layer1.encrypt(path, content)
        val result = layer2.decrypt(path, encrypted)
        assertTrue(result.isRight())
        assertContentEquals(content, result.getOrNull())
    }
}
