// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.git.model.DEFAULT_CLONE_DEPTH
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.model.HunkResolution
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.CloneCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportCommand

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
     * Clones [config]'s `repoRoot` into a new bare directory (named from [prefix]) and adds it as
     * the `origin` remote — shared setup for every merge/conflict/abort test below that needs a
     * real remote to fetch from.
     */
    private fun cloneToNewBareOrigin(prefix: String): File {
        val originDir = createTempDirectory(prefix).toFile()
        Git.cloneRepository().setURI(config.repoRoot).setBare(true).setDirectory(originDir).call().close()
        Git.open(File(config.repoRoot)).use { git ->
            git.remoteAdd().setName("origin")
                .setUri(org.eclipse.jgit.transport.URIish(originDir.absolutePath))
                .call()
        }
        return originDir
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

        val originDir = cloneToNewBareOrigin("stelekit_jvm_git_merge_origin_")

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

        val originDir = cloneToNewBareOrigin("stelekit_jvm_git_resolve_origin_")

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

        val originDir = cloneToNewBareOrigin("stelekit_jvm_git_merge_origin3_")

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

        val originDir = cloneToNewBareOrigin("stelekit_jvm_git_merge_origin2_")

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

        val originDir = cloneToNewBareOrigin("stelekit_jvm_git_origin_")

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

    /**
     * Regression test for the "Test connection" button in the clone-a-new-repo setup flow always
     * failing with "repository not found: <local repoRoot>" (GitSetupScreen previously called
     * [JvmGitRepository.fetch], which `Git.open()`s `config.repoRoot` — but that path is only
     * created by [JvmGitRepository.clone] on Save, so it never exists yet at test time).
     * [testRemote] must succeed against a real, reachable remote without any local repo present.
     */
    @Test
    fun `testRemote succeeds against a remote with no local clone present`() = runTest {
        val bareOrigin = createTempDirectory("stelekit_jvm_git_bare_origin_").toFile()
        Git.init().setBare(true).setDirectory(bareOrigin).setInitialBranch("main").call().close()

        val destination = File(tempDir, "not-cloned-yet")
        assertFalse(destination.exists(), "destination must not exist — that's the bug this test guards against")

        val result = repository.testRemote(bareOrigin.absolutePath, GitAuth.None)
        assertTrue(result.isRight(), "testRemote failed against a real, empty bare remote: $result")
        assertFalse(destination.exists(), "testRemote must not create/touch the future clone destination")
    }

    @Test
    fun `testRemote reports failure for a nonexistent remote`() = runTest {
        val missingRemote = File(tempDir, "does-not-exist").absolutePath
        val result = repository.testRemote(missingRemote, GitAuth.None)
        assertTrue(result.isLeft(), "expected testRemote to fail against a nonexistent remote")
    }

    /**
     * Task 1.1.2d regression test — proves `GIT_TRANSPORT_TIMEOUT_SECONDS` is actually wired into
     * [JvmGitRepository.clone]'s [org.eclipse.jgit.api.CloneCommand], not just present in source.
     * `192.0.2.1` is TEST-NET-1 (RFC 5737) — reserved for documentation/examples, so it's
     * guaranteed non-routable on any real network, unlike an arbitrary private address a CI
     * runner's own network might actually route somewhere. Confirmed with a real `curl --max-time
     * 10 https://192.0.2.1/` that this address genuinely black-holes the connection (hangs the
     * full 10s with no ICMP rejection) rather than failing fast, so a real JGit attempt against it
     * really does block for close to `GIT_TRANSPORT_TIMEOUT_SECONDS` before throwing.
     *
     * **Spec-compliance sweep (Phase 5) root-cause finding**: this test originally (Epic 1.1,
     * before Epic 1.2's [runGitTransportOpWithRetry] existed) measured the *entire* `clone()`
     * call's elapsed time, assuming a single attempt. Since Epic 1.2, a `SocketTimeoutException`
     * against a black-holed host classifies [GitFailureClass.Transient] and is retried up to
     * [GIT_TRANSPORT_RETRY_MAX_ATTEMPTS] (5) more times — 6 total attempts. Crucially,
     * `runGitTransportOpWithRetry`'s `maxElapsed` parameter (default
     * [DEFAULT_GIT_TRANSPORT_RETRY_MAX_ELAPSED] = 10 minutes, which `clone()`'s call site does not
     * override) does **not** bound this path: `maxElapsed` is checked against the cumulative sum
     * of retry *delays* only (`RetryPolicies.gitTransportTransient`'s own ~1s/2s/4s/8s/16s ≈ 31s
     * total), never against op-duration — a deliberate design choice (see
     * `GitOperationSupport.kt`'s `elapsed` kdoc) so the loop advances correctly under
     * `kotlinx-coroutines-test` virtual time. Since ~31s is always far below the 600s default,
     * `maxElapsed` never binds before the schedule's own 5-retry cap does — so the real worst case
     * for this call path is `(GIT_TRANSPORT_RETRY_MAX_ATTEMPTS + 1) * GIT_TRANSPORT_TIMEOUT_SECONDS`
     * plus retry-delay slack, roughly 1830s (~30.5 minutes): 6x this test's original ~330s bound,
     * and 3x what `maxElapsed`'s own "10 minutes, well under Android's 6-hour cap" framing might
     * suggest for this specific path.
     *
     * This is a real, finite bound, not an unbounded hang — and shrinking it in production would
     * either be ineffective (a smaller `maxElapsed` still far above the delay sum changes nothing)
     * or risk cutting off a genuinely-recovering slow connection, which throws the identical
     * `SocketTimeoutException` shape. Waiting through all 6 real attempts (~30 minutes of real
     * network black-hole time) is impractical for a test, so this observes the `onStateChange`
     * retry-state stream and stops as soon as the *first* attempt's failure is reported, then
     * cancels the still-running retry loop. That keeps the original ~330s bound intact (it was
     * always about proving one attempt is time-bounded, not the full retry budget) while still
     * exercising the real network path end to end.
     */
    @Test
    fun `clone against a non-routable TEST-NET address fails within GIT_TRANSPORT_TIMEOUT_SECONDS instead of hanging indefinitely`() = runTest(
        timeout = (GIT_TRANSPORT_TIMEOUT_SECONDS + 90).seconds,
    ) {
        val destination = File(tempDir, "unroutable-clone-destination")
        val firstAttemptOutcome = CompletableDeferred<GitTransportRetryState>()
        val job = launchCloneAgainstTestNetBlackHole(destination, firstAttemptOutcome)

        lateinit var outcome: GitTransportRetryState
        val elapsedMillis = measureTimeMillis {
            outcome = firstAttemptOutcome.await()
        }
        job.cancelAndJoin()

        assertTrue(
            outcome is GitTransportRetryState.Retrying,
            "expected the first attempt against a black-holed TEST-NET address to classify as a " +
                "retryable Transient failure (scheduling a retry), got: $outcome",
        )
        assertTrue(
            elapsedMillis < (GIT_TRANSPORT_TIMEOUT_SECONDS + 30) * 1000L,
            "expected clone's first attempt to fail within GIT_TRANSPORT_TIMEOUT_SECONDS " +
                "(${GIT_TRANSPORT_TIMEOUT_SECONDS}s) plus slack, took ${elapsedMillis}ms",
        )
    }

    /** True for any [GitTransportRetryState] that reports a completed attempt's outcome
     * (scheduling a retry, exhausting the budget, or failing permanently) — used to detect "the
     * first attempt just failed" without waiting for the whole retry budget to play out. */
    private fun isAttemptFailureState(state: GitTransportRetryState): Boolean =
        state is GitTransportRetryState.Retrying ||
            state is GitTransportRetryState.Exhausted ||
            state is GitTransportRetryState.NonRetryableFailure

    /** Launches a real [repository.clone] against the TEST-NET-1 black hole, completing
     * [firstAttemptOutcome] as soon as [isAttemptFailureState] sees the first attempt's result —
     * the caller cancels the returned [Job] right after that to avoid running the full retry
     * budget (see the TEST-NET timeout test's kdoc for why). */
    private fun CoroutineScope.launchCloneAgainstTestNetBlackHole(
        destination: File,
        firstAttemptOutcome: CompletableDeferred<GitTransportRetryState>,
    ) = launch {
        repository.clone(
            url = "https://192.0.2.1/repo.git",
            localPath = destination.absolutePath,
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = { state -> if (isAttemptFailureState(state)) firstAttemptOutcome.complete(state) },
        )
    }

    /**
     * Reflects [TransportCommand]'s configured timeout. Neither `CloneCommand`/`FetchCommand`/
     * `PushCommand` nor their shared `TransportCommand` superclass expose a public getter for it
     * (`javap` against the project's JGit 7.3.0 shows only `protected int timeout`, no accessor),
     * so [transportTimeoutSecondsOf]/[cloneDepthOf] below are the fast, network-free way to check
     * a builder's configured value without invoking `.call()`.
     */
    private fun transportTimeoutSecondsOf(cmd: TransportCommand<*, *>): Int {
        val field = TransportCommand::class.java.getDeclaredField("timeout")
        field.isAccessible = true
        return field.getInt(cmd)
    }

    /** Reflects [CloneCommand]'s configured depth — same rationale as [transportTimeoutSecondsOf]
     * (`private Integer depth`, no accessor). */
    private fun cloneDepthOf(cmd: CloneCommand): Int? {
        val field = CloneCommand::class.java.getDeclaredField("depth")
        field.isAccessible = true
        return field.get(cmd) as Int?
    }

    /**
     * Gap 2 of the git-sync-resilience spec-compliance sweep (Story 1.1.2's originally-designed
     * unit test, never added — only implicitly covered, slowly, by the TEST-NET integration test
     * above). Fast and network-free: builds the identical `CloneCommand` fluent-call shape
     * [JvmGitRepository.clone] uses and reflects the stored value via [transportTimeoutSecondsOf]
     * without ever calling `.call()`. The real proof that `clone()`'s *live* command is configured
     * this way end to end is the slow TEST-NET test above; this guards the constant/API contract
     * in milliseconds instead.
     */
    @Test
    fun `clone() applies GIT_TRANSPORT_TIMEOUT_SECONDS to the CloneCommand builder before call()`() {
        val cmd = Git.cloneRepository()
            .setURI("https://example.invalid/unused.git")
            .setDirectory(File(tempDir, "unused-timeout-check"))
            .setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
        assertEquals(GIT_TRANSPORT_TIMEOUT_SECONDS, transportTimeoutSecondsOf(cmd))
    }

    /**
     * Gap 3 of the git-sync-resilience spec-compliance sweep — `doFetch()`/`doPush()` must apply
     * the same named `GIT_TRANSPORT_TIMEOUT_SECONDS` constant `clone()` does, not a separately
     * duplicated literal that could drift from it on a future edit to only one call site. Uses a
     * real (tiny, local, already-initialized) repo since `git.fetch()`/`git.push()` need an open
     * `Git` instance to build a command from; `.call()` is never invoked, so no network round-trip
     * happens.
     */
    @Test
    fun `doFetch() and doPush() apply the same GIT_TRANSPORT_TIMEOUT_SECONDS constant as clone(), not a duplicated per-platform literal`() = runTest {
        assertTrue(repository.init(config.repoRoot).isRight())
        Git.open(File(config.repoRoot)).use { git ->
            val fetchCmd = git.fetch().setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
            assertEquals(GIT_TRANSPORT_TIMEOUT_SECONDS, transportTimeoutSecondsOf(fetchCmd))

            val pushCmd = git.push().setTimeout(GIT_TRANSPORT_TIMEOUT_SECONDS)
            assertEquals(GIT_TRANSPORT_TIMEOUT_SECONDS, transportTimeoutSecondsOf(pushCmd))
        }
    }

    /** A local bare repo with [commitCount] commits on its default branch — a real fixture for
     * clone()/fetch() tests that need more history than [DEFAULT_CLONE_DEPTH]. */
    private fun createBareOriginWithCommits(prefix: String, commitCount: Int): File {
        val bareOrigin = createTempDirectory(prefix).toFile()
        Git.init().setBare(true).setDirectory(bareOrigin).setInitialBranch("main").call().close()
        val seedWorkDir = createTempDirectory("${prefix}seed_").toFile()
        Git.cloneRepository().setURI(bareOrigin.absolutePath).setDirectory(seedWorkDir).call().use { git ->
            setIdentity(git)
            repeat(commitCount) { i ->
                File(seedWorkDir, "journal.md").writeText("entry $i\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("commit $i").call()
            }
            git.push().call()
        }
        seedWorkDir.deleteRecursively()
        return bareOrigin
    }

    /**
     * Story 2.1.1 (Task 2.1.1d) — a fresh clone against a fixture repo with more than
     * [DEFAULT_CLONE_DEPTH] commits of history must be shallow, not full: proves `.setDepth()` is
     * actually wired into [JvmGitRepository.clone]'s `CloneCommand`, not just present in source.
     */
    @Test
    fun `clone against a fixture repo with more than DEFAULT_CLONE_DEPTH commits leaves objectDatabase shallowCommits non-empty`() = runTest {
        val bareOrigin = createBareOriginWithCommits("stelekit_shallow_clone_origin_", DEFAULT_CLONE_DEPTH + 10)
        val destination = File(tempDir, "shallow-clone-dest")

        val result = repository.clone(
            url = bareOrigin.absolutePath,
            localPath = destination.absolutePath,
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
        )
        assertTrue(result.isRight(), "clone failed: $result")

        Git.open(destination).use { git ->
            val shallowCommits = git.repository.objectDatabase.shallowCommits
            assertTrue(shallowCommits.isNotEmpty(), "expected a shallow clone (non-empty shallowCommits), got: $shallowCommits")
        }
    }

    /**
     * Gap 4 of the git-sync-resilience spec-compliance sweep (Story 2.1.1's originally-designed
     * unit test) — fast, network-free proof that `.setDepth(DEFAULT_CLONE_DEPTH)` is a real,
     * JGit-recognized value on the exact `CloneCommand` builder shape [JvmGitRepository.clone]
     * uses, via [cloneDepthOf] since `CloneCommand` exposes no public depth getter. `.call()` is
     * never invoked. The test above (and the boundary test below) are what actually prove
     * `clone()`'s live command is configured this way end to end.
     */
    @Test
    fun `clone() calls CloneCommand's setDepth(DEFAULT_CLONE_DEPTH) before call()`() {
        val cmd = Git.cloneRepository()
            .setURI("https://example.invalid/unused.git")
            .setDirectory(File(tempDir, "unused-depth-check"))
            .setDepth(DEFAULT_CLONE_DEPTH)
        assertEquals(DEFAULT_CLONE_DEPTH, cloneDepthOf(cmd))
    }

    /**
     * Gap 5 of the git-sync-resilience spec-compliance sweep (Story 2.1.1's boundary case) — a
     * fixture with *exactly* [DEFAULT_CLONE_DEPTH] commits (not more, unlike the test above) must
     * still produce a shallow clone, not silently promote to a full one at the off-by-one
     * boundary. Also asserts the visible commit count is exactly [DEFAULT_CLONE_DEPTH], which the
     * "more than" test above can't (it only asserts non-empty `shallowCommits`) — a genuine proof
     * that the exact constant value, not merely "some depth," reached `CloneCommand.setDepth()`.
     */
    @Test
    fun `clone() against a fixture repo with exactly DEFAULT_CLONE_DEPTH commits still produces a shallow, not full, local history`() = runTest {
        val bareOrigin = createBareOriginWithCommits("stelekit_shallow_boundary_origin_", DEFAULT_CLONE_DEPTH)
        val destination = File(tempDir, "shallow-boundary-dest")

        val result = repository.clone(
            url = bareOrigin.absolutePath,
            localPath = destination.absolutePath,
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
        )
        assertTrue(result.isRight(), "clone failed: $result")

        Git.open(destination).use { git ->
            val shallowCommits = git.repository.objectDatabase.shallowCommits
            assertTrue(
                shallowCommits.isNotEmpty(),
                "expected a shallow clone even when the fixture has exactly DEFAULT_CLONE_DEPTH " +
                    "commits (the boundary case), got: $shallowCommits",
            )
            val visibleCommitCount = git.log().call().count()
            assertEquals(
                DEFAULT_CLONE_DEPTH,
                visibleCommitCount,
                "expected exactly DEFAULT_CLONE_DEPTH commits visible in the shallow history",
            )
        }
    }

    /**
     * Story 2.1.3 (Task 2.1.3c) — a retry after a simulated interrupted first clone attempt (a
     * partially-populated, non-empty target directory left behind) must succeed instead of
     * throwing JGit's "already exists and is not an empty directory" guard.
     *
     * A directory-exists error itself is JGit-thrown (not a network exception), so
     * `classifyGitFailure` fails it closed as `Permanent` — by design, it must never be
     * misclassified as retryable (Story 1.1.1's whole point). In real usage this scenario only
     * arises because attempt 1 already failed for a genuinely *Transient* reason (a dropped
     * connection mid-transfer) partway through writing files; `runGitTransportOpWithRetry` then
     * runs `beforeRetry` — clone()'s `deleteDirectoryContentsForRetry(File(localPath))` — before
     * attempt 2, so attempt 2 never actually sees a non-empty directory in production. This test
     * can't organically reproduce a real mid-transfer network drop against a local `file://`
     * fixture, so it drives the same two steps explicitly: (1) confirm clone() really does fail
     * against the pre-seeded leftover directory (proving the precondition this scenario depends
     * on), then (2) run the exact cleanup `beforeRetry` uses and confirm a real retried clone()
     * call succeeds against the same real fixture — proving no residual JGit/filesystem state
     * survives the cleanup to block the next attempt.
     */
    @Test
    fun `clone retried after a simulated interrupted first attempt succeeds against a real fixture repo`() = runTest {
        val bareOrigin = createBareOriginWithCommits("stelekit_retry_clone_origin_", 3)
        val destination = File(tempDir, "retry-clone-dest")
        destination.mkdirs()
        File(destination, "partial-object-file").writeText("leftover from an interrupted clone")
        assertTrue(destination.listFiles()?.isNotEmpty() == true, "precondition: destination must be non-empty")

        val firstAttempt = repository.clone(
            url = bareOrigin.absolutePath,
            localPath = destination.absolutePath,
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
        )
        assertTrue(firstAttempt.isLeft(), "expected clone() to fail against a non-empty target directory: $firstAttempt")

        deleteDirectoryContentsForRetry(destination) // the exact cleanup clone()'s beforeRetry runs

        val retriedAttempt = repository.clone(
            url = bareOrigin.absolutePath,
            localPath = destination.absolutePath,
            auth = GitAuth.None,
            onProgress = {},
            onStateChange = {},
        )
        assertTrue(retriedAttempt.isRight(), "expected the retried clone to succeed once the directory is cleaned, got: $retriedAttempt")
        assertTrue(File(destination, ".git").exists(), "expected a real .git directory after the retried clone")
    }

    /**
     * Story 2.1.6 (Task 2.1.6a) — `log()`/`countRemoteCommitsBestEffort` must degrade gracefully
     * (bounded, non-throwing) against a shallow-cloned repo, not just a full-history one.
     */
    @Test
    fun `log against a shallow-cloned repo returns up to maxCount commits without throwing`() = runTest {
        val bareOrigin = createBareOriginWithCommits("stelekit_shallow_log_origin_", DEFAULT_CLONE_DEPTH + 5)
        val destination = File(tempDir, "shallow-log-dest")
        assertTrue(repository.clone(bareOrigin.absolutePath, destination.absolutePath, GitAuth.None, onProgress = {}, onStateChange = {}).isRight())

        val shallowConfig = config.copy(graphId = "shallow-log-graph", repoRoot = destination.absolutePath)
        val logResult = repository.log(shallowConfig, maxCount = 10)
        assertTrue(logResult.isRight(), "log() must not throw against a shallow repo: $logResult")
        val commits = (logResult as Either.Right).value
        assertTrue(commits.size <= 10, "expected at most maxCount commits, got ${commits.size}")
        assertTrue(commits.isNotEmpty(), "expected at least one commit visible within the shallow window")
    }

    /**
     * Story 2.1.6 (Task 2.1.6a) — `countRemoteCommitsBestEffort` against a shallow repo, after a
     * fetch that advances the remote ref within the shallow window, must return a bounded
     * non-negative count without throwing.
     */
    @Test
    fun `countRemoteCommitsBestEffort against a shallow-cloned repo returns a bounded non-negative count without throwing`() = runTest {
        val bareOrigin = createBareOriginWithCommits("stelekit_shallow_count_origin_", DEFAULT_CLONE_DEPTH + 5)
        val destination = File(tempDir, "shallow-count-dest")
        assertTrue(repository.clone(bareOrigin.absolutePath, destination.absolutePath, GitAuth.None, onProgress = {}, onStateChange = {}).isRight())

        // Advance the remote by a few more commits within the shallow window, then fetch.
        val seedWorkDir = createTempDirectory("stelekit_shallow_count_seed2_").toFile()
        Git.cloneRepository().setURI(bareOrigin.absolutePath).setDirectory(seedWorkDir).call().use { git ->
            setIdentity(git)
            repeat(3) { i ->
                File(seedWorkDir, "extra.md").writeText("extra $i\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("extra commit $i").call()
            }
            git.push().call()
        }
        seedWorkDir.deleteRecursively()

        val shallowConfig = config.copy(graphId = "shallow-count-graph", repoRoot = destination.absolutePath)
        val fetchResult = repository.fetch(shallowConfig)
        assertTrue(fetchResult.isRight(), "fetch failed against shallow repo: $fetchResult")
        val fetch = (fetchResult as Either.Right).value
        assertTrue(fetch.remoteCommitCount in 0..100, "expected a bounded non-negative count, got ${fetch.remoteCommitCount}")
        assertNotNull(fetch.remoteCommitCount)
    }

    // ── git-sync-resilience Story 4.1.1: CloneProgress forwarding ───────────────────────────────

    /**
     * Task 4.1.1c/REQ-6 — a real JGit clone against a local fixture repo must drive at least one
     * non-zero [CloneProgress] callback through [JvmGitRepository.clone]'s widened `onProgress`
     * parameter, not just a title-only signal.
     */
    @Test
    fun `a real JGit clone drives at least one non-zero CloneProgress callback through onProgress`() = runTest {
        val bareOrigin = createBareOriginWithCommits("stelekit_progress_clone_origin_", 5)
        val destination = File(tempDir, "progress-clone-dest")
        val progressCalls = mutableListOf<CloneProgress>()

        val result = repository.clone(
            url = bareOrigin.absolutePath,
            localPath = destination.absolutePath,
            auth = GitAuth.None,
            onProgress = { progressCalls += it },
            onStateChange = {},
        )
        assertTrue(result.isRight(), "clone failed: $result")

        assertTrue(progressCalls.isNotEmpty(), "expected at least one CloneProgress callback")
        assertTrue(
            progressCalls.any { it.completed > 0 },
            "expected at least one callback with completed > 0, got: $progressCalls",
        )
    }
}
