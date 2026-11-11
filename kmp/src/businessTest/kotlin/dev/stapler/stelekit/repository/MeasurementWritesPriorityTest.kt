// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.repository

import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class MeasurementWritesPriorityTest {
    @Test
    fun measurementWrite_should_RunBeforeQueuedLowPriorityWrites_When_ActorIsBusy() = runBlocking<Unit> {
        val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
        try {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val blocker = launch {
                actor.execute(DatabaseWriteActor.Priority.LOW) { started.complete(Unit); gate.await(); Unit.right() }
            }
            withTimeout(10_000) { started.await() }
            val low = launch(start = CoroutineStart.UNDISPATCHED) {
                actor.execute(DatabaseWriteActor.Priority.LOW) { order += "low"; Unit.right() }
            }
            val high = launch(start = CoroutineStart.UNDISPATCHED) {
                MeasurementWrites(actor).execute { order += "measurement"; Unit.right() }
            }
            gate.complete(Unit)
            withTimeout(10_000) { listOf(blocker, low, high).forEach { it.join() } }
            assertEquals(listOf("measurement", "low"), order)
        } finally {
            actor.close()
        }
    }

    @Test
    fun measurementWrite_should_RunInline_When_NoActor() = runBlocking<Unit> {
        var ran = false
        MeasurementWrites(null).execute { ran = true; Unit.right() }
        assertEquals(true, ran)
    }
}
