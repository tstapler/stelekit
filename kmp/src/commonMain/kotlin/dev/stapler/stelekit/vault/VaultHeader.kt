package dev.stapler.stelekit.vault

/**
 * Binary layout of a .stele-vault file (total: 2605 bytes).
 *
 * Offset  Size     Field
 *  0       4        Magic: 0x53 0x4B 0x56 0x54 ("SKVT")
 *  4       1        Format version: 0x02
 *  5       8        Random padding (prevents zero-length fingerprinting)
 * 13       2048     Keyslot array: 8 × 256 bytes each
 *                     slots 0–3: OUTER namespace
 *                     slots 4–7: HIDDEN namespace
 * 2061     480      Reserved: random bytes. NOT authenticated by either MAC (intentional —
 *                   authentication requires a DEK to produce a MAC, and this region must be
 *                   freely mutable before either namespace is initialized). Any future semantic
 *                   use of bytes in this region MUST include a format version bump and MAC
 *                   coverage update.
 *                   (was 512; 32 bytes repurposed for hiddenHeaderMac)
 * 2541     32       HIDDEN namespace MAC: HMAC-SHA256(hidden_mac_key, prefix13 ++ slots4-7)
 *                     authenticates bytes[0..12] ++ bytes[1037..2060]
 * 2573     32       OUTER namespace MAC: HMAC-SHA256(outer_mac_key, bytes[0..1036])
 *                     authenticates bytes[0..1036] (magic+version+padding+slots0-3)
 *                   mac_key = HKDF-SHA256(DEK, salt="vault-header-mac", info="v1")
 *
 * Key property: neither MAC covers the other namespace's keyslot range, so HIDDEN slots
 * can be updated without invalidating the OUTER MAC (and vice versa).
 *
 * Total: 4 + 1 + 8 + 2048 + 480 + 32 + 32 = 2605
 */
data class VaultHeader(
    val version: Byte = 0x02,
    val randomPadding: ByteArray,            // 8 bytes
    val keyslots: List<Keyslot>,             // exactly 8 elements
    val reserved: ByteArray,                 // 480 bytes
    val hiddenHeaderMac: ByteArray,         // 32 bytes (HIDDEN namespace MAC, at [2541])
    val headerMac: ByteArray,               // 32 bytes (OUTER namespace MAC, at [2573])
) {
    companion object {
        val MAGIC = byteArrayOf(0x53, 0x4B, 0x56, 0x54)  // "SKVT"
        const val SUPPORTED_VERSION: Byte = 0x02
        const val TOTAL_SIZE = 2605
        const val KEYSLOT_COUNT = 8
        const val KEYSLOT_SIZE = 256
        const val RESERVED_SIZE = 480           // was 512; 32 bytes repurposed for hiddenHeaderMac
        const val MAC_SIZE = 32
        const val PADDING_SIZE = 8
        const val OUTER_MAC_AUTH_SIZE = 1037    // bytes[0..1036]: magic+version+padding+slots0-3
        const val HEADER_PREFIX_SIZE = 13       // bytes[0..12]: magic+version+padding
        const val HIDDEN_SLOT_START = 1037      // first byte of hidden keyslots (slots 4-7)
        const val HIDDEN_SLOT_END = 2061        // one-past-end of hidden keyslots
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultHeader) return false
        if (version != other.version) return false
        if (!randomPadding.contentEquals(other.randomPadding)) return false
        if (keyslots != other.keyslots) return false
        if (!reserved.contentEquals(other.reserved)) return false
        if (!hiddenHeaderMac.contentEquals(other.hiddenHeaderMac)) return false
        if (!headerMac.contentEquals(other.headerMac)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + randomPadding.contentHashCode()
        result = 31 * result + keyslots.hashCode()
        result = 31 * result + reserved.contentHashCode()
        result = 31 * result + hiddenHeaderMac.contentHashCode()
        result = 31 * result + headerMac.contentHashCode()
        return result
    }
}

/**
 * Per-keyslot layout (256 bytes each):
 *
 * Offset  Size    Field
 *  0       32      Argon2id salt (RFC 9106 recommended 32 bytes for high-security applications)
 *  32      4       Argon2id memory (KiB, LE uint32)
 *  36      2       Argon2id iterations (LE uint16)
 *  38      2       Argon2id parallelism (LE uint16)
 *  40      50      Encrypted DEK blob: ChaCha20-Poly1305(keyslot_key, slot_nonce, DEK||namespace_tag||provider_type)
 *                   DEK = 32 bytes, namespace_tag = 1 byte, provider_type = 1 byte, AEAD_tag = 16 bytes → 50 bytes
 *  90      12      slot_nonce (nonce for the DEK-wrapping AEAD)
 *  102     154     Reserved: reserved[0..3] is a 4-byte DEK-derived slot-activity marker
 *                   (HKDF-SHA256(dek, "slot-marker-v1", [slotIndex, namespace.tag]), length=4;
 *                   false-positive rate 1/2^32); remaining bytes are random. Active and decoy
 *                   slots are indistinguishable on disk — only Argon2id + AEAD decryption can
 *                   identify a valid slot.
 *
 * All 8 slots are always tried on unlock in constant order (no plaintext hint as to which are active),
 * preserving deniability for hidden-volume passphrases.
 */
data class Keyslot(
    val salt: ByteArray,              // 32 bytes
    val argon2Params: Argon2Params,
    val encryptedDekBlob: ByteArray,  // 50 bytes (34 plaintext + 16 AEAD tag)
    val slotNonce: ByteArray,         // 12 bytes
    val reserved: ByteArray,          // 154 bytes; reserved[0..3] is 4-byte slot-activity marker
) {
    companion object {
        const val SALT_SIZE = 32
        const val ENCRYPTED_BLOB_SIZE = 50  // 32 DEK + 1 namespace_tag + 1 provider_type + 16 AEAD tag
        const val NONCE_SIZE = 12
        const val RESERVED_SIZE = 154
        const val TOTAL_SIZE = 256

        const val PROVIDER_PASSPHRASE: Byte = 0x00
        const val PROVIDER_KEYFILE: Byte = 0x01
        const val PROVIDER_OS_KEYCHAIN: Byte = 0x02
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Keyslot) return false
        if (!salt.contentEquals(other.salt)) return false
        if (argon2Params != other.argon2Params) return false
        if (!encryptedDekBlob.contentEquals(other.encryptedDekBlob)) return false
        if (!slotNonce.contentEquals(other.slotNonce)) return false
        if (!reserved.contentEquals(other.reserved)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + argon2Params.hashCode()
        result = 31 * result + encryptedDekBlob.contentHashCode()
        result = 31 * result + slotNonce.contentHashCode()
        result = 31 * result + reserved.contentHashCode()
        return result
    }
}

enum class VaultNamespace(val tag: Byte) {
    OUTER(0x00),
    HIDDEN(0x01);

    companion object {
        fun fromTag(tag: Byte): VaultNamespace =
            entries.firstOrNull { it.tag == tag }
                ?: throw VaultAuthException("Unknown namespace tag: 0x${tag.toInt().and(0xFF).toString(16)}")
    }
}
