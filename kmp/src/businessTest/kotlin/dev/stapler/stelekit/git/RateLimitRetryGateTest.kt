// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 1.4b: a rate-limit retry waits for the repository gate instead of firing into a running sync (V-E1g). */
class RateLimitRetryGateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun retry_should_WaitForTheGate_When_AnotherRunHoldsItAtFireTime() = runBlocking<Unit> {
        val gateFree = CompletableDeferred<Unit>()
        val ran = CopyOnWriteArrayList<String>()
        val retry = RateLimitRetry(scope, awaitFree = { gateFree.await() })

        retry.schedule("g", retryAfterSeconds = 0) { ran += it }
        delay(300)
        assertEquals(emptyList(), ran.toList(), "the retry must not run while the gate is held")

        gateFree.complete(Unit)
        withTimeout(20_000) { while (ran.isEmpty()) delay(10) }
        assertEquals(listOf("g"), ran.toList())
    }

    @Test
    fun cancel_should_StopARetryThatIsStillWaitingForTheGate() = runBlocking<Unit> {
        val gateFree = CompletableDeferred<Unit>()
        val ran = CopyOnWriteArrayList<String>()
        val retry = RateLimitRetry(scope, awaitFree = { gateFree.await() })

        retry.schedule("g", retryAfterSeconds = 0) { ran += it }
        delay(200)
        retry.cancel()
        gateFree.complete(Unit)
        delay(300)

        assertEquals(emptyList(), ran.toList())
    }
}
