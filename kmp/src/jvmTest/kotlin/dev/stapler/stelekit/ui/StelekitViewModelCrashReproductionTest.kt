package dev.stapler.stelekit.ui

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Recreates the "SteleKit keeps stopping" crash observed on Android with 8 000+ page graphs.
 *
 * Mechanism under test: an uncaught Throwable (in the field: OutOfMemoryError under
 * large-graph heap pressure) escaping a coroutine launched on the ViewModel scope reaches
 * the platform default uncaught-exception handler. On Android that handler kills the
 * process; on desktop it merely logs — which is why the crash only ever manifested on
 * Android, and why mitigations inside GraphLoader's own scopes never fixed it.
 *
 * Each test installs a recording default uncaught-exception handler. Before the fix
 * (CoroutineExceptionHandler on the ViewModel scope + guarded collectors), these tests
 * fail because the simulated OutOfMemoryError reaches the default handler — i.e. the
 * crash is reproduced. After the fix they pass: the error is surfaced as
 * [AppState.fatalError] (recoverable error screen) instead of killing the process.
 */
class StelekitViewModelCrashReproductionTest {

    /** Records every Throwable that reaches the JVM default uncaught-exception handler. */
    private class UncaughtRecorder : AutoCloseable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        private val previous = Thread.getDefaultUncaughtExceptionHandler()

        init {
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e) }
        }

        override fun close() {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    private fun makeViewModel(pageRepo: FakePageRepository): StelekitViewModel {
        val blockRepo = FakeBlockRepository()
        val fileSystem = FakeFileSystem()
        // Production-shaped scope (App.kt): SupervisorJob + Dispatchers.Default, no handler.
        // The guard under test lives inside StelekitViewModel, not in the injected scope.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(PlatformFileSystem()),
                platformSettings = InMemorySettings(),
                scope = scope,
            )
        )
    }

    // ─── TC-CRASH-001: OOM surfacing through the page-list flow ─────────────────

    /**
     * Simulates an OutOfMemoryError surfacing while the page-name list is materialized
     * for a standing observer (the allocation that fails first on 8 000-page graphs).
     */
    private class OomPageFlowRepository : FakePageRepository() {
        val oomThrown = CompletableDeferred<Unit>()
        override fun getPageNameEntries(): Flow<Either<DomainError, List<dev.stapler.stelekit.repository.PageNameEntry>>> = flow {
            emit(emptyList<dev.stapler.stelekit.repository.PageNameEntry>().right())
            oomThrown.complete(Unit)
            throw OutOfMemoryError("simulated allocation failure materializing 8030 page names")
        }
    }

    @Test
    fun oom_in_page_list_observer_must_not_reach_default_uncaught_handler() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val repo = OomPageFlowRepository()
            val vm = makeViewModel(repo)

            withTimeout(10_000) { repo.oomThrown.await() }
            // Allow the coroutine scheduler to process the catch handler.
            delay(200)

            assertTrue(
                recorder.uncaught.isEmpty(),
                "OutOfMemoryError reached the default uncaught-exception handler — " +
                    "on Android this kills the process (\"SteleKit keeps stopping\"). " +
                    "Uncaught: ${recorder.uncaught.map { it::class.simpleName + ": " + it.message }}"
            )
            // ViewModel must remain alive and usable after the error.
            assertNotNull(vm.uiState.value, "uiState must remain readable after the error")
            // Note: fatalError is NOT expected here — PageNameIndex.catch {} gracefully
            // degrades suggestions (last good matcher retained) rather than surfacing a
            // fatal-error screen. The important guarantee is that the OOM is contained and
            // does not reach the default uncaught handler (which kills the process on Android).
        }
        Unit
    }

    // ─── TC-CRASH-002: OOM in a fire-and-forget launch (ensureTodayJournal) ─────

    /**
     * Simulates OutOfMemoryError thrown under heap pressure inside the unguarded
     * `scope.launch { journalService.ensureTodayJournal() }` fired on Phase-1 completion —
     * exactly the moment the field crash occurs ("Checking database..." then death).
     */
    private class OomJournalLookupRepository : FakePageRepository() {
        val lookupReached = CompletableDeferred<Unit>()
        override fun getJournalPageByDate(date: LocalDate): Flow<Either<DomainError, Page?>> =
            flow {
                lookupReached.complete(Unit)
                throw OutOfMemoryError("simulated OOM resolving today's journal")
            }
    }

    @Test
    fun oom_during_ensureTodayJournal_is_surfaced_as_fatalError_not_a_crash() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val repo = OomJournalLookupRepository()
            val vm = makeViewModel(repo)

            // Triggers loadGraph → Phase 1 completes (FakeFileSystem lists no files) →
            // onPhase1Complete launches ensureTodayJournal → repo throws OOM.
            vm.setGraphPath("/tmp/test-graph")

            withTimeout(10_000) { repo.lookupReached.await() }
            withTimeout(10_000) { vm.uiState.first { it.fatalError != null } }

            assertTrue(
                recorder.uncaught.isEmpty(),
                "OutOfMemoryError reached the default uncaught-exception handler — " +
                    "on Android this kills the process. " +
                    "Uncaught: ${recorder.uncaught.map { it::class.simpleName + ": " + it.message }}"
            )
            assertTrue(
                vm.uiState.value.fatalError.orEmpty().contains("OutOfMemoryError"),
                "fatalError should surface the OOM for the recovery screen; " +
                    "was: ${vm.uiState.value.fatalError}"
            )
        }
    }

    // Note: this file previously asserted at runtime that the ViewModel holds no standing
    // getAllPages() subscription. That guarantee is now structural — PageRepository has no
    // unbounded full-table read at all; whole-graph consumers go through the bounded
    // getAllPagesSnapshot() / getPageNameEntries() reads.
}
