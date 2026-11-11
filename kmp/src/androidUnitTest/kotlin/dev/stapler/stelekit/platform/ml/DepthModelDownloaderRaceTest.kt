package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WorkManager can report SUCCEEDED a moment before the model file is visible to [DepthModelStorage.isReady].
 * That must read as "still finishing", not as a failed download.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderRaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        DepthModelStorage.modelFile(context).parentFile?.deleteRecursively()
    }

    /** Ready-check that answers false [falseCount] times, then true. Not ready at construction either. */
    private fun downloader(falseCount: Int, alwaysFalse: Boolean = false): DepthModelDownloader {
        val calls = AtomicInteger(0)
        return DepthModelDownloader(
            context = context,
            readyCheck = { !alwaysFalse && calls.getAndIncrement() >= falseCount },
            settleTimeoutMs = 400,
            settlePollMs = 20,
        )
    }

    @Test
    fun `SUCCEEDED before the file is visible settles to Ready, never Failed`() = runBlocking<Unit> {
        val d = downloader(falseCount = 3)
        val seen = mutableListOf<DepthModelDownloader.ModelState>()
        val collector = async(start = CoroutineStart.UNDISPATCHED) { d.modelState.collect { seen += it } }

        d.onWorkSucceeded()
        withTimeout(5_000) { d.modelState.first { it is DepthModelDownloader.ModelState.Ready } }
        collector.cancel()

        assertTrue(seen.none { it is DepthModelDownloader.ModelState.Failed }, "emitted Failed during the race: $seen")
    }

    @Test
    fun `SUCCEEDED with the file never appearing eventually emits Failed`() = runBlocking<Unit> {
        val d = downloader(falseCount = 0, alwaysFalse = true)

        d.onWorkSucceeded()
        val failed = withTimeout(5_000) { d.modelState.first { it is DepthModelDownloader.ModelState.Failed } }

        assertEquals("The model file went missing after downloading. Try again.", (failed as DepthModelDownloader.ModelState.Failed).reason)
    }
}
