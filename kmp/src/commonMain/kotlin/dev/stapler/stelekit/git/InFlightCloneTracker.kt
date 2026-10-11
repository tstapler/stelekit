// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Remembers which graph's clone is in flight so the UI's Cancel targets the clone's own graphId —
 * the id a clone launches under is derived from its destination path, not the currently active graph.
 */
class InFlightCloneTracker {
    var graphId: String? = null
        private set

    suspend fun <T> track(graphId: String, block: suspend () -> T): T {
        this.graphId = graphId
        try {
            return block()
        } finally {
            if (this.graphId == graphId) this.graphId = null
        }
    }

    /** Cancels the tracked clone on [launcher]; a no-op when none is in flight. */
    fun cancel(launcher: GitCloneWorkerLauncher?) {
        val id = graphId ?: return
        launcher?.cancel(id)
    }
}
