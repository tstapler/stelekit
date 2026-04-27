package dev.stapler.stelekit.export

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.parsing.InlineParser
import dev.stapler.stelekit.parsing.ast.BlockRefNode
import dev.stapler.stelekit.repository.BlockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

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
    private val blockRepository: BlockRepository
) {
    private val exporterMap: Map<String, PageExporter> = exporters.associateBy { it.formatId }

    /**
     * Exports [page] + [blocks] to the given [formatId] and writes to [clipboard].
     * Runs on [Dispatchers.Default]; callers should switch to Main for UI updates.
     */
    suspend fun exportToClipboard(
        page: Page,
        blocks: List<Block>,
        formatId: String
    ): Either<DomainError, Unit> = withContext(Dispatchers.Default) {
        try {
            val exporter = exporterMap[formatId]
                ?: error("Unknown export format: $formatId")
            val resolvedRefs = resolveBlockRefs(collectBlockRefUuids(blocks))
            val output = exporter.export(page, blocks, resolvedRefs)
            if (formatId == "html") {
                val plainText = exporterMap["plain-text"]
                    ?.export(page, blocks, resolvedRefs)
                    ?: output
                clipboard.writeHtml(output, plainText)
            } else {
                clipboard.writeText(output)
            }
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
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
    ): Either<DomainError, String> = withContext(Dispatchers.Default) {
        try {
            val exporter = exporterMap[formatId]
                ?: error("Unknown export format: $formatId")
            val resolvedRefs = resolveBlockRefs(collectBlockRefUuids(blocks))
            exporter.export(page, blocks, resolvedRefs).right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
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
        withContext(Dispatchers.Default) {
            uuids.mapNotNull { uuid ->
                val block = runCatching {
                    blockRepository.getBlockByUuid(uuid).first().getOrNull()
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

            if (block.uuid in rootUuids) {
                result.add(block)
                captureStack.add(block.level)
            } else if (captureStack.isNotEmpty()) {
                // Inside a captured subtree
                result.add(block)
            }
        }
        return result
    }

    /** Display name for the given [formatId], or the ID itself if unknown. */
    fun displayNameFor(formatId: String): String =
        exporterMap[formatId]?.displayName ?: formatId
}
