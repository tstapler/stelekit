package dev.stapler.stelekit.transfer

import dev.stapler.stelekit.db.LogseqPageSerializer
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.repository.createGraphLoader
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val logger = Logger("GraphMergeService")

private data class PageSnapshot(val name: String, val markdown: String)

data class GraphMergeResult(
    val imported: List<String>,
    val skippedExisting: List<String>,
    val failed: List<String>,
)

/**
 * Cross-graph page/journal recovery. [dev.stapler.stelekit.db.GraphManager] keeps only one
 * graph's [RepositorySet] open at a time — `switchGraph` closes the previous DB connection
 * before opening the next — so this can't read the source graph and write the target graph
 * in the same call. Instead it's a two-step, in-memory staging flow: call [snapshot] while
 * the source graph is active, switch graphs normally, then call [merge] while the target
 * graph is active. Pages already present (by name) in the target are left untouched — this
 * recovers missing content, it never overwrites.
 */
class GraphMergeService {
    private var snapshot: List<PageSnapshot> = emptyList()

    private val _pendingPageCount = MutableStateFlow(0)

    /** Compose-observable count of pages captured by [snapshot] and not yet consumed by [merge]. */
    val pendingPageCount: StateFlow<Int> = _pendingPageCount.asStateFlow()

    suspend fun snapshot(source: RepositorySet) {
        val pages = source.pageRepository.getAllPagesSnapshot().getOrNull().orEmpty()
        snapshot = pages.map { page ->
            val blocks = source.blockRepository.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
            PageSnapshot(page.name, LogseqPageSerializer.serialize(page, blocks))
        }
        _pendingPageCount.value = snapshot.size
        logger.info("snapshot: captured ${snapshot.size} pages")
    }

    suspend fun merge(target: RepositorySet, fileSystem: FileSystem): GraphMergeResult {
        val pending = snapshot
        snapshot = emptyList()
        _pendingPageCount.value = 0

        val writeActor = target.writeActor
        if (writeActor == null || pending.isEmpty()) {
            return GraphMergeResult(emptyList(), emptyList(), pending.map { it.name })
        }

        val existingNames = target.pageRepository.getAllPagesSnapshot().getOrNull().orEmpty()
            .map { it.name.lowercase() }
            .toSet()
        val (toSkip, toImport) = pending.partition { it.name.lowercase() in existingNames }

        val importer = QrImportService(target.createGraphLoader(fileSystem), target.pageRepository, writeActor)
        val imported = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (page in toImport) {
            importer.import(page.markdown, PageName(page.name)).fold(
                ifLeft = { err ->
                    logger.error("merge: failed to import '${page.name}': $err")
                    failed += page.name
                },
                ifRight = { imported += page.name },
            )
        }
        logger.info("merge: imported=${imported.size} skippedExisting=${toSkip.size} failed=${failed.size}")
        return GraphMergeResult(imported, toSkip.map { it.name }, failed)
    }
}
