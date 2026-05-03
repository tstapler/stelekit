package dev.stapler.stelekit.performance

import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CancellationException
import io.opentelemetry.api.trace.StatusCode

/**
 * Executes [block] within the scope of [span], ending the span when the block completes.
 * Records the exception status if [block] throws.
 */
suspend fun <T> withSpan(span: Span, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR, e.message ?: "")
        throw e
    } finally {
        span.end()
    }
}
