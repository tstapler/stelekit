package dev.stapler.stelekit.performance

/** Compress [data] with GZIP. Returns null on platforms where GZIP compression is not available. */
expect fun gzipBytes(data: ByteArray): ByteArray?
