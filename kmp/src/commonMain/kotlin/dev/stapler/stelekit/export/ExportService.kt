package dev.stapler.stelekit.export

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.DomainError.ExportError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.BlockRefNode
import dev.stapler.stelekit.parsing.ast.WikiLinkNode
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.repository.BlockReadRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate

/**
 * Orchestrates the export pipeline:
 * 1. Pre-fetches block references in bulk (avoids N+1 DB calls during serialization)
 * 2. Dispatches to the correct [PageExporter]
 * 3. Writes output to the injected [ClipboardProvider]
 *
 * [clipboard] is a var so the composable root can inject the platform implementation
 * via [StelekitViewModel.setClipboardProvider] after construction.
 */
class ExportService(
    private val exporters: List<PageExporter>,
    var clipboard: ClipboardProvider,
    private val blockRepository: BlockReadRepository
) {
    private val exporterMap: Map<String, PageExporter> = exporters.associateBy { it.formatId }

    /**
     * Exports [page] + [blocks] to the given [formatId] and writes to [clipboard].
     * Runs on [PlatformDispatcher.DB] because [resolveBlockRefs] performs DB reads.
     */
    suspend fun exportToClipboard(
        page: Page,
        blocks: List<Block>,
        formatId: String
    ): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val exporter = exporterMap[formatId]
                ?: return@withContext ExportError.SerializationFailed("Unknown export format: $formatId").left()
            val resolvedRefs = resolveBlockRefs(collectBlockRefUuids(blocks))
            val output = runCatching { exporter.export(page, blocks, resolvedRefs) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    return@withContext ExportError.SerializationFailed(e.message ?: "unknown").left()
                }
            runCatching {
                if (formatId == "html") {
                    val plainText = exporterMap["plain-text"]
                        ?.export(page, blocks, resolvedRefs)
                        ?: output
                    clipboard.writeHtml(output, plainText)
                } else {
                    clipboard.writeText(output)
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                return@withContext ExportError.ClipboardFailed(e.message ?: "unknown").left()
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportError.SerializationFailed(e.message ?: "unknown").left()
        }
    }

    /**
     * Exports [page] + [blocks] to the given [formatId] and returns the result as a String.
     * Useful for file-save operations.
     */
    suspend fun exportToString(
        page: Page,
        blocks: List<Block>,
        formatId: String
    ): Either<DomainError, String> = withContext(PlatformDispatcher.DB) {
        try {
            val exporter = exporterMap[formatId]
                ?: return@withContext ExportError.SerializationFailed("Unknown export format: $formatId").left()
            val resolvedRefs = resolveBlockRefs(collectBlockRefUuids(blocks))
            exporter.export(page, blocks, resolvedRefs).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportError.SerializationFailed(e.message ?: "unknown").left()
        }
    }

    /**
     * Scans [blocks] for `((uuid))` references and returns the set of referenced UUIDs.
     * Uses [InlineParser] to extract [BlockRefNode] values.
     */
    fun collectBlockRefUuids(blocks: List<Block>): Set<String> {
        val uuids = mutableSetOf<String>()
        for (block in blocks) {
            InlineParser(block.content).parse().forEach { node ->
                if (node is BlockRefNode) uuids.add(node.blockUuid)
            }
        }
        return uuids
    }

    /**
     * Bulk-fetches [uuids] from [blockRepository] and returns a uuid→content map.
     * Missing/dangling refs are silently omitted (exporters fall back to "[block ref]").
     */
    suspend fun resolveBlockRefs(uuids: Set<String>): Map<String, String> =
        withContext(PlatformDispatcher.DB) {
            uuids.mapNotNull { uuid ->
                val block = runCatching {
                    blockRepository.getBlockByUuid(BlockUuid(uuid)).first().getOrNull()
                }.getOrNull()
                if (block != null) uuid to block.content else null
            }.toMap()
        }

    /**
     * Returns [allBlocks] filtered to the selected subtrees: each UUID in [rootUuids]
     * plus all of its descendants (blocks with higher level that follow it in DFS order).
     *
     * Uses a stack-based traversal of the depth-first sorted block list:
     * - When a root is encountered, its level is pushed onto the capture stack.
     * - Subsequent blocks with level > stack top are descendants and are included.
     * - When a block's level drops to ≤ the stack top, the capture context is popped.
     *
     * This handles disjoint roots, nested roots, and empty selections correctly.
     */
    fun subtreeBlocks(allBlocks: List<Block>, rootUuids: Set<String>): List<Block> {
        if (rootUuids.isEmpty()) return emptyList()
        val sorted = BlockSorter.sort(allBlocks)
        val result = mutableListOf<Block>()
        // Stack of root-block levels currently being captured
        val captureStack = mutableListOf<Int>()

        for (block in sorted) {
            // Pop any capture contexts whose subtrees have ended
            while (captureStack.isNotEmpty() && block.level <= captureStack.last()) {
                captureStack.removeLast()
            }

            if (block.uuid.value in rootUuids) {
                result.add(block)
                captureStack.add(block.level)
            } else if (captureStack.isNotEmpty()) {
                // Inside a captured subtree
                result.add(block)
            }
        }
        return result
    }

    /**
     * Exports [page] + [blocks] along with all pages linked via `[[PageName]]` tokens.
     *
     * Only resolves one level of links (non-recursive). Cycles are prevented by a visited set.
     * Empty linked pages (no blocks) are silently skipped.
     *
     * @param pageRepo Used to look up linked pages by name.
     * @param blockRepo Used to fetch blocks for each linked page.
     * @return Combined export string with a Markdown heading separator for each page.
     */
    suspend fun exportPageWithLinks(
        page: Page,
        blocks: List<Block>,
        formatId: String,
        pageRepo: PageRepository,
        blockRepo: BlockReadRepository,
    ): Either<DomainError, String> = withContext(PlatformDispatcher.DB) {
        try {
            val exporter = exporterMap[formatId]
                ?: return@withContext ExportError.SerializationFailed("Unknown export format: $formatId").left()

            val visited = mutableSetOf(page.name)
            val parts = mutableListOf<String>()

            // Export the root page
            val rootRefs = resolveBlockRefs(collectBlockRefUuids(blocks))
            parts.add(exporter.export(page, blocks, rootRefs))

            // Collect wiki-link targets from root blocks
            val linkedPageNames = mutableSetOf<String>()
            for (block in blocks) {
                InlineParser(block.content).parse().forEach { node ->
                    if (node is WikiLinkNode) linkedPageNames.add(node.target)
                }
            }

            // Export each linked page (one level only, cycle-safe)
            for (linkedName in linkedPageNames) {
                if (linkedName in visited) continue
                visited.add(linkedName)

                val linkedPage = runCatching {
                    pageRepo.getPageByName(linkedName).first().getOrNull()
                }.getOrNull() ?: continue

                val linkedBlocks = runCatching {
                    blockRepo.getBlocksForPage(linkedPage.uuid).first().getOrNull() ?: emptyList()
                }.getOrNull() ?: emptyList()

                if (linkedBlocks.isEmpty()) continue

                val linkedRefs = resolveBlockRefs(collectBlockRefUuids(linkedBlocks))
                // Use a format-appropriate separator; the exporter already adds the page title.
                parts.add("${pageSeparator(formatId)}${exporter.export(linkedPage, linkedBlocks, linkedRefs)}")
            }

            joinWithFormat(parts, formatId).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportError.SerializationFailed(e.message ?: "unknown").left()
        }
    }

    /**
     * Exports all journal pages in the date range [[from], [to]] (inclusive) as a single string.
     *
     * Uses [pageRepo.getAllPages()] and filters in memory (ADR-4). Empty days are skipped.
     * Pages within the range are sorted by date ascending and separated by a `## date` heading.
     *
     * @return [Left] with [ExportError.SerializationFailed] if no journal pages are in range.
     */
    suspend fun exportJournalRange(
        from: LocalDate,
        to: LocalDate,
        formatId: String,
        pageRepo: PageRepository,
        blockRepo: BlockReadRepository,
    ): Either<DomainError, String> = withContext(PlatformDispatcher.DB) {
        try {
            val exporter = exporterMap[formatId]
                ?: return@withContext ExportError.SerializationFailed("Unknown export format: $formatId").left()

            val allPages = pageRepo.getAllPages().first().getOrNull() ?: emptyList()

            // Filter to journal pages whose date falls in [from, to]
            val journalPages = allPages
                .filter { page ->
                    val date = page.journalDate ?: return@filter false
                    date in from..to
                }
                .sortedBy { it.journalDate }

            if (journalPages.isEmpty()) {
                return@withContext ExportError.SerializationFailed(
                    "No journal pages found in the selected date range"
                ).left()
            }

            val parts = mutableListOf<String>()
            for (journalPage in journalPages) {
                val pageBlocks = runCatching {
                    blockRepo.getBlocksForPage(journalPage.uuid).first().getOrNull() ?: emptyList()
                }.getOrNull() ?: emptyList()

                if (pageBlocks.isEmpty()) continue

                val resolvedRefs = resolveBlockRefs(collectBlockRefUuids(pageBlocks))
                // The exporter already includes the page title — add no extra heading here.
                parts.add(exporter.export(journalPage, pageBlocks, resolvedRefs))
            }

            if (parts.isEmpty()) {
                return@withContext ExportError.SerializationFailed(
                    "No journal pages with content found in the selected date range"
                ).left()
            }

            joinWithFormat(parts, formatId).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportError.SerializationFailed(e.message ?: "unknown").left()
        }
    }

    /** Display name for the given [formatId], or the ID itself if unknown. */
    fun displayNameFor(formatId: String): String =
        exporterMap[formatId]?.displayName ?: formatId

    // Format-appropriate separator placed BETWEEN multi-page export sections.
    // The exporter already renders the page title, so we only add a visual divider.
    private fun pageSeparator(formatId: String): String = when (formatId) {
        "html" -> "\n<hr>\n"
        "json" -> ",\n"
        else -> "\n\n---\n\n"   // markdown + plain-text
    }

    // Joins exported page strings with format-appropriate glue.
    // For JSON, wraps in an array; for all other formats, separates with pageSeparator.
    private fun joinWithFormat(parts: List<String>, formatId: String): String =
        if (formatId == "json") "[${parts.joinToString(",\n")}]"
        else parts.joinToString(pageSeparator(formatId))
}
