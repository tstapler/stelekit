// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.git.JvmGitRepository
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Detection for raw binaries that reached the remote outside the guard (a plain `git add` elsewhere). */
class LfsPostPushAuditTest {
    private val rules = "assets/** filter=lfs\n"

    @Test
    fun audit_should_ReportARawBlob_When_ItWasCommittedAtATrackedPathOutsideTheGuard() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.write("pages/a.md", "# a")
            f.add(".")
            val tip = f.commit("bypass").id

            val findings = LfsPostPushAudit.audit(f.repo, previousRemoteTip = null, pushedTip = tip)

            assertEquals(listOf("assets/a.bin"), findings.map { it.path })
            assertEquals(LfsViolationReason.RawBinaryPushed, findings.single().reason)
        }
    }

    @Test
    fun audit_should_ScanOnlyTheCommitsAfterThePreviousRemoteTip() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/old.bin", LfsRepoFixture.PNG)
            f.add(".")
            val alreadyRemote = f.commit("already pushed earlier").id
            f.write("assets/p.png", LfsRepoFixture.POINTER)
            f.write("pages/new.md", "# new")
            f.add(".")
            val tip = f.commit("this push").id

            assertTrue(LfsPostPushAudit.audit(f.repo, alreadyRemote, tip).isEmpty(), "old.bin predates the pushed range")
            assertEquals(listOf("assets/old.bin"), LfsPostPushAudit.audit(f.repo, null, tip).map { it.path })
        }
    }

    @Test
    fun audit_should_NotBlameAMergeCommitForTheOtherParentsFiles() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("pages/a.md", "# a")
            f.add(".")
            f.commit("base")
            f.git.checkout().setCreateBranch(true).setName("side").call()
            f.write("assets/theirs.bin", LfsRepoFixture.PNG)
            f.add("assets/theirs.bin")
            val theirs = f.commit("already on the remote").id
            f.git.checkout().setName("main").call()
            f.write("pages/b.md", "# b")
            f.add("pages/b.md")
            f.commit("my note")
            val merge = f.git.merge().include(theirs)
                .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF).call().newHead

            val findings = LfsPostPushAudit.audit(f.repo, previousRemoteTip = theirs, pushedTip = merge)

            assertTrue(findings.isEmpty(), "theirs.bin was pushed by someone else, not by this push: $findings")
        }
    }

    @Test
    fun audit_should_ReportNothing_When_TrackedPathsHoldPointersAndRawFilesAreUntracked() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write(".gitattributes", rules)
            f.write("assets/p.png", LfsRepoFixture.POINTER)
            f.write("assets/empty.bin", ByteArray(0))
            f.write("pages/photo.png", LfsRepoFixture.PNG)
            f.add(".")
            val tip = f.commit("clean").id

            assertTrue(LfsPostPushAudit.audit(f.repo, null, tip).isEmpty())
        }
    }

    @Test
    fun audit_should_ReportNothing_When_NoRuleTracksAnything() = runBlocking {
        LfsRepoFixture().use { f ->
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.add(".")
            val tip = f.commit("no rules").id

            assertTrue(LfsPostPushAudit.audit(f.repo, null, tip).isEmpty())
        }
    }

    @Test
    fun push_should_ExposeTheAuditOnTheRepository_When_ABypassCommitIsPushed() = runBlocking {
        val origin = createTempDirectory("stelekit_lfs_audit_origin_").toFile()
        Git.init().setBare(true).setDirectory(origin).setInitialBranch("main").call().close()
        try {
            LfsRepoFixture().use { f ->
                f.git.remoteAdd().setName("origin").setUri(URIish(origin.absolutePath)).call()
                // A clone has this refspec, which is what makes a push advance refs/remotes/origin/main.
                f.repo.config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                f.repo.config.save()
                f.write(".gitattributes", rules)
                f.write("pages/a.md", "# a")
                f.add(".")
                f.commit("base")
                val config = GitConfig(graphId = "g", repoRoot = RepoRoot(f.dir.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
                val repository = JvmGitRepository()
                assertTrue(repository.push(config).isRight())
                assertTrue(repository.lastPushLfsViolations.isEmpty())

                f.write("assets/sneaky.bin", LfsRepoFixture.PNG)
                f.add("assets/sneaky.bin")
                f.commit("outside the guard")
                val result = repository.push(config)

                assertTrue(result.isRight(), "the push itself still succeeds: $result")
                assertEquals(listOf("assets/sneaky.bin"), repository.lastPushLfsViolations.map { it.path })
                assertTrue(repository.push(config).isRight())
                assertTrue(repository.lastPushLfsViolations.isEmpty(), "the audit describes only the latest push")
            }
        } finally {
            File(origin.path).deleteRecursively()
        }
    }
}
