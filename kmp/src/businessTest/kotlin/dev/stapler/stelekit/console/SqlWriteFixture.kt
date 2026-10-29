// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import app.cash.sqldelight.db.QueryResult
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SqlConsoleWriter
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * A real file database plus the `sql!` write path built the way `RepositoryFactory` builds it: raw
 * statements on the app's write driver inside an actor-owned transaction. [pageCount] pages are seeded.
 */
internal class SqlWriteFixture(
    pageCount: Int = 3,
    deadlineMs: Long = SqlConsoleWriter.DEFAULT_DEADLINE_MS,
    actorScope: CoroutineScope? = null,
) : AutoCloseable {
    val base = SqlConsoleFixture(pages = pageCount)
    val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository(), scope = actorScope)
    val queries = RestrictedDatabaseQueries(SteleDatabase(base.app).steleDatabaseQueries, base.app)
    val writer = SqlConsoleWriter(queries, { invalidate, op -> actor.execute(invalidate = invalidate, op = op) }, deadlineMs)
    val backup: ConsoleBackup = ConsoleBackup.forJdbcUrl(base.url) ?: error("JVM must support console backups")

    fun scalar(sql: String): Long =
        base.app.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    fun text(sql: String): String? =
        base.app.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value

    fun exec(sql: String) {
        base.app.execute(null, sql, 0).value
    }

    fun addBlock(uuid: String, pageUuid: String, content: String) =
        exec("INSERT INTO blocks(uuid,page_uuid,content,position,created_at,updated_at) VALUES ('$uuid','$pageUuid','$content','a0',1,1)")

    fun backupFiles(): List<File> = File(base.dir, ConsoleBackup.DIRECTORY_NAME).listFiles().orEmpty().filter { it.name.endsWith(".db") }.sortedBy { it.name }

    /** Every file in the database directory that is not the database itself, its sidecars, or the backup directory. */
    fun strayFiles(): List<String> = base.dir.walkTopDown().filter { it.isFile }.map { it.name }
        .filterNot { it.startsWith("graph.db") || it.endsWith(".db") }.toList()

    override fun close() {
        actor.close()
        base.close()
    }
}
