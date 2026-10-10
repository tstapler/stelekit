package dev.stapler.stelekit.platform.sensor

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoOpCameraFrameSourceTest {

    private val source = NoOpCameraFrameSource()

    @Test
    fun noOpCameraFrameSource_should_ReturnEmptyFlowAndUnavailable_When_FrameStreamCollected() = runTest {
        assertFalse(source.isAvailable)

        val emissions = source.frameStream().toList()

        assertTrue(emissions.isEmpty(), "NoOpCameraFrameSource should emit no frames")
    }
}
