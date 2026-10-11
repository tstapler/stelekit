// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests [CloneProgressTracker] — the pure state machine behind [AndroidGitRepository]/
 * [JvmGitRepository]'s JGit `ProgressMonitor` (git-sync-resilience Story 4.1.1, REQ-6). Deliberately
 * platform-independent (no JGit dependency) so this covers the exact completed/totalWork mapping
 * without needing a real clone.
 */
class CloneProgressTrackerTest {

    @Test
    fun `update() before any beginTask() call reports CloneProgress with totalWork=0 (indeterminate) rather than throwing`() {
        val tracker = CloneProgressTracker()

        val progress = tracker.onUpdate(5)

        assertEquals(CloneProgress(phase = "", completed = 5, totalWork = 0), progress)
    }

    @Test
    fun `beginTask then update reports CloneProgress with the beginTask phase and update's completed count`() {
        val tracker = CloneProgressTracker()

        tracker.onBeginTask("Receiving objects", 100)
        val progress = tracker.onUpdate(42)

        assertEquals(CloneProgress(phase = "Receiving objects", completed = 42, totalWork = 100), progress)
    }

    @Test
    fun `a second beginTask resets completed back to 0 under the new phase title`() {
        val tracker = CloneProgressTracker()
        tracker.onBeginTask("Receiving objects", 100)
        tracker.onUpdate(100)

        val progress = tracker.onBeginTask("Resolving deltas", 40)

        assertEquals(CloneProgress(phase = "Resolving deltas", completed = 0, totalWork = 40), progress)
    }

    @Test
    fun `current reflects the last emitted CloneProgress without needing another callback`() {
        val tracker = CloneProgressTracker()
        tracker.onBeginTask("Receiving objects", 100)
        tracker.onUpdate(42)

        assertEquals(CloneProgress(phase = "Receiving objects", completed = 42, totalWork = 100), tracker.current)
    }
}
