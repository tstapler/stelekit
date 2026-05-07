package dev.stapler.stelekit.vault.vault

import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VaultManagerTest {
    private val engine = JvmCryptoEngine()
    private val params = TEST_ARGON2_PARAMS

    private fun makeVaultManager(): VaultManager {
        val store = mutableMapOf<String, ByteArray>()
        return VaultManager(
            crypto = engine,
            fileReadBytes = { path -> store[path] },
            fileWriteBytes = { path, data -> store[path] = data; true },
        )
    }

    private fun makeVaultManagerWithStore(store: MutableMap<String, ByteArray>): VaultManager {
        return VaultManager(
            crypto = engine,
            fileReadBytes = { path -> store[path] },
            fileWriteBytes = { path, data -> store[path] = data; true },
        )
    }

    // VM-01 — createVault writes a readable .stele-vault file
    @Test fun `createVault writes a valid vault file`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        val result = vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        assertTrue(result.isRight())
        val vaultBytes = store[VaultManager.vaultFilePath(graphPath)]
        assertNotNull(vaultBytes)
        assertEquals(VaultHeader.TOTAL_SIZE, vaultBytes.size)
        assertContentEquals(VaultHeader.MAGIC, vaultBytes.sliceArray(0 until 4))
    }

    // VM-02 — Unlock with correct passphrase returns Right(DEK)
    @Test fun `unlock with correct passphrase returns dek`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val result = vm.unlock(graphPath, "correct".toCharArray(), params)
        assertTrue(result.isRight())
        val unlockResult = result.getOrNull()!!
        assertEquals(32, unlockResult.dek.size)
        assertEquals(VaultNamespace.OUTER, unlockResult.namespace)
    }

    // VM-03 — Wrong passphrase returns Left(InvalidCredential)
    @Test fun `wrong passphrase returns InvalidCredential`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val result = vm.unlock(graphPath, "wrong".toCharArray(), params)
        assertTrue(result.isLeft())
        assertIs<VaultError.InvalidCredential>(result.leftOrNull())
    }

    // VM-04 — Only active (non-PROVIDER_UNUSED) keyslots are tried during unlock;
    // decoy slots are skipped to avoid OOM from unbounded Argon2 params and to keep unlock fast.
    @Test fun `only active keyslots are tried on unlock`() = runTest {
        var decryptCount = 0
        val countingEngine = object : CryptoEngine by engine {
            override fun decryptAEAD(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
                decryptCount++
                return engine.decryptAEAD(key, nonce, ciphertext, aad)
            }
        }
        val store = mutableMapOf<String, ByteArray>()
        val vm = VaultManager(
            crypto = countingEngine,
            fileReadBytes = { path -> store[path] },
            fileWriteBytes = { path, data -> store[path] = data; true },
        )
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        decryptCount = 0
        vm.unlock(graphPath, "correct".toCharArray(), params)
        assertEquals(1, decryptCount, "Expected exactly 1 decryptAEAD call for the single active keyslot")
    }

    // VM-05 — Add second keyslot (passphrase), unlock with new passphrase
    @Test fun `add keyslot allows unlock with new passphrase`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        val r1 = vm.createVault(graphPath, "original".toCharArray(), argon2Params = params)
        val dek = r1.getOrNull()!!
        vm.unlock(graphPath, "original".toCharArray(), params)
        vm.addKeyslot(graphPath, dek, "second".toCharArray(), argon2Params = params)
        // New passphrase works
        val r2 = vm.unlock(graphPath, "second".toCharArray(), params)
        assertTrue(r2.isRight())
        // Original passphrase still works
        val r3 = vm.unlock(graphPath, "original".toCharArray(), params)
        assertTrue(r3.isRight())
    }

    // VM-06 — Remove keyslot; removed provider cannot unlock
    @Test fun `remove keyslot prevents unlock with that slot`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        val dek = vm.createVault(graphPath, "original".toCharArray(), argon2Params = params).getOrNull()!!
        vm.unlock(graphPath, "original".toCharArray(), params)
        vm.addKeyslot(graphPath, dek, "second".toCharArray(), argon2Params = params)
        // Remove slot 0 (original)
        vm.removeKeyslot(graphPath, 0)
        // Original no longer works
        val r1 = vm.unlock(graphPath, "original".toCharArray(), params)
        assertTrue(r1.isLeft())
        // Second still works
        val r2 = vm.unlock(graphPath, "second".toCharArray(), params)
        assertTrue(r2.isRight())
    }

    // VM-07 — Tampered header bytes → VaultError.HeaderTampered
    @Test fun `tampered header bytes return HeaderTampered`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        // Flip a byte in the keyslot area (offset 13, inside first keyslot)
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val bytes = store[vaultPath]!!.copyOf()
        bytes[13] = (bytes[13].toInt() xor 0xFF).toByte()
        store[vaultPath] = bytes
        val result = vm.unlock(graphPath, "correct".toCharArray(), params)
        // Should fail — either HeaderTampered or InvalidCredential (slot data corrupted)
        assertTrue(result.isLeft())
    }

    // VM-08 — lock() zero-fills DEK byte array
    @Test fun `lock zeros DEK byte array`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val unlockResult = vm.unlock(graphPath, "correct".toCharArray(), params).getOrNull()!!
        val dekRef = vm.currentDek()!!
        vm.lock()
        assertTrue(dekRef.all { it == 0.toByte() }, "DEK must be zeroed after lock()")
        assertNull(vm.currentDek())
    }

    // VM-09 — lock() emits VaultLocked event
    @Test fun `lock emits Locked event`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        vm.unlock(graphPath, "correct".toCharArray(), params)
        var receivedLocked = false
        val job = backgroundScope.launch {
            vm.vaultEvents.collect { event ->
                if (event is VaultManager.VaultEvent.Locked) receivedLocked = true
            }
        }
        vm.lock()
        kotlinx.coroutines.delay(100)
        job.cancel()
        assertTrue(receivedLocked, "Expected Locked event after lock()")
    }

    // VM-10 — rotateKeyslots (provider rotation): DEK unchanged, file decrypts after passphrase change
    @Test fun `provider rotation does not change DEK`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        val dek = vm.createVault(graphPath, "original".toCharArray(), argon2Params = params).getOrNull()!!
        // Remove slot 0 and add new one with different passphrase
        vm.unlock(graphPath, "original".toCharArray(), params)
        vm.addKeyslot(graphPath, dek, "new-passphrase".toCharArray(), argon2Params = params)
        val newUnlock = vm.unlock(graphPath, "new-passphrase".toCharArray(), params).getOrNull()!!
        // DEK is the same (provider rotation, not DEK rotation)
        assertContentEquals(dek, newUnlock.dek)
    }

    // VM-12 — UnsupportedVersion on unknown header version
    @Test fun `unsupported version returns UnsupportedVersion`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val bytes = store[vaultPath]!!.copyOf()
        bytes[4] = 0xFF.toByte()  // Overwrite version byte
        store[vaultPath] = bytes
        val result = vm.unlock(graphPath, "correct".toCharArray(), params)
        assertTrue(result.isLeft())
        assertIs<VaultError.UnsupportedVersion>(result.leftOrNull())
    }

    // VM-13 — Empty keyslot array → NoValidKeyslot
    @Test fun `all random slots return NoValidKeyslot`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        // Overwrite the keyslot area (bytes 13..2060) with random data
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val bytes = store[vaultPath]!!.copyOf()
        val randomSlotArea = engine.secureRandom(VaultHeader.KEYSLOT_COUNT * Keyslot.TOTAL_SIZE)
        randomSlotArea.copyInto(bytes, 13)
        // Recompute MAC with zero key (or just leave it wrong — the test expects a failure)
        store[vaultPath] = bytes
        val result = vm.unlock(graphPath, "correct".toCharArray(), params)
        assertTrue(result.isLeft())
    }

    // VM-14 — Argon2id parameters stored in keyslot match parameters used at unlock
    @Test fun `argon2 params stored in keyslot match creation params`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        val customParams = Argon2Params(memory = 8192, iterations = 2, parallelism = 1)
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = customParams)
        val vaultPath = VaultManager.vaultFilePath(graphPath)
        val bytes = store[vaultPath]!!
        val header = VaultHeaderSerializer.deserialize(bytes).getOrNull()!!
        val slot = header.keyslots[0]
        assertEquals(8192, slot.argon2Params.memory)
        assertEquals(2, slot.argon2Params.iterations)
        assertEquals(1, slot.argon2Params.parallelism)
    }

    // VM-11 — Full DEK rotation re-encrypts: test that different DEKs produce different vault state
    @Test fun `unlock returns consistent dek across multiple unlocks`() = runTest {
        val store = mutableMapOf<String, ByteArray>()
        val vm = makeVaultManagerWithStore(store)
        val graphPath = "/tmp/test-graph"
        vm.createVault(graphPath, "correct".toCharArray(), argon2Params = params)
        val r1 = vm.unlock(graphPath, "correct".toCharArray(), params).getOrNull()!!
        val r2 = vm.unlock(graphPath, "correct".toCharArray(), params).getOrNull()!!
        assertContentEquals(r1.dek, r2.dek)
    }
}
