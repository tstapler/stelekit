package dev.stapler.stelekit.vault

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Fixed-offset binary serializer / deserializer for the .stele-vault header format.
 *
 * Layout:
 *   [0]     4 bytes  — magic "SKVT"
 *   [4]     1 byte   — version
 *   [5]     8 bytes  — random padding
 *   [13]    2048 bytes — 8 keyslots × 256 bytes each
 *   [2061]  512 bytes — reserved
 *   [2573]  32 bytes  — header MAC
 *   Total:  2605 bytes
 */
object VaultHeaderSerializer {

    fun serialize(header: VaultHeader): ByteArray {
        require(header.keyslots.size == VaultHeader.KEYSLOT_COUNT) {
            "Vault header must have exactly ${VaultHeader.KEYSLOT_COUNT} keyslots"
        }
        require(header.randomPadding.size == VaultHeader.PADDING_SIZE)
        require(header.reserved.size == VaultHeader.RESERVED_SIZE)
        require(header.headerMac.size == VaultHeader.MAC_SIZE)

        val buf = ByteArray(VaultHeader.TOTAL_SIZE)
        var pos = 0

        // Magic
        VaultHeader.MAGIC.copyInto(buf, pos); pos += 4
        // Version
        buf[pos] = header.version; pos += 1
        // Random padding
        header.randomPadding.copyInto(buf, pos); pos += VaultHeader.PADDING_SIZE

        // 8 keyslots
        for (slot in header.keyslots) {
            val slotBytes = serializeKeyslot(slot)
            require(slotBytes.size == Keyslot.TOTAL_SIZE)
            slotBytes.copyInto(buf, pos)
            pos += Keyslot.TOTAL_SIZE
        }

        // Reserved
        header.reserved.copyInto(buf, pos); pos += VaultHeader.RESERVED_SIZE
        // Header MAC
        header.headerMac.copyInto(buf, pos); pos += VaultHeader.MAC_SIZE

        check(pos == VaultHeader.TOTAL_SIZE)
        return buf
    }

    fun deserialize(bytes: ByteArray): Either<VaultError, VaultHeader> {
        if (bytes.size < VaultHeader.TOTAL_SIZE) {
            return VaultError.CorruptedFile("Vault header too short: ${bytes.size} < ${VaultHeader.TOTAL_SIZE}").left()
        }

        var pos = 0

        // Magic check
        val magic = bytes.sliceArray(pos until pos + 4); pos += 4
        if (!magic.contentEquals(VaultHeader.MAGIC)) {
            return VaultError.NotAVault().left()
        }

        // Version check
        val version = bytes[pos]; pos += 1
        if (version != VaultHeader.SUPPORTED_VERSION) {
            return VaultError.UnsupportedVersion(version.toInt() and 0xFF).left()
        }

        // Random padding
        val padding = bytes.sliceArray(pos until pos + VaultHeader.PADDING_SIZE); pos += VaultHeader.PADDING_SIZE

        // 8 keyslots
        val keyslots = mutableListOf<Keyslot>()
        repeat(VaultHeader.KEYSLOT_COUNT) {
            val slotBytes = bytes.sliceArray(pos until pos + Keyslot.TOTAL_SIZE)
            keyslots.add(deserializeKeyslot(slotBytes))
            pos += Keyslot.TOTAL_SIZE
        }

        // Reserved
        val reserved = bytes.sliceArray(pos until pos + VaultHeader.RESERVED_SIZE); pos += VaultHeader.RESERVED_SIZE

        // Header MAC
        val mac = bytes.sliceArray(pos until pos + VaultHeader.MAC_SIZE)

        return VaultHeader(
            version = version,
            randomPadding = padding,
            keyslots = keyslots,
            reserved = reserved,
            headerMac = mac,
        ).right()
    }

    private fun serializeKeyslot(slot: Keyslot): ByteArray {
        require(slot.salt.size == Keyslot.SALT_SIZE)
        require(slot.encryptedDekBlob.size == Keyslot.ENCRYPTED_BLOB_SIZE)
        require(slot.slotNonce.size == Keyslot.NONCE_SIZE)
        require(slot.reserved.size == Keyslot.RESERVED_SIZE)

        val buf = ByteArray(Keyslot.TOTAL_SIZE)
        var pos = 0

        slot.salt.copyInto(buf, pos); pos += Keyslot.SALT_SIZE
        writeInt32LE(buf, pos, slot.argon2Params.memory); pos += 4
        writeInt16LE(buf, pos, slot.argon2Params.iterations); pos += 2
        writeInt16LE(buf, pos, slot.argon2Params.parallelism); pos += 2
        slot.encryptedDekBlob.copyInto(buf, pos); pos += Keyslot.ENCRYPTED_BLOB_SIZE
        slot.slotNonce.copyInto(buf, pos); pos += Keyslot.NONCE_SIZE
        buf[pos] = slot.providerType; pos += 1
        slot.reserved.copyInto(buf, pos); pos += Keyslot.RESERVED_SIZE

        check(pos == Keyslot.TOTAL_SIZE)
        return buf
    }

    private fun deserializeKeyslot(bytes: ByteArray): Keyslot {
        var pos = 0
        val salt = bytes.sliceArray(pos until pos + Keyslot.SALT_SIZE); pos += Keyslot.SALT_SIZE
        val memory = readInt32LE(bytes, pos); pos += 4
        val iterations = readInt16LE(bytes, pos); pos += 2
        val parallelism = readInt16LE(bytes, pos); pos += 2
        val blob = bytes.sliceArray(pos until pos + Keyslot.ENCRYPTED_BLOB_SIZE); pos += Keyslot.ENCRYPTED_BLOB_SIZE
        val nonce = bytes.sliceArray(pos until pos + Keyslot.NONCE_SIZE); pos += Keyslot.NONCE_SIZE
        val providerType = bytes[pos]; pos += 1
        val reserved = bytes.sliceArray(pos until pos + Keyslot.RESERVED_SIZE)
        return Keyslot(
            salt = salt,
            argon2Params = Argon2Params(memory = memory, iterations = iterations, parallelism = parallelism),
            encryptedDekBlob = blob,
            slotNonce = nonce,
            providerType = providerType,
            reserved = reserved,
        )
    }

    private fun writeInt32LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset]     = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun readInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
