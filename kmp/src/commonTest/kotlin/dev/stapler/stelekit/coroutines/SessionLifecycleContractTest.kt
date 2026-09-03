package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Minimal [SessionLifecycle] whose [close] cancels its own [scope] — the contract every real
 * implementation (`HostDirectorySync`, `GraphSyncSession`) must uphold. */
class FakeSession(override val scope: CoroutineScope) : SessionLifecycle {
    override fun close() {
        scope.cancel()
    }
}

/**
 * Story 0.1.1: a class implementing [SessionLifecycle] exposes `val scope` and `fun close()`,
 * and calling [SessionLifecycle.close] must cancel that scope.
 */
class SessionLifecycleContractTest {

    @Test
    fun `close cancels the session's scope immediately`() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val session = FakeSession(scope)
        assertTrue(scope.isActive, "precondition: scope starts active")

        session.close()

        assertFalse(scope.isActive, "scope.isActive must be false immediately after close() returns")
    }
}
