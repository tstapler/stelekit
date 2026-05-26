package dev.stapler.stelekit.performance

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

actual object OtelProvider {
    private var sdk: OpenTelemetrySdk? = null
    private val _ringBuffer = RingBufferSpanExporter(1000)

    actual val isInitialized: Boolean get() = sdk != null
    actual val ringBuffer: RingBufferSpanExporter? get() = if (sdk != null) _ringBuffer else null

    actual fun initialize(config: OtelExporterConfig) {
        // Shutdown any existing SDK first if called repeatedly
        sdk?.shutdown()

        val tracerProviderBuilder = SdkTracerProvider.builder()
        if (config.enableStdout) {
            tracerProviderBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(LoggingSpanExporter.create()).build()
            )
        }
        if (config.enableRingBuffer) {
            _ringBuffer.clear()
            tracerProviderBuilder.addSpanProcessor(
                SimpleSpanProcessor.create(OtelRingBufferBridge(_ringBuffer))
            )
        }
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProviderBuilder.build())
            .setMeterProvider(SdkMeterProvider.builder().build())
            .build()
    }

    actual fun getTracer(instrumentationName: String): Any =
        sdk?.getTracer(instrumentationName)
            ?: error("OtelProvider not initialized — call initialize() first")

    actual fun getMeter(instrumentationName: String): Any =
        sdk?.getMeter(instrumentationName)
            ?: error("OtelProvider not initialized — call initialize() first")

    actual fun shutdown() {
        sdk?.shutdown()
        sdk = null
        _ringBuffer.clear()
    }
}
