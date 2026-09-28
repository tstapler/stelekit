package dev.stapler.stelekit.platform.sensor

import kotlin.test.Test
import kotlin.test.assertFalse

class SensorModuleTest {

    @Test
    fun sensorModule_should_RejectPreflightWithHardwareUnavailable_When_NoCameraFrameSourceWired() {
        // On a JVM process with no platform wiring, SensorModule.cameraFrameSource defaults
        // to NoOpCameraFrameSource — isAvailable=false is the contract a decode pre-flight
        // check gates on to reject with HardwareUnavailable before entering "Scanning".
        assertFalse(SensorModule.cameraFrameSource.isAvailable)
    }
}
