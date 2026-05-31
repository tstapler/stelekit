// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JvmCredentialStoreTest {

    @Test
    fun `retrieve returns null when key does not exist`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-missing").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            val store = CredentialStore()
            assertNull(store.retrieve("nonexistent-key"))
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `store and retrieve round-trips correctly`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-test").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            val store = CredentialStore()
            store.store("myKey", "mySecret")
            val retrieved = store.retrieve("myKey")
            assertEquals("mySecret", retrieved)
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `two instances share the same salt and can cross-decrypt`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-cross").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            val storeA = CredentialStore()
            storeA.store("sharedKey", "sharedValue")

            val storeB = CredentialStore()
            val retrieved = storeB.retrieve("sharedKey")
            assertEquals("sharedValue", retrieved, "Second instance pointing at same configDir should decrypt value stored by first")
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt salt file causes salt regeneration and returns null for old credentials`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-corrupt").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            // Store a credential with the original salt
            val storeA = CredentialStore()
            storeA.store("oldKey", "oldValue")

            // Corrupt the salt file
            val saltFile = tempDir.resolve(".config/stelekit/credentials.salt")
            saltFile.writeText("!!!not-valid-base64-garbage!!!")

            // Create a new instance — it should regenerate the salt
            val storeB = CredentialStore()

            // Old credential should be unreadable (key is still present but decryption fails)
            val oldResult = storeB.retrieve("oldKey")
            assertNull(oldResult, "Old credential encrypted with original salt should not decrypt after salt corruption")

            // New store/retrieve with the regenerated salt should work fine
            storeB.store("newKey", "newValue")
            val newResult = storeB.retrieve("newKey")
            assertNotNull(newResult)
            assertEquals("newValue", newResult)
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }
}
