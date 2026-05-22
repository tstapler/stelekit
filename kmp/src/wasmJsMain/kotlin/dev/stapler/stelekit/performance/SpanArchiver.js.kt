package dev.stapler.stelekit.performance

actual object SpanArchiver {
    actual fun archive(spans: List<SerializedSpan>) = Unit
}
