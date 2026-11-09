package dev.stapler.stelekit.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.ui.library.LibraryCursor
import dev.stapler.stelekit.ui.library.LibraryItem
import dev.stapler.stelekit.ui.library.LibraryQuery
import dev.stapler.stelekit.ui.library.LibraryRecord
import dev.stapler.stelekit.ui.library.LibraryReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Read-only, bounded access to the merged Library (ADR-006/008): assets with their annotation state joined
 * at query level. Every call is one bounded query; there is no unbounded read and no standing observer.
 */
interface LibraryRepository {
    /** At most [limit] (<= 100) items after [cursor] in `query.sort` order; the first page when [cursor] is null. */
    suspend fun getLibraryPage(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): Either<DomainError, List<LibraryItem>>

    /** Matching items, capped at [LibrarySql.COUNT_CAP] + 1 so the UI can show "10,000+". */
    suspend fun countLibrary(query: LibraryQuery): Either<DomainError, Long>

    /** Annotation tags by usage, at most [limit]; assets carry no tags in v1. */
    suspend fun getDistinctAnnotationTags(limit: Int = 200): Either<DomainError, List<String>>

    companion object {
        const val MAX_PAGE = 100
    }
}

private val listJson = Json { ignoreUnknownKeys = true }

private fun parseStringList(text: String?): List<String> =
    if (text.isNullOrBlank()) emptyList()
    else try { listJson.decodeFromString(ListSerializer(String.serializer()), text) } catch (_: Exception) { emptyList() }

/** Tags by usage, flattened from the bounded tag-set projection. */
internal fun flattenTagSets(rows: List<Pair<String, Long>>, limit: Int): List<String> {
    val uses = LinkedHashMap<String, Long>()
    for ((json, n) in rows) for (tag in parseStringList(json)) uses[tag] = (uses[tag] ?: 0L) + n
    return uses.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).take(limit).map { it.key }
}

class SqlDelightLibraryRepository(
    private val database: SteleDatabase,
    private val driver: SqlDriver,
) : LibraryRepository {

    private fun SqlCursor.toItem(): LibraryItem {
        val filePath = getString(1).orEmpty()
        return LibraryItem(
            id = getString(0).orEmpty(),
            filePath = filePath,
            fileName = getString(2) ?: filePath.substringAfterLast('/'),
            mediaType = getString(3).orEmpty(),
            sizeBytes = getLong(4) ?: 0L,
            importedAtMs = getLong(5) ?: 0L,
            isOrphan = (getLong(6) ?: 0L) != 0L,
            pageUuids = parseStringList(getString(7)),
            measurementCount = (getLong(8) ?: 0L).toInt(),
            lastAnnotatedAtMs = getLong(9),
            annotationUuid = getString(10),
            thumbnailPath = getString(11),
            annotated = (getLong(12) ?: 0L) != 0L,
            relativePath = getString(13).orEmpty(),
        )
    }

    private suspend fun <T> guarded(block: suspend () -> T): Either<DomainError, T> = withContext(PlatformDispatcher.DB) {
        try {
            block().right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
        }
    }

    private suspend fun <R> execute(statement: LibraryStatement, mapper: (SqlCursor) -> QueryResult<R>): R =
        driver.executeQuery(
            identifier = null,
            sql = statement.sql,
            mapper = mapper,
            parameters = statement.args.size,
            binders = {
                statement.args.forEachIndexed { i, a ->
                    when (a) {
                        null -> bindString(i, null)
                        is Long -> bindLong(i, a)
                        is String -> bindString(i, a)
                        else -> error("unsupported library argument ${a::class}")
                    }
                }
            },
        ).await()

    override suspend fun getLibraryPage(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): Either<DomainError, List<LibraryItem>> =
        guarded {
            val statement = LibrarySql.page(query, cursor, limit.coerceIn(1, LibraryRepository.MAX_PAGE))
            execute(statement) { c ->
                val rows = ArrayList<LibraryItem>()
                while (c.next().value) rows += c.toItem()
                QueryResult.Value(rows as List<LibraryItem>)
            }
        }

    override suspend fun countLibrary(query: LibraryQuery): Either<DomainError, Long> = guarded {
        execute(LibrarySql.count(query)) { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }
    }

    override suspend fun getDistinctAnnotationTags(limit: Int): Either<DomainError, List<String>> = guarded {
        val rows = database.steleDatabaseQueries.selectAnnotationTagSets(TAG_SET_LIMIT.toLong()).executeAsList()
        flattenTagSets(rows.map { it.tags to it.uses }, limit)
    }

    private companion object {
        const val TAG_SET_LIMIT = 500
    }
}

/**
 * In-memory backend and tests: composes the other in-memory repositories and applies [LibraryReference],
 * the semantics the SQL is tested against. Snapshots are built in bounded batches; this class is never
 * used with the SQLDelight backend.
 */
class InMemoryLibraryRepository(
    private val assets: AssetRepository,
    private val annotations: ImageAnnotationRepository,
    private val measurements: MeasurementAnnotationRepository,
) : LibraryRepository {

    private suspend fun records(): List<LibraryRecord> {
        val anns = annotations.getAllImageAnnotations().first().getOrNull().orEmpty().groupBy { it.filePath }
        val out = ArrayList<LibraryRecord>()
        var offset = 0
        while (true) {
            val batch = assets.getAssets(BATCH, offset).first().getOrNull().orEmpty()
            for (a in batch) {
                val rows = anns[a.filePath].orEmpty()
                val ms = rows.flatMap { measurements.getMeasurementsForImage(it.uuid).first().getOrNull().orEmpty() }
                out += LibraryRecord(
                    item = LibraryItem(
                        id = a.uuid.value, filePath = a.filePath, fileName = a.filePath.substringAfterLast('/'),
                        mediaType = a.mediaType.name, sizeBytes = a.sizeBytes, importedAtMs = a.importedAtMs,
                        isOrphan = a.isOrphan, pageUuids = a.pageUuids, annotated = rows.isNotEmpty(),
                        measurementCount = ms.size, lastAnnotatedAtMs = rows.maxOfOrNull { it.importedAtMs },
                        annotationUuid = rows.minOfOrNull { it.uuid }, thumbnailPath = rows.firstNotNullOfOrNull { it.thumbnailPath },
                    ),
                    annotationTags = rows.flatMap { it.tags },
                    measurementLabels = ms.mapNotNull { it.label },
                )
            }
            if (batch.size < BATCH) break
            offset += BATCH
        }
        return out
    }

    override suspend fun getLibraryPage(query: LibraryQuery, cursor: LibraryCursor?, limit: Int): Either<DomainError, List<LibraryItem>> =
        LibraryReference.page(records(), query, cursor, limit.coerceIn(1, LibraryRepository.MAX_PAGE)).right()

    override suspend fun countLibrary(query: LibraryQuery): Either<DomainError, Long> =
        LibraryReference.count(records(), query.filters, (LibrarySql.COUNT_CAP + 1).toInt()).toLong().right()

    override suspend fun getDistinctAnnotationTags(limit: Int): Either<DomainError, List<String>> {
        val uses = annotations.getAllImageAnnotations().first().getOrNull().orEmpty().flatMap { it.tags }.groupingBy { it }.eachCount()
        return uses.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(limit).map { it.key }.right()
    }

    private companion object {
        const val BATCH = 100
    }
}
