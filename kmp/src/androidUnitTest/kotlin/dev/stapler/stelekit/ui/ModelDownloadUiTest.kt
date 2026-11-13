// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

// jvmTest-reason: n/a (androidUnitTest, Robolectric); Gradle-only, not registered in AllAndroidUnitTests.
package dev.stapler.stelekit.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.platform.ml.DepthModelDownloader
import dev.stapler.stelekit.platform.ml.DepthModelStorage
import dev.stapler.stelekit.platform.ml.ModelDownloadFakeServer
import dev.stapler.stelekit.platform.ml.ModelDownloadFixture
import dev.stapler.stelekit.platform.ml.ModelDownloadTask
import dev.stapler.stelekit.platform.ml.toUiState
import dev.stapler.stelekit.ui.annotate.DepthEstimationPanel
import dev.stapler.stelekit.ui.annotate.DepthModelUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** UX-19 / UX-20: the model-download row on a metered network, and an exit from every state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ModelDownloadUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val server = ModelDownloadFakeServer()
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var fixture: ModelDownloadFixture

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        fixture = ModelDownloadFixture(context, server.url, network = NetworkSnapshot(online = true, isMetered = true)).also { it.install() }
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
        DepthModelStorage.modelFile(context).parentFile?.deleteRecursively()
        fixture.uninstall()
    }

    private fun activeRequest() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(DepthModelDownloader.UNIQUE_WORK_NAME).get().first { !it.state.isFinished }

    @Test
    fun waitingRow_should_offerDownloadNowAnywayAndReplaceWithAnyNetwork_When_networkIsMetered() = runBlocking<Unit> {
        val downloader = fixture.downloader
        val waiting = scope.async { downloader.downloadModel() }
        val queued = withTimeout(5_000) {
            downloader.modelState.first { it is DepthModelDownloader.ModelState.Downloading && it.waitingMessage != null }
        }
        val first = activeRequest()
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)

        rule.setContent {
            DepthEstimationPanel(
                modelState = queued.toUiState(),
                isInferenceRunning = false,
                depthEstimationError = null,
                onDownload = {},
                onCancel = { downloader.cancelDownload() },
                onDownloadAnyway = { scope.launch { downloader.downloadNowAnyway() } },
            )
        }
        rule.onNodeWithText("Waiting for an unmetered network").assertExists()
        rule.onNodeWithText("Download now anyway").assertExists()
        rule.onNodeWithContentDescription("Cancel model download").assertExists()

        rule.onNodeWithText("Download now anyway").performClick()
        withTimeout(5_000) {
            while (activeRequest().id == first.id) kotlinx.coroutines.delay(20)
        }

        val replaced = activeRequest()
        assertNotEquals(first.id, replaced.id)
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType)
        assertEquals(NetworkPolicy.Unmetered, fixture.store.read(ModelDownloadTask.KEY).constraints.network, "the override is never persisted")
        downloader.cancelDownload()
        waiting.await()
    }

    @Test
    fun everyState_should_keepAnExit() {
        val states = listOf(
            DepthModelUiState.Waiting("Offline - will download when you are online", canDownloadAnyway = false) to "Cancel model download",
            DepthModelUiState.Waiting("Download failed. It will retry automatically.", canDownloadAnyway = false) to "Cancel model download",
            DepthModelUiState.Downloading(30) to "Cancel model download",
        )
        var current by androidx.compose.runtime.mutableStateOf<DepthModelUiState>(states.first().first)
        rule.setContent {
            DepthEstimationPanel(current, false, null, onDownload = {}, onCancel = {}, onDownloadAnyway = {})
        }
        for ((state, exit) in states) {
            current = state
            rule.waitForIdle()
            rule.onNodeWithContentDescription(exit).assertExists()
        }
        current = DepthModelUiState.Failed("Download failed after 5 tries.")
        rule.waitForIdle()
        rule.onNodeWithText("Download failed after 5 tries. Tap to retry.").assertExists()
    }
}
