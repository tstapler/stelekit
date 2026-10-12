// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The repository-level backstop, against real JGit indexes. */
class LfsIndexVerifierTest {
    private val rules = "assets/** filter=lfs\n"

    private fun fixture(attributes: String? = rules): LfsRepoFixture =
        LfsRepoFixture().also { f -> attributes?.let { f.write(".gitattributes", it) } }

    @Test
    fun stagedViolations_should_ReportARawBlob_When_TrackedPathIsStagedRaw() = runBlocking {
        fixture().use { f ->
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.write("pages/a.md", "# a")
            f.add(".")

            val violations = LfsIndexVerifier(f.repo).stagedViolations()

            assertEquals(listOf("assets/a.bin"), violations.map { it.path })
            assertEquals(LfsViolationReason.RawBinary, violations.single().reason)
            assertEquals(".gitattributes:1", violations.single().ruleSource)
        }
    }

    @Test
    fun stagedViolations_should_PassPointersAndEmptyFiles() = runBlocking {
        fixture().use { f ->
            f.write("assets/p.png", LfsRepoFixture.POINTER)
            f.write("assets/empty.bin", ByteArray(0))
            f.add(".")

            assertTrue(LfsIndexVerifier(f.repo).stagedViolations().isEmpty())
        }
    }

    @Test
    fun stagedViolations_should_ReportACorruptPointer_When_PointerHasCrlf() = runBlocking {
        fixture().use { f ->
            f.write("assets/p.png", LfsRepoFixture.POINTER.decodeToString().replace("\n", "\r\n"))
            f.add(".")

            assertEquals(LfsViolationReason.CorruptPointer, LfsIndexVerifier(f.repo).stagedViolations().single().reason)
        }
    }

    @Test
    fun stagedViolations_should_HonourNestedAttributes_When_RuleLivesBesideTheFile() = runBlocking {
        fixture(attributes = null).use { f ->
            f.write("img/.gitattributes", "*.png filter=lfs\n")
            f.write("img/x.png", LfsRepoFixture.PNG)
            f.write("other/y.png", LfsRepoFixture.PNG)
            f.add(".")

            assertEquals(listOf("img/x.png"), LfsIndexVerifier(f.repo).stagedViolations().map { it.path })
        }
    }

    @Test
    fun stagedViolations_should_ApplyAGitattributesStagedInTheSameCommit() = runBlocking {
        fixture(attributes = null).use { f ->
            f.write("x.png", LfsRepoFixture.PNG)
            f.write(".gitattributes", "*.png filter=lfs\n")
            f.add(".")

            assertEquals(listOf("x.png"), LfsIndexVerifier(f.repo).stagedViolations().map { it.path })
        }
    }

    @Test
    fun stagedViolations_should_ReportNothing_When_NoRuleTracksAnything() = runBlocking {
        fixture(attributes = null).use { f ->
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.add(".")

            assertTrue(LfsIndexVerifier(f.repo).stagedViolations().isEmpty())
        }
    }

    @Test
    fun stagedViolations_should_IgnoreALegacyRawBlobAlreadyInHead_When_ItIsUnchanged() = runBlocking {
        fixture(attributes = null).use { f ->
            f.write("assets/old.bin", LfsRepoFixture.PNG)
            f.add(".")
            f.commit("legacy raw history")
            f.write(".gitattributes", rules)
            f.write("pages/new.md", "# new")
            f.add(".")

            assertTrue(LfsIndexVerifier(f.repo).stagedViolations().isEmpty(), "history written before the rule is not a change")
        }
    }

    /** Leaves the repository mid-merge: HEAD on main (adds pages/b.md), MERGE_HEAD on side (adds the raw assets/r.bin). */
    private fun startMergeThatBringsInARawRemoteBlob(f: LfsRepoFixture) {
        f.write(".gitattributes", rules)
        f.write("pages/a.md", "# a")
        f.add(".")
        f.commit("base")
        f.git.checkout().setCreateBranch(true).setName("side").call()
        f.write("assets/r.bin", LfsRepoFixture.PNG)
        f.add("assets/r.bin")
        f.commit("someone else pushed a raw file")
        f.git.checkout().setName("main").call()
        f.write("pages/b.md", "# b")
        f.add("pages/b.md")
        f.commit("my note")
        f.git.merge().include(f.repo.resolve("side")).setCommit(false)
            .setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF).call()
    }

    @Test
    fun stagedViolations_should_IgnoreContentThatCameFromMergeHead_DuringAMerge() = runBlocking {
        fixture(attributes = null).use { f ->
            startMergeThatBringsInARawRemoteBlob(f)
            assertTrue("assets/r.bin" in f.stagedPaths(), "the merge staged the other side's raw file")

            assertTrue(LfsIndexVerifier(f.repo).stagedViolations().isEmpty(), "the remote's file is already remote, not ours to judge")
        }
    }

    @Test
    fun stagedViolations_should_StillJudgeNewlyAuthoredContent_DuringAMerge() = runBlocking {
        fixture(attributes = null).use { f ->
            startMergeThatBringsInARawRemoteBlob(f)
            f.write("assets/mine.bin", LfsRepoFixture.PNG)
            f.add("assets/mine.bin")

            assertEquals(listOf("assets/mine.bin"), LfsIndexVerifier(f.repo).stagedViolations().map { it.path })
        }
    }

    @Test
    fun verifyAndUnstage_should_NotRevertTheRemotesFile_When_CompletingAMerge() = runBlocking {
        fixture(attributes = null).use { f ->
            startMergeThatBringsInARawRemoteBlob(f)

            assertTrue(LfsIndexVerifier(f.repo).verifyAndUnstage(f.git).isEmpty())
            f.commit("merge")

            assertTrue("assets/r.bin" in f.headBlobs(), "the merge commit keeps the remote's file")
        }
    }

    @Test
    fun verifyAndUnstage_should_RestoreHeadsVersion_When_AModifiedTrackedFileIsStagedRaw() = runBlocking {
        fixture().use { f ->
            f.write("assets/a.bin", LfsRepoFixture.POINTER)
            f.add(".")
            f.commit("pointer in history")
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.write("pages/a.md", "# a")
            f.add(".")

            val violations = LfsIndexVerifier(f.repo).verifyAndUnstage(f.git)

            assertEquals(listOf("assets/a.bin"), violations.map { it.path })
            assertEquals(setOf("pages/a.md"), f.stagedPaths(), "only the safe file stays staged")
            assertContentEquals(LfsRepoFixture.PNG, java.io.File(f.dir, "assets/a.bin").readBytes(), "the working file is untouched")
        }
    }

    @Test
    fun verifyAndUnstage_should_DropTheEntry_When_BranchIsUnborn() = runBlocking {
        fixture().use { f ->
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.write("pages/a.md", "# a")
            f.add(".")

            LfsIndexVerifier(f.repo).verifyAndUnstage(f.git)

            assertEquals(setOf(".gitattributes", "pages/a.md"), f.stagedPaths())
        }
    }

    @Test
    fun refuseOnViolation_should_ReturnTheTypedError_AndUnstage_When_RawBlobIsStaged() = runBlocking {
        fixture().use { f ->
            f.write("assets/a.bin", LfsRepoFixture.PNG)
            f.add(".")

            val error = LfsIndexVerifier(f.repo).refuseOnViolation(f.git)

            assertNotNull(error)
            assertEquals(listOf("assets/a.bin"), error.paths)
            assertTrue("assets/a.bin" !in f.stagedPaths())
        }
    }

    @Test
    fun refuseOnViolation_should_ReturnNull_When_NothingViolates() = runBlocking {
        fixture().use { f ->
            f.write("assets/p.png", LfsRepoFixture.POINTER)
            f.write("pages/a.md", "# a")
            f.add(".")

            assertNull(LfsIndexVerifier(f.repo).refuseOnViolation(f.git))
        }
    }
}
