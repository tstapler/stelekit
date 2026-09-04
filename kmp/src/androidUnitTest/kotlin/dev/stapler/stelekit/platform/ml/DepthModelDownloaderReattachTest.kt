package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression coverage for AC5 (screen navigate-away must not strand or double-enqueue) — see
 * project_plans/depth-model-download-stall/requirements.md and Story 5.1.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderReattachTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `second downloadModel call while one is in flight reattaches instead of double-enqueuing`() = runBlocking {
        val downloader = DepthModelDownloader(context)

        // First caller (e.g. the original AnnotationEditorViewModel instance).
        val first = async { downloader.downloadModel() }
        var attempts = 0
        while (downloader.modelState.value !is DepthModelDownloader.ModelState.Downloading && attempts < 50) {
            delay(10)
            attempts++
        }

        // Second caller (e.g. a fresh AnnotationEditorViewModel after navigating back to the screen).
        val second = async { downloader.downloadModel() }
        delay(50) // let it observe activeDownloadId and take the reattach branch, not a fresh enqueue

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        assertEquals(1, shadowOf(downloadManager).requestCount, "second call must not enqueue a second request")

        // Both callers resolve identically once the (single) transfer reaches a terminal state —
        // driven here via cancelDownload() rather than a simulated success (see PR description:
        // ShadowDownloadManager has no API to simulate a live in-flight transfer completing
        // successfully; the success path is covered by manual on-device verification instead).
        downloader.cancelDownload()
        val firstResult = first.await()
        val secondResult = second.await()
        assertEquals(firstResult.isLeft(), secondResult.isLeft())
    }
}
