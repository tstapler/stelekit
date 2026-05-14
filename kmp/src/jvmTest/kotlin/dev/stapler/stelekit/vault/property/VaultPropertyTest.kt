package dev.stapler.stelekit.vault.property

import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Property-based tests for the vault layer.
 * Each test checks an invariant over a range of inputs rather than a single example.
 */
@Suppress("DEPRECATION")
class VaultPropertyTest {
    private val engine = JvmCryptoEngine()
    private val params = TEST_ARGON2_PARAMS

    // PBT-01 — STEK round-trip holds for all byte lengths 0..256
    @Test fun `STEK round-trip holds for all byte lengths 0 to 256`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val path = "pages/SizeTest.md.stek"
        for (size in 0..256) {
            val data = ByteArray(size) { (it % 256).toByte() }
            val encrypted = layer.encrypt(path, data)
            val result = layer.decrypt(path, encrypted)
            assertTrue(result.isRight(), "Round-trip failed at size $size")
            assertContentEquals(data, result.getOrNull(), "Content mismatch at size $size")
        }
    }

    // PBT-02 — Different DEKs always produce non-decryptable cross-DEK ciphertexts
    @Test fun `different DEKs produce mutually non-decryptable ciphertexts`() {
        val path = "pages/CrossDek.md.stek"
        val content = "sensitive content".encodeToByteArray()
        repeat(10) {
            val dek1 = engine.secureRandom(32)
            val dek2 = engine.secureRandom(32)
            val layer1 = CryptoLayer(engine, dek1)
            val layer2 = CryptoLayer(engine, dek2)
            val encrypted = layer1.encrypt(path, content)
            val result = layer2.decrypt(path, encrypted)
            assertTrue(result.isLeft(), "Cross-DEK decryption must fail (iteration $it)")
            assertIs<VaultError.AuthenticationFailed>(result.leftOrNull())
        }
    }

    // PBT-03 — STEK magic is always present after encryption
    @Test fun `STEK magic is present in every encrypted file`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        repeat(20) { i ->
            val content = "content $i".encodeToByteArray()
            val encrypted = layer.encrypt("pages/Page$i.md.stek", content)
            assertContentEquals(
                CryptoLayer.STEK_MAGIC,
                encrypted.sliceArray(0 until 4),
                "STEK magic must be present (iteration $i)"
            )
        }
    }

    // PBT-04 — STEK version byte is always 0x01
    @Test fun `STEK version byte is always 0x01`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        repeat(10) { i ->
            val encrypted = layer.encrypt("pages/Ver$i.md.stek", "content".encodeToByteArray())
            assertEquals(CryptoLayer.STEK_VERSION, encrypted[4], "Version byte must be 0x01 (iteration $i)")
        }
    }

    // PBT-05 — VaultHeader serialize → deserialize is an identity for random headers
    @Test fun `header serialize deserialize is identity for random headers`() {
        repeat(20) { i ->
            val header = VaultHeader(
                randomPadding = engine.secureRandom(VaultHeader.PADDING_SIZE),
                keyslots = (0 until VaultHeader.KEYSLOT_COUNT).map {
                    Keyslot(
                        salt = engine.secureRandom(Keyslot.SALT_SIZE),
                        argon2Params = Argon2Params(
                            memory = (engine.secureRandom(2).let { ((it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)) + 1 }),
                            iterations = (engine.secureRandom(1)[0].toInt() and 0xFF) + 1,
                            parallelism = (engine.secureRandom(1)[0].toInt() and 0xFF) + 1,
                        ),
                        encryptedDekBlob = engine.secureRandom(Keyslot.ENCRYPTED_BLOB_SIZE),
                        slotNonce = engine.secureRandom(Keyslot.NONCE_SIZE),
                        reserved = engine.secureRandom(Keyslot.RESERVED_SIZE),
                    )
                },
                reserved = engine.secureRandom(VaultHeader.RESERVED_SIZE),
                hiddenHeaderMac = engine.secureRandom(VaultHeader.MAC_SIZE),
                headerMac = engine.secureRandom(VaultHeader.MAC_SIZE),
            )
            val bytes = VaultHeaderSerializer.serialize(header)
            assertEquals(VaultHeader.TOTAL_SIZE, bytes.size, "Serialized size must be TOTAL_SIZE (iteration $i)")
            val result = VaultHeaderSerializer.deserialize(bytes)
            assertTrue(result.isRight(), "Deserialization must succeed (iteration $i)")
            assertEquals(header, result.getOrNull(), "Round-trip must recover identical header (iteration $i)")
        }
    }

    // PBT-06 — createVault + unlock round-trips for random passphrases (ASCII printable)
    @Test fun `createVault and unlock round-trip for random printable ASCII passphrases`() = runTest {
        val printableAscii = (0x20..0x7E).map { it.toChar() }
        repeat(5) { i ->
            val store = mutableMapOf<String, ByteArray>()
            val vm = VaultManager(
                crypto = engine,
                fileReadBytes = { path -> store[path] },
                fileWriteBytes = { path, data -> store[path] = data; true },
            )
            val passLength = 8 + (i * 4)
            val passChars = CharArray(passLength) { printableAscii[engine.secureRandom(1)[0].toInt().and(0xFF) % printableAscii.size] }
            val graphPath = "/tmp/prop-test-$i"
            vm.createVault(graphPath, passChars.copyOf(), argon2Params = params)
            val result = vm.unlock(graphPath, passChars.copyOf(), argon2Params = null)
            assertTrue(result.isRight(), "Round-trip must succeed for random passphrase of length $passLength (iteration $i)")
            assertEquals(32, result.getOrNull()!!.dek.size)
        }
    }

    // PBT-07 — Encrypted ciphertext size is always plaintext size + HEADER_SIZE + AEAD_TAG_SIZE
    @Test fun `ciphertext size equals plaintext size plus header plus tag`() {
        val dek = engine.secureRandom(32)
        val layer = CryptoLayer(engine, dek)
        val aead_tag_size = 16  // Poly1305 tag appended by ChaCha20-Poly1305
        for (size in listOf(0, 1, 16, 64, 255, 1024)) {
            val content = ByteArray(size) { 0x41 }
            val encrypted = layer.encrypt("pages/Size$size.md.stek", content)
            val expected = CryptoLayer.HEADER_SIZE + size + aead_tag_size
            assertEquals(expected, encrypted.size, "Ciphertext size mismatch for plaintext size $size")
        }
    }
}
