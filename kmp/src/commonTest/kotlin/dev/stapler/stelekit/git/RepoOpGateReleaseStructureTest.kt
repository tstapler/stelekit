package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class RepoOpGateReleaseStructureTest {
    // ADR-001 decision 4: the release structure the service entry points use.
    private suspend fun runOwned(gate: RepoOpGate, lease: GateLease, endOwnedOperation: () -> Unit, body: () -> Unit) {
        try {
            body()
        } finally {
            try {
                withContext(NonCancellable) { endOwnedOperation() }
            } finally {
                gate.releaseOrHandOff(lease)
            }
        }
    }

    @Test
    fun releaseOrHandOff_should_FreeGate_When_EndOwnedOperationThrows() = runTest {
        for (failure in listOf<Throwable>(RuntimeException("disk"), CancellationException("cancelled"))) {
            val gate = RepoOpGate()
            val lease = assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress))

            assertFailsWith<Throwable> { runOwned(gate, lease, endOwnedOperation = { throw failure }, body = {}) }

            assertFalse(gate.isHeld("k"), "the gate must be free after a throwing end-record, got ${failure::class.simpleName}")
        }
    }

    @Test
    fun releaseOrHandOff_should_FreeGate_When_BodyThrows() = runTest {
        val gate = RepoOpGate()
        val lease = assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress))
        assertFailsWith<IllegalStateException> { runOwned(gate, lease, endOwnedOperation = {}, body = { error("boom") }) }
        assertFalse(gate.isHeld("k"))
    }

    @Test
    fun releaseOrHandOff_should_BeCallableFromANonSuspendFunction() {
        // Compile-time pin: if this became `suspend`, this plain function would stop compiling.
        fun nonSuspendFinally(gate: RepoOpGate, lease: GateLease) = gate.releaseOrHandOff(lease)

        val gate = RepoOpGate()
        nonSuspendFinally(gate, assertNotNull(gate.tryAcquire("k", SyncOrigin.UiPress)))
        assertFalse(gate.isHeld("k"))
    }
}
