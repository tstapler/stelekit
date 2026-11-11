// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.stapler.stelekit.jobs.schedule.NetworkSnapshot
import dev.stapler.stelekit.jobs.schedule.TaskEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The P1-1 regression: from a fresh install (empty settings) a requested download runs the REAL task body and ends
 * `Ready`. `enabled` is periodic-only, so neither a missing entry nor a stored `enabled=false` may skip it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ModelDownloadEndToEndTest {
    private val context: Context = ApplicationProvider.getApplicationContext<Application>()
    private val server = ModelDownloadFakeServer()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val target = DepthModelStorage.modelFile(context)
    private lateinit var fixture: ModelDownloadFixture

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        fixture = ModelDownloadFixture(context, server.url, network = NetworkSnapshot(online = true, isMetered = false)).also { it.install() }
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
        target.parentFile?.deleteRecursively()
        fixture.uninstall()
    }

    private fun downloadFromCurrentSettings() = runBlocking<Unit> {
        val result = scope.async { fixture.downloader.downloadModel() }
        val work = WorkManager.getInstance(context)
        val id: UUID = withTimeout(5_000) {
            var found: UUID? = null
            while (found == null) {
                found = work.getWorkInfosForUniqueWork(DepthModelDownloader.UNIQUE_WORK_NAME).get().firstOrNull { !it.state.isFinished }?.id
                if (found == null) delay(20)
            }
            found
        }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)

        assertTrue(withTimeout(60_000) { result.await() }.isRight())
        assertContentEquals(server.payload, target.readBytes())
        assertEquals(DepthModelDownloader.ModelState.Ready, fixture.downloader.modelState.value)
    }

    @Test
    fun downloadModel_should_endReady_When_settingsAreEmpty() {
        assertTrue(fixture.settings.store.keys.none { it.startsWith("task_entry_") })
        downloadFromCurrentSettings()
    }

    @Test
    fun downloadModel_should_endReady_When_storedEntryHasEnabledFalse() {
        runBlocking { fixture.store.write(ModelDownloadTask.KEY, TaskEntry(enabled = false, overrides = null)) }
        downloadFromCurrentSettings()
    }
}
