package dev.stapler.stelekit.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.digests.SHA256Digest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * JVM implementation of [CryptoEngine] using:
 *  - javax.crypto SunJCE provider for ChaCha20-Poly1305 AEAD
 *  - BouncyCastle for HKDF-SHA256 and Argon2id
 *  - java.security.SecureRandom for nonce generation
 *
 * A fresh [Cipher] instance is created per encrypt/decrypt call — never shared.
 */
class JvmCryptoEngine : CryptoEngine {
    private val rng = SecureRandom()

    override fun encryptAEAD(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = SecretKeySpec(key, "ChaCha20")
        val paramSpec = IvParameterSpec(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, paramSpec)
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun decryptAEAD(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        val keySpec = SecretKeySpec(key, "ChaCha20")
        val paramSpec = IvParameterSpec(nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, paramSpec)
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.BadPaddingException) {
            throw VaultAuthException("Authentication tag verification failed: ${e.message}")
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultAuthException("Authentication tag verification failed: ${e.message}")
        }
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val params = HKDFParameters(ikm, salt, info)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(params)
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memory: Int,
        iterations: Int,
        parallelism: Int,
        outputLength: Int,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(memory)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val output = ByteArray(outputLength)
        generator.generateBytes(password, output, 0, outputLength)
        return output
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(a, b)

    override fun secureRandom(length: Int): ByteArray {
        val bytes = ByteArray(length)
        rng.nextBytes(bytes)
        return bytes
    }
}

