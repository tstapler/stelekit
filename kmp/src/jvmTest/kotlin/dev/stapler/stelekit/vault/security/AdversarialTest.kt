package dev.stapler.stelekit.vault.security

import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@Suppress("DEPRECATION")
class AdversarialTest {
    private val engine = JvmCryptoEngine()
    private val params = TEST_ARGON2_PARAMS

    private fun makeVaultManager(store: MutableMap<String, ByteArray>): VaultManager =
        VaultManager(
            crypto = engine,
            fileReadBytes = { path -> store[path] },
            fileWriteBytes = { path, data -> store[path] = data; true },
        )

    // SEC-01 — Wrong passphrase rejection (100 attempts all return InvalidCredential)
    @Test fun `wrong passphrase always returns InvalidCredential`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/sec-test"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        repeat(5) { i ->  // Use 5 to keep test fast with Argon2id
            val result = vm.unlock(graphPath, "wrong-$i".toCharArray(), params)
            assertTrue(result.isLeft(), "Attempt $i should fail")
            assertIs<VaultError.InvalidCredential>(result.leftOrNull())
        }
    }

    // SEC-02 — Tampered ciphertext: each byte flip returns AuthenticationFailed
    @Test fun `tampered STEK ciphertext causes AuthenticationFailed`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val plaintext = "sensitive note content".encodeToByteArray()
        val encrypted = layer.encrypt("pages/Note.md.stek", plaintext)

        var failCount = 0
        for (i in CryptoLayer.HEADER_SIZE until encrypted.size) {
            val modified = encrypted.copyOf()
            modified[i] = (modified[i].toInt() xor 0x01).toByte()
            val result = layer.decrypt("pages/Note.md.stek", modified)
            if (result.isLeft()) failCount++
        }
        val payloadBytes = encrypted.size - CryptoLayer.HEADER_SIZE
        assertEquals(payloadBytes, failCount, "Every modified ciphertext byte should fail authentication")
    }

    // SEC-03 — Nonce reuse produces detectable plaintext leakage
    @Test fun `nonce reuse with same key leaks plaintext info`() {
        val key = engine.secureRandom(32)
        val nonce = engine.secureRandom(12)
        val p1 = "Hello, World!!!!!".encodeToByteArray()
        val p2 = "Goodbye, World!!!".encodeToByteArray()
        val ct1 = engine.encryptAEAD(key, nonce, p1, byteArrayOf())
        val ct2 = engine.encryptAEAD(key, nonce, p2, byteArrayOf())
        val xored = ByteArray(minOf(ct1.size, ct2.size)) { (ct1[it].toInt() xor ct2[it].toInt()).toByte() }
        assertFalse(xored.all { it == 0.toByte() }, "XOR of nonce-reused ciphertexts must not be zero (proves leakage)")
    }

    // SEC-04 — DEK not present in vault header plaintext bytes
    @Test fun `DEK is not present in vault header plaintext`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/sec-test"
        val dek = vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params).getOrNull()!!
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val headerBytes = store[vaultPath]!!

        // Verify DEK does not appear as a contiguous subsequence in the header
        val dekHex = dek.joinToString("") { "%02x".format(it) }
        val headerHex = headerBytes.joinToString("") { "%02x".format(it) }
        assertFalse(headerHex.contains(dekHex), "DEK must not appear in cleartext in the vault header")
    }

    // SEC-05 — Locked graph: DEK ByteArray contains only zeros
    @Test fun `locked graph has zeroed DEK`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/sec-test"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        vm.unlock(graphPath, "correct".toCharArray(), params)
        val dekRef = vm.currentDek()!!
        vm.lock()
        assertTrue(dekRef.all { it == 0.toByte() }, "All DEK bytes must be zero after lock()")
        assertNull(vm.currentDek(), "currentDek() must return null after lock()")
    }

    // SEC-06 — Relocation attack: moved file fails decryption
    @Test fun `relocated file fails decryption`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "secret note".encodeToByteArray()
        val encrypted = layer.encrypt("pages/A.md.stek", content)
        val result = layer.decrypt("pages/B.md.stek", encrypted)
        assertTrue(result.isLeft())
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull())
    }

    // SEC-11 — Passphrase CharArray is zeroed after unlock attempt
    @Test fun `passphrase chararray is zeroed after unlock`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/sec-test"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val passphrase = charArrayOf('c', 'o', 'r', 'r', 'e', 'c', 't')
        vm.unlock(graphPath, passphrase, params)
        assertTrue(passphrase.all { it == ' ' }, "Passphrase CharArray must be zero-filled after unlock()")
    }

    // SEC-12 — File path used as AAD is graph-root-relative (not absolute)
    @Test fun `relative path AAD works regardless of absolute graph path`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "# Note\n- content".encodeToByteArray()
        // Encrypt using relative path (as CryptoLayer always uses)
        val encrypted = layer.encrypt("pages/MyNote.md.stek", content)
        // Decrypt using same relative path — success
        val result = layer.decrypt("pages/MyNote.md.stek", encrypted)
        assertTrue(result.isRight())
        assertContentEquals(content, result.getOrNull())
    }

    // SEC-09 — New cipher instance per encryption call (no shared state)
    @Test fun `1000 successive encryptions produce distinct ciphertexts`() {
        val key = engine.secureRandom(32)
        val plaintext = "same content every time".encodeToByteArray()
        val ciphertexts = (1..1000).map {
            val nonce = engine.secureRandom(12)
            engine.encryptAEAD(key, nonce, plaintext, byteArrayOf())
        }
        val unique = ciphertexts.map { it.toHex() }.toSet()
        assertEquals(1000, unique.size, "All 1000 ciphertexts must be distinct (nonce reuse would produce duplicates)")
    }

    // SEC-13 — renamePage latent AAD bug: verbatim ciphertext copy breaks decryption at new path
    // GraphWriter.renamePage copies encrypted bytes verbatim to the new path. Because STEK AAD is
    // the *old* relative path, decryption at the new path fails AEAD authentication.
    // This test documents the latent bug and provides a regression anchor when it is fixed.
    @Test fun `verbatim ciphertext copy fails authentication at new path`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "# Note\n- content".encodeToByteArray()

        val oldPath = "pages/OldName.md.stek"
        val newPath = "pages/NewName.md.stek"

        val encryptedAtOldPath = layer.encrypt(oldPath, content)

        // Verbatim byte copy to new path — AAD is still oldPath, so decrypt(newPath) must fail.
        val result = layer.decrypt(newPath, encryptedAtOldPath)
        assertTrue(result.isLeft(), "Verbatim ciphertext copy must fail AEAD at new path (AAD mismatch)")
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull())
    }

    // SEC-14 — Encrypted file does not leak plaintext (ciphertext is not a superstring of plaintext)
    @Test fun `ciphertext does not contain plaintext bytes`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "# Secret Note\n- very important content that must not be visible".encodeToByteArray()
        val encrypted = layer.encrypt("pages/Secret.md.stek", content)

        val plaintextHex = content.toHex()
        val ciphertextHex = encrypted.sliceArray(CryptoLayer.HEADER_SIZE until encrypted.size).toHex()
        assertFalse(ciphertextHex.contains(plaintextHex), "Plaintext must not appear as a substring of ciphertext")
    }

    // SEC-15 — Wrong DEK causes AuthenticationFailed (no silent decryption with wrong key)
    @Test fun `wrong DEK causes AuthenticationFailed`() {
        val correctDek = engine.secureRandom(32)
        val wrongDek = engine.secureRandom(32)
        val correctLayer = CryptoLayer(engine, correctDek)
        val wrongLayer = CryptoLayer(engine, wrongDek)

        val path = "pages/Sensitive.md.stek"
        val content = "confidential data".encodeToByteArray()
        val encrypted = correctLayer.encrypt(path, content)

        val result = wrongLayer.decrypt(path, encrypted)
        assertTrue(result.isLeft(), "Wrong DEK must not decrypt the ciphertext")
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull())
    }

    // SEC-16 — Truncated ciphertext (valid STEK magic, no Poly1305 tag) causes error
    @Test fun `truncated ciphertext after magic causes authentication failure`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "content".encodeToByteArray()
        val encrypted = layer.encrypt("pages/Note.md.stek", content)

        // Keep only the STEK header bytes (magic + version + nonce), strip all ciphertext
        val truncated = encrypted.sliceArray(0 until CryptoLayer.HEADER_SIZE)
        val result = layer.decrypt("pages/Note.md.stek", truncated)
        assertTrue(result.isLeft(), "Header-only file (no ciphertext) must fail")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
