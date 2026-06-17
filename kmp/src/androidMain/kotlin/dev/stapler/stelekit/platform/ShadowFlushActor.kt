package dev.stapler.stelekit.platform

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drains the write-behind dirty queue to SAF in the background.
 * For each dirty page: reads content from shadow, writes to SAF via [fileSystem.writeFile],
 * dequeues on success, stamps the shadow mtime, and calls [onFlushed] so the FileRegistry
 * can record the post-flush SAF mtime and suppress the subsequent watcher poll event.
 *
 * [onFlushed] is required for encrypted graphs (.md.stek): those files skip the content-hash
 * guard in FileRegistry, so without the mtime update the watcher emits a spurious own-write
 * event after the flush completes and the SAF mtime advances.
 *
 * Lifecycle: instantiate and call [flush] directly — no long-lived scope is created.
 */
internal class ShadowFlushActor(
    private val fileSystem: FileSystem,
    private val shadowCache: ShadowFileCache,
    private val queue: WriteBehindQueue,
    private val onFlushed: (suspend (safPath: String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "ShadowFlushActor"
    }

    /** Drain all pending dirty pages to SAF. Suspends until the queue is empty or all retries exhausted. */
    suspend fun flush() = withContext(Dispatchers.IO) {
        val pending = queue.getAll()
        for (path in pending) {
            flushPage(path)
        }
    }

    /** Flush a single page: read from shadow, write to SAF, dequeue on success. */
    private suspend fun flushPage(safPath: String) {
        try {
            val relativePath = safPath
                .removePrefix("saf://")
                .let { it.substring(it.indexOf('/') + 1) }
                .takeIf { it.isNotEmpty() } ?: return

            val shadowFile = shadowCache.resolve(relativePath) ?: run {
                Log.w(TAG, "flushPage: shadow missing for $relativePath — dequeuing without flush")
                queue.dequeue(safPath)
                return
            }
            val content = try { shadowFile.readText() } catch (e: Exception) {
                Log.w(TAG, "flushPage: failed to read shadow for $relativePath", e); return
            }
            val ok = fileSystem.writeFile(safPath, content)
            if (ok) {
                queue.dequeue(safPath)
                // Stamp shadow mtime with the post-flush SAF mtime so the shadow is not
                // incorrectly deleted by invalidateStale on the next startup. Without this,
                // shadow.mtime = time of write-behind write < SAF mtime = time of flush,
                // causing the shadow to appear stale even though its content is correct.
                fileSystem.getLastModifiedTime(safPath)?.let { mtime ->
                    shadowCache.stampMtime(relativePath, mtime)
                }
                // Notify FileRegistry of the post-flush SAF mtime so the next poll cycle
                // does not emit a spurious own-write event (critical for .md.stek files
                // where the content-hash guard is disabled).
                onFlushed?.invoke(safPath)
                Log.d(TAG, "flushPage: flushed $relativePath to SAF")
            } else {
                Log.w(TAG, "flushPage: SAF write failed for $relativePath — will retry")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "flushPage: unexpected error for $safPath", e)
        }
    }
}
