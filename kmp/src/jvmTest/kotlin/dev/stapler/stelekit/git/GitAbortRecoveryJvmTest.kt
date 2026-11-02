// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.NetworkMonitor
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import kotlin.random.Random
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 2.4i: the user's abort must not silently discard edits that are not part of the merge. */
class GitAbortRecoveryJvmTest {

    private val temps = mutableListOf<File>()
    private val real = JvmGitRepository()

    @AfterTest
    fun tearDown() {
        temps.forEach { it.deleteRecursively() }
    }

    private class CountingRepository(private val inner: JvmGitRepository) : GitRepository by inner {
        var aborts = 0
        override suspend fun abortMerge(config: GitConfig): Either<dev.stapler.stelekit.error.DomainError.GitError, Unit> {
            aborts++
            return inner.abortMerge(config)
        }
    }

    /** Fails every byte write, to prove the abort refuses to reset without a verified copy. */
    private class FailingFileSystem : FakeFileSystem() {
        override fun writeFileBytes(path: String, data: ByteArray) = false
    }

    private class Fixture(val dir: File, val config: GitConfig, val repository: CountingRepository, val fileSystem: FakeFileSystem) {
        val service: GitSyncService by lazy {
            GitSyncService(
                gitRepository = repository,
                graphLoader = GraphLoader(fileSystem, InMemoryPageRepository(), InMemoryBlockRepository()),
                graphWriter = GraphWriter(fileSystem),
                editLock = EditLock(),
                configRepository = StubConfigRepository(config.right()),
                networkMonitor = NetworkMonitor(),
                fileSystem = fileSystem,
                wallClockMs = { 1_700_000_000_000L },
            )
        }

        fun read(path: String) = File(dir, path).readBytes()
        fun write(path: String, bytes: ByteArray) = File(dir, path).writeBytes(bytes)
    }

    /** A clone in MERGING with `journal.md` conflicted and [tracked] files committed and unmodified. */
    private suspend fun mergingClone(tracked: List<String>, fileSystem: FakeFileSystem = FakeFileSystem()): Fixture {
        val origin = BareOriginFixtures.createBareOrigin("stelekit_abort_origin_", "main").also { temps += it }
        tracked.forEach { BareOriginFixtures.pushCommit(origin, "main", it, "base of $it\n", "add $it") }
        val dest = createTempDirectory("stelekit_abort_clone_").toFile().also { temps += it }
        val dir = File(dest, "repo")
        assertTrue(real.clone(origin.absolutePath, RepoRoot(dir.absolutePath), GitAuth.None, onProgress = {}).isRight())
        Git.open(dir).use { BareOriginFixtures.setIdentity(it) }
        val config = GitConfig(graphId = "g", repoRoot = RepoRoot(dir.absolutePath), wikiSubdir = null, remoteBranch = "main", authType = GitAuthType.NONE)

        File(dir, "journal.md").writeText("local\n")
        Git.open(dir).use { it.add().addFilepattern(".").call(); it.commit().setMessage("local").call() }
        BareOriginFixtures.pushCommit(origin, "main", "journal.md", "remote\n", "remote")
        assertTrue(real.fetch(config).isRight())
        val merge = real.merge(config)
        assertTrue(merge.getOrNull()?.hasConflicts == true, "expected a conflict, got $merge")
        Git.open(dir).use { assertEquals(RepositoryState.MERGING, it.repository.repositoryState) }
        return Fixture(dir, config, CountingRepository(real), fileSystem)
    }

    private fun Fixture.state() = Git.open(dir).use { it.repository.repositoryState }

    @Test
    fun `conflicted paths only abort straight away with no snapshot and no prompt`() = runTest {
        val f = mergingClone(listOf("notes.md"))
        var prompted = false

        val result = f.service.abortActiveMerge("g") { prompted = true; true }

        assertTrue(result.isRight(), "abort failed: $result")
        assertFalse(prompted)
        assertEquals(1, f.repository.aborts)
        assertEquals(RepositoryState.SAFE, f.state())
        assertEquals(emptyList(), f.fileSystem.listFiles("~/.stelekit/git-abort-recovery/g"))
        f.service.shutdown()
    }

    @Test
    fun `a modified non-conflicted tracked file is snapshotted confirmed and restored byte for byte`() = runTest {
        val f = mergingClone(listOf("notes.md"))
        val mine = "my unrelated edit é\n".encodeToByteArray()
        f.write("notes.md", mine)
        var shown: AbortPreservation? = null

        val result = f.service.abortActiveMerge("g") { shown = it; true }

        assertTrue(result.isRight(), "abort failed: $result")
        assertEquals(listOf("notes.md"), shown?.paths)
        assertEquals(RepositoryState.SAFE, f.state())
        assertContentEquals(mine, f.read("notes.md"))
        assertEquals("local\n", f.read("journal.md").decodeToString(), "the conflicted file is reset to the pre-merge content")
        assertContentEquals(mine, f.fileSystem.readFileBytes("${shown?.recoveryDir}/notes.md"), "the recovery copy is kept")
        f.service.shutdown()
    }

    @Test
    fun `a snapshot write failure refuses the reset and leaves the tree untouched`() = runTest {
        val f = mergingClone(listOf("notes.md"), FailingFileSystem())
        val mine = "do not lose me\n".encodeToByteArray()
        f.write("notes.md", mine)

        val result = f.service.abortActiveMerge("g") { true }

        assertTrue(result.isLeft())
        assertEquals(0, f.repository.aborts)
        assertEquals(RepositoryState.MERGING, f.state())
        assertContentEquals(mine, f.read("notes.md"))
        f.service.shutdown()
    }

    @Test
    fun `cancelling the confirmation leaves the merge in place`() = runTest {
        val f = mergingClone(listOf("notes.md"))
        val mine = "keep\n".encodeToByteArray()
        f.write("notes.md", mine)

        val result = f.service.abortActiveMerge("g") { false }

        assertTrue(result.isLeft())
        assertEquals(0, f.repository.aborts)
        assertEquals(RepositoryState.MERGING, f.state())
        assertContentEquals(mine, f.read("notes.md"))
        f.service.shutdown()
    }

    @Test
    fun `any set of edited non-conflicted files is byte-identical after the abort`() = runTest {
        val names = listOf("a.md", "b.md", "c.md")
        val random = Random(SEED)
        repeat(ITERATIONS) {
            val contents = List(random.nextInt(1, names.size + 1)) { random.nextBytes(random.nextInt(0, 65)) }
            val f = mergingClone(names)
            val edited = contents.mapIndexed { i, bytes -> names[i] to bytes }.toMap()
            edited.forEach { (name, bytes) -> f.write(name, bytes) }

            val result = f.service.abortActiveMerge("g") { true }

            assertTrue(result.isRight(), "abort failed: $result")
            edited.forEach { (name, bytes) -> assertContentEquals(bytes, f.read(name), name) }
            assertEquals(RepositoryState.SAFE, f.state())
            f.service.shutdown()
        }
    }

    private companion object {
        const val SEED = 24L
        const val ITERATIONS = 6
    }
}
