package dev.stapler.stelekit.coroutines

import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A [SessionLifecycle] whose [close] always throws — used to prove `switchTo` isolates it. */
private class ThrowingCloseSession(override val scope: CoroutineScope) : SessionLifecycle {
    override fun close() {
        throw IllegalStateException("boom")
    }
}

/**
 * Story 0.1.2 AC3/AC4: teardown-failure isolation and the guaranteed-completion signal.
 */
class GraphScopedSessionTeardownIsolationTest {

    @Test
    fun `old session's close() throwing does not block the new session from opening`() = runTest {
        val session = GraphScopedSession<GraphId, ThrowingCloseSession>()
        session.switchTo(GraphId("journal-a")) { scope -> ThrowingCloseSession(scope) }

        // The old session's close() throws IllegalStateException("boom") internally, but it must
        // never propagate out of switchTo, and the new session must be fully constructed.
        val newSession = session.switchTo(GraphId("journal-b")) { scope -> ThrowingCloseSession(scope) }

        assertTrue(newSession.scope.isActive, "the newly-constructed session must be active")
    }

    @Test
    fun `a factory failure rethrows, leaves the previous session untouched, and does not wedge later switches`() = runTest {
        val session = GraphScopedSession<GraphId, FakeSession>()
        val original = session.switchTo(GraphId("journal-a")) { scope -> FakeSession(scope) }

        assertFailsWith<IllegalArgumentException> {
            session.switchTo(GraphId("journal-c")) { throw IllegalArgumentException("bad config") }
        }

        // The previous session (journal-a) must be left active and untouched by the failed attempt.
        val stillActive = session.switchTo(GraphId("journal-a")) { scope -> FakeSession(scope) }
        assertSame(original, stillActive, "the failed switchTo must not have disturbed the active session")

        // A second, correctly-configured switchTo for the id that previously failed must succeed
        // normally — no internal state left wedged from the failed attempt.
        var constructionCount = 0
        val recovered = session.switchTo(GraphId("journal-c")) { scope ->
            constructionCount++
            FakeSession(scope)
        }
        assertEquals(1, constructionCount)
        assertTrue(recovered.scope.isActive)
    }
}
