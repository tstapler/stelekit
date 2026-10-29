// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.db.BackupFileInfo
import dev.stapler.stelekit.db.BackupFiles
import dev.stapler.stelekit.db.RestoreHost
import dev.stapler.stelekit.db.RestoreInspector
import dev.stapler.stelekit.db.RowCounts
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An in-memory file system for restore tests. File content is a string whose length is the size; a database
 * file's content starts with `pages=N,blocks=M` so [FakeRestoreInspector] can "count" it.
 */
internal class FakeRestoreFiles(var free: Long? = Long.MAX_VALUE) : BackupFiles {
    val store = ConcurrentHashMap<String, String>()
    val modified = ConcurrentHashMap<String, Long>()
    var failRename: (from: String, to: String) -> Boolean = { _, _ -> false }
    var failCopy = false
    var failWrite: (path: String) -> Boolean = { false }
    var onRename: (from: String, to: String) -> Unit = { _, _ -> }
    val renames = mutableListOf<Pair<String, String>>()

    fun put(path: String, content: String, modifiedMs: Long = 0L) {
        store[path] = content
        modified[path] = modifiedMs
    }

    override fun ensureDir(path: String) = true
    override fun list(dir: String) = store.keys.filter { it.substringBeforeLast('/') == dir }.map { BackupFileInfo(it.substringAfterLast('/'), modified[it] ?: 0L) }
    override fun delete(path: String) = store.remove(path) != null
    override fun rename(from: String, to: String): Boolean {
        if (failRename(from, to)) return false
        val content = store.remove(from) ?: return false
        onRename(from, to)
        store[to] = content
        modified[to] = modified.remove(from) ?: 0L
        renames += from to to
        return true
    }
    override fun sizeOf(path: String) = store[path]?.length?.toLong()
    override fun usableSpace(path: String) = free
    override fun copy(from: String, to: String): Boolean {
        if (failCopy) return false
        val content = store[from] ?: return false
        store[to] = content
        modified[to] = 0L
        return true
    }
    override fun readText(path: String) = store[path]
    override fun writeText(path: String, text: String): Boolean {
        if (failWrite(path)) return false
        store[path] = text
        return true
    }
}

internal class FakeRestoreInspector(private val files: FakeRestoreFiles) : RestoreInspector {
    override suspend fun integrityCheck(path: String): List<String> = when {
        files.store[path] == null -> listOf("cannot open the file")
        files.store.getValue(path).startsWith("CORRUPT") -> listOf("*** in database main ***")
        else -> listOf("ok")
    }

    override suspend fun counts(path: String): RowCounts? {
        val head = files.store[path]?.substringBefore(';') ?: return null
        val fields = head.split(',').associate { it.substringBefore('=') to it.substringAfter('=').toLongOrNull() }
        return RowCounts(fields["pages"] ?: return null, fields["blocks"] ?: return null)
    }
}

/** A [RestoreHost] that records what the restore asked of the app. */
internal class FakeRestoreHost : RestoreHost {
    val calls = mutableListOf<String>()
    private val closes = AtomicInteger()
    var onClose: suspend () -> Unit = {}
    var onReopen: suspend () -> Boolean = { true }
    var lockHeld = false
        private set

    override suspend fun closeDatabase() {
        check(!lockHeld) { "closeDatabase while holding the swap lock" }
        calls += "close#${closes.incrementAndGet()}"
        onClose()
    }

    override suspend fun <T> underSwapLock(block: suspend () -> T): T {
        check(!lockHeld) { "swap lock is not reentrant" }
        lockHeld = true
        calls += "lock"
        try {
            return block()
        } finally {
            lockHeld = false
            calls += "unlock"
        }
    }

    override suspend fun reopenDatabase(): Boolean {
        check(!lockHeld) { "reopenDatabase while holding the swap lock" }
        calls += "reopen"
        return onReopen()
    }
}
