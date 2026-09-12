package dev.stapler.stelekit.coroutines

import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 0.1.3 AC1: structural cancellation. A switch mid-flight cancels the old session's scope
 * before the new one is constructed — proven with an explicit [CompletableDeferred] gate, never
 * `delay()`, so this can never be flaky the way the wall-clock-timing test from PR #293 was.
 */
class GraphScopedSessionCancellationTest {

    @Test
    fun `switchTo cancels the old session's scope, so gated work never mutates state after the switch`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var mutated: String? = null
        var caught: Throwable? = null

        // Every constructed session's scope must run on the TestScope's own virtual-time
        // dispatcher — otherwise a coroutine launched on FakeSession.scope runs on real
        // Dispatchers.Default and advanceUntilIdle() (which only drives this TestScope's
        // scheduler) cannot reliably synchronize with it.
        val session = GraphScopedSession<GraphId, FakeSession>(dispatcher = StandardTestDispatcher(testScheduler))
        val sessionA = session.switchTo(GraphId("a")) { scope -> FakeSession(scope) }

        sessionA.scope.launch {
            try {
                gate.await()
                mutated = "A"
            } catch (e: Throwable) {
                caught = e
            }
        }
        // Let the launched coroutine actually reach gate.await() before switching.
        advanceUntilIdle()

        // Triggers sessionA.close() -> sessionA.scope.cancel() before the new session is returned.
        session.switchTo(GraphId("b")) { scope -> FakeSession(scope) }

        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(mutated, "mutated must never become \"A\" — the old scope was cancelled before the gate resumed")
        assertTrue(
            caught is CancellationException,
            "A's launched coroutine must observe CancellationException at gate.await()'s resumption point, got: $caught",
        )
    }
}
