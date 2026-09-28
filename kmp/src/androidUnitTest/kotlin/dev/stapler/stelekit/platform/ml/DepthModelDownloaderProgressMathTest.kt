package dev.stapler.stelekit.platform.ml

import android.app.DownloadManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-function coverage for the percentage/stall/decision math extracted from
 * [DepthModelDownloader]'s polling loop — see project_plans/depth-model-download-stall.
 */
class DepthModelDownloaderProgressMathTest {

    @Test
    fun `computeProgressPercent returns proportional percentage`() {
        assertEquals(42, computeProgressPercent(bytesDownloaded = 42_000_000L, totalBytes = 100_000_000L))
    }

    @Test
    fun `computeProgressPercent returns -1 for unknown total size`() {
        assertEquals(-1, computeProgressPercent(bytesDownloaded = 5_000_000L, totalBytes = -1L))
    }

    @Test
    fun `computeProgressPercent coerces into 0 to 100`() {
        assertEquals(100, computeProgressPercent(bytesDownloaded = 150_000_000L, totalBytes = 100_000_000L))
        assertEquals(0, computeProgressPercent(bytesDownloaded = 0L, totalBytes = 100_000_000L))
    }

    @Test
    fun `hasStalled returns true past the timeout threshold`() {
        assertTrue(hasStalled(lastProgressAt = 1_000L, now = 32_000L, timeoutMs = 30_000L))
    }

    @Test
    fun `hasStalled returns false within the timeout threshold`() {
        assertFalse(hasStalled(lastProgressAt = 1_000L, now = 20_000L, timeoutMs = 30_000L))
    }

    @Test
    fun `decidePollTick reports Progress when bytes advance`() {
        val decision = decidePollTick(
            bytes = 21_500_000L,
            total = 100_000_000L,
            status = DownloadManager.STATUS_RUNNING,
            bytesAdvanced = true,
            stalled = false,
        )
        assertEquals(PollDecision.Progress(21), decision)
    }

    @Test
    fun `decidePollTick sequence produces increasing progress across two ticks`() {
        val first = decidePollTick(20_000_000L, 100_000_000L, DownloadManager.STATUS_RUNNING, bytesAdvanced = true, stalled = false)
        val second = decidePollTick(21_500_000L, 100_000_000L, DownloadManager.STATUS_RUNNING, bytesAdvanced = true, stalled = false)
        assertEquals(PollDecision.Progress(20), first)
        assertEquals(PollDecision.Progress(21), second)
    }

    @Test
    fun `decidePollTick reports Progress with indeterminate percent for unknown total size`() {
        val decision = decidePollTick(
            bytes = 5_000_000L,
            total = -1L,
            status = DownloadManager.STATUS_RUNNING,
            bytesAdvanced = true,
            stalled = false,
        )
        assertEquals(PollDecision.Progress(-1), decision)
    }

    @Test
    fun `decidePollTick reports Stalled only when bytes have not advanced and timeout exceeded`() {
        val decision = decidePollTick(
            bytes = 20_000_000L,
            total = 100_000_000L,
            status = DownloadManager.STATUS_RUNNING,
            bytesAdvanced = false,
            stalled = true,
        )
        assertEquals(PollDecision.Stalled, decision)
    }

    @Test
    fun `decidePollTick does not report Stalled if bytes are still advancing`() {
        val decision = decidePollTick(
            bytes = 20_000_000L,
            total = 100_000_000L,
            status = DownloadManager.STATUS_RUNNING,
            bytesAdvanced = true,
            stalled = true, // shouldn't matter — bytesAdvanced short-circuits it
        )
        assertEquals(PollDecision.Progress(20), decision)
    }

    @Test
    fun `decidePollTick reports Terminal on STATUS_SUCCESSFUL regardless of byte movement`() {
        val decision = decidePollTick(
            bytes = 100_000_000L,
            total = 100_000_000L,
            status = DownloadManager.STATUS_SUCCESSFUL,
            bytesAdvanced = false,
            stalled = true,
        )
        assertEquals(PollDecision.Terminal, decision)
    }

    @Test
    fun `decidePollTick reports Terminal on STATUS_FAILED`() {
        val decision = decidePollTick(
            bytes = 0L,
            total = -1L,
            status = DownloadManager.STATUS_FAILED,
            bytesAdvanced = false,
            stalled = false,
        )
        assertEquals(PollDecision.Terminal, decision)
    }
}
