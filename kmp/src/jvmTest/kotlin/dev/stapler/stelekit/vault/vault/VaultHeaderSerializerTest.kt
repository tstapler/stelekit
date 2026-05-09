package dev.stapler.stelekit.vault.vault

import dev.stapler.stelekit.vault.*
import kotlin.test.*

class VaultHeaderSerializerTest {
    private val engine = dev.stapler.stelekit.vault.JvmCryptoEngine()

    private fun makeHeader(): VaultHeader {
        return VaultHeader(
            randomPadding = engine.secureRandom(VaultHeader.PADDING_SIZE),
            keyslots = (0 until VaultHeader.KEYSLOT_COUNT).map { makeRandomSlot() },
            reserved = engine.secureRandom(VaultHeader.RESERVED_SIZE),
            hiddenHeaderMac = engine.secureRandom(VaultHeader.MAC_SIZE),
            headerMac = engine.secureRandom(VaultHeader.MAC_SIZE),
        )
    }

    private fun makeRandomSlot() = Keyslot(
        salt = engine.secureRandom(Keyslot.SALT_SIZE),
        argon2Params = Argon2Params(memory = 4096, iterations = 1, parallelism = 1),
        encryptedDekBlob = engine.secureRandom(Keyslot.ENCRYPTED_BLOB_SIZE),
        slotNonce = engine.secureRandom(Keyslot.NONCE_SIZE),
        reserved = engine.secureRandom(Keyslot.RESERVED_SIZE),
    )

    // VH-01 — Serialized header has correct total byte length
    @Test fun `serialized header has correct total byte length`() {
        val header = makeHeader()
        val bytes = VaultHeaderSerializer.serialize(header)
        assertEquals(VaultHeader.TOTAL_SIZE, bytes.size)
    }

    // VH-02 — Magic bytes at offset 0 are "SKVT"
    @Test fun `magic bytes at offset 0 are SKVT`() {
        val bytes = VaultHeaderSerializer.serialize(makeHeader())
        assertContentEquals(byteArrayOf(0x53, 0x4B, 0x56, 0x54), bytes.sliceArray(0 until 4))
    }

    // VH-03 — Round-trip: serialize → deserialize recovers identical VaultHeader
    @Test fun `round-trip serialize and deserialize recovers identical header`() {
        val header = makeHeader()
        val bytes = VaultHeaderSerializer.serialize(header)
        val result = VaultHeaderSerializer.deserialize(bytes)
        assertTrue(result.isRight())
        assertEquals(header, result.getOrNull())
    }

    // VH-04 — Wrong magic bytes → VaultError.NotAVault
    @Test fun `wrong magic bytes returns NotAVault`() {
        val bytes = VaultHeaderSerializer.serialize(makeHeader()).copyOf()
        bytes[0] = 0x58; bytes[1] = 0x58; bytes[2] = 0x58; bytes[3] = 0x58
        val result = VaultHeaderSerializer.deserialize(bytes)
        assertTrue(result.isLeft())
        assertIs<VaultError.NotAVault>(result.leftOrNull())
    }

    // VH-05 — Unknown version byte → VaultError.UnsupportedVersion
    @Test fun `unknown version byte returns UnsupportedVersion`() {
        val bytes = VaultHeaderSerializer.serialize(makeHeader()).copyOf()
        bytes[4] = 0xFF.toByte()
        val result = VaultHeaderSerializer.deserialize(bytes)
        assertTrue(result.isLeft())
        assertIs<VaultError.UnsupportedVersion>(result.leftOrNull())
    }

    // VH-06 — Unused keyslots have non-zero bytes (random padding)
    @Test fun `unused keyslots are filled with non-zero random bytes`() {
        // Create 10 headers each with 8 random slots (all "unused")
        var totalNonZeroFraction = 0.0
        repeat(10) {
            val header = makeHeader()
            val bytes = VaultHeaderSerializer.serialize(header)
            val slotArea = bytes.sliceArray(13 until 13 + VaultHeader.KEYSLOT_COUNT * Keyslot.TOTAL_SIZE)
            val nonZeroCount = slotArea.count { it != 0.toByte() }
            totalNonZeroFraction += nonZeroCount.toDouble() / slotArea.size
        }
        val avgNonZero = totalNonZeroFraction / 10
        assertTrue(avgNonZero >= 0.95, "Expected at least 95% non-zero bytes in random slot area, got $avgNonZero")
    }

    // U-HS-07 — Argon2 params at uint16 boundary values round-trip without sign extension
    // iterations and parallelism are stored as LE uint16 (max 65535); memory is LE uint32.
    // This guards against sign-extension bugs where the Kotlin Int (signed) misreads the stored value.
    @Test fun `argon2 params at uint16 max round-trip correctly`() {
        val maxU16 = 65535
        val slot = Keyslot(
            salt = engine.secureRandom(Keyslot.SALT_SIZE),
            argon2Params = Argon2Params(memory = maxU16, iterations = maxU16, parallelism = maxU16),
            encryptedDekBlob = engine.secureRandom(Keyslot.ENCRYPTED_BLOB_SIZE),
            slotNonce = engine.secureRandom(Keyslot.NONCE_SIZE),
            reserved = engine.secureRandom(Keyslot.RESERVED_SIZE),
        )
        val header = VaultHeader(
            randomPadding = engine.secureRandom(VaultHeader.PADDING_SIZE),
            keyslots = (0 until VaultHeader.KEYSLOT_COUNT).map { if (it == 0) slot else makeRandomSlot() },
            reserved = engine.secureRandom(VaultHeader.RESERVED_SIZE),
            hiddenHeaderMac = engine.secureRandom(VaultHeader.MAC_SIZE),
            headerMac = engine.secureRandom(VaultHeader.MAC_SIZE),
        )
        val bytes = VaultHeaderSerializer.serialize(header)
        val result = VaultHeaderSerializer.deserialize(bytes)
        assertTrue(result.isRight())
        val recovered = result.getOrNull()!!.keyslots[0].argon2Params
        assertEquals(maxU16, recovered.memory, "memory must survive uint16 boundary round-trip")
        assertEquals(maxU16, recovered.iterations, "iterations must survive uint16 boundary round-trip")
        assertEquals(maxU16, recovered.parallelism, "parallelism must survive uint16 boundary round-trip")
    }

    // U-HS-08 — deserialize rejects vault files larger than TOTAL_SIZE
    // Guards against garbage-appended files slipping through the size check.
    @Test fun `vault file larger than TOTAL_SIZE returns CorruptedFile`() {
        val oversized = ByteArray(VaultHeader.TOTAL_SIZE + 1)
        val result = VaultHeaderSerializer.deserialize(oversized)
        assertIs<VaultError.CorruptedFile>(result.leftOrNull(),
            "File with ${VaultHeader.TOTAL_SIZE + 1} bytes must be rejected as CorruptedFile")
    }
}
