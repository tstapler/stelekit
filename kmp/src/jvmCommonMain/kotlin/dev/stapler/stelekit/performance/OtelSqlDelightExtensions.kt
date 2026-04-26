package dev.stapler.stelekit.performance

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.db.DatabaseWriteActor
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlin.coroutines.coroutineContext

/**
 * Executes [block] inside [DatabaseWriteActor.execute] with an OTel span wrapping the
 * database operation. Parent span is resolved from the calling coroutine context.
 *
 * Mirrors Datadog's `transactionTraced { }` SQLDelight extension, adapted for OTel API:
 *
 * ```kotlin
 * actor.tracedExecute("block.save", tracer, mapOf("page.uuid" to pageUuid)) {
 *     queries.insertBlock(...)
 *     Unit.right()
 * }
 * ```
 */
suspend fun DatabaseWriteActor.tracedExecute(
    spanName: String,
    tracer: Tracer,
    attributes: Map<String, String> = emptyMap(),
    block: suspend () -> Either<DomainError, Unit>
): Either<DomainError, Unit> {
    val parentSpan = coroutineContext.currentSpan
    val builder = tracer.spanBuilder(spanName)
        .setAttribute("db.system", "sqlite")
    if (parentSpan != null) builder.setParent(Context.current().with(parentSpan))
    attributes.forEach { (k, v) -> builder.setAttribute(k, v) }
    val span = builder.startSpan()

    return execute {
        try {
            block().also {
                if (it.isLeft()) {
                    val msg = it.leftOrNull()?.message ?: "write failed"
                    span.setStatus(StatusCode.ERROR, msg)
                }
            }
        } finally {
            span.end()
        }
    }
}
