package dev.stapler.stelekit.performance

import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying the active span's trace and parent span IDs.
 * Install with [kotlinx.coroutines.withContext] when entering a root span.
 * [TracingQueryWrapper] reads this to attach query-level child spans to the correct trace.
 */
data class ActiveSpanContext(
    val traceId: String,
    val parentSpanId: String,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ActiveSpanContext>
    override val key: CoroutineContext.Key<*> = Key
}
