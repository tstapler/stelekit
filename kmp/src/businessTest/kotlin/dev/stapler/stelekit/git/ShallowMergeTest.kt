// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter

/**
 * git-sync-resilience Story 2.1.5 — [JvmGitRepository.merge]'s shallow-history/merge-base
 * collision guard ([isShallowHistoryInsufficientForMerge] in `GitOperationSupport.kt`), against
 * real JGit object-database fixtures (no mocks). Per pre-mortem.md P1 #2, this covers **both**
 * ways JGit's shallow-history merge-base search can be unreliable — absent and wrong-but-present
 * — not just the absent case.
 *
 * The absent and wrong-but-present fixtures are built with low-level `CommitBuilder`/
 * `ObjectInserter` calls rather than a real `clone --depth`, so the exact object shape each guard
 * condition checks (a merge-base commit with a parent that is genuinely missing from the local
 * object database, and independently marking which commits count as legitimate shallow roots) is
 * deterministic and doesn't depend on incidental fetch-protocol behavior. This still exercises the
 * real [org.eclipse.jgit.revwalk.RevWalk] merge-base search and the real [JvmGitRepository.merge]
 * production code path — nothing about the guard itself is mocked.
 */
class ShallowMergeTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(prefix: String) = createTempDirectory(prefix).toFile().also { tempDirs += it }

    private fun Repository.insertEmptyTree(): ObjectId =
        newObjectInserter().use { inserter ->
            val id = inserter.insert(TreeFormatter())
            inserter.flush()
            id
        }

    private fun Repository.insertCommit(treeId: ObjectId, parents: List<ObjectId>, message: String): ObjectId =
        newObjectInserter().use { inserter ->
            val builder = CommitBuilder()
            builder.setTreeId(treeId)
            val ident = PersonIdent("Stelekit Test", "stelekit-test@example.com")
            builder.setAuthor(ident)
            builder.setCommitter(ident)
            builder.setParentIds(parents)
            builder.setMessage(message)
            val id = inserter.insert(builder)
            inserter.flush()
            id
        }

    private fun Repository.forceSetRef(name: String, id: ObjectId) {
        val update = updateRef(name)
        update.setNewObjectId(id)
        update.forceUpdate()
    }

    private fun baseConfig(repoRoot: String) = GitConfig(
        graphId = "shallow-merge-graph",
        repoRoot = repoRoot,
        wikiSubdir = null,
        authType = GitAuthType.NONE,
        remoteName = "origin",
        remoteBranch = "main",
    )

    /**
     * Happy path (regression guard for the common case): a shallow repo whose local shallow
     * boundary safely covers the true merge base — the merge-base commit's parent chain is fully
     * present locally back to the shallow root — merges exactly as a full-history merge would.
     */
    @Test
    fun `merge against a shallow repo whose shallow boundary safely covers the merge base succeeds exactly as a full-history merge would`() = runTest {
        val repoDir = tempDir("stelekit_shallow_merge_happy_")
        Git.init().setDirectory(repoDir).setInitialBranch("main").call().use { git ->
            val repo = git.repository
            val emptyTree = repo.insertEmptyTree()

            val root = repo.insertCommit(emptyTree, emptyList(), "root (shallow boundary)")
            val r1 = repo.insertCommit(emptyTree, listOf(root), "r1")
            val mergeBase = repo.insertCommit(emptyTree, listOf(r1), "safe merge base")
            val localHead = repo.insertCommit(emptyTree, listOf(mergeBase), "local edit")
            val remoteHead = repo.insertCommit(emptyTree, listOf(mergeBase), "remote edit")

            repo.forceSetRef("refs/heads/main", localHead)
            repo.forceSetRef("refs/remotes/origin/main", remoteHead)
            repo.objectDatabase.setShallowCommits(setOf(root))
        }

        val repository = JvmGitRepository()
        val result = repository.merge(baseConfig(repoDir.absolutePath))
        assertIs<arrow.core.Either.Right<MergeResult>>(result, "expected the safe-merge-base case to merge normally, got: $result")
        assertTrue(!result.value.hasConflicts, "expected a clean merge (both sides share content), got conflicts: ${result.value}")
    }

    /**
     * Absent merge base: two entirely disjoint commit graphs sharing no common ancestor at all —
     * `RevWalk`'s merge-base search finds nothing within reach, not just within the shallow
     * window. `merge()` must fail closed with `ShallowHistoryInsufficient` and create no merge
     * commit.
     */
    @Test
    fun `merge returns Left ShallowHistoryInsufficient and creates no merge commit when no merge base is reachable at all`() = runTest {
        val repoDir = tempDir("stelekit_shallow_merge_absent_")
        lateinit var localHead: ObjectId
        Git.init().setDirectory(repoDir).setInitialBranch("main").call().use { git ->
            val repo = git.repository
            val emptyTree = repo.insertEmptyTree()

            val localRoot = repo.insertCommit(emptyTree, emptyList(), "local shallow root")
            val localMid = repo.insertCommit(emptyTree, listOf(localRoot), "local mid")
            localHead = repo.insertCommit(emptyTree, listOf(localMid), "local HEAD")

            // A completely disjoint graph — no shared ancestry with the local chain above.
            val remoteRoot = repo.insertCommit(emptyTree, emptyList(), "unrelated remote root")
            val remoteHead = repo.insertCommit(emptyTree, listOf(remoteRoot), "unrelated remote HEAD")

            repo.forceSetRef("refs/heads/main", localHead)
            repo.forceSetRef("refs/remotes/origin/main", remoteHead)
            repo.objectDatabase.setShallowCommits(setOf(localRoot))
        }

        val repository = JvmGitRepository()
        val result = repository.merge(baseConfig(repoDir.absolutePath))
        assertIs<arrow.core.Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.ShallowHistoryInsufficient>(result.value)

        Git.open(repoDir).use { git ->
            assertIs<ObjectId>(git.repository.resolve("HEAD"))
            org.junit.Assert.assertEquals(
                "no merge commit must be created", localHead, git.repository.resolve("HEAD"),
            )
        }
    }

    /**
     * Wrong-but-present merge base (pre-mortem.md P1 #2's other shape, distinct from absent):
     * `RevWalk` *does* return a merge-base commit, but one of its parents is missing from the
     * local object database and is not itself a registered shallow root — exactly the spurious
     * shape JGit's own caveat warns can surface instead of a clean "not found". `merge()` must
     * still fail closed rather than trust this merge base.
     */
    @Test
    fun `merge returns Left ShallowHistoryInsufficient and creates no merge commit when RevWalk returns a wrong-but-present merge base with a missing parent`() = runTest {
        val repoDir = tempDir("stelekit_shallow_merge_wrong_present_")
        lateinit var localHead: ObjectId
        Git.init().setDirectory(repoDir).setInitialBranch("main").call().use { git ->
            val repo = git.repository
            val emptyTree = repo.insertEmptyTree()

            // A fabricated OID never inserted into the object database — the "missing parent".
            val fakeMissingParent = ObjectId.fromString("d".repeat(40))
            val mergeBase = repo.insertCommit(emptyTree, listOf(fakeMissingParent), "merge base with a missing parent")
            localHead = repo.insertCommit(emptyTree, listOf(mergeBase), "local HEAD")
            val remoteHead = repo.insertCommit(emptyTree, listOf(mergeBase), "remote HEAD")

            // A separate, unrelated, legitimate shallow root — only here so
            // objectDatabase.shallowCommits is non-empty (the guard is a no-op on a full-history
            // repo). It is deliberately NOT fakeMissingParent, so the guard's "missing parent is
            // itself a registered shallow root" exemption does not apply here.
            val unrelatedShallowRoot = repo.insertCommit(emptyTree, emptyList(), "unrelated shallow root")

            repo.forceSetRef("refs/heads/main", localHead)
            repo.forceSetRef("refs/remotes/origin/main", remoteHead)
            repo.objectDatabase.setShallowCommits(setOf(unrelatedShallowRoot))
        }

        val repository = JvmGitRepository()
        val result = repository.merge(baseConfig(repoDir.absolutePath))
        assertIs<arrow.core.Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.ShallowHistoryInsufficient>(result.value)

        Git.open(repoDir).use { git ->
            org.junit.Assert.assertEquals(
                "no merge commit must be created for the wrong-but-present case either",
                localHead, git.repository.resolve("HEAD"),
            )
        }
    }
}
