package dev.stapler.stelekit.vault

/**
 * Binary layout of a .stele-vault file (total: 2605 bytes).
 *
 * Offset  Size     Field
 *  0       4        Magic: 0x53 0x4B 0x56 0x54 ("SKVT")
 *  4       1        Format version: 0x01
 *  5       8        Random padding (prevents zero-length fingerprinting)
 * 13       2048     Keyslot array: 8 × 256 bytes each
 * 2061     512      Reserved random area (future use)
 * 2573     32       Header MAC: HMAC-SHA256(header_mac_key, bytes[0..2572])
 *                   header_mac_key = HKDF-SHA256(DEK, salt="vault-header-mac", info="v1")
 *
 * Total: 4 + 1 + 8 + 2048 + 512 + 32 = 2605
 */
data class VaultHeader(
    val version: Byte = 0x01,
    val randomPadding: ByteArray,            // 8 bytes
    val keyslots: List<Keyslot>,             // exactly 8 elements
    val reserved: ByteArray,                 // 512 bytes
    val headerMac: ByteArray,               // 32 bytes
) {
    companion object {
        val MAGIC = byteArrayOf(0x53, 0x4B, 0x56, 0x54)  // "SKVT"
        const val SUPPORTED_VERSION: Byte = 0x01
        const val TOTAL_SIZE = 2605
        const val KEYSLOT_COUNT = 8
        const val KEYSLOT_SIZE = 256
        const val RESERVED_SIZE = 512
        const val MAC_SIZE = 32
        const val PADDING_SIZE = 8
        const val MAC_AUTHENTICATED_SIZE = 2573  // bytes[0..2572]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultHeader) return false
        if (version != other.version) return false
        if (!randomPadding.contentEquals(other.randomPadding)) return false
        if (keyslots != other.keyslots) return false
        if (!reserved.contentEquals(other.reserved)) return false
        if (!headerMac.contentEquals(other.headerMac)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + randomPadding.contentHashCode()
        result = 31 * result + keyslots.hashCode()
        result = 31 * result + reserved.contentHashCode()
        result = 31 * result + headerMac.contentHashCode()
        return result
    }
}

/**
 * Per-keyslot layout (256 bytes each):
 *
 * Offset  Size    Field
 *  0       16      Argon2id salt
 *  16      4       Argon2id memory (KiB, LE uint32)
 *  20      2       Argon2id iterations (LE uint16)
 *  22      2       Argon2id parallelism (LE uint16)
 *  24      49      Encrypted DEK blob: ChaCha20-Poly1305(keyslot_key, slot_nonce, DEK||namespace_tag)
 *                   DEK = 32 bytes, namespace_tag = 1 byte, tag = 16 bytes → 33 + 16 = 49 bytes
 *  73      12      slot_nonce (nonce for the DEK-wrapping AEAD)
 *  85      1       Provider type hint (0x00=passphrase, 0x01=keyfile, 0x02=os_keychain, 0xFF=unused/random)
 *  86      170     Reserved / random filler
 *
 * Unused slots fill ALL 256 bytes with random — indistinguishable from active slots
 * (the AEAD MAC is the only oracle for "is this slot active?").
 */
data class Keyslot(
    val salt: ByteArray,              // 16 bytes
    val argon2Params: Argon2Params,
    val encryptedDekBlob: ByteArray,  // 49 bytes (33 plaintext + 16 tag)
    val slotNonce: ByteArray,         // 12 bytes
    val providerType: Byte,
    val reserved: ByteArray,          // 171 bytes
) {
    companion object {
        const val SALT_SIZE = 16
        const val ENCRYPTED_BLOB_SIZE = 49  // 32 DEK + 1 namespace_tag + 16 AEAD tag
        const val NONCE_SIZE = 12
        const val RESERVED_SIZE = 170
        const val TOTAL_SIZE = 256

        const val PROVIDER_PASSPHRASE: Byte = 0x00
        const val PROVIDER_KEYFILE: Byte = 0x01
        const val PROVIDER_OS_KEYCHAIN: Byte = 0x02
        const val PROVIDER_UNUSED: Byte = 0xFF.toByte()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Keyslot) return false
        if (!salt.contentEquals(other.salt)) return false
        if (argon2Params != other.argon2Params) return false
        if (!encryptedDekBlob.contentEquals(other.encryptedDekBlob)) return false
        if (!slotNonce.contentEquals(other.slotNonce)) return false
        if (providerType != other.providerType) return false
        if (!reserved.contentEquals(other.reserved)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + argon2Params.hashCode()
        result = 31 * result + encryptedDekBlob.contentHashCode()
        result = 31 * result + slotNonce.contentHashCode()
        result = 31 * result + providerType.toInt()
        result = 31 * result + reserved.contentHashCode()
        return result
    }
}

enum class VaultNamespace(val tag: Byte) {
    OUTER(0x00),
    HIDDEN(0x01);

    companion object {
        fun fromTag(tag: Byte) = entries.first { it.tag == tag }
    }
}
