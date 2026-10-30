// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException

/**
 * One log line with the SQLite settings that decide how long a graph-wide DELETE takes: with
 * `foreign_keys` ON, deleting pages cascades into every block (14x slower in measurements), and the
 * file size and free pages say how much a wipe has to walk. Android's library logs lock waits only
 * after 30 s and never to the app's log file, so these are the facts a hang report is missing.
 */
internal object DatabaseOpenDiagnostics {
    private val logger = Logger("DatabaseOpen")

    suspend fun log(driver: SqlDriver) {
        logger.info(describe(driver))
    }

    /** Never throws: diagnostics must not stop a database from opening. */
    suspend fun describe(driver: SqlDriver): String = try {
        val foreignKeys = pragma(driver, "foreign_keys")
        val journalMode = pragma(driver, "journal_mode")
        val pageCount = pragma(driver, "page_count")?.toLongOrNull()
        val pageSize = pragma(driver, "page_size")?.toLongOrNull()
        val freePages = pragma(driver, "freelist_count")
        val sizeBytes = if (pageCount != null && pageSize != null) pageCount * pageSize else null
        "SQLite state: foreign_keys=$foreignKeys journal_mode=$journalMode sizeBytes=$sizeBytes " +
            "pageCount=$pageCount pageSize=$pageSize freePages=$freePages"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        "SQLite state unavailable: ${e.message}"
    }

    private suspend fun pragma(driver: SqlDriver, name: String): String? = try {
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name",
            mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0)) },
            parameters = 0,
        ).await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
