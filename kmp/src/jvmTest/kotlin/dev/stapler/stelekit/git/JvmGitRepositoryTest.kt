// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.HunkResolution
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git

/**
 * Desktop non-regression smoke test (Phase 7, android-git-saf-shadow-worktree plan) — exercises
 * [JvmGitRepository] end to end against a real temp-dir repo. `kmp/src/jvmTest` had zero coverage
 * of the Desktop git implementation itself (only credential/device-flow helpers around it); this
 * closes that gap while proving Desktop's plain-`repoRoot` git path is unaffected by the
 * Android SAF shadow-worktree work landing in parallel — `JvmGitRepository` has no shadow/mapper
 * concept at all, by construction, so `config.repoRoot` is used as a real filesystem path
 * throughout.
 */
class JvmGitRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: JvmGitRepository
    private lateinit var config: GitConfig

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("stelekit_jvm_git_repo_test_").toFile()
        repository = JvmGitRepository()
        config = GitConfig(
            graphId = "test-graph",
            repoRoot = tempDir.absolutePath,
            wikiSubdir = null,
            authType = GitAuthType.NONE,
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `commit followed by status shows no local changes and log returns one entry`() = runTest {
        val initResult = repository.init(config.repoRoot)
        assertTrue(initResult.isRight(), "init failed: $initResult")

        // JvmGitRepository never sets an author/committer itself — it delegates to JGit's default
        // PersonIdent resolution (repo config, falling back to system properties). Set a
        // repo-local identity explicitly so this test is hermetic regardless of the running
        // machine's ~/.gitconfig.
        Git.open(File(config.repoRoot)).use { git ->
            val storedConfig = git.repository.config
            storedConfig.setString("user", null, "name", "Stelekit Test")
            storedConfig.setString("user", null, "email", "stelekit-test@example.com")
            storedConfig.save()
        }

        File(config.repoRoot, "journal.md").writeText("# Test journal entry\n")

        val stageResult = repository.stageSubdir(config)
        assertTrue(stageResult.isRight(), "stageSubdir failed: $stageResult")

        val commitResult = repository.commit(config, "Initial commit")
        assertTrue(commitResult.isRight(), "commit failed: $commitResult")

        val statusResult = repository.status(config)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        val status = (statusResult as Either.Right).value
        assertFalse(status.hasLocalChanges, "expected no local changes immediately after commit, got $status")

        val logResult = repository.log(config, maxCount = 10)
        assertTrue(logResult.isRight(), "log failed: $logResult")
        val commits = (logResult as Either.Right).value
        assertEquals(1, commits.size, "expected exactly one commit in the log")
        assertEquals("Initial commit", commits.single().shortMessage)

        // config.repoRoot is a plain real filesystem path throughout -- no shadow/mapper
        // indirection exists on Desktop (that's an Android-only concept in this project).
        assertEquals(tempDir.absolutePath, config.repoRoot)
    }

    private fun setIdentity(git: Git) {
        val storedConfig = git.repository.config
        storedConfig.setString("user", null, "name", "Stelekit Test")
        storedConfig.setString("user", null, "email", "stelekit-test@example.com")
        storedConfig.save()
    }

    /**
     * merge() must parse the real conflict-marker content JGit wrote directly into the working
     * tree into hunks (via [ConflictResolver.parseConflictFile]) for line-level resolution,
     * instead of always shipping an empty hunk list — the pre-existing gap the conflict-resolution
     * project closes. `JvmGitRepositoryTest` had zero merge-conflict coverage at all before this.
     *
     * Uses a single-bullet page (not raw unbulleted text) because merge() now re-derives `.md`
     * conflicts via the block-aware merge ([tryBlockAwareConflict]) before falling back to JGit's
     * raw line markers — see that function's doc — and the block serializer always canonicalizes
     * a block back out with a `- ` prefix, mirroring [LogseqPageSerializer]'s own on-save format.
     */
    @Test
    fun `merge conflict reports real conflict marker content parsed into hunks`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }
        val baseBranch = Git.open(File(config.repoRoot)).use { it.repository.branch }
        val mergeConfig = config.copy(remoteBranch = baseBranch)

        File(config.repoRoot, "conflict.md").writeText("- base\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_merge_origin_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        val originWorkDir = createTempDirectory("stelekit_jvm_git_merge_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "conflict.md").writeText("- remote version\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        File(config.repoRoot, "conflict.md").writeText("- local version\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "local change").isRight())

        assertTrue(repository.fetch(mergeConfig).isRight())
        val mergeResult = repository.merge(mergeConfig)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val merge = (mergeResult as Either.Right).value
        assertTrue(merge.hasConflicts)
        assertEquals(1, merge.conflicts.size)

        val conflict = merge.conflicts.single()
        assertEquals(1, conflict.hunks.size, "expected exactly one conflicting hunk")
        val hunk = conflict.hunks.single()
        assertEquals(listOf("- local version"), hunk.localLines)
        assertEquals(listOf("- remote version"), hunk.remoteLines)
        assertTrue(
            conflict.rawContent?.contains("<<<<<<<") == true,
            "ConflictFile.rawContent must carry the real conflict-marker content, got: ${conflict.rawContent}",
        )
    }

    /**
     * SAF/disk write-back end-to-end: the prior test proves conflict *detection* writes real
     * marker content where the app can read it; this proves conflict *resolution* writes the
     * final resolved content back to the real working-tree file and clears git's own conflicted
     * index state — the actual gap, since [dev.stapler.stelekit.git.GitSyncService.resolveConflicts]'s
     * hunk-resolution branch (`ConflictResolver().applyResolutions` → `fileSystem.writeFile` →
     * `markResolved` → `commit`) had no coverage against a real [GitRepository], only against
     * [dev.stapler.stelekit.git.GitSyncServiceTest]'s stub. Mirrors that branch's exact steps
     * directly against [JvmGitRepository] (JVM has no `FileSystem` write-back indirection to
     * exercise — `File(filePath).writeText(...)` IS the real write-back here, same as production).
     */
    @Test
    fun `resolving a hunk writes the final content to the real working-tree file and clears the conflict`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }
        val baseBranch = Git.open(File(config.repoRoot)).use { it.repository.branch }
        val mergeConfig = config.copy(remoteBranch = baseBranch)

        File(config.repoRoot, "conflict.md").writeText("- base\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_resolve_origin_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        val originWorkDir = createTempDirectory("stelekit_jvm_git_resolve_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "conflict.md").writeText("- remote version\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        File(config.repoRoot, "conflict.md").writeText("- local version\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "local change").isRight())

        assertTrue(repository.fetch(mergeConfig).isRight())
        val mergeResult = repository.merge(mergeConfig)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val conflict = (mergeResult as Either.Right).value.conflicts.single()
        val hunk = conflict.hunks.single()

        Git.open(File(config.repoRoot)).use {
            assertEquals(RepositoryState.MERGING, it.repository.repositoryState, "expected a genuine in-progress merge conflict")
        }

        // Mirrors GitSyncService.resolveConflicts()'s hunk-resolution branch exactly, against the
        // real repository instead of GitSyncServiceTest's StubGitRepository.
        val resolvedContent = ConflictResolver().applyResolutions(
            conflict.rawContent!!,
            listOf(hunk.copy(resolution = HunkResolution.AcceptLocal)),
        ).let { (it as Either.Right).value }
        File(conflict.filePath).writeText(resolvedContent)
        assertTrue(repository.markResolved(mergeConfig, conflict.filePath).isRight())
        val commitResult = repository.commit(mergeConfig, "resolve conflict")
        assertTrue(commitResult.isRight(), "commit failed: $commitResult")

        assertEquals(resolvedContent, File(config.repoRoot, "conflict.md").readText())
        Git.open(File(config.repoRoot)).use {
            assertEquals(
                RepositoryState.SAFE,
                it.repository.repositoryState,
                "expected the merge-conflict state to be cleared after resolve + commit",
            )
        }
        val statusResult = repository.status(mergeConfig)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        assertFalse((statusResult as Either.Right).value.hasLocalChanges, "expected a clean tree after the resolve commit")
    }

    /**
     * End-to-end check that [dev.stapler.stelekit.git.merge.findDuplicateBlockIds] reaches
     * [dev.stapler.stelekit.git.model.ConflictFile.duplicateBlockIds] through a real JGit merge —
     * and that an ordinary same-id conflict (both sides editing block "second", id `other`, into
     * different content) does NOT itself get flagged as a duplicate, alongside a genuine
     * independent one (local separately adds a new block reusing id `dup`).
     */
    @Test
    fun `merge surfaces a real duplicate block id without flagging the ordinary conflict's own two sides`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }
        val baseBranch = Git.open(File(config.repoRoot)).use { it.repository.branch }
        val mergeConfig = config.copy(remoteBranch = baseBranch)

        File(config.repoRoot, "dup.md").writeText("- first\n\tid:: dup\n- second\n\tid:: other\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_merge_origin3_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        // remote edits "second"'s content only, keeping its id.
        val originWorkDir = createTempDirectory("stelekit_jvm_git_merge_origin_work3_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "dup.md").writeText("- first\n\tid:: dup\n- second remote\n\tid:: other\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("edit second on remote").call()
            originGit.push().call()
        }

        // local edits "second"'s content too (a real conflict with remote's edit), and separately
        // adds a new block that happens to reuse "dup" — an independent, genuine duplicate.
        File(config.repoRoot, "dup.md").writeText(
            "- first\n\tid:: dup\n- second local\n\tid:: other\n- local extra\n\tid:: dup\n",
        )
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "edit second and add duplicate on local").isRight())

        assertTrue(repository.fetch(mergeConfig).isRight())
        val mergeResult = repository.merge(mergeConfig)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val merge = (mergeResult as Either.Right).value
        assertTrue(merge.hasConflicts, "expected a real conflict from both sides editing 'second' differently")

        val conflict = merge.conflicts.single()
        assertTrue(
            conflict.duplicateBlockIds.any { it.id == "dup" && it.occurrences >= 2 },
            "expected the genuine 'dup' duplicate to be surfaced, got: ${conflict.duplicateBlockIds}",
        )
        assertTrue(
            conflict.duplicateBlockIds.none { it.id == "other" },
            "the two sides of the ordinary 'second' conflict must not be reported as a duplicate: ${conflict.duplicateBlockIds}",
        )
    }

    /**
     * End-to-end (real JGit merge, not the pure [dev.stapler.stelekit.git.merge.BlockDiff3Test]
     * unit coverage) check that a one-sided reparent and an unrelated, non-adjacent edit reach
     * `merge()` cleanly through the real JGit path when a genuine two-sided-unchanged block (`C`)
     * separates them.
     *
     * Empirically found while writing this test (via an earlier, wrong version that put the edit
     * on the block immediately adjacent to the reparented one): a reparented block cannot itself
     * serve as an anchor, since [dev.stapler.stelekit.git.merge.BlockDiff3]'s key intentionally
     * includes nesting `level` — reparenting IS a content-relevant change, by design (see that
     * class's doc). With no unchanged block between a reparent and a nearby edit, both regions
     * collapse into one ungrouped span and conflict — the exact same outcome JGit's own line diff
     * already produces for that shape, not a regression. This test instead places an untouched
     * block between the two edits, which both algorithms treat as a valid split point.
     */
    @Test
    fun `merge auto-resolves a reparented block and a distant edit separated by an untouched block`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }
        val baseBranch = Git.open(File(config.repoRoot)).use { it.repository.branch }
        val mergeConfig = config.copy(remoteBranch = baseBranch)

        File(config.repoRoot, "page.md").writeText("- A\n- B\n- C\n- D\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_merge_origin2_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        // remote reparents B under A only; C and D untouched.
        val originWorkDir = createTempDirectory("stelekit_jvm_git_merge_origin_work2_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "page.md").writeText("- A\n\t- B\n- C\n- D\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("reparent B").call()
            originGit.push().call()
        }

        // local edits D only; A/B/C untouched — C separates the two edited regions.
        File(config.repoRoot, "page.md").writeText("- A\n- B\n- C\n- D edited\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "edit D").isRight())

        assertTrue(repository.fetch(mergeConfig).isRight())
        val mergeResult = repository.merge(mergeConfig)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val merge = (mergeResult as Either.Right).value
        assertFalse(
            merge.hasConflicts,
            "expected a reparent and a distant edit, separated by an untouched block, to auto-resolve: $merge",
        )

        val merged = File(config.repoRoot, "page.md").readText()
        assertTrue(merged.contains("\t- B"), "expected B reparented under A in merged content, got: $merged")
        assertTrue(merged.contains("- D edited"), "expected local's edit to D preserved, got: $merged")
    }

    /**
     * Regression test for a real, pre-existing production bug this project's testing surfaced:
     * JGit 7.3.0's `ResetCommand` never implemented `ResetType.MERGE`/`KEEP` at all (confirmed by
     * disassembling `ResetCommand.class` — its mode switch implements only SOFT/MIXED/HARD and
     * unconditionally throws `UnsupportedOperationException` for MERGE/KEEP). `abortMerge()` used
     * `ResetType.MERGE` on both platforms, so calling it always threw — on every platform, with
     * zero prior test coverage anywhere in the suite. Fixed by switching to `ResetType.HARD`,
     * whose merge-state cleanup (`MERGE_HEAD`/`MERGE_MSG` removal, `RepositoryState` MERGING ->
     * SAFE) is unconditional on any `ResetType` other than SOFT, so it correctly aborts the
     * in-progress merge (empirically confirmed against a real conflicted merge before landing).
     */
    @Test
    fun `abortMerge resets a conflicted merge back to pre-merge HEAD content and clears merge state`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git -> setIdentity(git) }
        // GitConfig.remoteBranch defaults to the literal "main", but git's actual init branch
        // name depends on the running machine/CI runner's init.defaultBranch — derive it for real
        // instead of assuming, exactly like the merge-conflict tests above (this test previously
        // used the bare `config` here, which only passed when the ambient git config happened to
        // default to "main").
        val baseBranch = Git.open(File(config.repoRoot)).use { it.repository.branch }
        val mergeConfig = config.copy(remoteBranch = baseBranch)

        File(config.repoRoot, "shared.md").writeText("base\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_jvm_git_origin_").toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }

        val originWorkDir = createTempDirectory("stelekit_jvm_git_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setIdentity(originGit)
            File(originWorkDir, "shared.md").writeText("remote change\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        File(config.repoRoot, "shared.md").writeText("local change\n")
        assertTrue(repository.stageSubdir(mergeConfig).isRight())
        assertTrue(repository.commit(mergeConfig, "local change").isRight())

        assertTrue(repository.fetch(mergeConfig).isRight())
        val mergeResult = repository.merge(mergeConfig)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        assertTrue((mergeResult as Either.Right).value.hasConflicts, "expected a conflict")

        Git.open(File(config.repoRoot)).use { git ->
            assertEquals(
                org.eclipse.jgit.lib.RepositoryState.MERGING,
                git.repository.repositoryState,
                "expected MERGING state during the conflict",
            )
        }

        val abortResult = repository.abortMerge(mergeConfig)
        assertTrue(abortResult.isRight(), "abortMerge failed: $abortResult")

        Git.open(File(config.repoRoot)).use { git ->
            assertEquals(
                org.eclipse.jgit.lib.RepositoryState.SAFE,
                git.repository.repositoryState,
                "expected merge state cleared after abortMerge",
            )
        }
        assertEquals(
            "local change\n",
            File(config.repoRoot, "shared.md").readText(),
            "expected working tree reset to pre-merge (local) HEAD content",
        )

        val statusResult = repository.status(mergeConfig)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        assertFalse(
            (statusResult as Either.Right).value.hasLocalChanges,
            "expected a clean working tree after abortMerge",
        )
    }
}
