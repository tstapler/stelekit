// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ColdSyncRunnerTest {

    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** Counts every call; any working-tree method is answered with success so a stray call shows up in [calls], not as a crash. */
    private class Spy(var fetchResult: Either<DomainError.GitError, FetchResult> = FetchResult(false, 0).right()) : StubGitRepository() {
        val calls = mutableListOf<String>()
        override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> { calls += "fetch"; return fetchResult }
        override suspend fun merge(config: GitConfig): Either<DomainError.GitError, MergeResult> { calls += "merge"; return MergeResult(false, emptyList(), emptyList()).right() }
        override suspend fun commit(config: GitConfig, message: String): Either<DomainError.GitError, String> { calls += "commit"; return "sha".right() }
        override suspend fun push(config: GitConfig): Either<DomainError.GitError, Unit> { calls += "push"; return Unit.right() }
        override suspend fun stageSubdir(config: GitConfig, excludePaths: Set<String>): Either<DomainError.GitError, Unit> { calls += "stageSubdir"; return Unit.right() }
        override suspend fun checkoutFile(config: GitConfig, filePath: String, side: MergeSide): Either<DomainError.GitError, Unit> { calls += "checkout"; return Unit.right() }
        override suspend fun abortMerge(config: GitConfig): Either<DomainError.GitError, Unit> { calls += "abortMerge"; return Unit.right() }
        override suspend fun repositoryHealth(config: GitConfig): RepositoryHealth = RepositoryHealth(false, "MERGING")
        override suspend fun status(config: GitConfig) = GitStatus(true, listOf("u.md"), listOf("m.md")).right()
    }

    private val settings = MapSettings()
    private val staleness = SyncStalenessStore(settings)
    private var now = 1_000_000L

    private val gate = RepoOpGate()

    private fun runner(
        spy: Spy,
        config: GitConfig? = sampleConfig,
        gate: RepoOpGate = this.gate,
        keyFor: (GitConfig) -> String? = { "repo-key" },
    ) = ColdSyncRunner(spy, StubConfigRepository(config.right()), staleness, gate, keyFor) { now }

    @Test
    fun `exactly one fetch runs and no tree-touching call is made, whatever state the tree is in`() = runBlocking {
        val spy = Spy(FetchResult(true, 3).right())

        val outcome = runner(spy).run(sampleConfig.graphId)

        assertEquals(ColdSyncOutcome.Fetched(3, FetchOnlyReason.AppClosed), outcome)
        assertEquals(listOf("fetch"), spy.calls, "a MERGING, dirty tree gets one fetch and nothing else")
    }

    @Test
    fun `the behind count, fetch time and fetch-only reason are persisted`() = runBlocking {
        runner(Spy(FetchResult(true, 5).right())).run(sampleConfig.graphId)

        val s = staleness.read(sampleConfig.graphId)
        assertEquals(5, s.behindCount)
        assertEquals(now, s.lastFetchedAtMs)
        assertEquals(FetchOnlyReason.AppClosed, s.fetchOnlyReason)
        assertNull(s.lastMergedAtMs, "a cold run never records a merge")
    }

    @Test
    fun `a fetch with nothing new records zero behind`() = runBlocking {
        runner(Spy(FetchResult(false, 0).right())).run(sampleConfig.graphId)
        assertEquals(0, staleness.read(sampleConfig.graphId).behindCount)
    }

    @Test
    fun `a typed fetch error is recorded as the outcome and returned, never swallowed`() = runBlocking {
        val error = DomainError.GitError.RemoteBranchNotFound("origin", "main", emptyList())
        val spy = Spy(error.left())

        val outcome = runner(spy).run(sampleConfig.graphId)

        assertEquals(ColdSyncOutcome.Error(error), outcome)
        val last = staleness.read(sampleConfig.graphId).history.last()
        assertEquals(BackgroundOutcomeKind.Error, last.kind)
        assertEquals("RemoteBranchNotFound", last.detail)
        assertEquals(listOf("fetch"), spy.calls)
    }

    @Test
    fun `no git config means nothing to fetch`() = runBlocking {
        val spy = Spy()
        assertEquals(ColdSyncOutcome.NoConfig, runner(spy, config = null).run("g"))
        assertEquals(emptyList(), spy.calls)
    }

    @Test
    fun `concurrent runs for the same repository never overlap and the losers are SyncInProgress`() = runBlocking {
        var inFlight = 0
        var maxInFlight = 0
        val spy = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                kotlinx.coroutines.yield()
                inFlight--
                return FetchResult(false, 0).right()
            }
        }
        val cold = runner(Spy())
        val racing = ColdSyncRunner(spy, StubConfigRepository(sampleConfig.right()), staleness, gate, { "repo-key" }) { now }
        val outcomes = (1..5).map { async { racing.run(sampleConfig.graphId) } }.awaitAll()

        assertEquals(1, maxInFlight)
        assertEquals(1, outcomes.count { it is ColdSyncOutcome.Fetched })
        assertEquals(4, outcomes.count { it == ColdSyncOutcome.Error(DomainError.GitError.SyncInProgress(SyncOrigin.Cold)) })
        assertIs<ColdSyncOutcome.Fetched>(cold.run(sampleConfig.graphId), "the lease is released after the run")
        Unit
    }

    @Test
    fun `a held gate makes the run a busy outcome with no fetch and no staleness write`() = runBlocking {
        val spy = Spy()
        val lease = gate.tryAcquire("repo-key", SyncOrigin.UiPress)!!

        val outcome = runner(spy).run(sampleConfig.graphId)

        assertEquals(ColdSyncOutcome.Error(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress)), outcome)
        assertEquals(emptyList(), spy.calls)
        gate.releaseOrHandOff(lease)
        assertIs<ColdSyncOutcome.Fetched>(runner(Spy()).run(sampleConfig.graphId))
        Unit
    }

    @Test
    fun `an unresolvable git dir fails closed without fetching`() = runBlocking {
        val spy = Spy()
        val outcome = runner(spy, keyFor = { null }).run(sampleConfig.graphId)
        assertEquals(ColdSyncOutcome.Error(DomainError.GitError.SyncInProgress(null)), outcome)
        assertEquals(emptyList(), spy.calls)
    }

    @Test
    fun `a throwing fetch still releases the gate`() = runBlocking {
        val boom = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<DomainError.GitError, FetchResult> = error("boom")
        }
        val racing = ColdSyncRunner(boom, StubConfigRepository(sampleConfig.right()), staleness, gate, { "repo-key" }) { now }
        runCatching { racing.run(sampleConfig.graphId) }
        assertEquals(false, gate.isHeld("repo-key"))
    }
}
