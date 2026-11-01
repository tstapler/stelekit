// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.security.keystore.KeyGenParameterSpec
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Robolectric has no `AndroidKeyStore`, but `MainActivity`'s first composition builds the
 * `CredentialStore` (EncryptedSharedPreferences + a Keystore master key), which fails loudly on
 * purpose when the keystore is missing. This software stand-in lets the shipped activity compose in a
 * JVM unit test. Test-only: it keeps AES keys in memory and offers no protection.
 */
object FakeAndroidKeyStore {
    private const val PROVIDER_NAME = "AndroidKeyStore"
    private const val DEFAULT_KEY_BITS = 256
    private const val BITS_PER_BYTE = 8

    internal val keys: MutableMap<String, SecretKey> = ConcurrentHashMap()

    fun install() {
        if (Security.getProvider(PROVIDER_NAME) == null) Security.addProvider(FakeProvider())
    }

    fun uninstall() {
        Security.removeProvider(PROVIDER_NAME)
        keys.clear()
    }

    class FakeProvider : Provider(PROVIDER_NAME, 1.0, "Robolectric in-memory AndroidKeyStore") {
        init {
            put("KeyStore.$PROVIDER_NAME", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
        }
    }

    class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
        private var alias: String? = null
        private var bits = DEFAULT_KEY_BITS

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(keysize: Int, random: SecureRandom?) { bits = keysize }
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            val spec = params as? KeyGenParameterSpec ?: return
            alias = spec.keystoreAlias
            bits = spec.keySize.takeIf { it > 0 } ?: DEFAULT_KEY_BITS
        }

        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(bits / BITS_PER_BYTE).also { SecureRandom().nextBytes(it) }
            val key = SecretKeySpec(bytes, "AES")
            alias?.let { keys[it] = key }
            return key
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
        override fun engineAliases(): java.util.Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
        override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            (key as? SecretKey)?.let { keys[alias] = it }
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = Unit
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date() else null
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineEntryInstanceOf(alias: String, entryClass: Class<out KeyStore.Entry>): Boolean =
            keys.containsKey(alias) && entryClass == KeyStore.SecretKeyEntry::class.java
    }
}
