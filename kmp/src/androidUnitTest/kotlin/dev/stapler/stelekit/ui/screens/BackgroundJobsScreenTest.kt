// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.jobs.BackgroundJobRegistry
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobState
import dev.stapler.stelekit.jobs.JobStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** First-render and interaction checks for the Background jobs screen, over a fake registry (headless). */
@RunWith(RobolectricTestRunner::class)
class BackgroundJobsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeRegistry(initial: List<JobStatus> = emptyList()) : BackgroundJobRegistry {
        val list = MutableStateFlow(initial)
        val cancelled = mutableListOf<String>()
        var cleared = 0
        override val jobs: Flow<List<JobStatus>> = list
        override fun cancel(id: String) { cancelled += id }
        override fun clearFinished() { cleared++ }
    }

    private fun show(registry: BackgroundJobRegistry) =
        composeTestRule.setContent { MaterialTheme { BackgroundJobsScreen(registry) } }

    @Test
    fun emptyRegistry_should_ShowExplanationAndNoClearButton() {
        show(FakeRegistry())

        composeTestRule.onNodeWithText("Nothing running", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear finished").assertDoesNotExist()
    }

    @Test
    fun runningJob_should_ShowTitleKindAndProgress() {
        show(FakeRegistry(listOf(JobStatus("1", JobKind.ModelDownload, "Depth model download", JobState.Running(40)))))

        composeTestRule.onNodeWithText("Depth model download").assertIsDisplayed()
        composeTestRule.onNodeWithText("Model download · Running, 40%").assertIsDisplayed()
    }

    @Test
    fun cancelButton_should_CancelThatJob_When_JobIsActiveAndCancellable() {
        val registry = FakeRegistry(listOf(JobStatus("job-7", JobKind.GitSync, "Syncing with remote", JobState.Running(null))))
        show(registry)

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("job-7"), registry.cancelled)
    }

    @Test
    fun cancelButton_should_BeHidden_When_JobCannotBeStopped() {
        show(FakeRegistry(listOf(JobStatus("1", JobKind.StorageMove, "Moving", JobState.Running(null), cancellable = false))))

        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun failedJob_should_ShowReason_And_OfferClearFinished() {
        val registry = FakeRegistry(listOf(JobStatus("1", JobKind.LfsTransfer, "Downloading images", JobState.Failed("quota used up"))))
        show(registry)

        composeTestRule.onNodeWithText("Image transfer · Failed: quota used up").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeTestRule.onNodeWithText("Clear finished").performClick()

        assertEquals(1, registry.cleared)
    }

    @Test
    fun jobList_should_UpdateLive_When_RegistryEmitsNewState() {
        val registry = FakeRegistry(listOf(JobStatus("1", JobKind.GitClone, "Cloning notes", JobState.Queued)))
        show(registry)
        composeTestRule.onNodeWithText("Git clone · Waiting to start").assertIsDisplayed()

        registry.list.value = listOf(JobStatus("1", JobKind.GitClone, "Cloning notes", JobState.Succeeded))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Git clone · Finished").assertIsDisplayed()
    }
}
