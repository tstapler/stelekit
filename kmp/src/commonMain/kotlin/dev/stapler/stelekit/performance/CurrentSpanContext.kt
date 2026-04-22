package dev.stapler.stelekit.performance

/**
 * Platform-safe holder for the active [ActiveSpanContext] during a DB-bound coroutine.
 *
 * SQLDelight query methods are synchronous, so [kotlinx.coroutines.coroutineContext] is not
 * accessible inside them. This expect/actual provides a ThreadLocal on JVM/Android (where the
 * DB dispatcher uses a thread pool) and a simple nullable var on single-threaded targets.
 *
 * Usage pattern:
 * ```
 * CurrentSpanContext.set(ActiveSpanContext(traceId, spanId))
 * try {
 *     // ... DB calls here ...
 * } finally {
 *     CurrentSpanContext.set(null)
 * }
 * ```
 */
expect object CurrentSpanContext {
    fun set(ctx: ActiveSpanContext?)
    fun get(): ActiveSpanContext?
}
