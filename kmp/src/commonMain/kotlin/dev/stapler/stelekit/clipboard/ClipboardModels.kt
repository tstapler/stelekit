package dev.stapler.stelekit.clipboard

import dev.stapler.stelekit.model.Block

enum class ClipboardOperation { CUT, COPY }

data class ClipboardBlock(
    val block: Block,
    val operation: ClipboardOperation,
    val sourceGraphUuid: String
)

data class BlockClipboard(val entries: List<ClipboardBlock> = emptyList()) {

    val isEmpty: Boolean get() = entries.isEmpty()

    fun withBlock(block: Block, operation: ClipboardOperation, graphUuid: String): BlockClipboard =
        BlockClipboard(listOf(ClipboardBlock(block, operation, graphUuid)))

    fun clear(): BlockClipboard = BlockClipboard()

    val isCut: Boolean get() = entries.firstOrNull()?.operation == ClipboardOperation.CUT
}
