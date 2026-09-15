package dev.stapler.stelekit.domain

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.PageNameEntry
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * PageNameIndex builds an Aho-Corasick trie over every page name (plus stem variants) —
 * on 8 000+ page graphs this is one of the largest single allocations at startup and its
 * first build lands ~500 ms after launch, exactly when the Android crash was observed.
 *
 * An OutOfMemoryError escaping its collector or the stateIn matcher build is an uncaught
 * coroutine Throwable, which kills the process on Android. These tests reproduce that
 * vector: before the guards were added they fail (the simulated OOM reaches the default
 * uncaught-exception handler); with the guards the index degrades gracefully.
 */
class PageNameIndexResilienceTest {

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

    private fun page(name: String): Page {
        val now = Clock.System.now()
        return Page(uuid = PageUuid("uuid-$name"), name = name, createdAt = now, updatedAt = now)
    }

    private class OomAfterFirstEmissionRepository(
        private val pages: List<Page>,
    ) : FakePageRepository() {
        val oomThrown = CompletableDeferred<Unit>()
        override fun getPageNameEntries(): Flow<Either<DomainError, List<PageNameEntry>>> = flow {
            emit(pages.map { PageNameEntry(it.name, it.isJournal) }.right())
            // Leave time for the debounced rebuild to consume the first emission.
            delay(400)
            oomThrown.complete(Unit)
            throw OutOfMemoryError("simulated OOM re-materializing the page-name list")
        }
    }

    @Test
    fun oom_from_page_flow_keeps_last_matcher_and_does_not_crash() = runBlocking {
        UncaughtRecorder().use { recorder ->
            val repo = OomAfterFirstEmissionRepository(
                listOf(page("Kotlin"), page("Gradle"), page("Compose"))
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val index = PageNameIndex(repo, scope, rebuildDebounceMs = 100)

                // First emission must produce a working matcher.
                withTimeout(10_000) {
                    while (index.matcher.value == null) delay(50)
                }
                val matcher = index.matcher.value
                assertNotNull(matcher, "matcher must build from the first page list")

                // Then the flow dies with OOM — the index must absorb it.
                withTimeout(10_000) { repo.oomThrown.await() }
                delay(500)

                assertTrue(
                    recorder.uncaught.isEmpty(),
                    "OutOfMemoryError reached the default uncaught-exception handler — " +
                        "on Android this kills the process. " +
                        "Uncaught: ${recorder.uncaught.map { it::class.simpleName + ": " + it.message }}"
                )
                // Last good matcher must survive the upstream failure.
                assertNotNull(index.matcher.value, "matcher must keep its last good value")
                assertTrue(
                    index.matcher.value!!.findAll("learning Kotlin today").isNotEmpty(),
                    "surviving matcher must still match page names"
                )
            } finally {
                scope.cancel()
            }
        }
    }
}
