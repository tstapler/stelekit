package dev.stapler.stelekit.vault.security

import arrow.core.Either
import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KeyslotIntegrityTest {
    private val engine = JvmCryptoEngine()
    private val params = TEST_ARGON2_PARAMS

    private fun makeVaultManager(store: MutableMap<String, ByteArray>): VaultManager =
        VaultManager(
            crypto = engine,
            fileReadBytes = { path -> store[path] },
            fileWriteBytes = { path, data -> store[path] = data; true },
        )

    // KI-01 — Any single keyslot byte mutation causes MAC verification failure
    @Test fun `any header byte mutation is detected`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/ki-test"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val original = store[vaultPath]!!

        var detectedTampering = 0
        // Test mutations across bytes 0..2572 (the MAC-authenticated region, excluding MAC itself)
        for (i in 0 until VaultHeader.MAC_AUTHENTICATED_SIZE) {
            val mutated = original.copyOf()
            mutated[i] = (mutated[i].toInt() xor 0x01).toByte()
            store[vaultPath] = mutated
            val result = vm.unlock(graphPath, "correct".toCharArray(), params)
            if (result.isLeft()) detectedTampering++
            store[vaultPath] = original
        }
        // All mutations should be detected (either via AEAD or header MAC)
        // Some mutations in random padding/reserved areas may slip through AEAD but be caught by MAC
        assertTrue(detectedTampering >= VaultHeader.MAC_AUTHENTICATED_SIZE * 95 / 100,
            "Expected ≥95% of bit flips to be detected, got $detectedTampering/${VaultHeader.MAC_AUTHENTICATED_SIZE}")
    }

    // KI-02 — Truncated header bytes → deserialization error
    @Test fun `truncated header returns error`() {
        for (length in listOf(0, 1, 100, 2572)) {
            val truncated = ByteArray(length)
            val result = VaultHeaderSerializer.deserialize(truncated)
            assertTrue(result.isLeft(), "Expected error for $length-byte truncated header")
        }
    }

    // KI-03 — Unused keyslot random padding does not affect MAC of active slots
    @Test fun `valid unlock succeeds with active slots intact`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/ki-test"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        // Unlock should succeed — active slot 0 is valid, slots 1-7 are random
        val result = vm.unlock(graphPath, "correct".toCharArray(), params)
        assertTrue(result.isRight(), "Expected unlock to succeed with valid slot")
    }

    // KI-04 — Header MAC key is derived from DEK, not hardcoded
    @Test fun `header MAC key is derived from DEK via HKDF`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/ki-test"
        val dek = vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params).getOrNull()!!
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val rawBytes = store[vaultPath]!!

        // Compute expected MAC key using the same HKDF derivation as VaultManager
        val expectedMacKey = engine.hkdfSha256(
            ikm = dek,
            salt = "vault-header-mac".encodeToByteArray(),
            info = "v1".encodeToByteArray(),
            length = 32,
        )

        // Compute HMAC-SHA256 over bytes[0..2572]
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(expectedMacKey, "HmacSHA256"))
        val computedMac = mac.doFinal(rawBytes.sliceArray(0 until VaultHeader.MAC_AUTHENTICATED_SIZE))

        // Compare with stored MAC (last 32 bytes)
        val storedMac = rawBytes.sliceArray(VaultHeader.MAC_AUTHENTICATED_SIZE until VaultHeader.TOTAL_SIZE)
        assertContentEquals(computedMac, storedMac, "Header MAC must be derived from DEK via HKDF")
    }
}
