package dev.stapler.stelekit.platform.measurement.usb

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.measurement.DeviceConnectionState
import dev.stapler.stelekit.platform.measurement.ExternalMeasurementDevice
import dev.stapler.stelekit.platform.measurement.MeasurementReading
import dev.stapler.stelekit.platform.measurement.keyboard.KeyboardMeasurementParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** A byte channel to one serial meter; the seam that keeps [UsbSerialMeasurementDevice] testable without hardware. */
interface SerialMeterPort {
    val id: String
    val name: String

    /** Opens the port. Throws on failure. */
    fun open()

    /** Blocks up to [timeoutMs] and returns the number of bytes read into [buffer]; 0 on timeout. Throws when the port is gone. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun close()
}

fun interface SerialMeterPortFinder {
    fun findPorts(): List<SerialMeterPort>
}

/**
 * A serial meter that prints one reading per line (e.g. `12.34 mm`). Lines are parsed with
 * [KeyboardMeasurementParser]; lines that do not parse (status chatter, partial reads) are skipped.
 */
class UsbSerialMeasurementDevice(private val port: SerialMeterPort) : ExternalMeasurementDevice {
    override val deviceName: String = port.name
    override val deviceId: String = port.id

    private val _connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<DeviceConnectionState> = _connectionState.asStateFlow()

    override suspend fun connect(): Either<DomainError, Unit> {
        _connectionState.value = DeviceConnectionState.CONNECTING
        return try {
            port.open()
            _connectionState.value = DeviceConnectionState.CONNECTED
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _connectionState.value = DeviceConnectionState.ERROR
            DomainError.SensorError.HardwareUnavailable("USB serial meter: ${e.message ?: "could not open port"}").left()
        }
    }

    override suspend fun disconnect() {
        try {
            port.close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Closing an already-unplugged port throws; the end state is the same.
        }
        _connectionState.value = DeviceConnectionState.DISCONNECTED
    }

    override fun measurementFlow(): Flow<MeasurementReading> = flow {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val lines = LineAssembler()
        while (_connectionState.value == DeviceConnectionState.CONNECTED) {
            val n = try {
                port.read(buffer, READ_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unplugged mid-read.
                _connectionState.value = DeviceConnectionState.ERROR
                return@flow
            }
            lines.feed(buffer, n).forEach { text ->
                KeyboardMeasurementParser.parse(text, deviceId)?.let { emit(it) }
            }
        }
    }.flowOn(PlatformDispatcher.IO)

    /** Splits a byte stream into non-empty lines on CR/LF, dropping the tail of any line longer than [MAX_LINE_CHARS]. */
    private class LineAssembler {
        private val line = StringBuilder()

        fun feed(bytes: ByteArray, count: Int): List<String> {
            val done = mutableListOf<String>()
            for (i in 0 until count) {
                val ch = bytes[i].toInt().toChar()
                if (ch != '\n' && ch != '\r') {
                    if (line.length < MAX_LINE_CHARS) line.append(ch)
                } else if (line.isNotEmpty()) {
                    done += line.toString()
                    line.setLength(0)
                }
            }
            return done
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 256
        const val READ_TIMEOUT_MS = 200
        const val MAX_LINE_CHARS = 64
    }
}
