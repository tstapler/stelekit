package dev.stapler.stelekit.platform.sensor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthConversionsTest {

    // arKitMetresToMm

    @Test fun `arKitMetresToMm converts 1m to 1000mm`() {
        assertEquals(1000f, arKitMetresToMm(1f))
    }

    @Test fun `arKitMetresToMm converts 0m to 0mm`() {
        assertEquals(0f, arKitMetresToMm(0f))
    }

    @Test fun `arKitMetresToMm converts fractional metres`() {
        assertEquals(250f, arKitMetresToMm(0.25f))
    }

    @Test fun `arKitMetresToMm converts large value`() {
        assertEquals(5000f, arKitMetresToMm(5f))
    }

    // arKitConfidenceLevelToRaw

    @Test fun `arKitConfidenceLevelToRaw level 0 gives 85`() {
        assertEquals(85f, arKitConfidenceLevelToRaw(0))
    }

    @Test fun `arKitConfidenceLevelToRaw level 1 gives 170`() {
        assertEquals(170f, arKitConfidenceLevelToRaw(1))
    }

    @Test fun `arKitConfidenceLevelToRaw level 2 gives 255`() {
        assertEquals(255f, arKitConfidenceLevelToRaw(2))
    }

    @Test fun `arKitConfidenceLevelToRaw clamps negative to 0`() {
        assertEquals(85f, arKitConfidenceLevelToRaw(-1))
    }

    @Test fun `arKitConfidenceLevelToRaw clamps above 2`() {
        assertEquals(255f, arKitConfidenceLevelToRaw(3))
    }

    // ARKIT_CONFIDENCE_DEFAULT

    @Test fun `ARKIT_CONFIDENCE_DEFAULT is in range 0 to 255`() {
        assertTrue(ARKIT_CONFIDENCE_DEFAULT in 0f..255f)
    }
}
