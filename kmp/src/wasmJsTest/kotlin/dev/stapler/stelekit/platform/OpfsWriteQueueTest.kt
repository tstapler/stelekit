@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.7f: one ordered queue per path set; a fake whose operations complete out of order proves the settled order. */
class OpfsWriteQueueTest {
    private val failure = DomainError.FileSystemError.StorageQuotaExceeded("/g/a.json")

    private class Gated(val name: String, val log: MutableList<String>, val result: Either<DomainError, Unit> = Unit.right()) {
        val gate = CompletableDeferred<Unit>()
        suspend fun run(): Either<DomainError, Unit> {
            log += "start:$name"
            gate.await()
            log += "end:$name"
            return result
        }
    }

    private fun TestScope.queue(pending: MutableList<Int> = mutableListOf()) = OpfsWriteQueue(this) { pending += it }

    @Test
    fun threeWritesAndAReplace_should_SettleInEnqueueOrder_WhenTheFakeCompletesOutOfOrder() = runTest {
        val log = mutableListOf<String>()
        val q = queue()
        val ops = listOf("w1", "w2", "w3").map { Gated(it, log) }
        val tmp = Gated("tmp", log)
        val replace = Gated("replace", log)
        ops.forEach { op -> q.enqueue(listOf("/g/dest.json")) { op.run() } }
        q.enqueue(listOf("/g/dest.json.tmp")) { tmp.run() }
        q.enqueue(listOf("/g/dest.json.tmp", "/g/dest.json")) { replace.run() }
        runCurrent()

        // Release in the reverse of the enqueue order: nothing may overtake its predecessor.
        replace.gate.complete(Unit)
        tmp.gate.complete(Unit)
        ops.reversed().forEach { it.gate.complete(Unit); runCurrent() }
        runCurrent()

        assertEquals(listOf("w1", "w2", "w3", "replace"), log.filter { it.startsWith("end:") && it != "end:tmp" }.map { it.removePrefix("end:") })
        assertTrue(log.indexOf("start:w2") > log.indexOf("end:w1"), "w2 waited for w1: $log")
        assertTrue(log.indexOf("start:w3") > log.indexOf("end:w2"), "w3 waited for w2: $log")
        assertTrue(log.indexOf("start:replace") > maxOf(log.indexOf("end:w3"), log.indexOf("end:tmp")), "replace waited for both paths: $log")
        assertEquals(0, q.pendingPaths)
    }

    @Test
    fun aReplace_should_WaitForBothItsPaths_AndHoldOnePendingEntryPerPath() = runTest {
        val log = mutableListOf<String>()
        val q = queue()
        val w = Gated("w", log)
        val t = Gated("t", log)
        val r = Gated("r", log)
        q.enqueue(listOf("dest")) { w.run() }
        q.enqueue(listOf("tmp")) { t.run() }
        q.enqueue(listOf("tmp", "dest")) { r.run() }
        runCurrent()
        assertEquals(2, q.pendingPaths, "one tail entry per path")
        assertTrue(q.tailOf("tmp") === q.tailOf("dest"), "the replace is the tail of both")
        assertEquals(listOf("start:w", "start:t"), log)
        w.gate.complete(Unit)
        t.gate.complete(Unit)
        runCurrent()
        assertEquals("start:r", log.last())
        r.gate.complete(Unit)
        runCurrent()
        assertEquals(0, q.pendingPaths)
        assertNull(q.tailOf("dest"))
    }

    @Test
    fun aFailedPredecessor_should_NotBlockItsSuccessor_AndItsFailureShouldBeKeptUntilALaterSuccess() = runTest {
        val log = mutableListOf<String>()
        val q = queue()
        val first = Gated("first", log, failure.left())
        val second = Gated("second", log)
        q.enqueue(listOf("p")) { first.run() }
        val tail = q.enqueue(listOf("q", "p")) { second.run() }
        runCurrent()
        first.gate.complete(Unit)
        runCurrent()
        assertTrue("start:second" in log, "the successor ran after the failure")
        second.gate.complete(Unit)
        tail.await()
        assertTrue(q.awaitDurable("p").isRight(), "the successor's success superseded the failure")
    }

    @Test
    fun aFailedLastOperation_should_BeReportedByAwaitDurableOfEveryPathItNamed() = runTest {
        val q = queue()
        q.enqueue(listOf("tmp", "dest")) { failure.left() }
        assertIs<DomainError.FileSystemError.StorageQuotaExceeded>(q.awaitDurable("dest").leftOrNull())
        assertIs<DomainError.FileSystemError.StorageQuotaExceeded>(q.awaitDurable("tmp").leftOrNull())
    }

    @Test
    fun awaitDurable_should_ReturnTheFailureOfTheLatestOperation_AndClearItOnALaterSuccess() = runTest {
        val q = queue()
        val bad = q.enqueue(listOf("a")) { failure.left() }
        assertIs<DomainError.FileSystemError.StorageQuotaExceeded>(q.awaitDurable("a").leftOrNull())
        bad.await()
        assertIs<DomainError.FileSystemError.StorageQuotaExceeded>(q.awaitDurable("a").leftOrNull(), "kept after the operation settled")
        q.enqueue(listOf("a")) { Unit.right() }
        assertTrue(q.awaitDurable("a").isRight(), "a later success supersedes it")
        assertTrue(q.awaitDurable("never-written").isRight())
    }

    @Test
    fun anOperationThatThrows_should_BecomeAWriteFailed() = runTest {
        val q = queue()
        q.enqueue(listOf("a")) { error("boom") }
        val result = q.awaitDurable("a").leftOrNull()
        assertIs<DomainError.FileSystemError.WriteFailed>(result)
        assertEquals("a", result.path)
    }

    @Test
    fun anOperation_should_RemoveItsEntryOnlyWhileItIsStillTheTail() = runTest {
        val log = mutableListOf<String>()
        val q = queue()
        val first = Gated("first", log)
        val second = Gated("second", log)
        q.enqueue(listOf("p")) { first.run() }
        q.enqueue(listOf("p")) { second.run() }
        runCurrent()
        first.gate.complete(Unit)
        runCurrent()
        assertEquals(1, q.pendingPaths, "the first op settled but the second is still the tail")
        second.gate.complete(Unit)
        runCurrent()
        assertEquals(0, q.pendingPaths)
    }

    @Test
    fun onPendingChanged_should_ReportTheNumberOfPendingPaths_ForTheBeforeUnloadGuard() = runTest {
        val pending = mutableListOf<Int>()
        val q = queue(pending)
        q.enqueue(listOf("a", "b")) { Unit.right() }
        runCurrent()
        assertEquals(listOf(2, 0), pending)
    }
}
