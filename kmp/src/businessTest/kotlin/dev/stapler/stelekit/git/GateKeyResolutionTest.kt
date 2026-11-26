// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** U-E1-14: the service, the quiesce strategy and the clone path derive one key for one repository. */
class GateKeyResolutionTest {
    private val base: File = Files.createTempDirectory("gate_key_resolution_").toFile()
    private val real = File(base, "graph").apply { mkdirs() }
    private val link: File = File(base, "link").also { Files.createSymbolicLink(it.toPath(), real.toPath()) }
    private val gate = RepoOpGate()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        base.deleteRecursively()
    }

    /** Resolves like the JVM and Android repositories: the canonical key of the resolved `.git`. */
    private class BlockingRepository(val release: CompletableDeferred<Unit>) : StubGitRepository() {
        val entered = CompletableDeferred<Unit>()
        override fun gateKeyFor(config: GitConfig): String? = RepoOpGate.keyForOrNull(File(config.repoRoot.value, ".git"))
        override suspend fun status(config: GitConfig) = GitStatus(false, emptyList(), emptyList()).right()
        override suspend fun fetch(config: GitConfig): Either<dev.stapler.stelekit.error.DomainError.GitError, FetchResult> {
            entered.complete(Unit)
            release.await()
            return FetchResult(false, 0).right()
        }
        override suspend fun push(config: GitConfig) = Unit.right()
    }

    private fun serviceFor(repoRoot: String, git: GitRepository) = buildGitSyncTestService(
        gitRepository = git,
        configRepository = StubConfigRepository(Either.Right(sampleConfig.copy(repoRoot = RepoRoot(repoRoot)))),
        isOnline = { true },
        gate = gate,
    )

    // runBlocking: real threads/dispatchers do the work under test; virtual time cannot order or await them
    @Test
    fun quiesceKey_should_EqualSyncKey_When_SameGraph() = runBlocking<Unit> {
        val canonicalKey = assertNotNull(RepoOpGate.keyForOrNull(File(real, ".git")))
        // The quiesce strategy's injected lambda is the repository's gateKeyFor; the sync service uses the same call.
        val quiesceKeyFor: (GitConfig) -> String? = BlockingRepository(CompletableDeferred())::gateKeyFor

        for (spelling in listOf(real.path, real.path + "/", link.path, link.path + "/")) {
            val release = CompletableDeferred<Unit>()
            val git = BlockingRepository(release)
            val svc = serviceFor(spelling, git)
            val run = scope.async { svc.sync("g") }
            withTimeout(20_000) { git.entered.await() } // real-clock hang guard: awaits real threads

            assertTrue(gate.isHeld(canonicalKey), "sync under '$spelling' must hold the canonical key")
            assertEquals(canonicalKey, quiesceKeyFor(sampleConfig.copy(repoRoot = RepoRoot(spelling))))
            assertEquals(canonicalKey, RepoOpGate.keyForOrNull(File(File(spelling), ".git")), "clone key for '$spelling'")

            release.complete(Unit)
            withTimeout(20_000) { run.await() } // real-clock hang guard: awaits real threads
            svc.shutdown()
        }
    }

    @Test
    fun syncKey_should_DifferForDifferentRepositories() = runTest {
        val other = File(base, "other").apply { mkdirs() }
        val a = assertNotNull(RepoOpGate.keyForOrNull(File(real, ".git")))
        val b = assertNotNull(RepoOpGate.keyForOrNull(File(other, ".git")))
        assertTrue(a != b)
    }
}
