// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 1.4b: a rate-limit retry waits for the repository gate instead of firing into a running sync (V-E1g). */
class RateLimitRetryGateTest {
    private fun TestScope.retryScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun retry_should_WaitForTheGate_When_AnotherRunHoldsItAtFireTime() = runTest {
        val scope = retryScope()
        val gateFree = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val retry = RateLimitRetry(scope, awaitFree = { gateFree.await() })

        retry.schedule("g", retryAfterSeconds = 0) { ran += it }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(emptyList(), ran.toList(), "the retry must not run while the gate is held")

        gateFree.complete(Unit)
        runCurrent()
        assertEquals(listOf("g"), ran.toList())
        scope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun cancel_should_StopARetryThatIsStillWaitingForTheGate() = runTest {
        val scope = retryScope()
        val gateFree = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val retry = RateLimitRetry(scope, awaitFree = { gateFree.await() })

        retry.schedule("g", retryAfterSeconds = 0) { ran += it }
        advanceTimeBy(200)
        runCurrent()
        retry.cancel()
        gateFree.complete(Unit)
        advanceTimeBy(300)
        runCurrent()

        assertEquals(emptyList(), ran.toList())
        scope.coroutineContext[Job]?.cancel()
    }
}
