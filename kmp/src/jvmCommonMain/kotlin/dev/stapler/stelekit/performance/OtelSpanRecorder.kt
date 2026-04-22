package dev.stapler.stelekit.performance

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

/**
 * OTel implementation of [SpanRecorder] for JVM and Android.
 * Creates a span per screen view; interaction spans are children of the active screen span.
 *
 * Obtain from [OtelProvider] after calling [OtelProvider.initialize]:
 * ```kotlin
 * val recorder = OtelSpanRecorder(OtelProvider.getTracer("compose.navigation") as Tracer)
 * ```
 */
class OtelSpanRecorder(private val tracer: Tracer) : SpanRecorder {
    private var activeScreenSpan: Span? = null

    override fun recordScreenView(screenName: String) {
        activeScreenSpan?.end()
        activeScreenSpan = tracer.spanBuilder("screen.view")
            .setAttribute("screen.name", screenName)
            .startSpan()
    }

    override fun recordInteraction(componentName: String, action: String) {
        val parent = activeScreenSpan
        val builder = tracer.spanBuilder("user.interaction")
            .setAttribute("component.name", componentName)
            .setAttribute("interaction.action", action)
        if (parent != null) {
            builder.setParent(io.opentelemetry.context.Context.current().with(parent))
        }
        builder.startSpan().end() // Interactions are point-in-time; end immediately
    }
}
