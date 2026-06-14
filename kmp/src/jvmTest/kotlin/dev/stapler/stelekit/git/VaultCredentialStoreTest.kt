// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.vault.JvmCryptoEngine
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class VaultCredentialStoreTest {

    private val engine = JvmCryptoEngine()

    /** Minimal in-memory FileSystem stub that only supports binary I/O. */
    private class InMemoryFileSystem : FileSystem {
        private val files = mutableMapOf<String, ByteArray>()

        override fun readFileBytes(path: String): ByteArray? = files[path]
        override fun writeFileBytes(path: String, data: ByteArray): Boolean {
            files[path] = data.copyOf()
            return true
        }

        // Required stubs — not exercised by VaultCredentialStore
        override fun getDefaultGraphPath(): String = "/tmp/test-graph"
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = files.containsKey(path)
        override fun directoryExists(path: String): Boolean = false
        override fun createDirectory(path: String): Boolean = false
        override fun deleteFile(path: String): Boolean { files.remove(path); return true }
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private fun fakeDek(): ByteArray {
        val dek = ByteArray(32)
        SecureRandom().nextBytes(dek)
        return dek
    }

    private fun makeStore(
        graphPath: String = "/tmp/test-graph",
        fs: FileSystem = InMemoryFileSystem(),
    ): VaultCredentialStore = VaultCredentialStore(graphPath, engine, fs)

    // VCS-01 — store and retrieve when vault unlocked
    @Test
    fun `store and retrieve when vault unlocked`() {
        val store = makeStore()
        val dek = fakeDek()
        store.onVaultUnlocked(dek)

        store.store("git-token", "supersecret")
        val result = store.retrieve("git-token")

        assertEquals("supersecret", result)
    }

    // VCS-02 — retrieve returns null when vault locked
    @Test
    fun `retrieve returns null when vault locked`() {
        val fs = InMemoryFileSystem()
        val store = makeStore(fs = fs)
        val dek = fakeDek()

        store.onVaultUnlocked(dek)
        store.store("git-token", "supersecret")
        store.onVaultLocked()

        assertNull(store.retrieve("git-token"))
    }

    // VCS-03 — onVaultLocked clears cache and makes store unavailable
    @Test
    fun `onVaultLocked clears cache and closes cryptoLayer`() {
        val store = makeStore()
        val dek = fakeDek()

        store.onVaultUnlocked(dek)
        store.store("key", "value")
        store.onVaultLocked()

        assertFalse(store.isAvailable())
        assertNull(store.retrieve("key"))
    }

    // VCS-04 — migrateFrom copies credentials from source to vault and removes them from source
    @Test
    fun `migrateFrom copies credentials from source to vault`() {
        val tempDir = Files.createTempDirectory("stelekit-vault-migrate").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            val source = CredentialStore()
            source.store("ssh-passphrase", "hunter2")

            val vaultStore = makeStore()
            val dek = fakeDek()
            vaultStore.onVaultUnlocked(dek)

            vaultStore.migrateFrom(source, listOf("ssh-passphrase"))

            // Vault store should now have the credential
            assertEquals("hunter2", vaultStore.retrieve("ssh-passphrase"))

            // Source should no longer have it
            assertNull(source.retrieve("ssh-passphrase"))
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    // VCS-05 — credentials survive unlock → lock → unlock cycle (round-trip via disk)
    @Test
    fun `credentials survive unlock lock unlock cycle`() {
        val fs = InMemoryFileSystem()
        val graphPath = "/tmp/test-graph"
        val store = makeStore(graphPath = graphPath, fs = fs)
        val dek = fakeDek()

        // First unlock: store a credential (written to fs)
        store.onVaultUnlocked(dek)
        store.store("token", "mytoken")
        store.onVaultLocked()

        // Second unlock with the same DEK: credential must be recovered from disk
        store.onVaultUnlocked(dek)
        val result = store.retrieve("token")

        assertNotNull(result)
        assertEquals("mytoken", result)
    }
}
