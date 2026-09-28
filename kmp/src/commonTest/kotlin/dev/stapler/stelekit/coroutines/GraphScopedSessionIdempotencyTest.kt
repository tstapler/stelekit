package dev.stapler.stelekit.coroutines

import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Story 0.1.2 AC2: a repeated `switchTo` for the already-active [GraphId] returns the *same*
 * instance without constructing a second one or cancelling the first one's scope.
 */
class GraphScopedSessionIdempotencyTest {

    @Test
    fun `repeated switchTo for the active id returns the same instance without reconstruction`() = runTest {
        val session = GraphScopedSession<GraphId, FakeSession>()
        var constructionCount = 0

        val first = session.switchTo(GraphId("journal-a")) { scope ->
            constructionCount++
            FakeSession(scope)
        }

        val second = session.switchTo(GraphId("journal-a")) { scope ->
            constructionCount++
            FakeSession(scope)
        }

        assertSame(first, second, "a repeated switchTo for the active id must return the same instance")
        assertEquals(1, constructionCount, "the factory must not be invoked a second time")
        assertTrue(first.scope.isActive, "the active session's scope must not be cancelled by the idempotent call")
    }
}
