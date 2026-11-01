// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.BackupFiles
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** A journal page that exists in the database but has no markdown file; a restore would erase it. */
data class DbOnlyPage(val name: String, val markdown: String)

/** Finds the pages a restore would destroy. The production source reads journals with no file on disk. */
fun interface DbOnlyRowsSource {
    suspend fun scan(): List<DbOnlyPage>
}

/**
 * Exports DB-only journal pages to `console-recovery/<timestamp>/` before a restore (plan Task 3.4b), then reads
 * every file back; a restore that cannot save them does not start.
 */
class DbOnlyRowsGuard(
    private val source: DbOnlyRowsSource,
    private val files: BackupFiles,
    val recoveryDirectory: String,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
    suspend fun scan(): List<DbOnlyPage> = source.scan()

    /** Right is the directory holding the exported files. */
    fun export(pages: List<DbOnlyPage>): Either<String, String> {
        if (pages.isEmpty()) return recoveryDirectory.right()
        val dir = "$recoveryDirectory/${stamp(nowMs())}"
        if (!files.ensureDir(recoveryDirectory) || !files.ensureDir(dir)) return "Couldn't create $dir to save local-only rows. Nothing was changed.".left()
        val used = mutableSetOf<String>()
        for (page in pages) {
            val path = "$dir/${uniqueName(page.name, used)}.md"
            if (!files.writeText(path, page.markdown) || files.readText(path) != page.markdown) {
                return "Couldn't save local-only page \"${page.name}\" to $path. Nothing was changed.".left()
            }
        }
        return dir.right()
    }

    private fun uniqueName(pageName: String, used: MutableSet<String>): String {
        val safe = pageName.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").ifEmpty { "page" }
        var candidate = safe
        var n = 2
        while (!used.add(candidate)) candidate = "${safe}_${n++}"
        return candidate
    }

    private fun stamp(ms: Long): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year}${two(t.monthNumber)}${two(t.dayOfMonth)}-${two(t.hour)}${two(t.minute)}${two(t.second)}"
    }
}
