// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.right
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.random.Random
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 2.4e: the closed-app runner fetches and nothing else, so no tree state can change under it. */
class ColdSyncRunnerTreeJvmTest {

    private val temps = mutableListOf<File>()
    private val repository = JvmGitRepository()

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    @AfterTest
    fun tearDown() {
        temps.forEach { it.deleteRecursively() }
    }

    private class Setup(val origin: File, val dir: File, val config: GitConfig)

    private suspend fun clone(tracked: List<String>): Setup {
        val origin = BareOriginFixtures.createBareOrigin("stelekit_cold_origin_", "main").also { temps += it }
        tracked.forEach { BareOriginFixtures.pushCommit(origin, "main", it, "base of $it\n", "add $it") }
        val dir = File(createTempDirectory("stelekit_cold_clone_").toFile().also { temps += it }, "repo")
        assertTrue(repository.clone(origin.absolutePath, RepoRoot(dir.absolutePath), GitAuth.None, onProgress = {}).isRight())
        Git.open(dir).use { BareOriginFixtures.setIdentity(it) }
        val config = GitConfig(graphId = "g", repoRoot = RepoRoot(dir.absolutePath), wikiSubdir = null, remoteBranch = "main", authType = GitAuthType.NONE)
        return Setup(origin, dir, config)
    }

    private fun tree(dir: File): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile && !it.path.contains("${File.separator}.git${File.separator}") }
            .associate { it.relativeTo(dir).path to it.readBytes().toList() }

    private fun head(dir: File) = Git.open(dir).use { it.repository.resolve("HEAD").name }

    private suspend fun runCold(s: Setup, staleness: SyncStalenessStore): ColdSyncOutcome =
        ColdSyncRunner(repository, StubConfigRepository(s.config.right()), staleness, NamedLocks()) { 5L }.run("g")

    @Test
    fun `for any generated dirty set the working tree and HEAD are byte-identical after the run`() = runTest {
        val names = listOf("a.md", "b.md", "c.md")
        val random = Random(SEED)
        repeat(ITERATIONS) {
            val edits = List(random.nextInt(0, names.size + 1)) { random.nextBytes(random.nextInt(0, 49)) }
            val untracked = random.nextInt(0, 3)
            val s = clone(names)
            BareOriginFixtures.pushCommit(s.origin, "main", "remote.md", "new upstream\n", "upstream")
            edits.forEachIndexed { i, bytes -> File(s.dir, names[i]).writeBytes(bytes) }
            repeat(untracked) { File(s.dir, "untracked$it.md").writeText("scratch $it\n") }
            val before = tree(s.dir)
            val headBefore = head(s.dir)

            val outcome = runCold(s, SyncStalenessStore(MapSettings()))

            assertTrue(assertIs<ColdSyncOutcome.Fetched>(outcome).behindCount >= 1)
            assertEquals(before, tree(s.dir))
            assertEquals(headBefore, head(s.dir))
        }
    }

    @Test
    fun `a tree in MERGING is left exactly as it was`() = runTest {
        val s = clone(listOf("notes.md"))
        File(s.dir, "journal.md").writeText("local\n")
        Git.open(s.dir).use { it.add().addFilepattern(".").call(); it.commit().setMessage("local").call() }
        BareOriginFixtures.pushCommit(s.origin, "main", "journal.md", "remote\n", "remote")
        assertTrue(repository.fetch(s.config).isRight())
        assertTrue(repository.merge(s.config).getOrNull()?.hasConflicts == true)
        val before = tree(s.dir)
        val state = Git.open(s.dir).use { it.repository.repositoryState }

        val outcome = runCold(s, SyncStalenessStore(MapSettings()))

        assertIs<ColdSyncOutcome.Fetched>(outcome)
        assertEquals(before, tree(s.dir))
        assertEquals(state, Git.open(s.dir).use { it.repository.repositoryState })
    }

    private companion object {
        const val SEED = 7L
        const val ITERATIONS = 6
    }
}
