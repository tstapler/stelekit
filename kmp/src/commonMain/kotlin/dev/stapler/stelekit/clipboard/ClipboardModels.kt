package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block

enum class ClipboardOperation { COPY, CUT }

data class ClipboardBlock(
    val block: Block,
    val operation: ClipboardOperation,
    val sourceGraphUuid: String
)

data class BlockClipboard(val entries: List<ClipboardBlock> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    val isCut: Boolean get() = entries.firstOrNull()?.operation == ClipboardOperation.CUT

    fun withBlock(block: Block, operation: ClipboardOperation, graphUuid: String): BlockClipboard =
        BlockClipboard(listOf(ClipboardBlock(block, operation, graphUuid)))

    fun withBlocks(
        blocks: List<Block>,
        operation: ClipboardOperation,
        graphUuid: String,
    ): BlockClipboard = BlockClipboard(blocks.map { ClipboardBlock(it, operation, graphUuid) })

    fun clear(): BlockClipboard = BlockClipboard()

}
