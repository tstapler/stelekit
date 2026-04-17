package dev.stapler.stelekit.performance

import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor

actual object OtelProvider {
    private var sdk: OpenTelemetrySdk? = null

    actual val isInitialized: Boolean get() = sdk != null

    actual fun initialize(config: OtelExporterConfig) {
        val tracerProviderBuilder = SdkTracerProvider.builder()
        if (config.enableStdout) {
            tracerProviderBuilder.addSpanProcessor(
                BatchSpanProcessor.builder(LoggingSpanExporter.create()).build()
            )
        }
        sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProviderBuilder.build())
            .setMeterProvider(SdkMeterProvider.builder().build())
            .build()
    }

    actual fun getTracer(instrumentationName: String): Tracer =
        sdk?.getTracer(instrumentationName)
            ?: throw IllegalStateException("OtelProvider not initialized — call initialize() first")

    actual fun getMeter(instrumentationName: String): Meter =
        sdk?.getMeter(instrumentationName)
            ?: throw IllegalStateException("OtelProvider not initialized — call initialize() first")

    actual fun shutdown() {
        sdk?.shutdown()
        sdk = null
    }
}
