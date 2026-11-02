// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.ui.screens.git.FirstSyncPreviewState
import dev.stapler.stelekit.ui.screens.git.loadState
import dev.stapler.stelekit.ui.screens.git.summaryLines
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirstSyncPreviewSourceTest {

    private val preview = FirstSyncPreview(
        syncRef = "origin/main", ahead = 1, behind = 2,
        remoteBranches = listOf(PreviewRemoteBranch("main", true), PreviewRemoteBranch("old", false)),
        remoteBranchesLive = false,
        localOnlyCommits = listOf(PreviewCommit("abc1234", "edit", 0L)),
        filesToDelete = 0,
    )

    private fun source(
        repo: GitRepository = object : StubGitRepository() {
            override suspend fun firstSyncPreview(config: GitConfig) = preview.right()
        },
        config: Either<DomainError, GitConfig?> = sampleConfig.right(),
    ) = FirstSyncPreviewSource(repo, StubConfigRepository(config))

    @Test
    fun `load returns the repository preview for a configured graph`() = runTest {
        assertEquals(preview, source().load("g").getOrNull())
    }

    @Test
    fun `a missing config is NotAGitRepo`() = runTest {
        assertIs<DomainError.GitError.NotAGitRepo>((source(config = Either.Right(null)).load("g") as Either.Left).value)
    }

    @Test
    fun `a config read failure is a typed fetch failure`() = runTest {
        val failed = source(config = Either.Left(DomainError.DatabaseError.NotFound("GitConfig", "g"))).load("g")
        assertIs<DomainError.GitError.FetchFailed>((failed as Either.Left).value)
    }

    @Test
    fun `a preview that never finishes times out with a network failure`() = runTest {
        val hanging = object : StubGitRepository() {
            override suspend fun firstSyncPreview(config: GitConfig): Nothing = awaitCancellation()
        }

        val result = source(hanging).load("g")

        val error = assertIs<DomainError.GitError.NetworkFailure>((result as Either.Left).value)
        assertTrue("15 s" in error.message, error.message)
    }

    @Test
    fun `loadState maps success to Ready and failure to a redacted Failed`() = runTest {
        assertEquals(FirstSyncPreviewState.Ready(preview), source().loadState("g"))
        val failing = object : StubGitRepository() {
            override suspend fun firstSyncPreview(config: GitConfig) =
                Either.Left(DomainError.GitError.FetchFailed("fetch https://user:ghp_secret@host/r.git failed"))
        }
        val state = assertIs<FirstSyncPreviewState.Failed>(source(failing).loadState("g"))
        assertTrue("ghp_secret" !in state.message, state.message)
    }

    @Test
    fun `summary lines describe position branches deletions and local commits`() {
        val lines = preview.summaryLines()
        assertEquals("1 ahead, 2 behind origin/main", lines[0])
        assertEquals("Remote branches (last fetched): main, old (not the sync branch)", lines[1])
        assertEquals("No files would be deleted", lines[2])
        assertEquals(listOf("Local commits not on the remote:", "abc1234 edit"), lines.drop(3))
        val unknown = preview.copy(ahead = null, behind = null, filesToDelete = 4, localOnlyCommits = emptyList(), remoteBranchesLive = true)
        assertEquals("Ahead/behind of origin/main is unknown", unknown.summaryLines()[0])
        assertEquals("4 files would be deleted from the remote", unknown.summaryLines()[2])
        assertEquals(3, unknown.summaryLines().size)
    }

    @Test
    fun `defaults on the repository interface are the safe answers`() = runTest {
        val repo = StubGitRepository()
        assertEquals(RepositoryHealth.Clean, repo.repositoryHealth(sampleConfig))
        assertEquals(MarkerScan.Clean, repo.scanForConflictMarkers(sampleConfig, GuardScope.Staged))
        assertEquals(DeletionSummary.None, repo.deletionSummary(sampleConfig, GuardScope.NetPush))
        assertIs<DomainError.GitError.NotSupported>((repo.firstSyncPreview(sampleConfig) as Either.Left).value)
    }

    @Test
    fun `thresholds read from absent settings are the defaults`() {
        assertEquals(MassChangeThresholds(), MassChangeThresholds.from(null))
        assertNull(MassChangeThresholds().evaluate(DeletionSummary.None))
    }
}
