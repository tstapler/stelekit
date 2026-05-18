package dev.stapler.stelekit.platform

import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent append-only queue of SAF paths that have been written to shadow but not yet
 * flushed to SAF. Backed by a plain text file in app-private storage (one path per line).
 *
 * File-backed rather than SQLite so the queue lifecycle is independent of the graph database:
 * the same queue instance survives graph switches and is valid from process start.
 */
internal class WriteBehindQueue(private val queueFile: File) {
    private val lock = ReentrantLock()

    companion object {
        private const val TAG = "WriteBehindQueue"
    }

    fun enqueue(pagePath: String) = lock.withLock {
        try {
            queueFile.parentFile?.mkdirs()
            queueFile.appendText("$pagePath\n", Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "enqueue: failed", e)
        }
    }

    fun dequeue(pagePath: String) = lock.withLock {
        try {
            if (!queueFile.exists()) return@withLock
            val lines = queueFile.readLines(Charsets.UTF_8)
            val remaining = lines.filter { it.trim() != pagePath.trim() }
            queueFile.writeText(remaining.joinToString("\n").let { if (it.isNotEmpty()) "$it\n" else it }, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "dequeue: failed for $pagePath", e)
        }
    }

    fun getAll(): List<String> = lock.withLock {
        try {
            if (!queueFile.exists()) return@withLock emptyList()
            queueFile.readLines(Charsets.UTF_8)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "getAll: failed", e)
            emptyList()
        }
    }

    fun isEmpty(): Boolean = getAll().isEmpty()
}
