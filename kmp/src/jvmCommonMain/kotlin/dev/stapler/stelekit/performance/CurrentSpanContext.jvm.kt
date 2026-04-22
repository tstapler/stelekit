package dev.stapler.stelekit.performance

actual object CurrentSpanContext {
    private val threadLocal = ThreadLocal<ActiveSpanContext?>()
    actual fun set(ctx: ActiveSpanContext?) { threadLocal.set(ctx) }
    actual fun get(): ActiveSpanContext? = threadLocal.get()
}
