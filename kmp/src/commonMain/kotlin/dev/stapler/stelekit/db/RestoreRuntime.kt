// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.withContext

/** Reads a database file through a console-owned read-only handle, never the app pool. */
class ReadHandleRestoreInspector : RestoreInspector {
    override suspend fun integrityCheck(path: String): List<String> =
        withHandle(path) { handle -> collect(handle, "PRAGMA integrity_check").map { it.firstOrNull().orEmpty() } }
            ?: listOf("cannot open the file")

    override suspend fun counts(path: String): RowCounts? = withHandle(path) { handle ->
        val row = collect(handle, "SELECT (SELECT count(*) FROM pages), (SELECT count(*) FROM blocks)").firstOrNull()
        val pages = row?.getOrNull(0)?.toLongOrNull()
        val blocks = row?.getOrNull(1)?.toLongOrNull()
        if (pages != null && blocks != null) RowCounts(pages, blocks) else null
    }

    private suspend fun <T> withHandle(path: String, block: (SqlReadHandle) -> T): T? = withContext(PlatformDispatcher.DB) {
        val handle = openSqlReadHandle("jdbc:sqlite:$path") ?: return@withContext null
        try {
            runCatching { block(handle) }.getOrNull()
        } finally {
            handle.close()
        }
    }

    private companion object {
        const val CELL_CHARS = 256
        const val MAX_ROWS = 20

        fun collect(handle: SqlReadHandle, sql: String): List<List<String?>> {
            val rows = mutableListOf<List<String?>>()
            handle.prepare()
            handle.execute(sql, CELL_CHARS, object : SqlRowSink {
                override fun columns(names: List<String>) = Unit
                override fun hasCapacity() = rows.size < MAX_ROWS
                override fun row(cells: List<String?>) { rows += cells }
                override fun overflow() = Unit
            })
            return rows
        }
    }
}

/**
 * Restore's view of the running app, through [GraphManager]'s own API only: `tearDownAndCloseActiveGraph`
 * (the relocation precedent; drains the actor and closes the pool and console reader under `lock(id)`),
 * `graphWriteLock` for the file swap, and `switchGraph(forceReinit)` plus `awaitPendingMigration` to reopen.
 * The restore lock is never held while closing or reopening: both take `lock(id)` themselves.
 */
class GraphManagerRestoreHost(private val graphManager: GraphManager, private val graphId: GraphId) : RestoreHost {
    override suspend fun closeDatabase() = withContext(PlatformDispatcher.IO) { graphManager.tearDownAndCloseActiveGraph(graphId) }

    override suspend fun <T> underSwapLock(block: suspend () -> T): T = graphManager.graphWriteLock.withLock(graphId, "restore($graphId)", block)

    override suspend fun reopenDatabase(): Boolean {
        graphManager.switchGraph(graphId, forceReinit = true)
        return graphManager.awaitPendingMigration() != null
    }
}
