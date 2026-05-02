package dev.stapler.stelekit.performance

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Coroutine context element that carries the active OTel [Span] through a coroutine hierarchy.
 * Inspired by dd-sdk-android-rum-coroutines span propagation pattern.
 */
class OtelSpanElement(val span: Span) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<OtelSpanElement>
    override val key: CoroutineContext.Key<*> get() = Key
}

/** Returns the active [Span] from the coroutine context, or null if none is set. */
val CoroutineContext.currentSpan: Span? get() = get(OtelSpanElement)?.span

/**
 * Starts a named span, propagates it as a coroutine context element, and ends it when
 * [block] completes. Parent linkage is resolved from the current coroutine context span.
 *
 * Usage mirrors Datadog's `transactionTraced` but uses OTel API:
 * ```
 * withTracingSpan("graph.load", tracer) { span ->
 *     span.setAttribute("graph.path", path)
 *     loadGraph()
 * }
 * ```
 */
suspend fun <T> withTracingSpan(
    name: String,
    tracer: Tracer,
    attributes: Map<String, String> = emptyMap(),
    block: suspend CoroutineScope.(Span) -> T
): T = coroutineScope {
    val parentSpan = coroutineContext.currentSpan
    val builder = tracer.spanBuilder(name)
    if (parentSpan != null) builder.setParent(Context.current().with(parentSpan))
    attributes.forEach { (k, v) -> builder.setAttribute(k, v) }
    val span = builder.startSpan()
    try {
        withContext(OtelSpanElement(span)) { block(span) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR, e.message ?: "error")
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}

/**
 * Attaches error reporting to a [Flow], recording any exception on the current coroutine span.
 * Inspired by dd-sdk-android-rum-coroutines `Flow.sendErrorToDatadog()`.
 */
fun <T> Flow<T>.recordErrorsOnSpan(): Flow<T> = catch { e ->
    coroutineContext.currentSpan?.let { span ->
        span.setStatus(StatusCode.ERROR, e.message ?: "error")
        span.recordException(e)
    }
    throw e
}
