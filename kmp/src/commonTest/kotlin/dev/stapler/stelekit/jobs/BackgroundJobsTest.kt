// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import dev.stapler.stelekit.ui.screens.describe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundJobsTest {
    private fun job(id: String, state: JobState) = JobStatus(id, JobKind.ModelDownload, id, state)

    @Test
    fun activeFirst_should_PutQueuedAndRunningBeforeFinished_KeepingRelativeOrder() {
        val jobs = listOf(
            job("done", JobState.Succeeded),
            job("run", JobState.Running(10)),
            job("failed", JobState.Failed(null)),
            job("queued", JobState.Queued),
        )

        assertEquals(listOf("run", "queued", "done", "failed"), jobs.activeFirst().map { it.id })
    }

    @Test
    fun isActive_should_BeTrueOnlyForQueuedAndRunning() {
        assertTrue(job("a", JobState.Queued).isActive)
        assertTrue(job("a", JobState.Running(null)).isActive)
        assertFalse(job("a", JobState.Succeeded).isActive)
        assertFalse(job("a", JobState.Failed("x")).isActive)
        assertFalse(job("a", JobState.Cancelled).isActive)
    }

    @Test
    fun describe_should_IncludeProgressAndFailureReason_WhenKnown() {
        assertEquals("Running, 42%", describe(JobState.Running(42)))
        assertEquals("Running", describe(JobState.Running(null)))
        assertEquals("Failed: no network", describe(JobState.Failed("no network")))
        assertEquals("Failed", describe(JobState.Failed(null)))
    }
}
