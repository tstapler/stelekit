package dev.stapler.stelekit.repository

import dev.stapler.stelekit.ui.library.LibraryCursor
import dev.stapler.stelekit.ui.library.LibraryFilterRules
import dev.stapler.stelekit.ui.library.LibraryMediaClass
import dev.stapler.stelekit.ui.library.LibraryQuery
import dev.stapler.stelekit.ui.library.LibrarySearch
import dev.stapler.stelekit.ui.library.LibrarySort

/** A parameterised statement: [args] are `Long`, `String` or `null`, bound in order to the `?` markers. */
data class LibraryStatement(val sql: String, val args: List<Any?>)

/**
 * Builds the Library page and count statements (ADR-006/008). Built per request, not stored as a generated
 * query, because the SQLDelight dialect cannot parse `REPLACE()` and only the predicates in use are emitted.
 * One statement per page: the annotation state is joined with correlated sub-selects on the indexed
 * `image_annotations.file_path`, so there is no per-item lookup. Paging is keyset; there is no OFFSET.
 */
object LibrarySql {
    const val COUNT_CAP = 10_000L
    private const val ESC = "ESCAPE '\\'"

    private val SEPARATORS = listOf("_", "-", ".", ",", "/", "\"", "[", "]")

    /** SQL twin of [LibrarySearch.hay]. */
    private fun hay(column: String): String =
        "(' ' || " + SEPARATORS.fold(column) { acc, ch -> "REPLACE($acc, '$ch', ' ')" } + ")"

    private const val INNER = """SELECT a.uuid AS uuid, a.file_path AS file_path,
  REPLACE(a.file_path, RTRIM(a.file_path, REPLACE(a.file_path, '/', '')), '') AS file_name,
  a.media_type AS media_type, a.size_bytes AS size_bytes, a.imported_at_ms AS imported_at_ms,
  a.is_orphan AS is_orphan, a.page_uuids AS page_uuids,
  (SELECT COUNT(*) FROM measurement_annotations m JOIN image_annotations ia ON ia.uuid = m.image_uuid WHERE ia.file_path = a.file_path) AS measurement_count,
  (SELECT MAX(ia.imported_at_ms) FROM image_annotations ia WHERE ia.file_path = a.file_path) AS last_annotated_at_ms,
  (SELECT MIN(ia.uuid) FROM image_annotations ia WHERE ia.file_path = a.file_path) AS annotation_uuid,
  (SELECT MIN(ia.thumbnail_path) FROM image_annotations ia WHERE ia.file_path = a.file_path) AS thumbnail_path,
  CAST(EXISTS (SELECT 1 FROM image_annotations ia WHERE ia.file_path = a.file_path) AS INTEGER) AS annotated,
  a.relative_path AS relative_path
FROM asset_index a"""

    /** Column order of [page] results. */
    val PAGE_COLUMNS = listOf(
        "uuid", "file_path", "file_name", "media_type", "size_bytes", "imported_at_ms", "is_orphan", "page_uuids",
        "measurement_count", "last_annotated_at_ms", "annotation_uuid", "thumbnail_path", "annotated", "relative_path",
    )

    private fun addFilterClauses(clauses: MutableList<String>, args: MutableList<Any?>, query: LibraryQuery) {
        val f = query.filters
        if (f.types.isNotEmpty()) {
            val known = listOf("IMAGE", "PDF", "AUDIO", "VIDEO", "DOCUMENT")
            val parts = f.types.map { cls ->
                when (cls) {
                    LibraryMediaClass.IMAGES -> "t.media_type = 'IMAGE'"
                    LibraryMediaClass.PDFS -> "t.media_type = 'PDF'"
                    LibraryMediaClass.AUDIO -> "t.media_type = 'AUDIO'"
                    LibraryMediaClass.VIDEO -> "t.media_type = 'VIDEO'"
                    LibraryMediaClass.DOCS -> "t.media_type = 'DOCUMENT'"
                    LibraryMediaClass.OTHER -> "t.media_type NOT IN (${known.joinToString { "'$it'" }})"
                }
            }
            clauses += "(${parts.joinToString(" OR ")})"
        }
        if (f.annotated) clauses += "t.annotated = 1"
        if (f.orphaned) clauses += "t.is_orphan = 1"
        if (f.tags.isNotEmpty()) {
            val patterns = LibrarySearch.tagPatterns(f.tags).filterNotNull()
            val likes = patterns.joinToString(" OR ") { "ia.tags LIKE ? $ESC" }
            clauses += "EXISTS (SELECT 1 FROM image_annotations ia WHERE ia.file_path = t.file_path AND ($likes))"
            args.addAll(patterns)
        }
        for (pattern in LibrarySearch.patterns(f.search).filterNotNull()) {
            clauses += "(${hay("t.file_name")} LIKE ? $ESC" +
                " OR EXISTS (SELECT 1 FROM image_annotations ia WHERE ia.file_path = t.file_path AND ${hay("ia.tags")} LIKE ? $ESC)" +
                " OR EXISTS (SELECT 1 FROM image_annotations ia JOIN measurement_annotations m ON m.image_uuid = ia.uuid" +
                " WHERE ia.file_path = t.file_path AND ${hay("m.label")} LIKE ? $ESC))"
            args += pattern; args += pattern; args += pattern
        }
    }

    private fun keyset(sort: LibrarySort, c: LibraryCursor, clauses: MutableList<String>, args: MutableList<Any?>) {
        when (sort) {
            LibrarySort.RECENTLY_ADDED -> {
                clauses += "(t.imported_at_ms < ? OR (t.imported_at_ms = ? AND t.uuid < ?))"
                args += c.longs[0]; args += c.longs[0]; args += c.id
            }
            LibrarySort.RECENTLY_ANNOTATED -> {
                val k = "COALESCE(t.last_annotated_at_ms, -1)"
                clauses += "($k < ? OR ($k = ? AND (t.imported_at_ms < ? OR (t.imported_at_ms = ? AND t.uuid < ?))))"
                args += c.longs[0]; args += c.longs[0]; args += c.longs[1]; args += c.longs[1]; args += c.id
            }
            LibrarySort.MOST_MEASUREMENTS -> {
                clauses += "(t.annotated < ? OR (t.annotated = ? AND (t.measurement_count < ? OR (t.measurement_count = ? AND " +
                    "(t.imported_at_ms < ? OR (t.imported_at_ms = ? AND t.uuid < ?))))))"
                args += c.longs[0]; args += c.longs[0]; args += c.longs[1]; args += c.longs[1]; args += c.longs[2]; args += c.longs[2]; args += c.id
            }
            LibrarySort.NAME -> {
                clauses += "(LOWER(t.file_name) > ? OR (LOWER(t.file_name) = ? AND t.uuid > ?))"
                args += c.name; args += c.name; args += c.id
            }
        }
    }

    private fun orderBy(sort: LibrarySort) = when (sort) {
        LibrarySort.RECENTLY_ADDED -> "t.imported_at_ms DESC, t.uuid DESC"
        LibrarySort.RECENTLY_ANNOTATED -> "COALESCE(t.last_annotated_at_ms, -1) DESC, t.imported_at_ms DESC, t.uuid DESC"
        LibrarySort.MOST_MEASUREMENTS -> "t.annotated DESC, t.measurement_count DESC, t.imported_at_ms DESC, t.uuid DESC"
        LibrarySort.NAME -> "LOWER(t.file_name) ASC, t.uuid ASC"
    }

    /** One page of at most [limit] rows (callers cap at 100) after [cursor], or the first page when null. */
    fun page(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): LibraryStatement {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        addFilterClauses(clauses, args, query)
        if (cursor != null) keyset(query.sort, cursor, clauses, args)
        val where = if (clauses.isEmpty()) "" else "\nWHERE " + clauses.joinToString("\n  AND ")
        val sql = "SELECT * FROM (\n$INNER\n) AS t$where\nORDER BY ${orderBy(query.sort)}\nLIMIT ?"
        return LibraryStatement(sql, args + limit.toLong())
    }

    /** Count capped at [COUNT_CAP] + 1 rows scanned, so a huge library never materialises. */
    fun count(query: LibraryQuery): LibraryStatement {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        addFilterClauses(clauses, args, query)
        val where = if (clauses.isEmpty()) "" else "\nWHERE " + clauses.joinToString("\n  AND ")
        val sql = "SELECT COUNT(*) FROM (SELECT 1 FROM (\n$INNER\n) AS t$where\nLIMIT ${COUNT_CAP + 1})"
        return LibraryStatement(sql, args)
    }

    /** Statements for every sort and a spread of filters; `QueryPlanAuditTest` explains each. */
    fun auditStatements(): List<LibraryStatement> {
        val base = LibraryFilterRules.normalize(dev.stapler.stelekit.ui.library.LibraryFilterState())
        val filters = listOf(
            base,
            base.copy(annotated = true),
            base.copy(types = setOf(LibraryMediaClass.IMAGES)),
            base.copy(orphaned = true),
            base.copy(tags = setOf("site")),
            base.copy(search = "kit sink"),
        )
        return LibrarySort.entries.flatMap { sort ->
            filters.map { f ->
                val cursor = LibraryCursor(listOf(1L, 1L, 1L).take(if (sort == LibrarySort.MOST_MEASUREMENTS) 3 else if (sort == LibrarySort.RECENTLY_ANNOTATED) 2 else 1), "a", "id")
                page(LibraryQuery(f, sort), cursor, 100)
            }
        } + filters.map { count(LibraryQuery(it)) }
    }
}
