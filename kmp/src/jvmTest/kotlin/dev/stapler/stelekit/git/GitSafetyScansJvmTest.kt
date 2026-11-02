// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.BareOriginFixtures
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The JGit side of the repository-state, marker and mass-change guards, against real temp repositories. */
class GitSafetyScansJvmTest {

    private val temps = mutableListOf<File>()
    private val repository = JvmGitRepository()
    private val markered = "- mine\n<<<<<<< HEAD\n- a\n=======\n- b\n>>>>>>> c7d739709\n"

    @AfterTest
    fun tearDown() {
        temps.forEach { it.deleteRecursively() }
    }

    private class Clone(val dir: File, val config: GitConfig)

    private suspend fun clone(): Clone {
        val origin = BareOriginFixtures.createBareOrigin("stelekit_origin_", "main").also { temps += it }
        val dest = createTempDirectory("stelekit_clone_").toFile().also { temps += it }
        val dir = File(dest, "repo")
        assertTrue(repository.clone(origin.absolutePath, RepoRoot(dir.absolutePath), GitAuth.None, onProgress = {}).isRight())
        Git.open(dir).use { BareOriginFixtures.setIdentity(it) }
        val config = GitConfig(graphId = "g", repoRoot = RepoRoot(dir.absolutePath), wikiSubdir = null, remoteBranch = "main", authType = GitAuthType.NONE)
        return Clone(dir, config)
    }

    private fun Clone.write(path: String, content: String) = File(dir, path).also { it.parentFile.mkdirs() }.writeText(content)
    private fun Clone.stageAll() = Git.open(dir).use { it.add().addFilepattern(".").call(); it.add().setUpdate(true).addFilepattern(".").call() }
    private fun Clone.commit(msg: String) = Git.open(dir).use { it.commit().setMessage(msg).call() }
    private fun Clone.push() = Git.open(dir).use { it.push().setRemote("origin").add("refs/heads/main:refs/heads/main").call() }

    @Test
    fun `a clean repository is safe and MERGE_HEAD makes it unsafe`() = runTest {
        val c = clone()
        assertEquals(RepositoryHealth.Clean, repository.repositoryHealth(c.config))

        val head = Git.open(c.dir).use { it.repository.resolve("HEAD").name }
        File(c.dir, ".git/MERGE_HEAD").writeText("$head\n")

        val health = repository.repositoryHealth(c.config)
        assertFalse(health.safe)
        assertEquals("MERGING_RESOLVED", health.state)
    }

    @Test
    fun `staged added markers are found and fenced or clean files are not`() = runTest {
        val c = clone()
        c.write("journals/a.md", markered)
        c.write("pages/doc.md", "```\n<<<<<<< HEAD\n>>>>>>> x\n```\n")
        c.write("pages/clean.md", "- fine\n")
        c.stageAll()

        val scan = repository.scanForConflictMarkers(c.config, GuardScope.Staged)

        assertEquals(MarkerScan.Found(listOf("journals/a.md")), scan)
    }

    @Test
    fun `markers in any text file extension are found, not only markdown`() = runTest {
        val c = clone()
        c.write("notes/todo.txt", markered)
        c.stageAll()

        assertEquals(MarkerScan.Found(listOf("notes/todo.txt")), repository.scanForConflictMarkers(c.config, GuardScope.Staged))
    }

    @Test
    fun `binary files are skipped`() = runTest {
        val c = clone()
        File(c.dir, "assets").mkdirs()
        File(c.dir, "assets/x.bin").writeBytes(byteArrayOf(0, 1, 2) + markered.toByteArray() + byteArrayOf(0))
        c.stageAll()

        assertEquals(MarkerScan.Clean, repository.scanForConflictMarkers(c.config, GuardScope.Staged))
    }

    @Test
    fun `an oversize file fails closed`() = runTest {
        val c = clone()
        c.write("big.md", "x".repeat((MARKER_SCAN_FILE_CAP_BYTES + 1).toInt()))
        c.stageAll()

        assertIs<MarkerScan.Incomplete>(repository.scanForConflictMarkers(c.config, GuardScope.Staged))
    }

    @Test
    fun `net push scan finds a marker in an existing local commit`() = runTest {
        val c = clone()
        c.write("journals/a.md", markered)
        c.stageAll()
        c.commit("with markers")

        assertEquals(MarkerScan.Found(listOf("journals/a.md")), repository.scanForConflictMarkers(c.config, GuardScope.NetPush))
        assertEquals(MarkerScan.Clean, repository.scanForConflictMarkers(c.config, GuardScope.Staged))
    }

    @Test
    fun `a marker added then removed across two local commits is allowed by the net diff`() = runTest {
        val c = clone()
        c.write("journals/a.md", markered)
        c.stageAll()
        c.commit("with markers")
        c.write("journals/a.md", "- resolved\n")
        c.stageAll()
        c.commit("resolved")

        assertEquals(MarkerScan.Clean, repository.scanForConflictMarkers(c.config, GuardScope.NetPush))
    }

    @Test
    fun `net push scan is skipped when no remote-tracking ref exists`() = runTest {
        val c = clone()
        val noRemote = c.config.copy(remoteBranch = "does-not-exist")
        c.write("journals/a.md", markered)
        c.stageAll()
        c.commit("with markers")

        assertEquals(MarkerScan.Clean, repository.scanForConflictMarkers(noRemote, GuardScope.NetPush))
        assertEquals(DeletionSummary.None, repository.deletionSummary(noRemote, GuardScope.NetPush))
    }

    @Test
    fun `staged and net push deletions are counted against the previous tree`() = runTest {
        val c = clone()
        (1..30).forEach { c.write("journals/d$it.md", "- $it\n") }
        c.stageAll()
        c.commit("add 30")
        c.push()
        (1..25).forEach { assertTrue(File(c.dir, "journals/d$it.md").delete()) }
        c.stageAll()

        val staged = repository.deletionSummary(c.config, GuardScope.Staged)
        assertEquals(25, staged.deletedPaths.size)
        assertEquals(31, staged.previousTreeSize)

        c.commit("delete 25")
        val net = repository.deletionSummary(c.config, GuardScope.NetPush)
        assertEquals(25, net.deletedPaths.size)
        assertEquals(31, net.previousTreeSize)
        assertTrue(MassChangeThresholds().evaluate(net) != null, "25 of 31 files is a mass deletion")
    }

    @Test
    fun `a removed journals directory is blocked by the journal limit`() = runTest {
        val c = clone()
        (1..40).forEach { c.write("logseq/journals/d$it.md", "- $it\n") }
        (1..2000).forEach { c.write("logseq/pages/p$it.md", "- $it\n") }
        c.stageAll()
        c.commit("seed")
        c.push()
        File(c.dir, "logseq/journals").deleteRecursively()
        c.stageAll()

        val staged = repository.deletionSummary(c.config, GuardScope.Staged)

        assertEquals(40, staged.deletedPaths.size)
        assertEquals(40, MassChangeThresholds().evaluate(staged)?.deleted)
    }

    @Test
    fun `first sync preview reports ahead behind remote branches local commits and deletions`() = runTest {
        val c = clone()
        c.write("journals/a.md", "- a\n")
        c.stageAll()
        c.commit("local one")
        c.write("journals/b.md", "- b\n")
        c.stageAll()
        c.commit("local two")

        val preview = (repository.firstSyncPreview(c.config) as arrow.core.Either.Right).value

        assertEquals("origin/main", preview.syncRef)
        assertEquals(2, preview.ahead)
        assertEquals(0, preview.behind)
        assertEquals(listOf("local two", "local one"), preview.localOnlyCommits.map { it.subject })
        assertEquals(listOf(PreviewRemoteBranch("main", isSyncBranch = true)), preview.remoteBranches)
        assertTrue(preview.remoteBranchesLive)
        assertEquals(0, preview.filesToDelete)
    }

    @Test
    fun `first sync preview flags stray remote branches`() = runTest {
        val c = clone()
        Git.open(c.dir).use { it.push().setRemote("origin").add("refs/heads/main:refs/heads/stray").call() }

        val preview = (repository.firstSyncPreview(c.config) as arrow.core.Either.Right).value

        assertEquals(
            listOf(PreviewRemoteBranch("main", true), PreviewRemoteBranch("stray", false)),
            preview.remoteBranches,
        )
    }

    @Test
    fun `an unreadable repository fails closed for health and markers and reports no deletions`() = runTest {
        val notARepo = createTempDirectory("stelekit_not_a_repo_").toFile().also { temps += it }
        val config = GitConfig(graphId = "g", repoRoot = RepoRoot(notARepo.absolutePath), wikiSubdir = null, remoteBranch = "main", authType = GitAuthType.NONE)

        assertEquals(RepositoryHealth(safe = false, state = "UNREADABLE"), repository.repositoryHealth(config))
        assertIs<MarkerScan.Incomplete>(repository.scanForConflictMarkers(config, GuardScope.Staged))
        assertEquals(DeletionSummary.None, repository.deletionSummary(config, GuardScope.Staged))
        assertTrue(repository.firstSyncPreview(config).isLeft())
    }

    @Test
    fun `first sync preview falls back to the known remote-tracking branches when ls-remote fails`() = runTest {
        val c = clone()
        val gone = File(c.dir.parentFile, "elsewhere")
        Git.open(c.dir).use {
            it.repository.config.setString("remote", "origin", "url", gone.absolutePath)
            it.repository.config.save()
        }

        val preview = (repository.firstSyncPreview(c.config) as arrow.core.Either.Right).value

        assertFalse(preview.remoteBranchesLive)
        assertEquals(listOf(PreviewRemoteBranch("main", isSyncBranch = true)), preview.remoteBranches)
    }

    @Test
    fun `first sync preview without a remote-tracking ref has unknown ahead and behind`() = runTest {
        val c = clone()

        val preview = (repository.firstSyncPreview(c.config.copy(remoteBranch = "missing")) as arrow.core.Either.Right).value

        assertEquals(null, preview.ahead)
        assertEquals(null, preview.behind)
        assertEquals(0, preview.filesToDelete)
    }
}
