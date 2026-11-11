package dev.stapler.stelekit.ui.library

/** One card in the Library: an indexed asset with its annotation state joined at query level (ADR-008). */
data class LibraryItem(
    val id: String,
    val filePath: String,
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val importedAtMs: Long,
    val isOrphan: Boolean,
    val pageUuids: List<String>,
    val annotated: Boolean,
    val measurementCount: Int,
    val lastAnnotatedAtMs: Long?,
    val annotationUuid: String?,
    val thumbnailPath: String? = null,
    val relativePath: String = "",
) {
    val mediaClass: LibraryMediaClass get() = LibraryMediaClass.of(mediaType)
    val isImage: Boolean get() = mediaType == "IMAGE"
}

/** A normalized query: filters plus sort. The repository assumes [filters] went through [LibraryFilterRules.normalize]. */
data class LibraryQuery(
    val filters: LibraryFilterState = LibraryFilterState(),
    val sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
)

/**
 * Keyset cursor: the key tuple of the last row of the previous page (LD-2). Never an OFFSET.
 * [longs] holds the numeric key components in sort order; [name] is the Name A-Z key.
 */
data class LibraryCursor(val longs: List<Long>, val name: String?, val id: String) {
    companion object {
        fun of(item: LibraryItem, sort: LibrarySort): LibraryCursor = when (sort) {
            LibrarySort.RECENTLY_ADDED -> LibraryCursor(listOf(item.importedAtMs), null, item.id)
            LibrarySort.RECENTLY_ANNOTATED ->
                LibraryCursor(listOf(item.lastAnnotatedAtMs ?: -1L, item.importedAtMs), null, item.id)
            LibrarySort.MOST_MEASUREMENTS ->
                LibraryCursor(listOf(if (item.annotated) 1L else 0L, item.measurementCount.toLong(), item.importedAtMs), null, item.id)
            LibrarySort.NAME -> LibraryCursor(emptyList(), LibrarySearch.asciiLower(item.fileName), item.id)
        }
    }
}

/** An item plus the searchable text the SQL reaches through the annotation join; input to the reference filter. */
data class LibraryRecord(val item: LibraryItem, val annotationTags: List<String> = emptyList(), val measurementLabels: List<String> = emptyList())

/** In-memory reference semantics. The SQL queries must agree with this (`LibraryFilterSqlParityTest`). */
object LibraryReference {
    fun matches(record: LibraryRecord, filters: LibraryFilterState): Boolean {
        val item = record.item
        if (filters.types.isNotEmpty() && item.mediaClass !in filters.types) return false
        if (filters.annotated && !item.annotated) return false
        if (filters.orphaned && !item.isOrphan) return false
        if (filters.tags.isNotEmpty()) {
            val wanted = filters.tags.take(LibraryFilterRules.MAX_TAGS).map { LibrarySearch.asciiLower(it) }
            if (record.annotationTags.none { LibrarySearch.asciiLower(it) in wanted }) return false
        }
        val fields = listOf(item.fileName) + record.annotationTags + record.measurementLabels
        return LibrarySearch.matches(filters.search, fields)
    }

    private fun sortKey(sort: LibrarySort): Comparator<LibraryItem> = when (sort) {
        LibrarySort.RECENTLY_ADDED ->
            compareByDescending<LibraryItem> { it.importedAtMs }.thenByDescending { it.id }
        LibrarySort.RECENTLY_ANNOTATED ->
            compareByDescending<LibraryItem> { it.lastAnnotatedAtMs ?: -1L }.thenByDescending { it.importedAtMs }.thenByDescending { it.id }
        LibrarySort.MOST_MEASUREMENTS ->
            compareByDescending<LibraryItem> { if (it.annotated) 1 else 0 }
                .thenByDescending { it.measurementCount }.thenByDescending { it.importedAtMs }.thenByDescending { it.id }
        LibrarySort.NAME ->
            compareBy<LibraryItem> { LibrarySearch.asciiLower(it.fileName) }.thenBy { it.id }
    }

    /** Filter, sort, then page after [cursor] (exclusive) - the contract of `LibraryRepository.getLibraryPage`. */
    fun page(records: List<LibraryRecord>, query: LibraryQuery, cursor: LibraryCursor?, limit: Int): List<LibraryItem> {
        val cmp = sortKey(query.sort)
        val sorted = records.filter { matches(it, query.filters) }.map { it.item }.sortedWith(cmp)
        val after = if (cursor == null) sorted else {
            val last = sorted.indexOfFirst { cmpKey(it, query.sort, cursor) > 0 }
            if (last < 0) emptyList() else sorted.drop(last)
        }
        return after.take(limit)
    }

    /** Positive when [item] sorts strictly after [cursor]. */
    private fun cmpKey(item: LibraryItem, sort: LibrarySort, cursor: LibraryCursor): Int {
        val key = LibraryCursor.of(item, sort)
        return when (sort) {
            LibrarySort.NAME -> {
                val c = key.name!!.compareTo(cursor.name!!)
                if (c != 0) c else key.id.compareTo(cursor.id)
            }
            else -> {
                // Descending sorts: a smaller tuple sorts later.
                for (i in key.longs.indices) {
                    val c = cursor.longs[i].compareTo(key.longs[i])
                    if (c != 0) return c
                }
                cursor.id.compareTo(key.id)
            }
        }
    }

    fun count(records: List<LibraryRecord>, filters: LibraryFilterState, cap: Int = COUNT_CAP): Int =
        minOf(records.count { matches(it, filters) }, cap)

    const val COUNT_CAP = 10_000
}
