package dev.stapler.stelekit.performance

/**
 * No-op implementation of OtelProvider for the iOS target.
 * OpenTelemetry SDK is JVM/Android-only; on iOS we simply skip instrumentation.
 */
actual object OtelProvider {
    actual val isInitialized: Boolean get() = false
    actual fun initialize(config: OtelExporterConfig) { /* no-op */ }
    actual fun getTracer(instrumentationName: String): Any =
        throw UnsupportedOperationException("OpenTelemetry is not available on iOS")
    actual fun getMeter(instrumentationName: String): Any =
        throw UnsupportedOperationException("OpenTelemetry is not available on iOS")
    actual fun shutdown() { /* no-op */ }
}
