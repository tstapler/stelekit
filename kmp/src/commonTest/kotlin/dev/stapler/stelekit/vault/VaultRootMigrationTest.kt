// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.vault

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.git.VaultCredentialStore
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.model.WikiSubdir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultRootMigrationTest {
    private val root = RepoRoot("/repo")
    private val notes = EffectiveNotesPath.of(root, WikiSubdir("wiki"))
    private val vaultOld = "/repo/wiki/.stele-vault"
    private val vaultNew = "/repo/.stele-vault"
    private val credsOld = "/repo/wiki/.stele-credentials"
    private val reserveOld = "/repo/wiki/_hidden_reserve/.stele-reserve"
    private val reserveNew = "/repo/_hidden_reserve/.stele-reserve"

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `GitConfig repoRoot serializes as a bare string`() {
        val config = GitConfig(graphId = "g", repoRoot = root, wikiSubdir = null, authType = GitAuthType.NONE)
        val json = Json.encodeToString(GitConfig.serializer(), config)
        assertEquals(JsonPrimitive("/repo"), Json.parseToJsonElement(json).jsonObject["repoRoot"])
        assertEquals(config, Json.decodeFromString(GitConfig.serializer(), json))
    }

    @Test
    fun `credential file lives at the repo root not the wiki subdir`() {
        assertEquals("/repo/.stele-credentials", VaultCredentialStore.credentialFilePath(root))
        assertEquals("/repo/.stele-credentials", VaultCredentialStore.credentialFilePath(RepoRoot("/repo/")))
        assertEquals(vaultNew, VaultManager.vaultFilePath(root))
    }

    @Test
    fun `subdir layout moves all vault files to the repo root`() {
        val fs = FakeFileSystem()
        fs.writeFileBytes(vaultOld, bytes(1, 2))
        fs.writeFileBytes(credsOld, bytes(3))
        fs.writeFileBytes(reserveOld, bytes(4))

        assertEquals(3, VaultRootMigration(fs).migrate(root, notes))

        assertContentEquals(bytes(1, 2), fs.readFileBytes(vaultNew))
        assertContentEquals(bytes(3), fs.readFileBytes("/repo/.stele-credentials"))
        assertContentEquals(bytes(4), fs.readFileBytes(reserveNew))
        listOf(vaultOld, credsOld, reserveOld).forEach { assertFalse(fs.fileExists(it), it) }
    }

    @Test
    fun `flat layout is a no-op`() {
        val fs = FakeFileSystem()
        fs.writeFileBytes(vaultNew, bytes(9))
        val flat = EffectiveNotesPath.of(root, WikiSubdir(""))

        assertEquals(0, VaultRootMigration(fs).migrate(root, flat))
        assertContentEquals(bytes(9), fs.readFileBytes(vaultNew))
    }

    @Test
    fun `an existing repo-root file is never overwritten`() {
        val fs = FakeFileSystem()
        fs.writeFileBytes(vaultOld, bytes(1))
        fs.writeFileBytes(vaultNew, bytes(2))

        assertEquals(0, VaultRootMigration(fs).migrate(root, notes))
        assertContentEquals(bytes(2), fs.readFileBytes(vaultNew))
        assertTrue(fs.fileExists(vaultOld))
    }

    @Test
    fun `migration is idempotent`() {
        val fs = FakeFileSystem()
        fs.writeFileBytes(vaultOld, bytes(1))
        val migration = VaultRootMigration(fs)

        assertEquals(1, migration.migrate(root, notes))
        assertEquals(0, migration.migrate(root, notes))
        assertContentEquals(bytes(1), fs.readFileBytes(vaultNew))
    }

    @Test
    fun `a failed copy leaves the source intact`() {
        val fs = object : FakeFileSystem() {
            override fun writeFileBytes(path: String, data: ByteArray): Boolean =
                if (path == vaultNew) false else super.writeFileBytes(path, data)
        }
        fs.writeFileBytes(vaultOld, bytes(1))

        assertEquals(0, VaultRootMigration(fs).migrate(root, notes))
        assertTrue(fs.fileExists(vaultOld))
        assertFalse(fs.fileExists(vaultNew))
    }
}
