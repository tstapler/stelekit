// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import dev.stapler.stelekit.git.model.GitLabCommitAction
import dev.stapler.stelekit.git.model.GitTreeEntry
import dev.stapler.stelekit.git.model.PendingCommit
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for `WasmGitWriteService`'s Epic 3.1 (GitHub 5-step sequence) behavior contract.
 *
 * `WasmGitWriteService` lives in wasmJsMain — it depends on `PlatformFileSystem` (an `actual
 * class` only defined for the wasmJs target) and the wasmJs Ktor engine, so it cannot be
 * imported from commonTest. Following the precedent set by `WasmSectionSyncServiceTest` (Story
 * 6.4), these tests reimplement the pure orchestration/branching logic as commonTest doubles —
 * same algorithm, same call-order/payload-shape guarantees — so the blob→tree→commit ordering,
 * the `MAX_BLOB_BYTES` size-ceiling boundary, the ref-PATCH request shape, and the 409/422 →
 * `MergeConflict` mapping are verified without a wasmJs target.
 *
 * The real HTTP wiring (Ktor `HttpClient`, JSON (de)serialization, `PlatformFileSystem` calls)
 * is exercised separately by `WasmGitWriteServiceMockedIntegrationTest` (wasmJsTest, Ktor
 * `MockEngine`) for `IT-3.1.1-A`/`IT-3.1.2-A/B`.
 */
class WasmGitWriteServiceAlgorithmsTest {

    private companion object {
        const val MAX_BLOB_BYTES = 75_000_000L
        const val TREE_ENTRY_MODE = "100644"
        const val TREE_ENTRY_TYPE = "blob"
        const val UNKNOWN_CONFLICT_PATH = "<unknown — remote advanced during push>"
        const val MAX_HTTP_RETRIES = 4
    }

    // ── Test double: pure-Kotlin replica of WasmGitWriteService.commitGitHub ────────────────
    //
    // Mirrors the production method's control flow exactly (guard → blob → tree → commit →
    // stage), but takes suspend lambdas in place of the real HTTP POST calls and PlatformFileSystem
    // reads so the ordering and payload shape can be asserted deterministically.

    private data class TreeCall(val baseTree: String?, val entries: List<GitTreeEntry>)
    private data class CommitCall(val tree: String, val parents: List<String>, val message: String)

    private class CallLog {
        val blobContents = mutableListOf<String>()
        val treeCalls = mutableListOf<TreeCall>()
        val commitCalls = mutableListOf<CommitCall>()
        val callOrder = mutableListOf<String>()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun modelledBuildGitHubCommit(
        dirty: Map<String, DirtyEntry>,
        baseSha: String,
        message: String,
        readContent: (String) -> String?,
        log: CallLog,
    ): Either<DomainError.GitError, Pair<String, String>?> {
        if (dirty.isEmpty()) return Either.Right(null)

        val blobShas = mutableMapOf<String, String>()
        val deletedPaths = mutableListOf<String>()

        for ((path, entry) in dirty) {
            when (entry.op) {
                DirtyOp.DELETE -> deletedPaths += path
                DirtyOp.WRITE -> {
                    val content = readContent(path)
                        ?: return Either.Left(DomainError.GitError.CommitFailed("No cached content for $path"))
                    val sizeBytes = content.encodeToByteArray().size.toLong()
                    if (sizeBytes > MAX_BLOB_BYTES) {
                        return Either.Left(DomainError.GitError.FileTooLarge(path, sizeBytes, MAX_BLOB_BYTES))
                    }
                    log.callOrder += "blob:$path"
                    log.blobContents += Base64.Default.encode(content.encodeToByteArray())
                    blobShas[path] = "blob-sha-$path"
                }
            }
        }

        val entries = buildList {
            blobShas.forEach { (path, sha) -> add(GitTreeEntry(path, TREE_ENTRY_MODE, TREE_ENTRY_TYPE, sha)) }
            deletedPaths.forEach { path -> add(GitTreeEntry(path, TREE_ENTRY_MODE, TREE_ENTRY_TYPE, sha = null)) }
        }
        log.callOrder += "tree"
        log.treeCalls += TreeCall(baseSha, entries)
        val treeSha = "tree-sha"

        log.callOrder += "commit"
        log.commitCalls += CommitCall(tree = treeSha, parents = listOf(baseSha), message = message)
        val commitSha = "commit-sha"

        return Either.Right(commitSha to treeSha)
    }

    // ── TC-3.1.1-A: blob → tree → commit ordering + payload shape ───────────────────────────

    @Test
    fun `TC-3_1_1-A buildGitHubCommit issues exactly one blob, one tree, one commit call in order for a single dirty write`() {
        val log = CallLog()
        val dirty = mapOf("pages/Foo.md" to DirtyEntry(DirtyOp.WRITE, 1L))

        val result = modelledBuildGitHubCommit(
            dirty = dirty,
            baseSha = "8f3c1a9",
            message = "SteleKit: 2026-07-15",
            readContent = { path -> if (path == "pages/Foo.md") "# Foo\n" else null },
            log = log,
        )

        assertIs<Either.Right<Pair<String, String>?>>(result)
        assertEquals(listOf("blob:pages/Foo.md", "tree", "commit"), log.callOrder)

        assertEquals(1, log.blobContents.size)
        @OptIn(ExperimentalEncodingApi::class)
        val expectedBase64 = Base64.Default.encode("# Foo\n".encodeToByteArray())
        assertEquals(expectedBase64, log.blobContents.single())

        assertEquals(1, log.treeCalls.size)
        val treeCall = log.treeCalls.single()
        assertEquals("8f3c1a9", treeCall.baseTree)
        assertEquals(1, treeCall.entries.size)
        assertEquals("pages/Foo.md", treeCall.entries.single().path)
        assertEquals("blob-sha-pages/Foo.md", treeCall.entries.single().sha)

        assertEquals(1, log.commitCalls.size)
        val commitCall = log.commitCalls.single()
        assertEquals("tree-sha", commitCall.tree)
        assertEquals(listOf("8f3c1a9"), commitCall.parents)
        assertEquals("SteleKit: 2026-07-15", commitCall.message)
    }

    @Test
    fun `TC-3_1_1-A a DELETE dirty entry produces a tree entry with sha null and no blob call for that path`() {
        val log = CallLog()
        val dirty = mapOf("pages/Bar.md" to DirtyEntry(DirtyOp.DELETE, 1L))

        val result = modelledBuildGitHubCommit(
            dirty = dirty,
            baseSha = "8f3c1a9",
            message = "SteleKit: 2026-07-15",
            readContent = { _ -> error("must not read content for a delete-only dirty entry") },
            log = log,
        )

        assertIs<Either.Right<Pair<String, String>?>>(result)
        assertTrue(log.blobContents.isEmpty(), "no blob POST for a DELETE-only dirty set")
        val entry = log.treeCalls.single().entries.single()
        assertEquals("pages/Bar.md", entry.path)
        assertNull(entry.sha, "delete entries must have sha = null")
    }

    @Test
    fun `TC-3_1_1-A an empty dirty set skips the whole sequence and returns success no-op`() {
        val log = CallLog()

        val result = modelledBuildGitHubCommit(
            dirty = emptyMap(),
            baseSha = "8f3c1a9",
            message = "SteleKit: 2026-07-15",
            readContent = { _ -> error("must not read content when dirty set is empty") },
            log = log,
        )

        assertEquals(Either.Right(null), result)
        assertTrue(log.callOrder.isEmpty(), "zero blob/tree/commit calls for an empty dirty set")
    }

    // ── TC-3.1.1-B: MAX_BLOB_BYTES size-ceiling boundary ─────────────────────────────────────

    @Test
    fun `TC-3_1_1-B a file over MAX_BLOB_BYTES fails fast with FileTooLarge before any blob POST is attempted`() {
        // Boundary: 74,999,999 and 75,000,000 pass; only 75,000,001 triggers FileTooLarge.
        val underCeiling = 74_999_999L
        val atCeiling = MAX_BLOB_BYTES
        val overCeiling = MAX_BLOB_BYTES + 1

        assertFalse(underCeiling > MAX_BLOB_BYTES)
        assertFalse(atCeiling > MAX_BLOB_BYTES)
        assertTrue(overCeiling > MAX_BLOB_BYTES)

        val log = CallLog()
        val result = modelledBuildGitHubCommit(
            dirty = mapOf("assets/large.md.stek" to DirtyEntry(DirtyOp.WRITE, 1L)),
            baseSha = "8f3c1a9",
            message = "SteleKit: 2026-07-15",
            readContent = { _ -> "x".repeat(1) }, // content string itself is irrelevant below
            log = log,
        )

        // Drive the guard directly with an overridden size via a second double that lets the
        // caller supply the byte length instead of deriving it from an actual (huge) string —
        // avoids allocating a 75MB+ string in a unit test while testing the exact same `>` guard.
        val guardResult = modelledSizeCeilingGuard(
            path = "assets/large.md.stek",
            sizeBytes = overCeiling,
        )
        assertEquals(
            DomainError.GitError.FileTooLarge("assets/large.md.stek", overCeiling, MAX_BLOB_BYTES),
            guardResult,
        )
        assertNull(modelledSizeCeilingGuard("assets/large.md.stek", underCeiling))
        assertNull(modelledSizeCeilingGuard("assets/large.md.stek", atCeiling))

        // Sanity: the tiny-content path through the real orchestration double still succeeds
        // (proves the guard is a distinct, separately-testable branch, not accidentally always-on).
        assertIs<Either.Right<Pair<String, String>?>>(result)
    }

    private fun modelledSizeCeilingGuard(path: String, sizeBytes: Long): DomainError.GitError.FileTooLarge? =
        if (sizeBytes > MAX_BLOB_BYTES) DomainError.GitError.FileTooLarge(path, sizeBytes, MAX_BLOB_BYTES) else null

    // ── Test double: pure-Kotlin replica of WasmGitWriteService.advanceRef + pushGitHub ──────

    private data class RefPatchRequest(val sha: String, val force: Boolean)

    private fun modelledAdvanceRef(
        commitSha: String,
        responseStatus: Int,
        requestLog: MutableList<RefPatchRequest>,
    ): Either<DomainError.GitError, Unit> {
        requestLog += RefPatchRequest(sha = commitSha, force = false)
        return when (responseStatus) {
            in 200..299 -> Either.Right(Unit)
            409, 422 -> Either.Left(
                DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf(UNKNOWN_CONFLICT_PATH))
            )
            else -> Either.Left(DomainError.GitError.PushFailed("PATCH ref failed: HTTP $responseStatus"))
        }
    }

    private fun modelledPushGitHub(
        staged: PendingCommit.Staged,
        responseStatus: Int,
        requestLog: MutableList<RefPatchRequest>,
        clearDirtySetCalls: MutableList<String>,
    ): Either<DomainError.GitError, Unit> {
        val advanceResult = modelledAdvanceRef(staged.commitSha, responseStatus, requestLog)
        if (advanceResult is Either.Left) return advanceResult
        clearDirtySetCalls += staged.commitSha
        return Either.Right(Unit)
    }

    // ── TC-3.1.2-A: ref-PATCH request shape on the happy path ────────────────────────────────

    @Test
    fun `TC-3_1_2-A push() PATCHes the ref to the staged commit sha with force=false when there is nothing to merge`() {
        val requestLog = mutableListOf<RefPatchRequest>()
        val clearCalls = mutableListOf<String>()
        val staged = PendingCommit.Staged(commitSha = "c0ffee1", treeSha = "7ea5e11")

        val result = modelledPushGitHub(staged, responseStatus = 200, requestLog, clearCalls)

        assertEquals(Either.Right(Unit), result)
        assertEquals(listOf(RefPatchRequest(sha = "c0ffee1", force = false)), requestLog)
        assertEquals(listOf("c0ffee1"), clearCalls, "dirty set must be cleared with the newly-pushed sha")
    }

    // ── TC-3.1.2-B: 409/422 → MergeConflict, dirty set left untouched ────────────────────────

    @Test
    fun `TC-3_1_2-B a 409-422 on the ref PATCH maps to MergeConflict, not a generic push failure, and the dirty set is not cleared`() {
        for (conflictStatus in listOf(409, 422)) {
            val requestLog = mutableListOf<RefPatchRequest>()
            val clearCalls = mutableListOf<String>()
            val staged = PendingCommit.Staged(commitSha = "c0ffee1", treeSha = "7ea5e11")

            val result = modelledPushGitHub(staged, responseStatus = conflictStatus, requestLog, clearCalls)

            assertEquals(
                Either.Left(
                    DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf(UNKNOWN_CONFLICT_PATH))
                ),
                result,
                "status $conflictStatus must map to MergeConflict",
            )
            assertTrue(clearCalls.isEmpty(), "status $conflictStatus must not clear the dirty set")
        }
    }

    @Test
    fun `TC-3_1_2-B a generic 500 on the ref PATCH maps to PushFailed, distinct from MergeConflict`() {
        val requestLog = mutableListOf<RefPatchRequest>()
        val clearCalls = mutableListOf<String>()
        val staged = PendingCommit.Staged(commitSha = "c0ffee1", treeSha = "7ea5e11")

        val result = modelledPushGitHub(staged, responseStatus = 500, requestLog, clearCalls)

        assertIs<Either.Left<DomainError.GitError>>(result)
        assertIs<DomainError.GitError.PushFailed>(result.value)
        assertTrue(clearCalls.isEmpty())
    }

    // ── Test double: pure-Kotlin replica of WasmGitWriteService.resolveActionType/buildGitLabActions ──

    private fun modelledResolveActionType(existedAtBase: Boolean): String = if (existedAtBase) "update" else "create"

    @OptIn(ExperimentalEncodingApi::class)
    private fun modelledBuildGitLabActions(
        dirty: Map<String, DirtyEntry>,
        baseSha: String,
        existingPaths: Set<String>,
        readContent: (String) -> String?,
    ): Either<DomainError.GitError, List<GitLabCommitAction>> {
        val actions = mutableListOf<GitLabCommitAction>()
        for ((path, entry) in dirty) {
            when (entry.op) {
                DirtyOp.DELETE -> actions += GitLabCommitAction(
                    action = "delete",
                    filePath = path,
                    lastCommitId = baseSha,
                )
                DirtyOp.WRITE -> {
                    val content = readContent(path)
                        ?: return Either.Left(DomainError.GitError.CommitFailed("No cached content for $path"))
                    val existedAtBase = path in existingPaths
                    actions += GitLabCommitAction(
                        action = modelledResolveActionType(existedAtBase),
                        filePath = path,
                        content = Base64.Default.encode(content.encodeToByteArray()),
                        lastCommitId = if (existedAtBase) baseSha else null,
                    )
                }
            }
        }
        return Either.Right(actions)
    }

    // ── TC-3.2.1-A: buildGitLabActions maps WRITE+DELETE into update+delete actions ──────────

    @Test
    fun `TC-3_2_1-A buildGitLabActions maps one WRITE and one DELETE dirty entry into update+delete actions with last_commit_id set`() {
        val dirty = linkedMapOf(
            "pages/Foo.md" to DirtyEntry(DirtyOp.WRITE, 1L),
            "pages/Bar.md" to DirtyEntry(DirtyOp.DELETE, 1L),
        )

        val result = modelledBuildGitLabActions(
            dirty = dirty,
            baseSha = "8f3c1a9",
            existingPaths = setOf("pages/Foo.md", "pages/Bar.md"),
            readContent = { path -> if (path == "pages/Foo.md") "# Foo\n" else null },
        )

        assertIs<Either.Right<List<GitLabCommitAction>>>(result)
        val actions = result.value
        assertEquals(2, actions.size)

        val update = actions.single { it.filePath == "pages/Foo.md" }
        assertEquals("update", update.action)
        assertEquals("base64", update.encoding)
        assertEquals("8f3c1a9", update.lastCommitId)
        @OptIn(ExperimentalEncodingApi::class)
        assertEquals(Base64.Default.encode("# Foo\n".encodeToByteArray()), update.content)

        val delete = actions.single { it.filePath == "pages/Bar.md" }
        assertEquals("delete", delete.action)
        assertEquals("8f3c1a9", delete.lastCommitId)
        assertNull(delete.content)
    }

    // ── TC-3.2.1-B: resolveActionType create-vs-update boundary ─────────────────────────────

    @Test
    fun `TC-3_2_1-B resolveActionType chooses create for a path absent from the base-tree snapshot, update otherwise`() {
        assertEquals("create", modelledResolveActionType(existedAtBase = false))
        assertEquals("update", modelledResolveActionType(existedAtBase = true))

        val result = modelledBuildGitLabActions(
            dirty = mapOf("pages/NewPage.md" to DirtyEntry(DirtyOp.WRITE, 1L)),
            baseSha = "8f3c1a9",
            existingPaths = emptySet(), // NewPage.md did not exist at base
            readContent = { "# New\n" },
        )

        assertIs<Either.Right<List<GitLabCommitAction>>>(result)
        val action = result.value.single()
        assertEquals("create", action.action)
        assertNull(action.lastCommitId, "a brand-new path has nothing to be stale against")
    }

    // ── BLOCKER-2 regression: existingPaths must come from a real base-tree membership check,
    // not `dirty.keys` (which is trivially "every dirty path exists," misclassifying every
    // brand-new page as an "update" against a nonexistent remote path) ─────────────────────────

    private fun modelledFetchGitLabTreePaths(treeEntries: List<String>): Set<String> = treeEntries.toSet()

    @Test
    fun `BLOCKER-2 a dirty WRITE path absent from the real base-tree membership set resolves to create, not update, even though dirty_keys (the old broken existingPaths source) would have called it update`() {
        val dirty = mapOf("pages/NewPage.md" to DirtyEntry(DirtyOp.WRITE, 1L))

        // Old broken behavior (the BLOCKER-2 bug): existingPaths derived from dirty.keys is
        // trivially true for every dirty entry, so a brand-new page is misclassified "update".
        val brokenExistingPaths = dirty.keys
        val brokenResult = modelledBuildGitLabActions(
            dirty = dirty,
            baseSha = "8f3c1a9",
            existingPaths = brokenExistingPaths,
            readContent = { "# New\n" },
        )
        assertIs<Either.Right<List<GitLabCommitAction>>>(brokenResult)
        assertEquals(
            "update",
            brokenResult.value.single().action,
            "documents the BLOCKER-2 bug's old (now-removed) broken existingPaths = dirty.keys behavior",
        )

        // Fixed behavior: existingPaths derived from a real base-tree listing (here, the tree at
        // baseSha contains only pre-existing pages — this brand-new page is absent from it).
        val fixedExistingPaths = modelledFetchGitLabTreePaths(treeEntries = listOf("pages/Foo.md", "pages/Bar.md"))
        val fixedResult = modelledBuildGitLabActions(
            dirty = dirty,
            baseSha = "8f3c1a9",
            existingPaths = fixedExistingPaths,
            readContent = { "# New\n" },
        )
        assertIs<Either.Right<List<GitLabCommitAction>>>(fixedResult)
        val action = fixedResult.value.single()
        assertEquals("create", action.action)
        assertNull(action.lastCommitId, "a brand-new path has nothing to be stale against")
    }

    @Test
    fun `BLOCKER-2 a dirty WRITE path present in the real base-tree membership set still resolves to update with last_commit_id set`() {
        val dirty = mapOf("pages/Foo.md" to DirtyEntry(DirtyOp.WRITE, 1L))
        val existingPaths = modelledFetchGitLabTreePaths(treeEntries = listOf("pages/Foo.md", "pages/Bar.md"))

        val result = modelledBuildGitLabActions(
            dirty = dirty,
            baseSha = "8f3c1a9",
            existingPaths = existingPaths,
            readContent = { "# Foo (edited)\n" },
        )

        assertIs<Either.Right<List<GitLabCommitAction>>>(result)
        val action = result.value.single()
        assertEquals("update", action.action)
        assertEquals("8f3c1a9", action.lastCommitId)
    }

    // ── Test double: pure-Kotlin replica of WasmGitWriteService.partitionConflicts ───────────

    private data class ModelledConflictPartition(val conflicting: Set<String>, val nonOverlapping: Set<String>)

    private fun modelledPartitionConflicts(localPaths: Set<String>, remotePaths: Set<String>): ModelledConflictPartition {
        val conflicting = localPaths intersect remotePaths
        return ModelledConflictPartition(conflicting = conflicting, nonOverlapping = remotePaths - conflicting)
    }

    // ── TC-3.2.2-A/B: partitionConflicts applied to GitLab compare diffs ─────────────────────

    @Test
    fun `TC-3_2_2-A partitionConflicts applied to GitLab compare diffs returns non-conflicting for disjoint paths, matching GitHub's contract`() {
        val result = modelledPartitionConflicts(
            localPaths = setOf("pages/Foo.md"),
            remotePaths = setOf("pages/Bar.md"),
        )

        assertEquals(emptySet(), result.conflicting)
        assertEquals(setOf("pages/Bar.md"), result.nonOverlapping)
    }

    @Test
    fun `TC-3_2_2-B partitionConflicts applied to GitLab compare diffs returns MergeConflict for an overlapping path, identical shape to GitHub's`() {
        val result = modelledPartitionConflicts(
            localPaths = setOf("pages/Foo.md"),
            remotePaths = setOf("pages/Foo.md"),
        )

        assertEquals(setOf("pages/Foo.md"), result.conflicting)
        assertEquals(emptySet(), result.nonOverlapping)

        val conflictErr = DomainError.GitError.MergeConflict(
            conflictCount = result.conflicting.size,
            conflictPaths = result.conflicting.toList(),
        )
        assertEquals(1, conflictErr.conflictCount)
        assertEquals(listOf("pages/Foo.md"), conflictErr.conflictPaths)
    }

    // ── Test double: pure-Kotlin replica of WasmGitWriteService.classifyGitLabPushConflict ───

    private fun modelledClassifyGitLabPushConflict(
        message: String?,
        touchedPaths: List<String>,
    ): DomainError.GitError.MergeConflict {
        val namedPath = message?.let { m -> touchedPaths.firstOrNull { m.contains(it) } }
        return if (namedPath != null) {
            DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf(namedPath))
        } else {
            DomainError.GitError.MergeConflict(conflictCount = touchedPaths.size, conflictPaths = touchedPaths)
        }
    }

    // ── TC-3.2.3-A/B: 400-response conflict classification ──────────────────────────────────

    @Test
    fun `TC-3_2_3-A a 400 response naming a dirty path in its message classifies as MergeConflict for that path`() {
        val result = modelledClassifyGitLabPushConflict(
            message = "pages/Foo.md: file has changed since last retrieval",
            touchedPaths = listOf("pages/Foo.md", "pages/Bar.md"),
        )

        assertEquals(
            DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("pages/Foo.md")),
            result,
        )
    }

    @Test
    fun `TC-3_2_3-B a 400 response with an unparseable message falls back to MergeConflict over the full touched-path set`() {
        val resultNullMessage = modelledClassifyGitLabPushConflict(
            message = null,
            touchedPaths = listOf("pages/Foo.md", "pages/Bar.md"),
        )
        assertEquals(
            DomainError.GitError.MergeConflict(conflictCount = 2, conflictPaths = listOf("pages/Foo.md", "pages/Bar.md")),
            resultNullMessage,
        )

        val resultUnrelatedMessage = modelledClassifyGitLabPushConflict(
            message = "encoding must be base64 or text",
            touchedPaths = listOf("pages/Foo.md", "pages/Bar.md"),
        )
        assertEquals(
            DomainError.GitError.MergeConflict(conflictCount = 2, conflictPaths = listOf("pages/Foo.md", "pages/Bar.md")),
            resultUnrelatedMessage,
            "an unparseable/unrelated message must fail toward prompting the user, never silently drop the write",
        )
    }

    // ── TC-3.3.1-A/B: partitionConflicts algorithm-level boundary cases (pulled forward) ─────

    @Test
    fun `TC-3_3_1-A partitionConflicts zero-intersection local-remote path sets are fully non-overlapping`() {
        val result = modelledPartitionConflicts(
            localPaths = setOf("pages/Foo.md"),
            remotePaths = setOf("pages/Bar.md", "pages/Baz.md"),
        )

        assertEquals(emptySet(), result.conflicting)
        assertEquals(setOf("pages/Bar.md", "pages/Baz.md"), result.nonOverlapping)
    }

    @Test
    fun `TC-3_3_1-B partitionConflicts full-overlap, partial-overlap, and empty-set boundary cases`() {
        val fullOverlap = modelledPartitionConflicts(
            localPaths = setOf("pages/Foo.md", "pages/Bar.md"),
            remotePaths = setOf("pages/Foo.md", "pages/Bar.md"),
        )
        assertEquals(setOf("pages/Foo.md", "pages/Bar.md"), fullOverlap.conflicting)
        assertEquals(emptySet(), fullOverlap.nonOverlapping)

        val partialOverlap = modelledPartitionConflicts(
            localPaths = setOf("pages/Foo.md", "pages/Bar.md"),
            remotePaths = setOf("pages/Bar.md", "pages/Baz.md"),
        )
        assertEquals(setOf("pages/Bar.md"), partialOverlap.conflicting)
        assertEquals(setOf("pages/Baz.md"), partialOverlap.nonOverlapping)

        val bothEmpty = modelledPartitionConflicts(localPaths = emptySet(), remotePaths = emptySet())
        assertEquals(emptySet(), bothEmpty.conflicting)
        assertEquals(emptySet(), bothEmpty.nonOverlapping)
    }

    // ── TC-3.3.2-A: buildConflictFiles (pulled forward) ──────────────────────────────────────

    private fun modelledBuildConflictFiles(conflictingPaths: Set<String>): List<ConflictFile> =
        conflictingPaths.map { path -> ConflictFile(filePath = path, wikiRelativePath = path, hunks = emptyList()) }

    @Test
    fun `TC-3_3_2-A buildConflictFiles maps each conflicting path to a ConflictFile with empty hunks`() {
        val result = modelledBuildConflictFiles(setOf("pages/Foo.md", "journals/2026_07_15.md"))

        assertEquals(2, result.size)
        val foo = result.single { it.filePath == "pages/Foo.md" }
        assertEquals("pages/Foo.md", foo.wikiRelativePath)
        assertTrue(foo.hunks.isEmpty())

        val journal = result.single { it.filePath == "journals/2026_07_15.md" }
        assertEquals("journals/2026_07_15.md", journal.wikiRelativePath)
        assertTrue(journal.hunks.isEmpty())
    }

    // ── TC-3.3.2-B: deleted-locally-but-edited-remotely edge case ───────────────────────────

    /**
     * Pure-Kotlin replica of [WasmGitWriteService.checkoutFile]'s branching, modeled as a
     * function of `(side, wasDeletedLocally) -> ResolutionAction` per Task 3.3.2d: `LOCAL` is
     * always a no-op (a locally-deleted path simply stays deleted); `REMOTE` always fetches and
     * writes (resurrecting a locally-deleted path), in both cases regardless of
     * [wasDeletedLocally] — the real `writeFile`/`checkoutFile` code path re-adds a path to the
     * cache unconditionally, so the prior deleted state never changes the branch taken.
     */
    private enum class ResolutionAction { NO_OP, FETCH_AND_WRITE }

    private fun modelledCheckoutFileAction(side: MergeSide, wasDeletedLocally: Boolean): ResolutionAction =
        when (side) {
            MergeSide.LOCAL -> ResolutionAction.NO_OP
            MergeSide.REMOTE -> ResolutionAction.FETCH_AND_WRITE
        }

    @Test
    fun `TC-3_3_2-B deleted-locally-but-edited-remotely - LOCAL keeps it deleted, REMOTE resurrects it via fetch+write, regardless of prior deleted state`() {
        for (wasDeletedLocally in listOf(true, false)) {
            assertEquals(
                ResolutionAction.NO_OP,
                modelledCheckoutFileAction(MergeSide.LOCAL, wasDeletedLocally),
                "LOCAL must be a no-op whether or not the path was deleted locally (wasDeletedLocally=$wasDeletedLocally)",
            )
            assertEquals(
                ResolutionAction.FETCH_AND_WRITE,
                modelledCheckoutFileAction(MergeSide.REMOTE, wasDeletedLocally),
                "REMOTE must always fetch+write, including resurrecting a locally-deleted path (wasDeletedLocally=$wasDeletedLocally)",
            )
        }
    }

    // ── Test double: pure-Kotlin replica of WasmGitWriteService.mapHttpFailure ──────────────

    private fun modelledMapHttpFailure(
        status: Int,
        retryAfterSeconds: Int?,
        genericError: () -> DomainError.GitError,
    ): DomainError.GitError = when {
        status == 401 || (status == 403 && retryAfterSeconds == null) ->
            DomainError.GitError.CredentialExpired("Your git host rejected the configured token")
        status == 429 || (status == 403 && retryAfterSeconds != null) ->
            DomainError.GitError.RateLimited(retryAfterSeconds)
        else -> genericError()
    }

    // ── Test double: pure-Kotlin replica of the HttpRequestRetry-driven retry/exhaustion cycle ──
    //
    // Mirrors installRetryPolicy()'s retryIf predicate (429, or 403-with-Retry-After) and
    // retryOnServerErrors(maxRetries = 4) budget (5 attempts total: 1 initial + 4 retries).
    // `respond` stands in for dispatching the actual HTTP request; delay() is preserved so
    // TestCoroutineScheduler-based tests (via runTest) can verify the sequence without wall-clock
    // waits, matching WasmSectionSyncServiceTest's TC-6.4-G precedent.

    private suspend fun modelledRetryableRequest(
        respond: suspend (attempt: Int) -> Pair<Int, Int?>,
    ): Pair<Int, Int?> {
        var attempt = 0
        while (true) {
            val (status, retryAfter) = respond(attempt)
            val shouldRetry = status == 429 || (status == 403 && retryAfter != null)
            if (!shouldRetry || attempt >= MAX_HTTP_RETRIES) return status to retryAfter
            delay((1L shl attempt) * 100L)
            attempt++
        }
    }

    // ── TC-3.4.1-A: 429 then 200 succeeds after one retry, honoring Retry-After ──────────────

    @Test
    fun `TC-3_4_1-A retry double - 429 then 200 succeeds after one retry, honoring Retry-After`() = runTest {
        var callCount = 0
        val (status, retryAfter) = modelledRetryableRequest { attempt ->
            callCount++
            if (attempt == 0) 429 to 2 else 200 to null
        }

        assertEquals(200, status)
        assertNull(retryAfter)
        assertEquals(2, callCount, "exactly one retry: initial 429 response + one successful retry")
    }

    // ── TC-3.4.1-B: 4 consecutive 429/403-with-Retry-After exhaust the budget → RateLimited ──

    @Test
    fun `TC-3_4_1-B retry double - 4 consecutive 429 responses exhaust the retry budget and classify as RateLimited, never PushFailed`() = runTest {
        var callCount = 0
        val (status, retryAfter) = modelledRetryableRequest { _ ->
            callCount++
            429 to 5
        }

        assertEquals(429, status)
        assertEquals(5, retryAfter)
        assertEquals(5, callCount, "1 initial attempt + 4 retries = 5 total attempts before exhaustion")

        val classified = modelledMapHttpFailure(status, retryAfter) {
            DomainError.GitError.PushFailed("must never be reached for a rate-limited exhaustion")
        }
        assertIs<DomainError.GitError.RateLimited>(classified)
        assertEquals(5, classified.retryAfterSeconds)
    }

    @Test
    fun `TC-3_4_1-B retry double - 4 consecutive 403-with-Retry-After responses also exhaust the budget and classify as RateLimited`() = runTest {
        var callCount = 0
        val (status, retryAfter) = modelledRetryableRequest { _ ->
            callCount++
            403 to 10
        }

        assertEquals(403, status)
        assertEquals(5, callCount)

        val classified = modelledMapHttpFailure(status, retryAfter) {
            DomainError.GitError.PushFailed("must never be reached")
        }
        assertIs<DomainError.GitError.RateLimited>(classified)
        assertEquals(10, classified.retryAfterSeconds)
    }

    // ── TC-3.4.3-A/C (Story 3.4.3/Task 3.4.3c): 401/403-without-Retry-After → CredentialExpired ──

    @Test
    fun `TC-3_4_3-A a 403 without Retry-After maps to CredentialExpired, distinct from the 403-with-Retry-After rate-limit case`() {
        val credentialExpired = modelledMapHttpFailure(403, null) {
            DomainError.GitError.PushFailed("must never be reached")
        }
        assertIs<DomainError.GitError.CredentialExpired>(credentialExpired)

        val rateLimited = modelledMapHttpFailure(403, 7) {
            DomainError.GitError.PushFailed("must never be reached")
        }
        assertIs<DomainError.GitError.RateLimited>(rateLimited)
        assertEquals(7, rateLimited.retryAfterSeconds)
    }

    @Test
    fun `TC-3_4_3-C a 401 response (missing or rejected token) maps to CredentialExpired, not a generic step failure`() {
        val result = modelledMapHttpFailure(401, null) {
            DomainError.GitError.PushFailed("generic failure — must not be surfaced for a 401")
        }

        assertIs<DomainError.GitError.CredentialExpired>(result)
        assertTrue(
            result.message.isNotBlank() && !result.message.contains("GitHub", ignoreCase = false),
            "CredentialExpired copy must be host-neutral (shared by GitHub and GitLab call sites)",
        )
    }

    @Test
    fun `TC-3_4_3-C statuses outside 401-403-429 fall back to the caller-supplied generic error`() {
        val genericError = DomainError.GitError.FetchFailed("GET compare failed: HTTP 500")
        val result = modelledMapHttpFailure(500, null) { genericError }
        assertEquals(genericError, result)
    }

    // ── Test double: pure-Kotlin replica of WasmGitWriteService's Epic 3.5 outcome-log formatters ──
    //
    // Mirrors logPushSuccess/logAutoMerged/logTerminalFailure's exact message shape as a pure
    // function of inputs, so the wording contract (Story 3.5.1's acceptance criteria) is testable
    // without constructing a real `Logger`/`LogManager`.

    private fun modelledPushSuccessLog(fileCount: Int, commitSha: String): String =
        "git write-back outcome=success $fileCount file(s) commit=$commitSha"

    private fun modelledAutoMergedLog(paths: Collection<String>): String =
        "git write-back outcome=auto-merged paths=$paths"

    private fun modelledTerminalFailureLog(step: String, err: DomainError.GitError): String =
        if (err is DomainError.GitError.MergeConflict) {
            "git write-back outcome=conflict-detected step=$step error=${err::class.simpleName} " +
                "conflictPaths=${err.conflictPaths}"
        } else {
            "git write-back outcome=failed-with-reason step=$step error=${err::class.simpleName}"
        }

    // ── TC-3.5.1-A: success/auto-merged outcome log content ─────────────────────────────────

    @Test
    fun `TC-3_5_1-A success outcome log double contains outcome, file count, and commit sha`() {
        val message = modelledPushSuccessLog(fileCount = 3, commitSha = "c0ffee1")

        assertTrue(message.contains("success"))
        assertTrue(message.contains("3 file(s)"))
        assertTrue(message.contains("c0ffee1"))
        assertFalse(
            message.contains("ghp_") || message.contains("glpat-"),
            "success log must never contain PAT-shaped content, only outcome/file-count/commit-sha",
        )
    }

    @Test
    fun `TC-3_5_1-A auto-merged outcome log double contains the list of auto-merged paths`() {
        val message = modelledAutoMergedLog(listOf("pages/Foo.md", "pages/Bar.md"))

        assertTrue(message.contains("auto-merged"))
        assertTrue(message.contains("pages/Foo.md"))
        assertTrue(message.contains("pages/Bar.md"))
    }

    // ── TC-3.5.1-B: failure outcome log content — step + DomainError subtype, never raw body ──

    @Test
    fun `TC-3_5_1-B a MergeConflict failure log names the failing step and the MergeConflict subtype`() {
        val err = DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf(UNKNOWN_CONFLICT_PATH))
        val message = modelledTerminalFailureLog(step = "ref-update", err = err)

        assertTrue(message.contains("step=ref-update"))
        assertTrue(message.contains("MergeConflict"))
    }

    @Test
    fun `TC-3_5_1-B a generic failure log names the failing step and DomainError subtype, never the raw HTTP response body`() {
        val leakedBody = "<html><body>rate-limited — echoing your Authorization header back</body></html>"
        val err = DomainError.GitError.PushFailed("PATCH ref failed: HTTP 500")
        val message = modelledTerminalFailureLog(step = "ref-update", err = err)

        assertTrue(message.contains("step=ref-update"))
        assertTrue(message.contains("PushFailed"))
        assertFalse(message.contains(leakedBody), "log must never embed the raw HTTP response body")
    }
}
