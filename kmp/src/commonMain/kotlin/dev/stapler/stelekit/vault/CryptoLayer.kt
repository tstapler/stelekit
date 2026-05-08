package dev.stapler.stelekit.vault

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Per-file encrypt/decrypt using the STEK binary format.
 *
 * STEK file layout:
 *   [0]    4 bytes — magic "STEK" (0x53 0x54 0x45 0x4B)
 *   [4]    1 byte  — version 0x01
 *   [5]   12 bytes — random nonce (SecureRandom, per-write)
 *   [17]   N bytes — ciphertext + 16-byte Poly1305 tag (ChaCha20-Poly1305)
 *                    AAD = relative file path UTF-8 bytes
 *                    subkey = HKDF-SHA256(DEK, salt=filePathBytes, info="stelekit-file-v1")
 *
 * The file path used as AAD and HKDF salt is always graph-root-relative (e.g. "pages/Note.md.stek").
 * This binds the ciphertext to its location — moving a file breaks decryption (relocation attack
 * prevention per plan OPEN-1 decision).
 *
 * [cryptoEngine] — platform crypto implementation
 * [dek] — 32-byte Data Encryption Key held in memory while the vault is unlocked
 */
class CryptoLayer(
    private val cryptoEngine: CryptoEngine,
    private val dek: ByteArray,
) {
    companion object {
        val STEK_MAGIC = byteArrayOf(0x53, 0x54, 0x45, 0x4B)
        const val STEK_VERSION: Byte = 0x01
        const val HEADER_SIZE = 17  // 4 magic + 1 version + 12 nonce
        private const val NONCE_SIZE = 12
        private val HKDF_INFO = "stelekit-file-v1".encodeToByteArray()
    }

    /**
     * Encrypt [plaintext] and return STEK-format bytes.
     * [relativeFilePath] is used as both HKDF salt and AEAD additional data.
     */
    fun encrypt(relativeFilePath: String, plaintext: ByteArray): ByteArray {
        val pathBytes = relativeFilePath.encodeToByteArray()
        val subkey = cryptoEngine.hkdfSha256(dek, pathBytes, HKDF_INFO, 32)
        try {
            val nonce = cryptoEngine.secureRandom(NONCE_SIZE)
            val ciphertext = cryptoEngine.encryptAEAD(subkey, nonce, plaintext, pathBytes)

            val result = ByteArray(HEADER_SIZE + ciphertext.size)
            STEK_MAGIC.copyInto(result, 0)
            result[4] = STEK_VERSION
            nonce.copyInto(result, 5)
            ciphertext.copyInto(result, HEADER_SIZE)
            return result
        } finally {
            cryptoEngine.clearBytes(subkey)
        }
    }

    /**
     * Decrypt STEK-format [raw] bytes.
     * Returns [VaultError.NotEncrypted] if magic bytes do not match (plaintext file).
     * Returns [VaultError.CorruptedFile] if the file is too short.
     * Returns [VaultError.AuthenticationFailed] if AEAD tag fails.
     */
    fun decrypt(relativeFilePath: String, raw: ByteArray): Either<VaultError, ByteArray> {
        if (raw.size < 4) return VaultError.CorruptedFile("File too short (${raw.size} bytes)").left()

        val magic = raw.sliceArray(0 until 4)
        if (!magic.contentEquals(STEK_MAGIC)) {
            return VaultError.NotEncrypted().left()
        }

        if (raw.size < HEADER_SIZE) {
            return VaultError.CorruptedFile("STEK header truncated (${raw.size} < $HEADER_SIZE bytes)").left()
        }
        if (raw.size == HEADER_SIZE) {
            return VaultError.CorruptedFile("STEK ciphertext is empty — no Poly1305 tag").left()
        }

        val nonce = raw.sliceArray(5 until 17)
        val ciphertext = raw.sliceArray(HEADER_SIZE until raw.size)
        val pathBytes = relativeFilePath.encodeToByteArray()
        val subkey = cryptoEngine.hkdfSha256(dek, pathBytes, HKDF_INFO, 32)
        try {
            return try {
                cryptoEngine.decryptAEAD(subkey, nonce, ciphertext, pathBytes).right()
            } catch (_: VaultAuthException) {
                VaultError.AuthenticationFailed().left()
            }
        } finally {
            cryptoEngine.clearBytes(subkey)
        }
    }

    /** Guard against outer-graph writes into the hidden volume reserve directory. */
    fun checkNotHiddenReserve(relativeFilePath: String): Either<VaultError, Unit> {
        return if (relativeFilePath.startsWith("_hidden_reserve/")) {
            VaultError.HiddenAreaWriteDenied().left()
        } else {
            Unit.right()
        }
    }
}
