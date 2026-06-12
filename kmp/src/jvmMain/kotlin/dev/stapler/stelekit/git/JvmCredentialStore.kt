// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.logging.Logger
import java.io.File
import java.nio.file.Files as NioFiles
import java.nio.file.attribute.PosixFilePermission
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
 * from a machine-unique identifier (username + OS name) with a per-machine random salt persisted
 * in `credentials.salt` on first use.
 *
 * Security note: This uses a deterministic key derivation from semi-public machine info.
 * It protects against casual snooping but not against a determined local attacker.
 * Replace with OS keychain integration (SecretService on Linux, DPAPI on Windows,
 * Keychain on macOS) for higher assurance in a future release.
 */
actual class CredentialStore actual constructor() : dev.stapler.stelekit.git.CredentialAccess {

    companion object {
        private const val SALT_BYTES = 16
    }

    private val logger = Logger("CredentialStore")

    private val configDir: File by lazy {
        File(System.getProperty("user.home"), ".config/stelekit").also { it.mkdirs() }
    }

    private val storageFile: File by lazy {
        File(configDir, "credentials.enc")
    }

    private val secretKey: SecretKeySpec by lazy {
        val entropy = System.getProperty("user.name", "stelekit") +
            System.getProperty("os.name", "unknown")
        val salt = loadOrCreateSalt()
        val spec: KeySpec = PBEKeySpec(entropy.toCharArray(), salt, 65536, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        SecretKeySpec(keyBytes, "AES")
    }

    private fun loadOrCreateSalt(): ByteArray {
        val saltFile = File(configDir, "credentials.salt")
        if (saltFile.exists()) {
            return try {
                Base64.getDecoder().decode(saltFile.readText().trim())
            } catch (_: Exception) {
                logger.warn("credentials.salt decode failed, regenerating — existing credentials will need to be re-entered")
                generateAndSaveSalt(saltFile)
            }
        }
        return generateAndSaveSalt(saltFile)
    }

    private fun generateAndSaveSalt(saltFile: File): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        try {
            saltFile.writeText(Base64.getEncoder().encodeToString(salt))
            try {
                NioFiles.setPosixFilePermissions(
                    saltFile.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem (Windows) — default permissions apply
            }
        } catch (e: Exception) {
            // Salt write failed (e.g. disk full) — continue with in-memory salt for this session.
            // Credentials stored this session will be unrecoverable next run (missing salt → re-entry required).
            logger.warn("Failed to persist credentials salt to ${saltFile.path}: ${e.message} — credentials will not survive this session")
        }
        return salt
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
        try {
            storageFile.outputStream().use { props.store(it, "SteleKit Credentials") }
        } catch (e: Exception) {
            logger.warn("Failed to save credentials to ${storageFile.path}: ${e.message}")
        }
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

    actual override fun store(key: String, value: String) {
        val props = loadProperties()
        props.setProperty(key, encrypt(value))
        saveProperties(props)
    }

    actual override fun retrieve(key: String): String? {
        val props = loadProperties()
        val encoded = props.getProperty(key)
        val decrypted = if (encoded != null) decrypt(encoded) else null
        // Fall back to STELEKIT_GIT_TOKEN env var for headless/CI usage when no stored credential is found
        if (decrypted == null) {
            val envToken = System.getenv("STELEKIT_GIT_TOKEN")
            if (envToken != null) return envToken
        }
        return decrypted
    }

    actual override fun delete(key: String) {
        val props = loadProperties()
        props.remove(key)
        saveProperties(props)
    }
}
