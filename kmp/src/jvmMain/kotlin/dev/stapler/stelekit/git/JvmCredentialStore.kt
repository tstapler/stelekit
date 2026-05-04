// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM (Desktop) implementation of [CredentialStore] using AES-256-GCM encryption.
 *
 * Credentials are stored in `~/.config/stelekit/credentials.enc` as a Java Properties file
 * where each value is individually encrypted. The encryption key is derived via PBKDF2-HMAC-SHA256
 * from a machine-unique identifier (username + OS name) with a fixed salt.
 *
 * Security note: This uses a deterministic key derivation from semi-public machine info.
 * It protects against casual snooping but not against a determined local attacker.
 * Replace with OS keychain integration (SecretService on Linux, DPAPI on Windows,
 * Keychain on macOS) for higher assurance in a future release.
 */
actual class CredentialStore actual constructor() {

    private val storageFile: File by lazy {
        val configDir = File(System.getProperty("user.home"), ".config/stelekit")
        configDir.mkdirs()
        File(configDir, "credentials.enc")
    }

    private val secretKey: SecretKeySpec by lazy {
        val entropy = System.getProperty("user.name", "stelekit") +
            System.getProperty("os.name", "unknown")
        val salt = "stelekit-credential-salt-v1".toByteArray()
        val spec: KeySpec = PBEKeySpec(entropy.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        SecretKeySpec(keyBytes, "AES")
    }

    private fun loadProperties(): Properties {
        val props = Properties()
        if (storageFile.exists()) {
            try {
                storageFile.inputStream().use { props.load(it) }
            } catch (_: Exception) {
                // If we can't read/decrypt, start fresh
            }
        }
        return props
    }

    private fun saveProperties(props: Properties) {
        storageFile.outputStream().use { props.store(it, "SteleKit Credentials") }
    }

    private fun encrypt(value: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = iv + cipherText
        return Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.getDecoder().decode(encoded)
            val iv = combined.copyOfRange(0, 12)
            val cipherText = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    actual fun store(key: String, value: String) {
        val props = loadProperties()
        props.setProperty(key, encrypt(value))
        saveProperties(props)
    }

    actual fun retrieve(key: String): String? {
        val props = loadProperties()
        val encoded = props.getProperty(key) ?: return null
        return decrypt(encoded)
    }

    actual fun delete(key: String) {
        val props = loadProperties()
        props.remove(key)
        saveProperties(props)
    }
}
