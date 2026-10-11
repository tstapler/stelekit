package dev.stapler.stelekit.coroutines

import arrow.atomic.AtomicBoolean
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 0.1.2's caller-side-cancellation contract (pre-mortem.md P1-2): if the *calling*
 * coroutine — not the session being switched to — is cancelled while `switchTo`'s factory is
 * suspended having already acquired a resource, the factory must release that resource in its
 * own `finally`, and `GraphScopedSession` must not leak the `pending` bookkeeping entry — a later
 * `switchTo` for the same id must not hang awaiting a stale, never-completing entry.
 */
class GraphScopedSessionCallerCancellationTest {

    @Test
    fun `cancelling the caller during factory construction releases the resource and does not wedge a later switchTo`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val acquired = AtomicBoolean(false)
        val session = GraphScopedSession<GraphId, FakeSession>()

        val callerJob = launch {
            session.switchTo(GraphId("a")) { scope ->
                acquired.value = true
                try {
                    gate.await()
                    FakeSession(scope)
                } finally {
                    acquired.value = false
                }
            }
        }

        advanceUntilIdle() // let the factory reach gate.await() after setting acquired = true
        assertTrue(acquired.value, "precondition: the factory must have acquired the resource before cancellation")

        callerJob.cancel()
        callerJob.join()

        assertFalse(acquired.value, "the factory's finally must have released the resource on caller cancellation")

        // A subsequent switchTo for the same id must complete normally, not hang awaiting a
        // stale pending entry left behind by the cancelled attempt.
        var constructionCount = 0
        val recovered = session.switchTo(GraphId("a")) { scope ->
            constructionCount++
            FakeSession(scope)
        }
        assertTrue(recovered.scope.isActive)
        assertEquals(1, constructionCount)
    }
}
