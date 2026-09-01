// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.treewalk.TreeWalk
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowStatFs

/**
 * Regression suite for [AndroidGitRepository] against a real Robolectric shadow-worktree
 * (`context.filesDir`), closing the zero-coverage gap flagged by finding #8 of
 * `project_plans/android-git-saf-shadow-worktree/implementation/plan.md` (Epic 8.2, Story 8.2.1).
 *
 * `pathResolver = { null }` on every constructed [AndroidGitRepository] forces shadow-mirror mode
 * to activate for every `saf://...` `repoRoot` (plan.md design decision #6 — no [GitShadowWorktree]
 * is ever passed in directly). [FakeSafFileSystem] stands in for the user's SAF folder.
 *
 * Robolectric's [ShadowStatFs] intercepts `android.os.StatFs` regardless of the actual host
 * filesystem's free space, defaulting to whatever the *real* underlying temp filesystem reports —
 * which can legitimately be below Task 6.2.1a's 200 MB shadow-storage threshold in a constrained
 * CI/sandbox environment. Every `init()`/`clone()` call below must register ample stats for its
 * worktree path first so this suite exercises the JGit ops it's actually testing, not the storage
 * guard (that's Story 8.2.3's job, in `AndroidGitRepositoryStorageGuardTest`).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidGitRepositoryShadowWorktreeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        ShadowStatFs.reset()
    }

    private fun newRepository(fileSystem: FakeSafFileSystem = FakeSafFileSystem()): AndroidGitRepository =
        AndroidGitRepository(
            context = context,
            sshKeyProvider = null,
            credentialAccess = FakeCredentialAccess(),
            pathResolver = { null },
            fileSystem = fileSystem,
        )

    private fun setTestIdentity(git: Git) {
        val cfg = git.repository.config
        cfg.setString("user", null, "name", "Stelekit Test")
        cfg.setString("user", null, "email", "stelekit-test@example.com")
        cfg.save()
    }

    /** Registers ample [ShadowStatFs] stats for [repoRoot]'s shadow worktree so the Task 6.2.1a storage guard never trips in this suite. */
    private fun registerAmpleStorage(repository: AndroidGitRepository, repoRoot: String) {
        val worktreePath = requireNotNull(repository.shadowWorktreeFor(repoRoot)).worktreeRootPath
        ShadowStatFs.registerStats(worktreePath, 2_000_000, 1_000_000, 1_000_000)
    }

    // ── Task 8.2.1a: init/stageSubdir/commit/status end to end ─────────────────────────────

    @Test
    fun `init stageSubdir commit and status succeed end to end against the shadow worktree`() = runTest {
        val fs = FakeSafFileSystem()
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-basic"
        val repository = newRepository(fs)

        // Pre-existing SAF content (as if the user already had a wiki page before enabling git
        // sync) — pulled into the shadow tree unconditionally by syncShadowAfterInitOrClone.
        fs.seed("$repoRoot/note.md", "- Hello\n")

        registerAmpleStorage(repository, repoRoot)
        val initResult = repository.init(repoRoot)
        assertTrue(initResult.isRight(), "init failed: $initResult")

        // Strengthens this test past a no-op-commit false positive: prove init()'s unconditional
        // post-creation syncFromSafRoot() itself pulled the pre-existing SAF content into the
        // shadow tree. Checked directly against the shadow tree's own file — not via status()/any
        // other AndroidGitRepository call, each of which would independently resync through
        // openGit()'s ensureFresh() staleness fallback and mask a regression that reduced
        // syncShadowAfterInitOrClone to a no-op.
        val shadowNoteContent = repository.shadowWorktreeFor(repoRoot)?.readShadowFile("note.md")
        assertEquals(
            "- Hello\n",
            shadowNoteContent,
            "expected init()'s syncFromSafRoot() to have pulled pre-existing SAF content " +
                "('note.md') into the shadow tree before any other git operation ran",
        )

        val realPath = repository.resolveForJGit(repoRoot)
        Git.open(File(realPath)).use { git -> setTestIdentity(git) }

        val config = GitConfig(
            graphId = "wiki-basic",
            repoRoot = repoRoot,
            wikiSubdir = null,
            authType = GitAuthType.NONE,
        )

        val stageResult = repository.stageSubdir(config)
        assertTrue(stageResult.isRight(), "stageSubdir failed: $stageResult")

        val commitResult = repository.commit(config, "Initial commit")
        assertTrue(commitResult.isRight(), "commit failed: $commitResult")

        val statusResult = repository.status(config)
        assertTrue(statusResult.isRight(), "status failed: $statusResult")
        val status = (statusResult as Either.Right).value
        assertFalse(status.hasLocalChanges, "expected no local changes immediately after commit, got $status")
    }

    // ── Tasks 8.2.1b/8.2.1c: merge-conflict path mapping + checkoutFile round trip ─────────

    @Test
    fun `merge conflict reports SAF-facing conflict paths and checkoutFile round trips via the fake SAF provider`() =
        runTest {
            val fs = FakeSafFileSystem()
            val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-merge"
            val repository = newRepository(fs)

            // Bulleted single-block content: merge() now re-derives `.md` conflicts via the
            // block-aware merge (tryBlockAwareConflict) before falling back to JGit's raw line
            // markers — see that function's doc — and its serializer always canonicalizes a
            // block back out with a `- ` prefix, mirroring LogseqPageSerializer's on-save format.
            fs.seed("$repoRoot/conflict.md", "- base\n")

            registerAmpleStorage(repository, repoRoot)
            val initResult = repository.init(repoRoot)
            assertTrue(initResult.isRight(), "init failed: $initResult")

            val shadowPath = repository.resolveForJGit(repoRoot)
            Git.open(File(shadowPath)).use { git -> setTestIdentity(git) }

            // Discover the actual initial branch name rather than assuming "main"/"master" — JGit's
            // default depends on the running environment's git config.
            val branchName = Git.open(File(shadowPath)).use { it.repository.branch } ?: "main"

            val config = GitConfig(
                graphId = "wiki-merge",
                repoRoot = repoRoot,
                wikiSubdir = null,
                remoteBranch = branchName,
                authType = GitAuthType.NONE,
            )

            assertTrue(repository.stageSubdir(config).isRight())
            assertTrue(repository.commit(config, "base commit").isRight())

            // "origin": a bare clone of the shadow repo at the base commit.
            val originDir = createTempDirectory("stelekit_git_origin_").toFile()
            Git.cloneRepository().setURI(shadowPath).setBare(true).setDirectory(originDir).call().close()

            Git.open(File(shadowPath)).use { git ->
                git.remoteAdd().setName("origin").setUri(URIish(originDir.absolutePath)).call()
            }

            // Remote-side divergent commit: clone origin into a scratch working dir, change the
            // same file differently, and push back.
            val originWorkDir = createTempDirectory("stelekit_git_origin_work_").toFile()
            Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
                setTestIdentity(originGit)
                File(originWorkDir, "conflict.md").writeText("- remote version\n")
                originGit.add().addFilepattern(".").call()
                originGit.commit().setMessage("remote change").call()
                originGit.push().call()
            }

            // Local-side divergent commit, driven through the fake SAF provider (mirrors a real
            // edit made through the app), not by touching the shadow tree directly.
            fs.seed("$repoRoot/conflict.md", "- local version\n")
            assertTrue(repository.stageSubdir(config).isRight())
            assertTrue(repository.commit(config, "local change").isRight())

            val fetchResult = repository.fetch(config)
            assertTrue(fetchResult.isRight(), "fetch failed: $fetchResult")

            val mergeResult = repository.merge(config)
            assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
            val merge = (mergeResult as Either.Right).value
            assertTrue(merge.hasConflicts, "expected a conflict from two divergent edits to the same file")
            assertEquals(1, merge.conflicts.size)

            val conflict = merge.conflicts.single()
            // Task 8.2.1b: this is the exact bug class fixed in commit 61b689fa61 — the conflict's
            // filePath must be SAF-facing, never a shadow-absolute path.
            assertTrue(
                conflict.filePath.startsWith("saf://"),
                "ConflictFile.filePath must be SAF-facing, got: ${conflict.filePath}",
            )
            assertFalse(
                conflict.filePath.contains("/gitshadow/"),
                "ConflictFile.filePath must never be a shadow-absolute path, got: ${conflict.filePath}",
            )
            assertEquals("$repoRoot/conflict.md", conflict.filePath)

            // merge() must parse the real conflict-marker content JGit wrote into the shadow tree
            // into hunks the UI can resolve line by line, instead of always shipping an empty
            // hunk list (the pre-existing gap this project's conflict-resolution work closes).
            // For markdown, this now goes through the block-aware merge (tryBlockAwareConflict)
            // rather than JGit's own line-level markers — see that function's doc — hence the
            // canonicalized `- ` prefix on both sides below.
            assertEquals(1, conflict.hunks.size, "expected exactly one conflicting hunk")
            val hunk = conflict.hunks.single()
            assertEquals(listOf("- local version"), hunk.localLines)
            assertEquals(listOf("- remote version"), hunk.remoteLines)
            assertTrue(
                conflict.rawContent?.contains("<<<<<<<") == true,
                "ConflictFile.rawContent must carry the real conflict-marker content, got: ${conflict.rawContent}",
            )

            // The marker content must also have been best-effort written back to SAF, so the
            // conflicted file is visible with real markers outside the app too (matching what a
            // direct-filesystem git working tree already shows for free).
            assertTrue(
                fs.readFile(conflict.filePath)?.contains("<<<<<<<") == true,
                "expected conflict markers to be written back to SAF, got: ${fs.readFile(conflict.filePath)}",
            )

            // Task 8.2.1c: resolve via checkoutFile(LOCAL) and confirm the fake SAF provider's
            // content was updated to match what was checked out. checkoutFile reads straight from
            // git's LOCAL blob (unaffected by the block-aware re-derivation above, which only
            // touches conflict-marker display/resolution content, not git object content), so the
            // original unbulleted seed content round-trips here verbatim.
            val checkoutResult = repository.checkoutFile(config, conflict.filePath, MergeSide.LOCAL)
            assertTrue(checkoutResult.isRight(), "checkoutFile failed: $checkoutResult")
            assertEquals("- local version\n", fs.readFile(conflict.filePath))
        }

    // ── Task 4.1.1b: markResolved() pulls fresh SAF content into the shadow tree ───────────

    @Test
    fun `markResolved stages resolved SAF content not stale shadow content before git add`() = runTest {
        val fs = FakeSafFileSystem()
        val repoRoot =
            "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-markresolved"
        val repository = newRepository(fs)

        fs.seed("$repoRoot/conflict.md", "base\n")

        registerAmpleStorage(repository, repoRoot)
        assertTrue(repository.init(repoRoot).isRight())

        val shadowPath = repository.resolveForJGit(repoRoot)
        Git.open(File(shadowPath)).use { git -> setTestIdentity(git) }
        val branchName = Git.open(File(shadowPath)).use { it.repository.branch } ?: "main"

        val config = GitConfig(
            graphId = "wiki-markresolved",
            repoRoot = repoRoot,
            wikiSubdir = null,
            remoteBranch = branchName,
            authType = GitAuthType.NONE,
        )

        assertTrue(repository.stageSubdir(config).isRight())
        assertTrue(repository.commit(config, "base commit").isRight())

        // "origin": a bare clone of the shadow repo at the base commit.
        val originDir = createTempDirectory("stelekit_git_origin_").toFile()
        Git.cloneRepository().setURI(shadowPath).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(shadowPath)).use { git ->
            git.remoteAdd().setName("origin").setUri(URIish(originDir.absolutePath)).call()
        }

        // Remote-side divergent commit.
        val originWorkDir = createTempDirectory("stelekit_git_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setTestIdentity(originGit)
            File(originWorkDir, "conflict.md").writeText("remote version\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        // Local-side divergent commit, driven through the fake SAF provider.
        fs.seed("$repoRoot/conflict.md", "local version\n")
        assertTrue(repository.stageSubdir(config).isRight())
        assertTrue(repository.commit(config, "local change").isRight())

        assertTrue(repository.fetch(config).isRight())
        val mergeResult = repository.merge(config)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val merge = (mergeResult as Either.Right).value
        assertTrue(merge.hasConflicts, "expected a conflict from two divergent edits to the same file")
        val conflict = merge.conflicts.single()

        // Simulate GitSyncService.resolveConflict's write-before-markResolved ordering: the
        // resolved content is written straight to SAF, while the shadow tree still has whatever
        // JGit's conflicted merge left in the working tree (conflict markers / stale content).
        val resolvedContent = "resolved content\n"
        fs.seed(conflict.filePath, resolvedContent)

        // Make the sync manifest artificially "fresh" for this path — matching the just-seeded SAF
        // mtime/size — WITHOUT touching shadow file content. Without this, markResolved()'s own
        // openGit() -> ensureFresh() staleness fallback would independently resync the shadow tree
        // from SAF anyway (since the manifest would otherwise be stale), which would mask the fix
        // under test: this isolates markResolved()'s explicit writeShadowFile() pull-in as the only
        // mechanism that can put the resolved content into the shadow tree before git.add() runs.
        val worktree = requireNotNull(repository.shadowWorktreeFor(repoRoot))
        val gitRelativePath = worktree.toGitRelativePath(conflict.filePath)
        val freshSafMtime = requireNotNull(fs.getLastModifiedTime(conflict.filePath))
        worktree.updateManifestEntry(
            gitRelativePath,
            freshSafMtime,
            resolvedContent.encodeToByteArray().size.toLong(),
        )

        val markResolvedResult = repository.markResolved(config, conflict.filePath)
        assertTrue(markResolvedResult.isRight(), "markResolved failed: $markResolvedResult")

        val commitResult = repository.commit(config, "resolve conflict")
        assertTrue(commitResult.isRight(), "commit failed: $commitResult")
        val commitSha = (commitResult as Either.Right).value

        Git.open(File(shadowPath)).use { git ->
            val repo = git.repository
            val commitId = requireNotNull(repo.resolve(commitSha)) { "cannot resolve $commitSha" }
            val committedContent = RevWalk(repo).use { walk ->
                val commitObj = walk.parseCommit(commitId)
                TreeWalk.forPath(repo, gitRelativePath, commitObj.tree).use { treeWalk ->
                    requireNotNull(treeWalk) { "$gitRelativePath not found in committed tree" }
                    val blobId = treeWalk.getObjectId(0)
                    String(repo.open(blobId).bytes, Charsets.UTF_8)
                }
            }

            assertEquals(
                resolvedContent,
                committedContent,
                "markResolved() must stage the resolved SAF content, not stale shadow content " +
                    "(e.g. lingering conflict markers)",
            )
        }
    }

    // ── Task 4.2.1b: abortMerge() reconciles the shadow tree from SAF after a JGit reset ───

    // Previously blocked by a real, pre-existing production bug: JGit 7.3.0's ResetCommand never
    // implemented ResetType.MERGE/KEEP (confirmed by disassembling ResetCommand.class — its mode
    // switch implements only SOFT/MIXED/HARD and unconditionally throws UnsupportedOperationException
    // for MERGE/KEEP). AndroidGitRepository.abortMerge() now uses ResetType.HARD instead — HARD's
    // merge-state cleanup (MERGE_HEAD/MERGE_MSG removal, RepositoryState MERGING -> SAFE) is
    // unconditional on any ResetType other than SOFT, so it correctly aborts the in-progress merge
    // (empirically confirmed against a real conflicted merge). See AndroidGitRepository.kt's
    // abortMerge() comment for the full rationale.
    @Test
    fun `abortMerge reconciles shadow to SAF content after partial conflict resolution`() = runTest {
        val fs = FakeSafFileSystem()
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-abort"
        val repository = newRepository(fs)

        fs.seed("$repoRoot/fileA.md", "base A\n")
        fs.seed("$repoRoot/fileB.md", "base B\n")

        registerAmpleStorage(repository, repoRoot)
        assertTrue(repository.init(repoRoot).isRight())

        val shadowPath = repository.resolveForJGit(repoRoot)
        Git.open(File(shadowPath)).use { git -> setTestIdentity(git) }
        val branchName = Git.open(File(shadowPath)).use { it.repository.branch } ?: "main"

        val config = GitConfig(
            graphId = "wiki-abort",
            repoRoot = repoRoot,
            wikiSubdir = null,
            remoteBranch = branchName,
            authType = GitAuthType.NONE,
        )

        assertTrue(repository.stageSubdir(config).isRight())
        assertTrue(repository.commit(config, "base commit").isRight())

        val originDir = createTempDirectory("stelekit_git_origin_").toFile()
        Git.cloneRepository().setURI(shadowPath).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(shadowPath)).use { git ->
            git.remoteAdd().setName("origin").setUri(URIish(originDir.absolutePath)).call()
        }

        val originWorkDir = createTempDirectory("stelekit_git_origin_work_").toFile()
        Git.cloneRepository().setURI(originDir.absolutePath).setDirectory(originWorkDir).call().use { originGit ->
            setTestIdentity(originGit)
            File(originWorkDir, "fileA.md").writeText("remote A version from far away\n")
            File(originWorkDir, "fileB.md").writeText("remote B version from far away\n")
            originGit.add().addFilepattern(".").call()
            originGit.commit().setMessage("remote change").call()
            originGit.push().call()
        }

        fs.seed("$repoRoot/fileA.md", "local A version\n")
        fs.seed("$repoRoot/fileB.md", "local B version\n")
        assertTrue(repository.stageSubdir(config).isRight())
        assertTrue(repository.commit(config, "local change").isRight())

        assertTrue(repository.fetch(config).isRight())
        val mergeResult = repository.merge(config)
        assertTrue(mergeResult.isRight(), "merge failed: $mergeResult")
        val merge = (mergeResult as Either.Right).value
        assertTrue(merge.hasConflicts, "expected conflicts from two files diverging on both sides")
        assertEquals(2, merge.conflicts.size)

        val conflictA = merge.conflicts.single { it.filePath == "$repoRoot/fileA.md" }

        // Resolve fileA via the REMOTE side — deliberately NOT the LOCAL/HEAD side, so a bare
        // `git reset --merge` (which resets to pre-merge HEAD == the LOCAL commit) would discard
        // this resolution and revert fileA back to the LOCAL content. This is exactly the traced
        // partial-resolution-then-abort sequence from plan.md's Epic 4.2 rationale. checkoutFile()
        // write-backs the resolved content to SAF immediately (Phase 3 behavior).
        val resolvedContentA = "remote A version from far away\n"
        val checkoutResult = repository.checkoutFile(config, conflictA.filePath, MergeSide.REMOTE)
        assertTrue(checkoutResult.isRight(), "checkoutFile failed: $checkoutResult")
        assertEquals(resolvedContentA, fs.readFile(conflictA.filePath))

        // fileB is deliberately left unresolved — the merge is only partially resolved when abort
        // is called below.

        val abortResult = repository.abortMerge(config)
        assertTrue(abortResult.isRight(), "abortMerge failed: $abortResult")

        // (a) SAF is the source of truth and must never be touched by abortMerge — fileA's
        // resolved content must still be there, unchanged.
        assertEquals(
            resolvedContentA,
            fs.readFile(conflictA.filePath),
            "abortMerge() must never reset/overwrite SAF content — SAF is always the source of truth",
        )

        // (b) The shadow tree's copy of fileA must ALSO be reconciled back to the resolved SAF
        // content after the JGit reset touched it — NOT left at the pre-merge HEAD (LOCAL) content
        // a bare `git reset --merge` alone would have produced.
        val worktree = requireNotNull(repository.shadowWorktreeFor(repoRoot))
        val gitRelativePathA = worktree.toGitRelativePath(conflictA.filePath)
        val shadowContentA = worktree.readShadowFile(gitRelativePathA)
        assertEquals(
            resolvedContentA,
            shadowContentA,
            "abortMerge() must re-sync the shadow tree from SAF after `git reset --merge`, so an " +
                "already write-back'd conflict resolution isn't silently discarded",
        )
    }

    // ── Task 8.2.1d: literal regression guard for the original resolveForJGit bug ──────────

    @Test
    fun `resolveForJGit never returns unresolvable saf scheme string when shadow worktree is active`() = runTest {
        val fs = FakeSafFileSystem()
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-resolve"
        val repository = newRepository(fs)

        registerAmpleStorage(repository, repoRoot)
        assertTrue(repository.init(repoRoot).isRight())

        val worktree = repository.shadowWorktreeFor(repoRoot)
        assertNotNull(worktree, "shadow-mirror mode must be active when pathResolver returns null for a saf:// root")

        val resolved = repository.resolveForJGit(repoRoot)
        assertFalse(
            resolved.startsWith("saf://"),
            "resolveForJGit() must never fall through to the raw unresolvable saf:// string once a " +
                "shadow worktree is active, got: $resolved",
        )
        assertEquals(worktree.worktreeRootPath, resolved)
    }
}
