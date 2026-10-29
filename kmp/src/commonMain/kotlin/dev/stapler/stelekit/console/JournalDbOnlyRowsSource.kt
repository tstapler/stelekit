// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.SqlReadHandle
import dev.stapler.stelekit.db.SqlRowSink
import dev.stapler.stelekit.db.openSqlReadHandle
import kotlinx.coroutines.withContext

/** One block row as stored: enough to rebuild the outline. */
class StoredBlock(val uuid: String, val parentUuid: String?, val content: String, val position: String)

/**
 * Journal pages whose markdown file is missing on disk (or never recorded), read through a console-owned read-only
 * handle on the database at [dbPath]. [fileExists] receives the page's stored absolute `file_path`.
 */
class JournalDbOnlyRowsSource(private val dbPath: String, private val fileExists: (String) -> Boolean) : DbOnlyRowsSource {
    override suspend fun scan(): List<DbOnlyPage> = withContext(PlatformDispatcher.DB) {
        val handle = openSqlReadHandle("jdbc:sqlite:$dbPath") ?: return@withContext emptyList()
        try {
            dbOnlyJournals(handle).map { (uuid, name) -> DbOnlyPage(name, renderOutline(blocksOf(handle, uuid))) }
        } finally {
            handle.close()
        }
    }

    private fun dbOnlyJournals(handle: SqlReadHandle): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        var offset = 0
        while (true) {
            val page = query(handle, "SELECT uuid, name, file_path FROM pages WHERE is_journal = 1 ORDER BY uuid LIMIT $PAGE_ROWS OFFSET $offset", PAGE_ROWS)
            page.filter { it[FILE_PATH_COLUMN].isNullOrEmpty() || !fileExists(it[FILE_PATH_COLUMN].orEmpty()) }
                .forEach { found += it[0].orEmpty() to it[1].orEmpty() }
            if (page.size < PAGE_ROWS) return found
            offset += PAGE_ROWS
        }
    }

    private fun blocksOf(handle: SqlReadHandle, pageUuid: String): List<StoredBlock> {
        val sql = "SELECT uuid, parent_uuid, content, position FROM blocks WHERE page_uuid = '${pageUuid.replace("'", "''")}'"
        return query(handle, sql, MAX_BLOCKS).map { StoredBlock(it[0].orEmpty(), it[1], it[2].orEmpty(), it[3].orEmpty()) }
    }

    private fun query(handle: SqlReadHandle, sql: String, maxRows: Int): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        handle.prepare()
        handle.execute(sql, MAX_CELL_CHARS, object : SqlRowSink {
            override fun columns(names: List<String>) = Unit
            override fun hasCapacity() = rows.size < maxRows
            override fun row(cells: List<String?>) { rows += cells }
            override fun overflow() = Unit
        })
        return rows
    }

    companion object {
        private const val PAGE_ROWS = 500
        private const val MAX_BLOCKS = 20_000
        private const val MAX_CELL_CHARS = 200_000
        private const val FILE_PATH_COLUMN = 2

        /** Logseq outline markdown: one `- ` bullet per block, children indented a tab deeper, siblings in `position` order. */
        fun renderOutline(blocks: List<StoredBlock>): String {
            val ids = blocks.map { it.uuid }.toSet()
            val children = blocks.groupBy { b -> b.parentUuid?.takeIf { it in ids } }
            val out = StringBuilder()
            fun emit(parent: String?, depth: Int) {
                children[parent].orEmpty().sortedWith(compareBy({ it.position }, { it.uuid })).forEach { block ->
                    val indent = "\t".repeat(depth)
                    val lines = block.content.lines()
                    out.append(indent).append("- ").append(lines.first()).append('\n')
                    lines.drop(1).forEach { out.append(indent).append("  ").append(it).append('\n') }
                    emit(block.uuid, depth + 1)
                }
            }
            emit(null, 0)
            return out.toString()
        }
    }
}
