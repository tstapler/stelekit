package dev.stapler.stelekit.transfer.qrcode

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 2.1.3 (Task 2.1.3b) acceptance criteria: the pre-flight `ceil(payloadLen / fragLen) x
 * redundancy` estimate is exposed for the UI before any frame is displayed, and fail-fast flags
 * oversize transfers.
 */
class FountainEncoderPreflightTest {

    @Test
    fun preflight_should_ReportEstimatedChunkCountAndByteSize_When_PageIsTwoKilobytes() {
        val payloadLen = 2 * 1024
        val maxFragmentBytes = 512

        val estimate = FountainEncoder.preflightEstimate(payloadLen, maxFragmentBytes = maxFragmentBytes)

        val expectedFragmentLen = findNominalFragmentLength(
            payloadLen,
            FountainEncoder.DEFAULT_MIN_FRAGMENT_BYTES,
            maxFragmentBytes,
        )
        val expectedPureCount = ceil(payloadLen.toDouble() / expectedFragmentLen).toInt()

        assertEquals(expectedFragmentLen, estimate.fragmentLen)
        assertEquals(expectedPureCount, estimate.pureFragmentCount)
        assertTrue(estimate.estimatedFrameCount >= expectedPureCount)
        assertEquals(
            estimate.estimatedFrameCount * (expectedFragmentLen + FountainEncoder.FRAME_OVERHEAD_BYTES),
            estimate.estimatedTotalBytes,
        )
        assertFalse(estimate.isOversize)
    }

    @Test
    fun preflight_should_FlagOversizeBeforeSending_When_EstimatedFrameCountExceedsThreshold() {
        // Tiny fragment size against a large payload forces a huge frame count.
        val payloadLen = 65536
        val maxFragmentBytes = 20

        val estimate = FountainEncoder.preflightEstimate(
            payloadLen,
            maxFragmentBytes = maxFragmentBytes,
            oversizeFrameThreshold = 50,
        )

        assertTrue(estimate.estimatedFrameCount > 50)
        assertTrue(estimate.isOversize)
    }
}
