package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.vault.JvmCryptoEngine
import dev.stapler.stelekit.vault.TEST_ARGON2_PARAMS
import dev.stapler.stelekit.vault.VaultManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR-020: the vault UI wiring is keyed on the repo root, and migrates legacy notes-folder vault files. */
class GraphContentVaultRepoRootTest {
    private val root = RepoRoot("/tmp/vault-repo")
    private val store = mutableMapOf<String, ByteArray>()
    private val vm = VaultManager(
        crypto = JvmCryptoEngine(),
        fileReadBytes = { store[it] },
        fileWriteBytes = { path, data -> store[path] = data; true },
    )

    private fun info(path: String, isDemo: Boolean = false) =
        GraphInfo(id = GraphId("g"), path = path, displayName = "g", addedAt = 0L, isDemo = isDemo)

    @Test
    fun `handlers are absent without a vault manager or repo root`() {
        assertNull(buildAddKeyslotHandler(null, root))
        assertNull(buildAddKeyslotHandler(vm, null))
        assertNull(buildRemoveKeyslotHandler(null, root))
        assertNull(buildRemoveKeyslotHandler(vm, null))
        assertNull(buildListActiveSlotsHandler(null, root))
        assertNull(buildListActiveSlotsHandler(vm, null))
    }

    @Test
    fun `keyslot handlers operate on the vault at the repo root`() = runTest {
        vm.createVault(root, "first".toCharArray(), argon2Params = TEST_ARGON2_PARAMS)
        vm.unlock(root, "first".toCharArray(), TEST_ARGON2_PARAMS)
        assertTrue(store.containsKey(VaultManager.vaultFilePath(root)))

        val list = assertNotNull(buildListActiveSlotsHandler(vm, root))
        assertEquals(listOf(0), list())

        val add = assertNotNull(buildAddKeyslotHandler(vm, root))
        assertTrue(add("second".toCharArray()).isRight())
        assertEquals(2, list().size)

        val remove = assertNotNull(buildRemoveKeyslotHandler(vm, root))
        assertTrue(remove(list().last()).isRight())
        assertEquals(1, list().size)
    }

    @Test
    fun `add keyslot on a locked vault is rejected`() = runTest {
        val add = assertNotNull(buildAddKeyslotHandler(vm, root))
        assertTrue(add("x".toCharArray()).isLeft())
    }

    @Test
    fun `listActiveKeyslotIndices is empty while locked`() = runTest {
        assertEquals(emptyList(), vm.listActiveKeyslotIndices(root))
    }

    @Test
    fun `migrateAndResolveRepoRoot skips demo and missing graphs`() {
        val fs = FakeFileSystem()
        assertNull(migrateAndResolveRepoRoot(null, fs))
        assertNull(migrateAndResolveRepoRoot(info("/demo", isDemo = true), fs))
    }

    @Test
    fun `migrateAndResolveRepoRoot returns the repo root of a real graph`() {
        assertEquals(RepoRoot("/repo"), migrateAndResolveRepoRoot(info("/repo"), FakeFileSystem()))
    }
}
