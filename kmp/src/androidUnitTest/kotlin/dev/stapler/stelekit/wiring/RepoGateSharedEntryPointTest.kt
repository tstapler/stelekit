// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.AndroidGraphMoveQuiesceStrategy
import dev.stapler.stelekit.git.ColdSyncOutcome
import dev.stapler.stelekit.git.FetchResult
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.SkipReason
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.TickOutcome
import dev.stapler.stelekit.git.runColdFetch
import dev.stapler.stelekit.git.SyncStalenessStore
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.git.toTickOutcome
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The root's one [dev.stapler.stelekit.git.RepoOpGate] is the object every participant consults: the quiesce
 * strategy takes it first, and the closed-app cold run built on the same instance then skips as busy. The
 * `GitSyncService` leg (same gate via `StelekitAppPlatformIntegrations.repoOpGate`) needs a real database, which
 * Robolectric lacks (no FTS5); `DesktopWiredAppFixture` covers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RepoGateSharedEntryPointTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("repo_gate_shared_").toFile()
    private var fixture: WiredAppFixture? = null
    private val key = "shared-repo-key"

    @After
    fun tearDown() {
        // runBlocking: quiesce hops to Dispatchers.IO and blocks on real locks; virtual time cannot order it
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private val op = StorageMoveOperation.Relocate(
        graphId = "g1",
        source = StorageLocation.SafFolder("g1", "content://tree/x"),
        destination = StorageLocation.AppOwned("g1"),
        deleteSourceAfterVerify = true,
    )

    private suspend fun coldRun(fixture: WiredAppFixture): ColdSyncOutcome {
        var fetched = false
        val repo = object : StubGitRepository() {
            override suspend fun fetch(config: GitConfig): Either<dev.stapler.stelekit.error.DomainError.GitError, FetchResult> {
                fetched = true
                return FetchResult(false, 0).right()
            }
        }
        val outcome = runColdFetch(
            loadConfig = { sampleConfig },
            gitRepository = repo,
            staleness = SyncStalenessStore(fixture.ports.settings),
            clock = { 1L },
            gate = fixture.repoOpGate,
            gateKeyFor = { key },
        )
        if (outcome is ColdSyncOutcome.Error) assertEquals(false, fetched, "a busy cold run must not fetch")
        return outcome
    }

    // runBlocking: quiesce hops to Dispatchers.IO and blocks on real locks; virtual time cannot order it
    @Test
    fun the_fixture_gate_is_the_one_in_the_platform_integrations() = runBlocking {
        val f = WiredAppFixture.android(context, dataDir).also { fixture = it }
        ObservableEffect.assert("repo-gate-shared") {
            assertSame(f.repoOpGate, f.deps.platformIntegrations.repoOpGate)
        }
    }

    // runBlocking: quiesce hops to Dispatchers.IO and blocks on real locks; virtual time cannot order it
    @Test
    fun a_quiesce_hold_on_the_shared_gate_makes_the_cold_run_skip_as_busy() = runBlocking {
        val f = WiredAppFixture.android(context, dataDir).also { fixture = it }
        val earlier = checkNotNull(f.repoOpGate.tryAcquire(key, SyncOrigin.UiPress))
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = GitSyncBusyCounter(),
            gate = f.repoOpGate,
            gateKeyFor = { key },
            shadowWorktreeTarget = { null },
        )
        val quiesce = async { strategy.quiesce(op) }
        f.repoOpGate.releaseOrHandOff(earlier)
        withTimeout(QUIESCE_WAIT_MS) { quiesce.await() } // real-clock hang guard: awaits real threads

        ObservableEffect.assert("repo-gate-shared") {
            assertEquals(SyncOrigin.Quiesce, f.repoOpGate.heldBy(key))
            val tick = (coldRun(f) as ColdSyncOutcome.Error).toTickOutcome()
            assertEquals(TickOutcome.Skip(SkipReason.Busy), tick)
        }

        strategy.release(op)
        assertNull(f.repoOpGate.heldBy(key))
        assertEquals(ColdSyncOutcome.Fetched(0, dev.stapler.stelekit.git.FetchOnlyReason.AppClosed), coldRun(f))
    }

    private companion object {
        const val QUIESCE_WAIT_MS = 10_000L
    }
}
