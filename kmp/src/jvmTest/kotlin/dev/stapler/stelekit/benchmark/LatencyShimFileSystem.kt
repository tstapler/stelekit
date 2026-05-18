package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.platform.FileSystem

/**
 * FileSystem wrapper that injects configurable blocking latency before each
 * write/exists/read call, simulating Android SAF Binder IPC overhead for CI
 * benchmarks.
 *
 * Uses [Thread.sleep] (not `delay`) because `savePageInternal` calls these methods
 * synchronously inside `withContext(PlatformDispatcher.IO)`. Blocking the IO-pool
 * thread is the correct simulation of Binder IPC — using `delay` would yield the
 * coroutine without holding the thread, which does not reproduce the starvation
 * behaviour that caused the Android insert lag.
 *
 * Default latency profile ("typical mid-range device" P50 real-device measurements):
 *   writeLatencyMs = 50ms
 *   existsLatencyMs = 10ms
 *   readLatencyMs   = 30ms
 *
 * The P99 performance budget assertion uses 200ms, which is conservative relative
 * to these defaults so the test passes even with OS scheduling jitter.
 */
class LatencyShimFileSystem(
    private val delegate: FileSystem,
    private val writeLatencyMs: Long = 50L,
    private val existsLatencyMs: Long = 10L,
    private val readLatencyMs: Long = 30L,
) : FileSystem by delegate {

    override fun writeFile(path: String, content: String): Boolean {
        // Simulate Binder IPC: block the calling thread (IO-pool) for the configured latency.
        Thread.sleep(writeLatencyMs)
        return delegate.writeFile(path, content)
    }

    override fun writeFileBytes(path: String, data: ByteArray): Boolean {
        Thread.sleep(writeLatencyMs)
        return delegate.writeFileBytes(path, data)
    }

    override fun fileExists(path: String): Boolean {
        Thread.sleep(existsLatencyMs)
        return delegate.fileExists(path)
    }

    override fun readFile(path: String): String? {
        Thread.sleep(readLatencyMs)
        return delegate.readFile(path)
    }
}
