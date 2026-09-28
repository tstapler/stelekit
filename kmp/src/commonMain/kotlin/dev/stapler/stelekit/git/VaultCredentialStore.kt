// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.vault.CryptoEngine
import dev.stapler.stelekit.vault.CryptoLayer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Vault-integrated [CredentialAccess] implementation.
 *
 * Credentials are stored in `<graphPath>/.stele-credentials` encrypted with the vault DEK
 * using the same STEK format as note files ([CryptoLayer]).
 *
 * Lifecycle — must be called from the vault event handlers in App.kt:
 * - [onVaultUnlocked]: builds a [CryptoLayer] from the live DEK, decrypts credential file, populates cache.
 * - [onVaultLocked]: zeroes the owned [CryptoLayer] DEK copy, clears cache.
 *
 * Thread safety: [cache] and [cryptoLayer] are @Volatile references. [store] and [delete]
 * write synchronously — credential mutations are infrequent (setup/config screens only).
 *
 * Note on the DEK parameter: [CryptoLayer] immediately copies the DEK array internally,
 * so lock() zeroing the live session DEK does not race with in-flight encrypt/decrypt calls.
 */
class VaultCredentialStore(
    private val graphPath: String,
    private val cryptoEngine: CryptoEngine,
    private val fileSystem: FileSystem,
) : CredentialAccess {

    private val logger = Logger("VaultCredentialStore")
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cryptoLayer: CryptoLayer? = null
    @Volatile private var cache: MutableMap<String, String>? = null

    override fun isAvailable(): Boolean = cryptoLayer != null

    /**
     * Called when the vault is unlocked.
     * [dek] is the live session DEK — [CryptoLayer] makes its own copy; do NOT store [dek] elsewhere.
     */
    fun onVaultUnlocked(dek: ByteArray) {
        val layer = CryptoLayer(cryptoEngine, dek)
        cryptoLayer = layer
        cache = loadCredentials(layer).toMutableMap()
    }

    /** Called when the vault is locked. Zeroes the owned CryptoLayer DEK copy and clears cache. */
    fun onVaultLocked() {
        val layer = cryptoLayer
        cryptoLayer = null
        cache = null
        layer?.close()
    }

    override fun retrieve(key: String): String? = cache?.get(key)

    override fun store(key: String, value: String) {
        val layer = cryptoLayer ?: return
        val updated = (cache ?: emptyMap()).toMutableMap().also { it[key] = value }
        cache = updated
        saveCredentials(layer, updated)
    }

    override fun delete(key: String) {
        val layer = cryptoLayer ?: return
        val current = cache ?: return
        if (!current.containsKey(key)) return
        val updated = current.toMutableMap().also { it.remove(key) }
        cache = updated
        saveCredentials(layer, updated)
    }

    /**
     * Migrates credentials from a [CredentialAccess] (PBKDF2-based) into this vault store.
     * Called once when paranoid mode is first enabled. Moves each [keys] entry from [source]
     * into this store, then deletes from [source].
     */
    fun migrateFrom(source: CredentialAccess, keys: List<String>) {
        if (!isAvailable()) return
        for (key in keys) {
            val value = source.retrieve(key) ?: continue
            store(key, value)
            source.delete(key)
        }
    }

    private fun loadCredentials(layer: CryptoLayer): Map<String, String> {
        val filePath = credentialFilePath(graphPath)
        val raw = try { fileSystem.readFileBytes(filePath) } catch (_: Exception) { null }
            ?: return emptyMap()
        return when (val result = layer.decrypt(RELATIVE_PATH, raw)) {
            is Either.Left -> emptyMap()
            is Either.Right -> try {
                json.decodeFromString<Map<String, String>>(result.value.decodeToString())
            } catch (_: Exception) { emptyMap() }
        }
    }

    private fun saveCredentials(layer: CryptoLayer, credentials: Map<String, String>) {
        val filePath = credentialFilePath(graphPath)
        val plaintext = json.encodeToString(credentials).encodeToByteArray()
        val encrypted = layer.encrypt(RELATIVE_PATH, plaintext)
        try {
            fileSystem.writeFileBytes(filePath, encrypted)
        } catch (e: Exception) {
            logger.warn("Failed to persist credentials to $filePath: ${e.message} — credentials valid for this session only")
        }
    }

    companion object {
        private const val RELATIVE_PATH = ".stele-credentials"
        fun credentialFilePath(graphPath: String): String = "$graphPath/.stele-credentials"
    }
}
