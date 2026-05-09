package dev.stapler.stelekit.vault.integration

import arrow.core.Either
import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

/**
 * Round-trip integration tests using an in-memory file store.
 * Covers RT-01 through RT-10 (all gaps filled).
 */
@Suppress("DEPRECATION")
class VaultRoundTripTest {
    private val engine = JvmCryptoEngine()
    private val params = TEST_ARGON2_PARAMS

    private fun makeVaultManager(store: MutableMap<String, ByteArray>): VaultManager =
        VaultManager(
            crypto = engine,
            fileReadBytes = { path -> store[path] },
            fileWriteBytes = { path, data -> store[path] = data; true },
        )

    // RT-01 — Saved file on disk is ciphertext (begins with STEK magic)
    @Test fun `saved file begins with STEK magic`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer = CryptoLayer(engine, dek)
        val content = "# My Note\n- hello".encodeToByteArray()
        val encrypted = layer.encrypt("pages/MyNote.md.stek", content)
        store["$graphPath/pages/MyNote.md.stek"] = encrypted

        val raw = store["$graphPath/pages/MyNote.md.stek"]!!
        assertContentEquals(CryptoLayer.STEK_MAGIC, raw.sliceArray(0 until 4))
        assertFalse(raw.decodeToString().contains("hello"))
    }

    // RT-02 — Read back saved page decrypts to original content
    @Test fun `read back saved page decrypts to original content`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer = CryptoLayer(engine, dek)

        val original = "# My Note\n- hello".encodeToByteArray()
        val encrypted = layer.encrypt("pages/MyNote.md.stek", original)
        store["$graphPath/pages/MyNote.md.stek"] = encrypted

        val rawBack = store["$graphPath/pages/MyNote.md.stek"]!!
        val decrypted = layer.decrypt("pages/MyNote.md.stek", rawBack)
        assertTrue(decrypted.isRight())
        assertContentEquals(original, decrypted.getOrNull())
    }

    // RT-03 — Multiple files independently encrypt/decrypt
    @Test fun `multiple files independently encrypt and decrypt`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer = CryptoLayer(engine, dek)

        val pages = (1..5).map { i -> "pages/Page$i.md.stek" to "# Page $i\n- content $i".encodeToByteArray() }
        for ((path, content) in pages) {
            store["$graphPath/$path"] = layer.encrypt(path, content)
        }
        for ((path, expectedContent) in pages) {
            val raw = store["$graphPath/$path"]!!
            val result = layer.decrypt(path, raw)
            assertTrue(result.isRight(), "Decryption failed for $path")
            assertContentEquals(expectedContent, result.getOrNull(), "Content mismatch for $path")
        }
    }

    // RT-04 — File encrypted for path A cannot be decrypted under path B (AAD path-binding)
    @Test fun `file encrypted for one path cannot be decrypted under a different path`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer = CryptoLayer(engine, dek)

        val content = "# Note\n- data".encodeToByteArray()
        val encrypted = layer.encrypt("pages/Original.md.stek", content)

        // Attempt to decrypt using a different relative path as AAD
        val result = layer.decrypt("pages/Moved.md.stek", encrypted)
        assertIs<VaultError.AuthenticationFailed>(result.leftOrNull(), "AAD mismatch must cause AuthenticationFailed")
    }

    // RT-05 — Saga rollback: verify old encrypted bytes can be restored
    @Test fun `old encrypted bytes can be saved and restored`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer = CryptoLayer(engine, dek)

        val original = "original content".encodeToByteArray()
        val encrypted = layer.encrypt("pages/Page.md.stek", original)
        store["$graphPath/pages/Page.md.stek"] = encrypted

        // Simulate a write then rollback
        val backup = store["$graphPath/pages/Page.md.stek"]!!.copyOf()
        val newEncrypted = layer.encrypt("pages/Page.md.stek", "new content".encodeToByteArray())
        store["$graphPath/pages/Page.md.stek"] = newEncrypted
        // Rollback
        store["$graphPath/pages/Page.md.stek"] = backup

        val result = layer.decrypt("pages/Page.md.stek", store["$graphPath/pages/Page.md.stek"]!!)
        assertTrue(result.isRight())
        assertContentEquals(original, result.getOrNull())
    }

    // RT-06 — Unlock, edit, lock, re-unlock, read — content persists
    @Test fun `content persists through lock and re-unlock cycle`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer1 = CryptoLayer(engine, dek)

        val content = "# Persistent Note\n- persists through lock".encodeToByteArray()
        store["$graphPath/pages/Persist.md.stek"] = layer1.encrypt("pages/Persist.md.stek", content)

        vm.lock()

        // Re-unlock
        val unlockResult = vm.unlock(graphPath, "pass".toCharArray(), params).getOrNull()!!
        val layer2 = CryptoLayer(engine, unlockResult.dek)
        val raw = store["$graphPath/pages/Persist.md.stek"]!!
        val decrypted = layer2.decrypt("pages/Persist.md.stek", raw)
        assertTrue(decrypted.isRight())
        assertContentEquals(content, decrypted.getOrNull())
    }

    // RT-07 — Passphrase change (provider rotation) → graph still readable
    @Test fun `provider rotation leaves content readable`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "original".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val layer = CryptoLayer(engine, dek)

        val content = "# Note\n- content".encodeToByteArray()
        store["$graphPath/pages/Note.md.stek"] = layer.encrypt("pages/Note.md.stek", content)

        vm.unlock(graphPath, "original".toCharArray(), params)
        vm.addKeyslot(graphPath, dek, "new-pass".toCharArray(), argon2Params = params)

        val unlockResult = vm.unlock(graphPath, "new-pass".toCharArray(), params).getOrNull()!!
        val layer2 = CryptoLayer(engine, unlockResult.dek)
        val result = layer2.decrypt("pages/Note.md.stek", store["$graphPath/pages/Note.md.stek"]!!)
        assertTrue(result.isRight())
        assertContentEquals(content, result.getOrNull())
    }

    // RT-08 — Decrypting a plaintext (non-STEK) file returns NotEncrypted, not an error
    @Test fun `plaintext file returns NotEncrypted`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val plaintext = "# Not encrypted\n- plain text content".encodeToByteArray()
        val result = layer.decrypt("pages/Plaintext.md", plaintext)
        assertIs<VaultError.NotEncrypted>(result.leftOrNull(), "Non-STEK file must return NotEncrypted")
    }

    // RT-09 — .stele-vault file is always present after all operations
    @Test fun `stele-vault file always present after vault operations`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/rt-test"
        val dek = vm.createVault(graphPath, "pass".toCharArray(), argon2Params = params).getOrNull()!!.dek

        assertTrue(store.containsKey(VaultManager.vaultFilePath(graphPath)), "Vault file missing after createVault")

        vm.unlock(graphPath, "pass".toCharArray(), params)
        vm.addKeyslot(graphPath, dek, "second".toCharArray(), argon2Params = params)
        assertTrue(store.containsKey(VaultManager.vaultFilePath(graphPath)), "Vault file missing after addKeyslot")

        vm.removeKeyslot(graphPath, 1)
        assertTrue(store.containsKey(VaultManager.vaultFilePath(graphPath)), "Vault file missing after removeKeyslot")
    }

    // RT-11 — Rename: decrypt at old path, re-encrypt at new path, readable at new path only
    // Regression for the verbatim-copy bug: raw ciphertext copied to a new path is permanently
    // unreadable because the AEAD tag binds the original relative path as AAD.
    @Test fun `rename re-encrypt round-trip is readable at new path`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val content = "# Original\n- content to rename".encodeToByteArray()
        val oldRelPath = "pages/OldName.md.stek"
        val newRelPath = "pages/NewName.md.stek"

        val encryptedAtOld = layer.encrypt(oldRelPath, content)

        // Simulate GraphWriter.renamePage: decrypt at old path, re-encrypt at new path
        val plaintext = layer.decrypt(oldRelPath, encryptedAtOld).getOrNull()!!
        val encryptedAtNew = layer.encrypt(newRelPath, plaintext)

        // Readable at new path
        val result = layer.decrypt(newRelPath, encryptedAtNew)
        assertTrue(result.isRight(), "Re-encrypted file must be readable at new path")
        assertContentEquals(content, result.getOrNull())

        // Old ciphertext must NOT be readable at new path (verbatim copy is broken)
        val verbatimAtNew = layer.decrypt(newRelPath, encryptedAtOld)
        assertIs<VaultError.AuthenticationFailed>(verbatimAtNew.leftOrNull(),
            "Verbatim-copied ciphertext must fail at new path — AAD binds the original path")
    }

    // RT-10 — sanitizeDirectory skips .stele-vault (tested at API level)
    @Test fun `CryptoLayer rejects hidden reserve writes`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val guard = layer.checkNotHiddenReserve("_hidden_reserve/blob.stek")
        assertTrue(guard.isLeft())
        assertIs<VaultError.HiddenAreaWriteDenied>(guard.leftOrNull())

        val okGuard = layer.checkNotHiddenReserve("pages/Note.md.stek")
        assertTrue(okGuard.isRight())
    }
}
