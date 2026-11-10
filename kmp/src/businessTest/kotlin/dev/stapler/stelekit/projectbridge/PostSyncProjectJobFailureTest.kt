// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.jobs.JobHandle
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Failure paths of [PostSyncProjectJob] and [PostSyncProjectCoordinator] that the entry-point tests do not reach. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostSyncProjectJobFailureTest {
    private class RecordingHandle(private val onSucceed: () -> Unit) : JobHandle {
        val events = mutableListOf<String>()
        override fun progress(percent: Int?) = Unit
        override fun succeed() { events += "succeed"; onSucceed() }
        override fun fail(reason: String?) { events += "fail:$reason" }
        override fun cancelled() { events += "cancelled" }
    }

    private class FixedTracker(val handle: RecordingHandle) : JobTracker {
        override fun begin(kind: JobKind, title: String, onCancel: (() -> Unit)?): JobHandle = handle
    }

    private fun activeSettings() = BridgeMapSettings().also { enableBridge(it) }

    @Test
    fun anUnexpectedThrowable_IsReportedAsFailed_WithItsTypeName() = runTest {
        val handle = RecordingHandle { throw IllegalStateException("boom") }
        val settings = activeSettings()
        val deps = bridgeDeps(settings, FixedTracker(handle), router = { newBridgeTestRouter() })

        val outcome = PostSyncProjectJob(deps).run(BRIDGE_GRAPH) { false }

        assertEquals(PostSyncRunOutcome.Failed("IllegalStateException"), outcome)
        assertEquals(listOf("succeed", "fail:project digest failed (IllegalStateException)"), handle.events)
    }

    @Test
    fun cancellation_MarksTheEntryCancelled_AndIsRethrown() = runTest {
        val handle = RecordingHandle { throw CancellationException("stop") }
        val deps = bridgeDeps(activeSettings(), FixedTracker(handle), router = { newBridgeTestRouter() })

        assertFailsWith<CancellationException> { PostSyncProjectJob(deps).run(BRIDGE_GRAPH) { false } }

        assertEquals(listOf("succeed", "cancelled"), handle.events)
    }

    @Test
    fun aThrowingCollector_IsLoggedByTheCoordinator_AndRetriedOnTheNextSync() = runTest {
        val settings = activeSettings()
        val jobs = dev.stapler.stelekit.jobs.InProcessJobs()
        val deps = bridgeDeps(settings, jobs, router = { newBridgeTestRouter() })
        var calls = 0
        val collector = object : ProjectSourceCollector() {
            override suspend fun collect(): CollectedSources {
                calls++
                if (calls == 1) error("collector down")
                return CollectedSources()
            }
        }
        val lastSyncAt = kotlinx.coroutines.flow.MutableStateFlow<Long?>(1L)
        val parent = Job()
        PostSyncProjectCoordinator(
            deps = deps,
            gitLastSyncAt = lastSyncAt,
            syncStateProvider = { SyncState.Idle },
            activeGraphIdProvider = { BRIDGE_GRAPH },
            parentContext = StandardTestDispatcher(testScheduler) + parent,
            job = PostSyncProjectJob(deps, collector),
        ).start()
        advanceUntilIdle()
        assertEquals(1, calls, "first run threw and was swallowed")

        lastSyncAt.value = 2L
        advanceUntilIdle()
        assertEquals(2, calls, "the failed key was not remembered, so the next sync runs again")
        parent.cancel()
    }
}
