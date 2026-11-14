// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.jobs.schedule.BackgroundTaskScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals

/** The initial empty WorkInfo snapshot computes `Absent` while idle; it must not overwrite a download that started since. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderAbsentRaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        scope.cancel()
        DepthModelStorage.modelFile(context).parentFile?.deleteRecursively()
    }

    @Test
    fun staleAbsentSnapshot_should_notOverwriteDownloading_When_downloadStartedAfterItWasComputed() = runBlocking<Unit> {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val firstWrite = AtomicBoolean(true)
        // runOnce is a suspend Unit method: a null return resumes it with Unit.
        val noopScheduler = Proxy.newProxyInstance(
            BackgroundTaskScheduler::class.java.classLoader,
            arrayOf(BackgroundTaskScheduler::class.java),
        ) { _, _, _ -> null } as BackgroundTaskScheduler
        val downloader = DepthModelDownloader(
            context,
            scheduler = noopScheduler,
            beforeStateWrite = {
                if (firstWrite.getAndSet(false)) {
                    reached.complete(Unit)
                    release.await()
                }
            },
            afterStateWrite = { written.complete(Unit) },
        )
        withTimeout(10_000) { reached.await() } // snapshot computed as Absent with inFlight == 0; write held

        val waiting = scope.async { downloader.downloadModel() }
        withTimeout(10_000) { downloader.modelState.first { it is DepthModelDownloader.ModelState.Downloading } }
        release.complete(Unit)
        withTimeout(10_000) { written.await() } // the released stale write has been applied (or skipped)

        assertEquals(DepthModelDownloader.ModelState.Downloading(progress = -1), downloader.modelState.value)
        waiting.cancel()
    }
}
