// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent queue of git-relative paths that have been written to a [GitShadowWorktree] but not
 * yet flushed back to SAF. Backed by a plain text file in app-private storage (one path per
 * line), modeled on [dev.stapler.stelekit.platform.WriteBehindQueue] — see plan.md Task 3.1.1a.
 *
 * Improves on that sibling's non-atomic `dequeue()` (`WriteBehindQueue.kt:31-40` writes the
 * filtered line list directly to [queueFile], which can leave the backing file partially
 * written if the process dies mid-write): [dequeue] writes to a temp file and atomically renames
 * it over [queueFile] instead.
 */
class GitWriteBackQueue(private val queueFile: File) {
    private val lock = ReentrantLock()

    companion object {
        private const val TAG = "GitWriteBackQueue"
    }

    fun enqueue(relativePath: String) = lock.withLock {
        try {
            queueFile.parentFile?.mkdirs()
            queueFile.appendText("$relativePath\n", Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "enqueue: failed", e)
        }
    }

    /**
     * Removes [relativePath] from the queue. Writes the filtered line list to
     * `${queueFile.name}.tmp` (same directory) then atomically renames it over [queueFile] —
     * a process death mid-write leaves either the untouched old queue file (rename never ran)
     * or the fully-written new one (rename completed), never a partially-written file.
     */
    fun dequeue(relativePath: String) = lock.withLock {
        try {
            if (!queueFile.exists()) return@withLock
            val lines = queueFile.readLines(Charsets.UTF_8)
            val remaining = lines.filter { it.trim() != relativePath.trim() }
            val tmpFile = File(queueFile.parentFile, "${queueFile.name}.tmp")
            tmpFile.writeText(
                remaining.joinToString("\n").let { if (it.isNotEmpty()) "$it\n" else it },
                Charsets.UTF_8,
            )
            if (!tmpFile.renameTo(queueFile)) {
                Log.w(TAG, "dequeue: atomic rename failed for $relativePath")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dequeue: failed for $relativePath", e)
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
