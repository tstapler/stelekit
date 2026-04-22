package dev.stapler.stelekit.performance

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
 *
 * [getTracer] and [getMeter] return [Any] in commonMain to avoid a compile-time
 * dependency on OpenTelemetry API types that are not available on all platforms.
 * JVM/Android actuals cast the returned value to the appropriate OTel types internally.
 */
expect object OtelProvider {
    val isInitialized: Boolean
    /** The ring buffer wired into the OTel SDK span pipeline, or null if not initialized. */
    val ringBuffer: RingBufferSpanExporter?
    fun initialize(config: OtelExporterConfig = OtelExporterConfig())
    /** Returns an `io.opentelemetry.api.trace.Tracer` on JVM/Android; throws on wasmJs. */
    fun getTracer(instrumentationName: String): Any
    /** Returns an `io.opentelemetry.api.metrics.Meter` on JVM/Android; throws on wasmJs. */
    fun getMeter(instrumentationName: String): Any
    fun shutdown()
}
