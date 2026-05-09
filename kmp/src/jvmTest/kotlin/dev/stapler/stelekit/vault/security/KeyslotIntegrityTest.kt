package dev.stapler.stelekit.vault.security

import arrow.core.Either
import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

@Suppress("DEPRECATION")
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
    // Timeout is generous: 2573 bytes × 8 Argon2id calls on slow CI may exceed the default 60s.
    @Test fun `any header byte mutation is detected`() = runTest(timeout = 5.minutes) {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManager(store)
        val graphPath = "/tmp/ki-test"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val original = store[vaultPath]!!

        var detectedTampering = 0
        // Test mutations across bytes 0..1036 (the OUTER MAC-authenticated region, excluding MAC itself).
        // createVault defaults to OUTER namespace, so the OUTER MAC covers bytes[0..1036].
        for (i in 0 until VaultHeader.OUTER_MAC_AUTH_SIZE) {
            val mutated = original.copyOf()
            mutated[i] = (mutated[i].toInt() xor 0x01).toByte()
            store[vaultPath] = mutated
            val result = vm.unlock(graphPath, "correct".toCharArray(), params)
            if (result.isLeft()) detectedTampering++
            store[vaultPath] = original
        }
        // Every mutation must be detected — the OUTER header MAC covers bytes[0..1036],
        // so even mutations in random padding or OUTER slot areas fail the MAC check.
        assertEquals(VaultHeader.OUTER_MAC_AUTH_SIZE, detectedTampering,
            "Expected 100% of bit flips to be detected, got $detectedTampering/${VaultHeader.OUTER_MAC_AUTH_SIZE}")
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
        val dek = vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params).getOrNull()!!.dek
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val rawBytes = store[vaultPath]!!

        // Compute expected MAC key using the same HKDF derivation as VaultManager
        val expectedMacKey = engine.hkdfSha256(
            ikm = dek,
            salt = "vault-header-mac".encodeToByteArray(),
            info = "v1".encodeToByteArray(),
            length = 32,
        )

        // Compute HMAC-SHA256 over bytes[0..OUTER_MAC_AUTH_SIZE-1] (OUTER authenticated region)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(expectedMacKey, "HmacSHA256"))
        val computedMac = mac.doFinal(rawBytes.sliceArray(0 until VaultHeader.OUTER_MAC_AUTH_SIZE))

        // OUTER MAC is stored in the last 32 bytes (bytes[2573..2604])
        val outerMacOffset = VaultHeader.TOTAL_SIZE - VaultHeader.MAC_SIZE
        val storedMac = rawBytes.sliceArray(outerMacOffset until VaultHeader.TOTAL_SIZE)
        assertContentEquals(computedMac, storedMac, "OUTER header MAC must be derived from DEK via HKDF")
    }
}
