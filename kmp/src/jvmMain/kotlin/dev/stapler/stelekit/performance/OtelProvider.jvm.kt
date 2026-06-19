package dev.stapler.stelekit.performance

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanLimits
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

actual object OtelProvider {
    private var sdk: OpenTelemetrySdk? = null
    private var _ringBuffer: RingBufferSpanExporter? = null
    private var _isRingBufferActive = false

    actual val isInitialized: Boolean get() = sdk != null
    actual val ringBuffer: RingBufferSpanExporter? get() = if (sdk != null && _isRingBufferActive) _ringBuffer else null

    actual fun initialize(config: OtelExporterConfig) {
        // Shutdown any existing SDK first if called repeatedly
        sdk?.shutdown()

        // Bound attribute count and value length via SDK limits — prevents a single
        // runaway span from consuming unbounded memory in the 1000-span ring buffer.
        val spanLimits = SpanLimits.builder()
            .setMaxNumberOfAttributes(32)
            .setMaxAttributeValueLength(512)
            .build()
        val tracerProviderBuilder = SdkTracerProvider.builder()
            .setSpanLimits(spanLimits)
        if (config.enableStdout) {
            tracerProviderBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(LoggingSpanExporter.create()).build()
            )
        }
        _isRingBufferActive = config.enableRingBuffer
        if (config.enableRingBuffer) {
            val rb = _ringBuffer ?: RingBufferSpanExporter(config.ringBufferCapacity).also { _ringBuffer = it }
            rb.clear()
            tracerProviderBuilder.addSpanProcessor(
                SimpleSpanProcessor.create(OtelRingBufferBridge(rb))
            )
        } else {
            _ringBuffer?.clear()
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
        _isRingBufferActive = false
        _ringBuffer?.clear()
    }
}
