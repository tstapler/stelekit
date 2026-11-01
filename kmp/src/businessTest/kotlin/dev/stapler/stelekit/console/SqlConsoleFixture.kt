// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.console.commands.SqlCommand
import dev.stapler.stelekit.console.views.SqlConsoleView
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SqlConsoleReader
import java.io.File
import java.nio.file.Files

/** A real file database (app schema, 3 pages) with the app's pooled driver beside the console reader. */
internal class SqlConsoleFixture(pages: Int = 3) : AutoCloseable {
    val dir: File = Files.createTempDirectory("sql-console").toFile()
    val dbFile: File = dir.resolve("graph.db")
    val url = "jdbc:sqlite:${dbFile.absolutePath}"
    val app: SqlDriver = DriverFactory().createDriver(url)

    init {
        repeat(pages) { insertPage("page-$it", "Page $it") }
    }

    fun insertPage(uuid: String, name: String) {
        app.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('$uuid','$name',1,${uuid.hashCode().toLong()})", 0).value
    }

    fun reader(useStatementGuard: Boolean = true): SqlConsoleReader =
        SqlConsoleReader(open = { dev.stapler.stelekit.db.openSqlReadHandle(url) }, useStatementGuard = useStatementGuard)

    /** Row count of every table in the database, read through the app driver. */
    fun tableCounts(): Map<String, Long> {
        val names = mutableListOf<String>()
        app.executeQuery(
            null, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", { c ->
                while (c.next().value) names += c.getString(0)!!
                QueryResult.Value(Unit)
            }, 0,
        ).value
        return names.associateWith { name ->
            app.executeQuery(null, "SELECT COUNT(*) FROM \"$name\"", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value
        }
    }

    fun schemaObjectCount(): Long =
        app.executeQuery(null, "SELECT COUNT(*) FROM sqlite_master", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    override fun close() {
        app.close()
        dir.deleteRecursively()
    }
}

internal class CapturedOutput : ConsoleOutput {
    val lines = mutableListOf<String>()
    override fun info(text: String) { lines += text }
    override fun error(text: String) { lines += "ERR $text" }
}

internal suspend fun runSql(view: SqlConsoleView?, statement: String, out: CapturedOutput = CapturedOutput(), limits: dev.stapler.stelekit.console.views.SqlLimits = dev.stapler.stelekit.console.views.SqlLimits()) =
    SqlCommand(limits) { view }.run(listOf(statement), ConsoleContext(ConsoleRegistry(emptyList())), out)

internal inline fun <T> SqlConsoleReader.using(block: (SqlConsoleReader) -> T): T = try { block(this) } finally { close() }
