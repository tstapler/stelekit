package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for cancelDownload() — see project_plans/depth-model-download-stall's
 * adversarial-review.md Blocker #2: a caller suspended in `downloadModel()` awaiting a terminal
 * state must actually resolve (not hang forever) once `cancelDownload()` runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderCancelTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `cancelDownload resolves a concurrently-suspended downloadModel caller instead of hanging`() = runBlocking {
        val downloader = DepthModelDownloader(context)

        val pending = async { downloader.downloadModel() }

        // Give downloadModel() a chance to enqueue and start suspending on the terminal-state flow.
        var attempts = 0
        while (downloader.modelState.value !is DepthModelDownloader.ModelState.Downloading && attempts < 50) {
            kotlinx.coroutines.delay(10)
            attempts++
        }
        assertTrue(downloader.modelState.value is DepthModelDownloader.ModelState.Downloading)

        downloader.cancelDownload()

        val result = withTimeout(5_000) { pending.await() }
        assertTrue(result.isLeft(), "expected cancelDownload() to resolve the pending caller as a Left, not hang")
        assertEquals(DepthModelDownloader.ModelState.Absent, downloader.modelState.value)
    }

    @Test
    fun `cancelDownload removes the DownloadManager request and resets state`() = runBlocking {
        val downloader = DepthModelDownloader(context)
        val pending = async { downloader.downloadModel() }

        var attempts = 0
        while (downloader.modelState.value !is DepthModelDownloader.ModelState.Downloading && attempts < 50) {
            kotlinx.coroutines.delay(10)
            attempts++
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        assertEquals(1, shadowOf(downloadManager).requestCount)

        downloader.cancelDownload()
        withTimeout(5_000) { pending.await() }

        assertEquals(DepthModelDownloader.ModelState.Absent, downloader.modelState.value)
    }

    @Test
    fun `cancelDownload with no active download is a no-op`() {
        val downloader = DepthModelDownloader(context)
        downloader.cancelDownload() // must not throw
        assertEquals(DepthModelDownloader.ModelState.Absent, downloader.modelState.value)
    }
}
