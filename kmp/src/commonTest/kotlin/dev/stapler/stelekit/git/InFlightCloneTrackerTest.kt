// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InFlightCloneTrackerTest {
    private class RecordingLauncher : GitCloneWorkerLauncher {
        val cancelled = mutableListOf<String>()
        override suspend fun launchClone(
            graphId: String, url: String, localPath: String, auth: GitAuth,
            onProgress: (CloneProgress) -> Unit, onStateChange: (GitTransportRetryState) -> Unit,
            graphDisplayName: String?,
        ): Either<DomainError.GitError, Unit> = Unit.right()

        override fun cancel(graphId: String) { cancelled += graphId }
    }

    @Test
    fun `cancel targets the clone's own graphId while in flight, not any other graph`() = runTest {
        val tracker = InFlightCloneTracker()
        val launcher = RecordingLauncher()
        tracker.track("clone-graph") {
            tracker.cancel(launcher)
        }
        assertEquals(listOf("clone-graph"), launcher.cancelled)
    }

    @Test
    fun `cancel is a no-op once the clone has finished`() = runTest {
        val tracker = InFlightCloneTracker()
        val launcher = RecordingLauncher()
        tracker.track("clone-graph") { }
        tracker.cancel(launcher)
        assertNull(tracker.graphId)
        assertEquals(emptyList(), launcher.cancelled)
    }

    @Test
    fun `tracking is cleared when the clone throws`() = runTest {
        val tracker = InFlightCloneTracker()
        runCatching { tracker.track("g") { error("boom") } }
        assertNull(tracker.graphId)
    }
}
