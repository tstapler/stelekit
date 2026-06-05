package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the Android startup crash:
 * "attempt to re-open an already-closed object: SQLiteDatabase"
 *
 * Root cause: GraphManager.switchGraph/shutdown closed the SQLite driver while Compose
 * LaunchedEffect coroutines were still collecting repository Flows. mapToList() on the
 * closed driver threw IllegalStateException which propagated uncaught to the main thread.
 *
 * Fix: .catch handlers on Flow chains convert IllegalStateException → Either.Left(ReadFailed).
 *
 * Pre-fix behaviour: flow.first() throws IllegalStateException (test fails with exception).
 * Post-fix behaviour: flow.first() returns Either.Left (test assertion passes).
 */
class RepositoryFlowResilienceTest {

    // TC-DB-FLOW-001
    @Test
    fun `getAllPages emits Either Left when database is closed rather than throwing`() = runBlocking {
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)

            // Simulate the race: close the DB (as switchGraph/shutdown does) while a
            // Compose LaunchedEffect would be collecting the flow.
            factory.close()

            val result = repoSet.pageRepository.getAllPages().first()

            assertTrue(
                result.isLeft(),
                "getAllPages must emit Either.Left on a closed database, not throw. " +
                    "Pre-fix: throws IllegalStateException. Post-fix: emits DomainError.ReadFailed. " +
                    "Actual result: $result"
            )
        } finally {
            scope.cancel()
        }
    }

    // TC-DB-FLOW-002
    @Test
    fun `getBlocksForPage emits Either Left when database is closed rather than throwing`() = runBlocking {
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repoSet = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)

            factory.close()

            val result = repoSet.blockRepository.getBlocksForPage(PageUuid("any-page")).first()

            assertTrue(
                result.isLeft(),
                "getBlocksForPage must emit Either.Left on a closed database, not throw. " +
                    "Pre-fix: throws IllegalStateException. Post-fix: emits DomainError.ReadFailed."
            )
        } finally {
            scope.cancel()
        }
    }
}
