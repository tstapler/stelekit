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
    private var _ringBuffer: RingBufferSpanExporter? = null

    actual val isInitialized: Boolean get() = sdk != null
    actual val ringBuffer: RingBufferSpanExporter? get() = _ringBuffer

    actual fun initialize(config: OtelExporterConfig) {
        val tracerProviderBuilder = SdkTracerProvider.builder()
        if (config.enableStdout) {
            tracerProviderBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(LoggingSpanExporter.create()).build()
            )
        }
        if (config.enableRingBuffer) {
            val rb = RingBufferSpanExporter(config.ringBufferCapacity)
            _ringBuffer = rb
            tracerProviderBuilder.addSpanProcessor(
                SimpleSpanProcessor.create(OtelRingBufferBridge(rb))
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
        _ringBuffer = null
    }
}
