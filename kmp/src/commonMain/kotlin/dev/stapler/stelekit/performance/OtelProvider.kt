package dev.stapler.stelekit.performance

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.metrics.Meter

/**
 * Configuration for OpenTelemetry exporters.
 *
 * @param enableStdout When true, completed spans are logged to stdout (useful during development).
 * @param enableRingBuffer When true, spans are stored in an in-memory ring buffer for bug reports.
 * @param ringBufferCapacity Maximum number of spans held in the ring buffer.
 */
data class OtelExporterConfig(
    val enableStdout: Boolean = false,
    val enableRingBuffer: Boolean = true,
    val ringBufferCapacity: Int = 1000
)

/**
 * Platform-agnostic façade for OpenTelemetry SDK initialisation.
 *
 * Implemented as `expect`/`actual` so that Android and JVM can initialise the
 * appropriate SDK variant while sharing the same call site in common code.
 *
 * Call [initialize] once at application startup (before any [getTracer] calls).
 * Call [shutdown] when the application exits to flush pending spans.
 */
expect object OtelProvider {
    val isInitialized: Boolean
    fun initialize(config: OtelExporterConfig = OtelExporterConfig())
    fun getTracer(instrumentationName: String): Tracer
    fun getMeter(instrumentationName: String): Meter
    fun shutdown()
}
