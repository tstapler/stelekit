package dev.stapler.stelekit.platform.sensor

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class NoOpCameraProviderTest {

    private val provider = NoOpCameraProvider()

    @Test
    fun `isAvailable is false`() {
        assertFalse(provider.isAvailable)
    }

    @Test
    fun `capturePhoto returns HardwareUnavailable`() = kotlinx.coroutines.test.runTest {
        val result = provider.capturePhoto()
        val error = result.leftOrNull()
        assertIs<DomainError.SensorError.HardwareUnavailable>(error)
        assertEquals("camera", error.sensor)
    }
}
