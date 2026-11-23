package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter

/** Flat `• content` list: one line per block, hierarchy dropped, property-only blocks skipped. */
class BulletListExporter : PageExporter {
    override val formatId = "bullet-list"
    override val displayName = "Bullet List"

    override fun export(page: Page, blocks: List<Block>, resolvedRefs: Map<String, String>): String =
        BlockSorter.sort(blocks)
            .filterNot { it.content.isBlank() && it.properties.isNotEmpty() }
            .joinToString("\n") { "• ${renderInlinePlainText(it.content, resolvedRefs).replace("\n", " ")}" }
}
