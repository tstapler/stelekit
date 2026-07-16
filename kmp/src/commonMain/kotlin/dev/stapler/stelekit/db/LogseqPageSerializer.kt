package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.util.indentContinuationLines

/**
 * Canonical on-disk Logseq markdown builder, extracted from [GraphWriter.buildMarkdown] so it
 * can be reused by the QR transfer encoder without pulling in [GraphWriter]'s I/O dependencies.
 *
 * Pure function of [Page] and [Block] — no field access on any writer/repository — guaranteeing
 * the QR encoder serializes exactly what [dev.stapler.stelekit.outliner.OutlinerPipeline] /
 * the markdown parser reads back (round-trip fidelity).
 */
object LogseqPageSerializer {

    fun serialize(page: Page, blocks: List<Block>): String = buildString {
        // 1. Page Properties
        if (page.properties.isNotEmpty()) {
            page.properties.forEach { (key, value) ->
                appendLine("$key:: $value")
            }
        }

        // 2. Blocks — reconstruct tree from flat list
        val blocksByParent = blocks.groupBy { it.parentUuid }

        fun writeBlocks(parentUuid: dev.stapler.stelekit.model.BlockUuid?) {
            val siblings = blocksByParent[parentUuid] ?: return
            val sortedSiblings = siblings.sortedBy { it.position }

            sortedSiblings.forEach { block ->
                val indent = "\t".repeat(block.level)
                append(indent)
                append("- ")
                appendLine(block.content.indentContinuationLines(indent + "\t"))

                if (block.properties.isNotEmpty()) {
                    val propIndent = indent + "\t"
                    block.properties.forEach { (key, value) ->
                        append(propIndent)
                        appendLine("$key:: $value")
                    }
                }

                writeBlocks(block.uuid)
            }
        }

        writeBlocks(null)
    }
}
