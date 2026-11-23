package dev.stapler.stelekit.util

/**
 * Minimal span-recording port so low-level layers (platform file systems) can emit a span without
 * depending on `performance.SpanEmitter`, which sits above them in the module graph.
 */
interface SpanSink {
    /** Records a root span that started at [startMs] and ends now. */
    fun trace(name: String, startMs: Long, attrs: Map<String, String> = emptyMap())
}
