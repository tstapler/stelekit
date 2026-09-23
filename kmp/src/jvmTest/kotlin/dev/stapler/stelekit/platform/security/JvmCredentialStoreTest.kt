// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmCredentialStoreTest {

    /**
     * Reflectively mutates the JVM's cached process environment so [System.getenv] observes the
     * override for the remainder of the test. Requires `--add-opens=java.base/java.lang=ALL-UNNAMED`
     * (already set for the `jvmTest` task in `kmp/build.gradle.kts`). Test-only — production code
     * never mutates its own environment.
     *
     * On modern JDKs `ProcessEnvironment.theEnvironment` is a `HashMap<Variable, Value>` (POSIX-only
     * wrapper types, not plain `String`), and `System.getenv(String)` looks a key up by converting it
     * to a `Variable` first — inserting raw `String` keys into that map (as older reflection recipes
     * do) silently no-ops because the lookup never matches. This goes through `Variable.valueOf` /
     * `Value.valueOf` so the mutation is actually visible to [System.getenv].
     */
    @Suppress("UNCHECKED_CAST")
    private fun withEnv(key: String, value: String?, block: () -> Unit) {
        val processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment")
        val variableClass = Class.forName("java.lang.ProcessEnvironment\$Variable")
        val valueClass = Class.forName("java.lang.ProcessEnvironment\$Value")
        val variableValueOf = variableClass.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }
        val valueValueOf = valueClass.getDeclaredMethod("valueOf", String::class.java).apply { isAccessible = true }

        val envField = processEnvironmentClass.getDeclaredField("theEnvironment").apply { isAccessible = true }
        val theEnvironment = envField.get(null) as MutableMap<Any, Any>

        val variableKey = variableValueOf.invoke(null, key)
        val previous = theEnvironment[variableKey]
        try {
            if (value == null) theEnvironment.remove(variableKey) else theEnvironment[variableKey] = valueValueOf.invoke(null, value)
            block()
        } finally {
            if (previous == null) theEnvironment.remove(variableKey) else theEnvironment[variableKey] = previous
        }
    }

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

    // B15: STELEKIT_GIT_TOKEN must only ever be returned for the git-sync token key, never as a
    // generic "miss" fallback — otherwise a git PAT would leak to LLM providers via
    // LlmCredentialStore (which shares this backing store).
    @Test
    fun `retrieve falls back to STELEKIT_GIT_TOKEN only for git_https_token_ prefixed keys`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-git-token").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            withEnv("STELEKIT_GIT_TOKEN", "ghp_test_token_value") {
                val store = CredentialStore()
                assertEquals(
                    "ghp_test_token_value",
                    store.retrieve("git_https_token_graph-123"),
                    "git-sync token key should fall back to STELEKIT_GIT_TOKEN when unset",
                )
            }
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `retrieve does not leak STELEKIT_GIT_TOKEN for unrelated keys like LLM api keys`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-llm-key").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            withEnv("STELEKIT_GIT_TOKEN", "ghp_test_token_value") {
                val store = CredentialStore()
                assertNull(
                    store.retrieve("llm.anthropic.api_key"),
                    "LLM credential key must NOT fall back to the git-sync token env var",
                )
            }
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    // MA13: storeBlocking() must observe a failed disk write and return false, not silently
    // succeed via the default interface delegation to store() (which swallows write failures).
    @Test
    fun `storeBlocking returns false when the underlying disk write fails`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-unwritable").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            // Pre-create credentials.enc as a directory so opening it for output fails with
            // "Is a directory" — a reliable, cross-platform-ish way to force saveProperties() to
            // fail without relying on filesystem permission semantics.
            val configDir = tempDir.resolve(".config/stelekit").apply { mkdirs() }
            configDir.resolve("credentials.enc").mkdirs()

            val store = CredentialStore()
            val result = store.storeBlocking("myKey", "mySecret")

            assertFalse(result, "storeBlocking() must return false when the disk write fails")
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `storeBlocking returns true and persists value when the write succeeds`() {
        val tempDir = Files.createTempDirectory("stelekit-cred-blocking-ok").toFile()
        val savedHome = System.getProperty("user.home")
        System.setProperty("user.home", tempDir.absolutePath)
        try {
            val store = CredentialStore()
            val result = store.storeBlocking("myKey", "mySecret")

            assertTrue(result, "storeBlocking() must return true when the disk write succeeds")
            assertEquals("mySecret", store.retrieve("myKey"))
        } finally {
            System.setProperty("user.home", savedHome)
            tempDir.deleteRecursively()
        }
    }
}
