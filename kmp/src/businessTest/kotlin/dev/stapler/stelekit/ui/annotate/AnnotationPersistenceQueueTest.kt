// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnotationPersistenceQueueTest {

    private fun TestScope.newQueue(onThrowable: (Throwable) -> Unit = {}) =
        AnnotationPersistenceQueue(StandardTestDispatcher(testScheduler), onThrowable)

    private fun row(uuid: String) = MeasurementAnnotation(
        uuid = uuid, imageUuid = "img", annotationType = AnnotationType.DISTANCE, normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.2, 0.2)),
    )

    /** A write that takes a variable number of suspension points, so unserialized runs would interleave. */
    private class Store {
        val rows = linkedMapOf<String, MeasurementAnnotation>()
        val appliedOrder = mutableListOf<Int>()
    }

    private sealed interface Step { val key: Int; val slowness: Int; val seq: Int }
    private data class Put(override val key: Int, override val slowness: Int, override val seq: Int) : Step
    private data class Del(override val key: Int, override val slowness: Int, override val seq: Int) : Step

    private fun decode(codes: List<Int>): List<Step> = codes.mapIndexed { i, c ->
        val key = c.mod(5)
        val slow = (c / 5).mod(4)
        if ((c / 20).mod(3) == 0) Del(key, slow, i) else Put(key, slow, i)
    }

    @Test
    fun randomInterleavings_should_PersistEveryCommittedRowInSubmissionOrder() = runTest {
        checkAll(200, Arb.list(Arb.int(0..59), 0..40)) { codes ->
            val steps = decode(codes)
            val queue = newQueue()
            val store = Store()
            val model = linkedMapOf<String, MeasurementAnnotation>()

            steps.forEach { step ->
                val id = "r${step.key}"
                when (step) {
                    is Put -> model[id] = row(id)
                    is Del -> model.remove(id)
                }
                queue.submit {
                    repeat(step.slowness) { yield() }
                    if (step.slowness == 3) delay(200)
                    store.appliedOrder += step.seq
                    when (step) {
                        is Put -> store.rows[id] = row(id)
                        is Del -> store.rows.remove(id)
                    }
                    Unit.right()
                }
            }
            queue.awaitIdle()

            assertEquals(steps.indices.toList(), store.appliedOrder, "ops ran out of submission order")
            assertEquals(model.keys, store.rows.keys, "last-write state differs from sequential application")
            assertEquals(0, queue.pending.value)
            queue.close()
        }
    }

    @Test
    fun commitThenUndo_should_NotResurrectRow_When_SaveIsSlow() = runTest {
        val queue = newQueue()
        val rows = mutableMapOf<String, MeasurementAnnotation>()
        var shown = listOf(row("x"))
        queue.submit { delay(200); rows["x"] = row("x"); Unit.right() }
        shown = emptyList()
        queue.submit { rows.keys.retainAll(shown.map { it.uuid }.toSet()); Unit.right() }
        queue.awaitIdle()
        assertTrue(rows.isEmpty())
        queue.close()
    }

    @Test
    fun failedWrite_should_BeReportedAndNotStopLaterWrites() = runTest {
        val queue = newQueue()
        var ran = false
        val results = mutableListOf<Either<DomainError, Unit>>()
        queue.submit({ results += it }) { Either.Left(DomainError.DatabaseError.WriteFailed("boom")) }
        queue.submit({ results += it }) { ran = true; Unit.right() }
        queue.awaitIdle()
        assertTrue(ran)
        assertEquals(listOf(true, false), results.map { it.isLeft() })
        assertNull(queue.failure.value, "a later success clears the failure")
        queue.close()
    }

    @Test
    fun throwingWrite_should_ReachOwnerAndKeepDraining() = runTest {
        val seen = mutableListOf<Throwable>()
        val queue = newQueue { seen += it }
        var after = false
        var onResultCalled = false
        queue.submit({ onResultCalled = true }) { throw OutOfMemoryError("simulated") }
        queue.submit { after = true; Unit.right() }
        queue.awaitIdle()
        assertTrue(seen.single() is OutOfMemoryError)
        assertTrue(after)
        assertFalse(onResultCalled)
        queue.close()
    }

    @Test
    fun failureState_should_BeSetUntilCleared() = runTest {
        val queue = newQueue()
        queue.submit { Either.Left(DomainError.DatabaseError.WriteFailed("boom")) }
        queue.awaitIdle()
        assertNotNull(queue.failure.value)
        queue.clearFailure()
        assertNull(queue.failure.value)
        queue.close()
    }

    @Test
    fun close_should_DrainQueuedWritesAndRejectNewOnes() = runTest {
        val queue = newQueue()
        var ran = 0
        queue.submit { delay(50); ran++; Unit.right() }
        queue.close()
        assertFalse(queue.submit { ran++; Unit.right() })
        queue.awaitIdle()
        assertEquals(1, ran)
    }

    @Test
    fun cancel_should_ReleaseWaitersWithoutRunningQueuedWrites() = runTest {
        val queue = newQueue()
        var ran = false
        queue.submit { ran = true; Unit.right() }
        queue.cancel()
        queue.awaitIdle()
        assertFalse(ran)
    }

    @Test
    fun mergeInitialLoad_should_KeepPersistedAndLocalRows() {
        val merged = mergeInitialLoad(
            persisted = listOf(row("a"), row("b"), row("c")),
            local = listOf(row("n")),
            touchedUuids = emptySet(),
        )
        assertEquals(listOf("a", "b", "c", "n"), merged.map { it.uuid })
    }

    @Test
    fun mergeInitialLoad_should_SkipRemovedAndDuplicateRows() = runTest {
        checkAll(Arb.list(Arb.int(0..9), 0..10), Arb.list(Arb.int(0..9), 0..10), Arb.list(Arb.int(0..9), 0..10)) { p, l, r ->
            val persisted = p.distinct().map { row("r$it") }
            val local = l.distinct().map { row("r$it") }
            val removed = r.map { "r$it" }.toSet()
            val merged = mergeInitialLoad(persisted, local, removed).map { it.uuid }
            assertEquals(merged.size, merged.toSet().size, "duplicate rows")
            assertTrue(local.all { it.uuid in merged })
            assertTrue(persisted.filter { it.uuid !in removed }.all { it.uuid in merged })
        }
    }
}
