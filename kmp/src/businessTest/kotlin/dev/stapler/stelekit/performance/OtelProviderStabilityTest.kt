package dev.stapler.stelekit.performance

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class OtelProviderStabilityTest {

    @Test
    fun testOtelProviderMaintainsStableRingBufferReferenceOnReinitialization() {
        // 1. Initialise the provider
        OtelProvider.initialize(OtelExporterConfig(enableStdout = false, enableRingBuffer = true))
        
        val initialRingBuffer = OtelProvider.ringBuffer
        assertNotNull(initialRingBuffer, "Ring buffer should be instantiated on initialization")

        // 2. Shut down and re-initialise the provider (simulating toggling OTel Stdout in the dev menu)
        OtelProvider.shutdown()
        OtelProvider.initialize(OtelExporterConfig(enableStdout = true, enableRingBuffer = true))

        val secondRingBuffer = OtelProvider.ringBuffer
        assertNotNull(secondRingBuffer, "Ring buffer should still be instantiated after re-initialization")

        // 3. Assert that the exact same instance is returned to ensure no desynchronization occurs
        assertSame(
            initialRingBuffer, 
            secondRingBuffer, 
            "OtelProvider must retain the same stable RingBufferSpanExporter instance across SDK lifetimes"
        )

        // 4. Clean up
        OtelProvider.shutdown()
    }
}
