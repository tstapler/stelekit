package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Progress of the name index for the selected pull source. */
sealed interface PullIndexState {
    data object Idle : PullIndexState

    /** [filesFound] entries are listed so far; the reader does not know the total up front. */
    data class Reading(val filesFound: Int) : PullIndexState

    data class Ready(val count: Int) : PullIndexState

    data class Failed(val error: ReadError) : PullIndexState
}

/**
 * [PageSource] over a [SourceGraphReader]: the read-only, not-open source of a pull copy.
 *
 * [select] starts streaming the source's name index (a [SourceEntry] projection, never bodies) in
 * pages of [SourceGraphReader.MAX_PAGE_SIZE]; [listPages]/[countPages] filter whatever has loaded so
 * far, so the picker is usable before the index completes. Page uuids are derived from the graph id
 * and file name, so they are stable across re-listings. Tag/property filters do not exist here.
 *
 * Owns its scope; call [close] when done. Unreadable pages are reported through [takeUnreadable].
 */
class PullPageSource(
    private val reader: SourceGraphReader,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PageSource {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher +
            CoroutineExceptionHandler { _, e ->
                if (e !is CancellationException) _indexState.value = PullIndexState.Failed(ReadError.Unreadable(e.message ?: "listing failed"))
            },
    )

    private class Index(val entries: List<SourceEntry>, val byUuid: Map<String, SourceEntry>)

    private val _indexState = MutableStateFlow<PullIndexState>(PullIndexState.Idle)
    val indexState: StateFlow<PullIndexState> = _indexState.asStateFlow()

    private val index = MutableStateFlow(Index(emptyList(), emptyMap()))
    private var graph: GraphInfo? = null
    private var indexJob: Job? = null
    private val unreadable = MutableStateFlow<List<UnreadablePage>>(emptyList())

    /** Switches the source (null = none) and restarts the name index. Cancels any listing in flight. */
    fun select(source: GraphInfo?) {
        stop()
        graph = source
        index.value = Index(emptyList(), emptyMap())
        unreadable.value = emptyList()
        if (source == null) _indexState.value = PullIndexState.Idle else startIndexing(source)
    }

    /** Re-runs the listing for the current source (after a failure or a re-selected folder). */
    fun retry() {
        graph?.let(::select)
    }

    /** Cancels a listing in flight; what has loaded stays usable. */
    fun stop() {
        indexJob?.cancel()
        indexJob = null
        if (_indexState.value is PullIndexState.Reading) _indexState.value = PullIndexState.Ready(index.value.entries.size)
    }

    fun close() = scope.cancel()

    private fun startIndexing(source: GraphInfo) {
        _indexState.value = PullIndexState.Reading(0)
        indexJob = scope.launch {
            val loaded = ArrayList<SourceEntry>()
            val byUuid = HashMap<String, SourceEntry>()
            var after: String? = null
            while (true) {
                val batch = when (val r = reader.listEntries(source, after, SourceGraphReader.MAX_PAGE_SIZE)) {
                    is Either.Left -> {
                        _indexState.value = PullIndexState.Failed(r.value)
                        return@launch
                    }
                    is Either.Right -> r.value
                }
                if (batch.isEmpty()) break
                for (e in batch) {
                    loaded += e
                    byUuid[uuidOf(source, e).value] = e
                }
                after = batch.last().name
                index.value = Index(loaded.toList(), byUuid.toMap())
                _indexState.value = PullIndexState.Reading(loaded.size)
                if (batch.size < SourceGraphReader.MAX_PAGE_SIZE) break
            }
            _indexState.value = PullIndexState.Ready(loaded.size)
        }
    }

    @Suppress("InMemoryPagination") // windows the bounded in-memory name index (entry projection), not a DB result set
    override suspend fun listPages(filter: SelectionFilter, search: String?, limit: Int, offset: Int): Either<DomainError, List<Page>> {
        val source = graph ?: return emptyList<Page>().right()
        val size = limit.coerceIn(0, PageSource.MAX_PAGE_SIZE)
        return matching(filter, search).drop(offset).take(size).map { toPage(source, it) }.toList().right()
    }

    override suspend fun countPages(filter: SelectionFilter, search: String?): Either<DomainError, Long> =
        matching(filter, search).count().toLong().right()

    override suspend fun readPages(uuids: List<PageUuid>): Either<DomainError, List<SourcePage>> {
        require(uuids.size <= PageSource.MAX_PAGE_SIZE) { "readPages is bounded to ${PageSource.MAX_PAGE_SIZE} uuids" }
        val source = graph ?: return DomainError.FileSystemError.ReadFailed("", "No source graph selected").left()
        val snapshot = index.value
        val out = ArrayList<SourcePage>(uuids.size)
        for (uuid in uuids) {
            val entry = snapshot.byUuid[uuid.value] ?: continue
            readOne(source, uuid, entry).fold({ return it.left() }, { page -> if (page != null) out += page })
        }
        return out.right()
    }

    /** Right(null) = this page was skipped and recorded as unreadable; Left = the whole source failed. */
    private suspend fun readOne(source: GraphInfo, uuid: PageUuid, entry: SourceEntry): Either<DomainError, SourcePage?> {
        val error = when (val r = reader.readPage(source, entry)) {
            is Either.Right -> return toSourcePage(uuid, entry, r.value).right()
            is Either.Left -> r.value
        }
        return when (error) {
            ReadError.NoGrant, ReadError.FolderMissing, ReadError.Encrypted ->
                DomainError.FileSystemError.ReadFailed(source.path, error.message).left()
            is ReadError.TooLarge, is ReadError.Unreadable -> {
                val bad = UnreadablePage(entry.name, DomainError.FileSystemError.ReadFailed(entry.fileName, error.message))
                unreadable.update { it + bad }
                null.right()
            }
        }
    }

    override fun takeUnreadable(): List<UnreadablePage> = unreadable.getAndUpdate { emptyList() }

    /** "12 KB - 2026-10-08" for a listed page, or null when [uuid] is not in the loaded index. */
    fun subtitleFor(uuid: PageUuid): String? = index.value.byUuid[uuid.value]?.let(::subtitleOf)

    private fun matching(filter: SelectionFilter, search: String?): Sequence<SourceEntry> {
        val query = search?.trim().orEmpty()
        return index.value.entries.asSequence().filter { matches(it, filter, query) }
    }

    private fun matches(e: SourceEntry, filter: SelectionFilter, query: String): Boolean {
        if (!filter.journals && e.isJournal) return false
        if (filter.dateFrom != null || filter.dateTo != null) {
            val d = if (e.isJournal) JournalUtils.parseJournalDate(e.name) else null
            if (d == null) return false
            if (filter.dateFrom != null && d < filter.dateFrom) return false
            if (filter.dateTo != null && d > filter.dateTo) return false
        }
        filter.namePrefix?.let { if (!e.name.asciiLower().startsWith(it.asciiLower())) return false }
        return query.isEmpty() || e.name.contains(query, ignoreCase = true)
    }

    private fun toPage(source: GraphInfo, e: SourceEntry): Page = Page(
        uuid = uuidOf(source, e),
        name = e.name,
        filePath = e.fileName,
        createdAt = EPOCH,
        updatedAt = EPOCH,
        isJournal = e.isJournal,
        journalDate = if (e.isJournal) JournalUtils.parseJournalDate(e.name) else null,
    )

    private fun toSourcePage(uuid: PageUuid, e: SourceEntry, staged: StagedPage): SourcePage {
        val merge = staged.toMergePage()
        val page = MergeConverters.toPage(merge, e.fileName).copy(uuid = uuid)
        return SourcePage(page, MergeConverters.toBlocks(merge, uuid, e.fileName))
    }

    private fun uuidOf(source: GraphInfo, e: SourceEntry) =
        PageUuid(UuidGenerator.generateDeterministic("pull:${source.id.value}:${e.fileName}"))

    private companion object {
        val EPOCH = Instant.fromEpochMilliseconds(0)
    }
}

internal fun subtitleOf(e: SourceEntry): String {
    val size = when {
        e.sizeBytes < KB -> "${e.sizeBytes} B"
        e.sizeBytes < KB * KB -> "${e.sizeBytes / KB} KB"
        else -> "${e.sizeBytes / (KB * KB)}.${(e.sizeBytes % (KB * KB)) * 10 / (KB * KB)} MB"
    }
    val date = e.modifiedAtMs?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date.toString() }
    return listOfNotNull(size, date).joinToString(" - ")
}

private const val KB = 1024L
