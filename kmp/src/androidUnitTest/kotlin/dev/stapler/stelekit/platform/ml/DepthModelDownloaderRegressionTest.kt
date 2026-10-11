package dev.stapler.stelekit.platform.ml

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC6 regression coverage — the fast path and the Failed→retry flow must keep working after the
 * download-stall fix. See project_plans/depth-model-download-stall/implementation/plan.md Story
 * 5.1.2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DepthModelDownloaderRegressionTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `isModelReady fast path short-circuits without enqueueing`() = runBlocking {
        val modelFile = File(context.filesDir, "models/depth_anything_v2_small.onnx")
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(ByteArray(11 * 1024 * 1024)) // > 10MB sanity threshold

        val downloader = DepthModelDownloader(context)
        val result = downloader.downloadModel()

        assertTrue(result.isRight())
        assertEquals(DepthModelDownloader.ModelState.Ready, downloader.modelState.value)
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        assertEquals(0, shadowOf(downloadManager).requestCount)
    }

    @Test
    fun `retry after a genuine failure re-enqueues a fresh download`() = runBlocking {
        val downloader = DepthModelDownloader(context)
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        // Drive the first attempt to Failed: ShadowDownloadManager never marks a request
        // STATUS_SUCCESSFUL, so delivering the completion broadcast for it resolves as a genuine
        // failure — a convenient, real (not mocked) way to reach ModelState.Failed under test.
        val firstResult = withTimeout(5_000) {
            val pending = async { downloader.downloadModel() }
            var attempts = 0
            while (downloader.modelState.value !is DepthModelDownloader.ModelState.Downloading && attempts < 50) {
                delay(10)
                attempts++
            }
            // Recover the id ShadowDownloadManager assigned to the one request we enqueued —
            // it doesn't expose enqueue()'s return value directly, so probe for the first id
            // whose request is non-null.
            val downloadId = (0L..20L).first { shadowOf(downloadManager).getRequest(it) != null }
            context.sendBroadcast(
                Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                    .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId),
            )
            // Robolectric's default PAUSED looper mode queues the broadcast dispatch on the main
            // looper rather than delivering it inline — must idle it before awaiting the receiver's
            // effect.
            shadowOf(Looper.getMainLooper()).idle()
            pending.await()
        }
        assertTrue(firstResult.isLeft())
        assertEquals(1, shadowOf(downloadManager).requestCount)

        // Retry: activeDownloadId must have been reset to -1L by the completion handler, so this
        // enqueues a fresh request rather than reattaching to the dead one.
        val secondAttempt = async { downloader.downloadModel() }
        var attempts = 0
        while (shadowOf(downloadManager).requestCount < 2 && attempts < 50) {
            delay(10)
            attempts++
        }
        assertEquals(2, shadowOf(downloadManager).requestCount, "retry must enqueue a fresh DownloadManager request")

        downloader.cancelDownload()
        secondAttempt.await()
        Unit
    }
}
