// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the diagnostics export tells a user about a clone's refs (redaction is covered by `GitRefDiagnosticsRedactionTest`). */
class GitRefDiagnosticsTest {
    private val tempDirs = mutableListOf<File>()
    private val openGits = mutableListOf<Git>()

    @AfterTest
    fun cleanUp() {
        openGits.forEach { it.close() }
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("git-refs-test").toFile().also { tempDirs += it }

    private fun initRepo(): Git =
        Git.init().setDirectory(tempDir()).setInitialBranch("main").call().also { openGits += it }

    private fun Git.commitFile(name: String, content: String) {
        File(repository.workTree, name).writeText(content)
        add().addFilepattern(name).call()
        commit().setMessage("add $name").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").setSign(false).call()
    }

    private fun cloneOf(origin: Git): Git = Git.cloneRepository()
        .setURI(origin.repository.directory.absolutePath)
        .setDirectory(tempDir())
        .setBranch("main")
        .call().also { openGits += it }

    private fun config(remoteBranch: String = "main") = GitConfig(
        graphId = "g", repoRoot = RepoRoot("/r"), wikiSubdir = null, remoteBranch = remoteBranch, authType = GitAuthType.NONE,
    )

    @Test
    fun cleanCloneReportsResolvedRefTrackingAndLiveRemoteHeads() {
        val origin = initRepo().also { it.commitFile("a", "1") }
        val clone = cloneOf(origin)

        val out = describeGitRefs(clone, config()) { }

        assertContains(out, "branch=main fullBranch=refs/heads/main")
        assertContains(out, "isShallow=false")
        assertContains(out, "exactRef(refs/remotes/origin/main)=")
        assertFalse("NOT RESOLVED" in out, out)
        assertContains(out, "tracking(main): ahead=0 behind=0 of refs/remotes/origin/main")
        assertContains(out, "remote has refs/heads/main: true")
        assertContains(out, "refs/remotes/origin/main ")
    }

    @Test
    fun trackingStatusReflectsLocalAndRemoteCommits() {
        val origin = initRepo().also { it.commitFile("a", "1") }
        val clone = cloneOf(origin)
        clone.commitFile("local", "x")
        origin.commitFile("remote-1", "y")
        origin.commitFile("remote-2", "z")
        clone.fetch().setRemote("origin").call()

        val out = describeGitRefs(clone, config()) { }

        assertContains(out, "tracking(main): ahead=1 behind=2 of refs/remotes/origin/main")
    }

    @Test
    fun missingRemoteBranchIsCalledOutBothLocallyAndOnTheRemote() {
        val origin = initRepo().also { it.commitFile("a", "1") }
        val clone = cloneOf(origin)

        val out = describeGitRefs(clone, config(remoteBranch = "develop")) { }

        assertContains(out, "exactRef(refs/remotes/origin/develop)=NOT RESOLVED")
        assertContains(out, "remote has refs/heads/develop: false")
        assertFalse("tracking(" in out, out)
    }

    @Test
    fun unsetRemoteUrlAndUnreachableRemoteDoNotHideOtherSections() {
        val repo = initRepo().also { it.commitFile("a", "1") }

        val out = describeGitRefs(repo, config()) { }

        assertContains(out, "origin.url=<unset>")
        assertContains(out, "-- live remote heads (ls-remote)")
        assertTrue("<failed:" in out, out)
        assertContains(out, "-- local branches")
        assertContains(out, "refs/heads/main ")
    }

    @Test
    fun emptyRepoHasNoHeadCommit() {
        val repo = initRepo()

        val out = describeGitRefs(repo, config()) { }

        assertContains(out, "head=<none>")
    }

    @Test
    fun lsRemoteConfigurationHookIsInvoked() {
        val origin = initRepo().also { it.commitFile("a", "1") }
        val clone = cloneOf(origin)
        var configured = 0

        describeGitRefs(clone, config()) { configured++ }

        assertTrue(configured == 1, "configureLsRemote called $configured times")
    }
}
