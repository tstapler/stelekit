package dev.stapler.stelekit.performance

/**
 * Platform-specific archiver that writes drained spans to a compressed file in the
 * platform's temporary/cache directory as a backup alongside SQLite persistence.
 *
 * Android: writes GZIP-compressed NDJSON to cacheDir/spans_archive.jsonl.gz (max 5MB).
 * Other platforms: no-op.
 */
expect object SpanArchiver {
    fun archive(spans: List<SerializedSpan>)
}
