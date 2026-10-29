// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.measurement.usb

import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Story 7.3b: USB serial scanning and parsing over a fake [SerialMeterPort] (no hardware; OD-4 unanswered). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidUsbSerialFactoryTest {

    /** Delivers [script] in the given chunks, then reports the port as unplugged. */
    private class FakePort(
        private val script: List<String>,
        private val failOpen: Boolean = false,
    ) : SerialMeterPort {
        override val id = "/dev/bus/usb/001/002"
        override val name = "Fake meter"
        var opened = false
        var closed = false
        private val chunks = ArrayDeque(script)

        override fun open() {
            if (failOpen) error("permission denied")
            opened = true
        }

        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val chunk = chunks.removeFirstOrNull() ?: throw java.io.IOException("device unplugged")
            chunk.toByteArray().copyInto(buffer)
            return chunk.length
        }

        override fun close() {
            closed = true
        }
    }

    private fun factory(finder: SerialMeterPortFinder) =
        AndroidUsbSerialFactory(ApplicationProvider.getApplicationContext(), finder)

    @Test
    fun scan_emitsDeviceThatParsesAReading() = runBlocking {
        val port = FakePort(listOf("12.34 mm\r\n"))
        val device = factory { listOf(port) }.scan().first()

        assertEquals("Fake meter", device.deviceName)
        assertTrue(device.connect().isRight())
        assertEquals(DeviceConnectionState.CONNECTED, device.connectionState.value)
        val reading = device.measurementFlow().first()
        assertEquals(0.01234, reading.valueMeters, 1e-9)
        assertEquals(port.id, reading.deviceId)
    }

    @Test
    fun readings_splitAcrossChunksAndInterleavedWithNoise() = runBlocking {
        val port = FakePort(listOf("READY\r\n1", "2.5 c", "m\n\n", "garbage\r\n", "3 m\r\n"))
        val device = factory { listOf(port) }.scan().first()
        device.connect()

        val readings = device.measurementFlow().toList().map { it.valueMeters }

        assertEquals(listOf(0.125, 3.0), readings)
        assertEquals(DeviceConnectionState.ERROR, device.connectionState.value) // fake unplugs after its script
    }

    @Test
    fun scan_withoutAdapter_completesEmpty() = runBlocking {
        assertEquals(emptyList(), factory { emptyList() }.scan().toList())
    }

    @Test
    fun scan_whenProbingThrows_completesEmptyInsteadOfCrashing() = runBlocking {
        assertEquals(emptyList(), factory { error("usb service unavailable") }.scan().toList())
    }

    @Test
    fun connect_failure_isReportedAsLeftNotThrown() = runBlocking {
        val device = factory { listOf(FakePort(emptyList(), failOpen = true)) }.scan().first()

        assertTrue(device.connect().isLeft())
        assertEquals(DeviceConnectionState.ERROR, device.connectionState.value)
    }

    private class ThrowingPort(private val failure: () -> Throwable) : SerialMeterPort {
        override val id = "x"
        override val name = "Throwing"
        override fun open() = throw failure()
        override fun read(buffer: ByteArray, timeoutMs: Int): Int = throw failure()
        override fun close() = throw failure()
    }

    @Test
    fun disconnect_swallowsACloseFailure() = runBlocking {
        val device = factory { listOf(ThrowingPort { java.io.IOException("gone") }) }.scan().first()

        device.disconnect()

        assertEquals(DeviceConnectionState.DISCONNECTED, device.connectionState.value)
    }

    @Test
    fun cancellation_isNeverSwallowed() = runBlocking {
        val cancel = { CancellationException("cancelled") }
        val device = factory { listOf(ThrowingPort(cancel)) }.scan().first()

        assertFailsWith<CancellationException> { device.connect() }
        assertFailsWith<CancellationException> { device.disconnect() }
        assertFailsWith<CancellationException> { factory { throw cancel() }.scan().toList() }
        val reading = object : SerialMeterPort {
            override val id = "y"
            override val name = "Reading"
            override fun open() {}
            override fun read(buffer: ByteArray, timeoutMs: Int): Int = throw cancel()
            override fun close() {}
        }
        val connected = factory { listOf(reading) }.scan().first()
        connected.connect()
        assertFailsWith<CancellationException> { connected.measurementFlow().toList() }
        Unit
    }

    @Test
    fun disconnect_closesThePort() = runBlocking {
        val port = FakePort(emptyList())
        val device = factory { listOf(port) }.scan().first()
        device.connect()

        device.disconnect()

        assertTrue(port.closed)
        assertEquals(DeviceConnectionState.DISCONNECTED, device.connectionState.value)
    }
}
