package dev.stapler.stelekit.performance

actual object CurrentSpanContext {
    private var current: ActiveSpanContext? = null
    actual fun set(ctx: ActiveSpanContext?) { current = ctx }
    actual fun get(): ActiveSpanContext? = current
}
