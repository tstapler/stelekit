// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** The searchable text of one sheet as stored: lower-cased lines, and the hash used to skip an unchanged sheet. */
data class SketchSearchRow(val projectUuid: String, val sheetUuid: String, val body: String, val hash: String, val updatedAt: Long)

/** A sketch found by text: [snippet] is the first stored line that matched, ready to show under the project name. */
data class SketchSearchHit(val projectUuid: String, val sheetUuid: String, val projectName: String, val snippet: String)

/**
 * Text index of the v2 sketches of one graph (E8). One small row per sheet, derived from the sheet file, so it can be dropped
 * and rebuilt at any time. Reads are bounded: [search] returns at most `limit` hits and scans a bounded number of rows.
 */
interface SketchSearchTextRepository {
    /** Hashes of the rows stored for [projectUuid], keyed by sheet uuid; a backfill skips a sheet whose hash is unchanged. */
    suspend fun hashesOf(projectUuid: String): Either<DomainError, Map<String, String>>

    @DirectRepositoryWrite
    suspend fun save(row: SketchSearchRow): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteSheet(sheetUuid: String): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteProject(projectUuid: String): Either<DomainError, Unit>

    /** Sketches whose text (or project name) contains every word of [query], newest first. A blank query finds nothing. */
    suspend fun search(query: String, limit: Int): Either<DomainError, List<SketchSearchHit>>

    companion object {
        /** Rows read per SQL page and the most pages one query reads: the scan bound of [search]. */
        const val CANDIDATE_PAGE = 100
        const val CANDIDATE_PAGES = 5
        const val MAX_HASH_ROWS = 1_000
    }
}

/** The word logic both implementations share, so memory and SQLite agree on what matches. */
internal object SketchSearchMatching {
    fun words(query: String): List<String> = query.lowercase().split(' ', '\n', '\t').filter { it.isNotEmpty() }

    /** The word to ask SQL for: the longest one narrows the candidates most. */
    fun anchor(words: List<String>): String? = words.maxByOrNull { it.length }

    /** Escapes `%`, `_` and `\` for a LIKE pattern that declares `ESCAPE '\'`. */
    fun likeEscape(word: String): String = buildString {
        for (c in word) {
            if (c == '%' || c == '_' || c == '\\') append('\\')
            append(c)
        }
    }

    fun matches(body: String, name: String, words: List<String>): Boolean {
        val haystack = body + "\n" + name.lowercase()
        return words.all { it in haystack }
    }

    fun snippet(body: String, words: List<String>): String =
        body.lineSequence().firstOrNull { line -> words.any { it in line } } ?: body.lineSequence().firstOrNull().orEmpty()
}

class InMemorySketchSearchTextRepository(private val projectName: (String) -> String? = { it }) : SketchSearchTextRepository {
    private val rows = LinkedHashMap<Pair<String, String>, SketchSearchRow>()

    override suspend fun hashesOf(projectUuid: String): Either<DomainError, Map<String, String>> =
        rows.values.filter { it.projectUuid == projectUuid }.associate { it.sheetUuid to it.hash }.right()

    override suspend fun save(row: SketchSearchRow): Either<DomainError, Unit> {
        rows[row.projectUuid to row.sheetUuid] = row
        return Unit.right()
    }

    override suspend fun deleteSheet(sheetUuid: String): Either<DomainError, Unit> {
        rows.keys.removeAll { it.second == sheetUuid }
        return Unit.right()
    }

    override suspend fun deleteProject(projectUuid: String): Either<DomainError, Unit> {
        rows.keys.removeAll { it.first == projectUuid }
        return Unit.right()
    }

    override suspend fun search(query: String, limit: Int): Either<DomainError, List<SketchSearchHit>> {
        val words = SketchSearchMatching.words(query)
        if (words.isEmpty() || limit <= 0) return emptyList<SketchSearchHit>().right()
        return rows.values.sortedByDescending { it.updatedAt }.mapNotNull { r ->
            val name = projectName(r.projectUuid) ?: return@mapNotNull null
            if (SketchSearchMatching.matches(r.body, name, words)) SketchSearchHit(r.projectUuid, r.sheetUuid, name, SketchSearchMatching.snippet(r.body, words)) else null
        }.take(limit).right()
    }
}

class SqlDelightSketchSearchTextRepository(database: SteleDatabase) : SketchSearchTextRepository {
    private val queries = database.steleDatabaseQueries

    override suspend fun hashesOf(projectUuid: String): Either<DomainError, Map<String, String>> = guarded {
        queries.selectSketchSearchHashes(projectUuid, SketchSearchTextRepository.MAX_HASH_ROWS.toLong()).executeAsList()
            .associate { it.sheet_uuid to it.body_hash }
    }

    override suspend fun save(row: SketchSearchRow): Either<DomainError, Unit> = guardedWrite {
        queries.upsertSketchSearchText(row.projectUuid, row.sheetUuid, row.body, row.hash, row.updatedAt)
    }

    override suspend fun deleteSheet(sheetUuid: String): Either<DomainError, Unit> = guardedWrite {
        queries.deleteSketchSearchTextBySheet(sheetUuid)
    }

    override suspend fun deleteProject(projectUuid: String): Either<DomainError, Unit> = guardedWrite {
        queries.deleteSketchSearchTextByProject(projectUuid)
    }

    override suspend fun search(query: String, limit: Int): Either<DomainError, List<SketchSearchHit>> = guarded {
        val words = SketchSearchMatching.words(query)
        val anchor = SketchSearchMatching.anchor(words)
        if (anchor == null || limit <= 0) return@guarded emptyList()
        val needle = SketchSearchMatching.likeEscape(anchor)
        val hits = ArrayList<SketchSearchHit>()
        var page = 0
        while (hits.size < limit && page < SketchSearchTextRepository.CANDIDATE_PAGES) {
            val offset = page.toLong() * SketchSearchTextRepository.CANDIDATE_PAGE
            val rows = queries.searchSketchText(needle, SketchSearchTextRepository.CANDIDATE_PAGE.toLong(), offset).executeAsList()
            rows.filter { SketchSearchMatching.matches(it.body, it.project_name, words) }
                .take(limit - hits.size)
                .mapTo(hits) { SketchSearchHit(it.project_uuid, it.sheet_uuid, it.project_name, SketchSearchMatching.snippet(it.body, words)) }
            if (rows.size < SketchSearchTextRepository.CANDIDATE_PAGE) break
            page++
        }
        hits
    }

    private suspend fun <T> guarded(block: () -> T): Either<DomainError, T> = withContext(PlatformDispatcher.DB) {
        try {
            block().right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
        }
    }

    private suspend fun guardedWrite(block: suspend () -> Long): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            block()
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }
}
