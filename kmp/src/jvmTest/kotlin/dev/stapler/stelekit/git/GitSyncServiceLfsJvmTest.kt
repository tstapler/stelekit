// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.model.RepoRoot
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.lfs.LfsMode
import dev.stapler.stelekit.git.lfs.LfsModeSource
import dev.stapler.stelekit.git.lfs.LfsOid
import dev.stapler.stelekit.git.lfs.LfsPointer
import dev.stapler.stelekit.git.lfs.LfsPointerCodec
import dev.stapler.stelekit.git.lfs.LfsViolationReason
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End to end on the JVM sync path: real [GitSyncService] + real [JvmGitRepository] + a real bare origin.
 * A raw binary at a path `.gitattributes` stores with LFS must never reach the remote, while the text
 * notes beside it do, and the held file survives on disk.
 */
class GitSyncServiceLfsJvmTest {
    private lateinit var origin: File
    private lateinit var work: File
    private lateinit var config: GitConfig

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(3_000)
    private val pointer = LfsPointerCodec.serialize(
        LfsPointer(LfsOid("4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393"), 3_000_000),
    )

    /** Real files on disk, byte-addressable, for the service's guard. */
    private class DiskFileSystem : StubFileSystem() {
        override fun readFile(path: String): String? = File(path).takeIf { it.isFile }?.readText()
        override fun readFileBytes(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()
        override fun getFileSize(path: String): Long? = File(path).takeIf { it.isFile }?.length()
    }

    @BeforeTest
    fun setUp() {
        origin = BareOriginFixtures.createBareOrigin("stelekit_lfs_origin_", "main")
        BareOriginFixtures.pushCommit(origin, "main", ".gitattributes", "assets/** filter=lfs\n", "lfs rules")
        work = createTempDirectory("stelekit_lfs_work_").toFile()
        Git.cloneRepository().setURI(origin.absolutePath).setDirectory(work).setBranch("main").call().use { git ->
            BareOriginFixtures.setIdentity(git)
            // A global filter.lfs.* (git-lfs installed on the developer machine) would make JGit convert adds.
            git.repository.config.apply {
                setString("filter", "lfs", "clean", "cat")
                setString("filter", "lfs", "smudge", "cat")
                setBoolean("filter", "lfs", "required", false)
                setString("core", null, "autocrlf", "false")
                save()
            }
        }
        config = GitConfig(graphId = "g", repoRoot = RepoRoot(work.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
    }

    @AfterTest
    fun tearDown() {
        origin.deleteRecursively()
        work.deleteRecursively()
    }

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private fun service(): GitSyncService {
        val fs = DiskFileSystem()
        return GitSyncService(
            // These tests pin the safety floor (GUARD_ONLY); transfer is covered by JvmGitRepositoryLfsTransferTest.
            gitRepository = JvmGitRepository(),
            settings = MapSettings().also { LfsModeSource(it).set("g", LfsMode.GUARD_ONLY) },
            graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
            graphWriter = GraphWriter(fileSystem = fs),
            editLock = EditLock(),
            configRepository = StubConfigRepository(config.right()),
            networkMonitor = NetworkMonitor(),
            fileSystem = fs,
            graphId = "g",
            isOnline = { true },
        )
    }

    private fun remoteBlobs(): Map<String, ByteArray> {
        val blobs = LinkedHashMap<String, ByteArray>()
        Git.open(origin).use { git ->
            val repo = git.repository
            RevWalk(repo).use { walk ->
                TreeWalk(repo).use { tw ->
                    tw.addTree(walk.parseCommit(repo.resolve(Constants.R_HEADS + "main")).tree)
                    tw.isRecursive = true
                    while (tw.next()) blobs[tw.pathString] = repo.open(tw.getObjectId(0)).bytes
                }
            }
        }
        return blobs
    }

    private fun remoteCommitCount(): Int = Git.open(origin).use { git -> git.log().call().count() }

    @Test
    fun sync_should_PushTextNotesAndNeverTheRawBinary_When_AssetsAreLfsTracked() = runBlocking {
        File(work, "pages").mkdirs()
        File(work, "assets").mkdirs()
        File(work, "pages/a.md").writeText("- a note\n")
        File(work, "assets/photo.png").writeBytes(png)
        File(work, "assets/p.png").writeBytes(pointer)
        val service = service()

        val result = service.sync("g")

        assertTrue(result.isRight(), "a held file must not fail the sync: $result")
        val blobs = remoteBlobs()
        assertTrue("pages/a.md" in blobs, "the text note syncs: ${blobs.keys}")
        assertContentEquals(pointer, blobs.getValue("assets/p.png"), "a pointer is committed as is")
        assertTrue("assets/photo.png" !in blobs, "the raw PNG must never be pushed: ${blobs.keys}")
        blobs.filterKeys { it.startsWith("assets/") }.forEach { (path, bytes) ->
            assertTrue(LfsPointerCodec.parse(bytes) is dev.stapler.stelekit.git.lfs.LfsPointerParse.Valid, "raw blob on the remote at $path")
        }
        assertContentEquals(png, File(work, "assets/photo.png").readBytes(), "the held file stays untouched on disk")
        val state = assertIs<SyncState.LfsActionRequired>(service.syncState.value)
        assertEquals(listOf("assets/photo.png"), state.held.map { it.path })
        assertEquals(LfsViolationReason.RawBinary, state.held.single().reason)
        service.shutdown()
    }

    @Test
    fun sync_should_NotCreateEmptyCommits_When_OnlyAHeldFileIsPending() = runBlocking {
        File(work, "assets").mkdirs()
        File(work, "assets/photo.png").writeBytes(png)
        val service = service()
        val before = remoteCommitCount()

        service.sync("g")
        service.sync("g")

        assertEquals(before, remoteCommitCount(), "holding a file back must not mint commits on every sync")
        assertNotNull(service.syncState.value as? SyncState.LfsActionRequired)
        service.shutdown()
    }

    @Test
    fun sync_should_PushTheFileAsAPointer_OnceTheRawFileIsReplaced() = runBlocking {
        File(work, "assets").mkdirs()
        File(work, "assets/photo.png").writeBytes(png)
        val service = service()
        service.sync("g")
        assertIs<SyncState.LfsActionRequired>(service.syncState.value)

        File(work, "assets/photo.png").writeBytes(pointer)
        service.sync("g")

        assertContentEquals(pointer, remoteBlobs().getValue("assets/photo.png"))
        assertIs<SyncState.Success>(service.syncState.value)
        service.shutdown()
    }
}
