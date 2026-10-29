// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/**
 * A virtual-time dispatcher that also notices when a continuation comes back from a real thread.
 *
 * `GitSyncService.fetchOnly` always hops onto the real `PlatformDispatcher.IO` and back, so plain
 * virtual time cannot tell when a tick has finished and re-parked in its `delay`. Each dispatch from
 * a non-test thread releases [resumedFromOtherThread]; the test acquires it once per tick, then
 * runs the scheduler. No real sleeps and no polling.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
internal class HopAwareTestDispatcher(
    private val inner: TestDispatcher,
    private val testThread: Thread,
) : CoroutineDispatcher(), Delay by inner {
    private val resumedFromOtherThread = Semaphore(0)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        inner.dispatch(context, block)
        if (Thread.currentThread() !== testThread) resumedFromOtherThread.release()
    }

    /** Blocks the test thread until one tick's continuation has been dispatched back. */
    fun awaitTickReturned(timeoutSeconds: Long = HOP_TIMEOUT_SECONDS): Boolean =
        resumedFromOtherThread.tryAcquire(timeoutSeconds, TimeUnit.SECONDS)

    private companion object {
        const val HOP_TIMEOUT_SECONDS = 30L
    }
}
