package dev.stapler.stelekit.performance

/**
 * No-op implementation of OtelProvider for the wasmJs browser target.
 * OpenTelemetry SDK is JVM/Android-only; on the web we simply skip instrumentation.
 */
actual object OtelProvider {
    actual val isInitialized: Boolean get() = false
    actual fun initialize(config: OtelExporterConfig) { /* no-op */ }
    actual fun getTracer(instrumentationName: String): Any =
        throw UnsupportedOperationException("OpenTelemetry is not available on wasmJs")
    actual fun getMeter(instrumentationName: String): Any =
        throw UnsupportedOperationException("OpenTelemetry is not available on wasmJs")
    actual fun shutdown() { /* no-op */ }
}
