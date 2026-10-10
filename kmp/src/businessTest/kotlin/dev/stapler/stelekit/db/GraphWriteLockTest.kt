package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GraphWriteLockTest {
    private val a = GraphId("aaaaaaaaaaaaaaaa")
    private val b = GraphId("bbbbbbbbbbbbbbbb")

    @AfterTest
    fun resetGuard() {
        GraphWriteLockOrderGuard.forceEnabled = false
    }

    private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(30.seconds) { block(this) } }
    }

    @Test
    fun `same id is mutually exclusive`() = realTime {
        val lock = GraphWriteLock()
        val gate = CompletableDeferred<Unit>()
        val first = async { lock.withLock(a) { gate.await() } }
        while (!lock.isLocked(a)) delay(5)
        var second = false
        val secondJob = async { lock.withLock(a) { second = true } }
        delay(100)
        assertFalse(second)
        gate.complete(Unit)
        first.await(); secondJob.await()
        assertTrue(second)
        assertFalse(lock.isLocked(a))
    }

    @Test
    fun `different ids do not block each other`() = realTime {
        val lock = GraphWriteLock()
        val gate = CompletableDeferred<Unit>()
        val holder = async { lock.withLock(a) { gate.await() } }
        while (!lock.isLocked(a)) delay(5)
        lock.withLock(b) { }
        gate.complete(Unit)
        holder.await()
    }

    @Test
    fun `lock is released when block throws`() = realTime {
        val lock = GraphWriteLock()
        assertFailsWith<IllegalArgumentException> { lock.withLock(a) { throw IllegalArgumentException("x") } }
        assertFalse(lock.isLocked(a))
        lock.withLock(a) { }
    }

    @Test
    fun `timeout variant returns null and reports the holder label`() = realTime {
        val lock = GraphWriteLock()
        val gate = CompletableDeferred<Unit>()
        val holder = async { lock.withLock(a, label = "merge-batch") { gate.await() } }
        while (lock.holderLabel(a) == null) delay(5)
        var reported: String? = null
        var ran = false
        val result = lock.withLockTimeout(a, 100.milliseconds, onTimeout = { reported = it }) { ran = true; 1 }
        assertNull(result)
        assertFalse(ran)
        assertEquals("merge-batch", reported)
        gate.complete(Unit)
        holder.await()
        assertEquals(2, lock.withLockTimeout(a, 1.seconds) { 2 })
    }

    @Test
    fun `degrade variant runs the block without the lock after timeout`() = realTime {
        val lock = GraphWriteLock()
        val gate = CompletableDeferred<Unit>()
        val holder = async { lock.withLock(a) { gate.await() } }
        while (!lock.isLocked(a)) delay(5)
        var timedOut = false
        val v = lock.withLockOrDegrade(a, 100.milliseconds, onTimeout = { timedOut = true }) { 7 }
        assertEquals(7, v)
        assertTrue(timedOut)
        assertTrue(lock.isLocked(a), "holder must be untouched")
        gate.complete(Unit)
        holder.await()
        assertFalse(lock.isLocked(a))
    }

    @Test
    fun `order guard rejects a second graph lock while one is held`() = realTime {
        GraphWriteLockOrderGuard.forceEnabled = true
        val lock = GraphWriteLock()
        assertFailsWith<IllegalStateException> {
            lock.withLock(a) { lock.withLock(b) { } }
        }
        assertFailsWith<IllegalStateException> {
            lock.withLock(a) { lock.withLockTimeout(b, 1.seconds) { } }
        }
        assertFalse(lock.isLocked(a))
        assertFalse(lock.isLocked(b))
        // sequential, non-nested acquisition is fine
        lock.withLock(a) { }
        lock.withLock(b) { }
    }

    @Test
    fun `order guard rejects awaiting pending migration while holding a lock`() = realTime {
        GraphWriteLockOrderGuard.forceEnabled = true
        val lock = GraphWriteLock()
        assertFailsWith<IllegalStateException> {
            lock.withLock(a) { GraphWriteLockOrderGuard.checkAwaitPendingMigration(kotlin.coroutines.coroutineContext) }
        }
        GraphWriteLockOrderGuard.checkAwaitPendingMigration(kotlin.coroutines.coroutineContext)
    }

    @Test
    fun `order guard is a no-op when disabled`() = realTime {
        val lock = GraphWriteLock()
        lock.withLock(a) { lock.withLock(b) { } }
    }
}
