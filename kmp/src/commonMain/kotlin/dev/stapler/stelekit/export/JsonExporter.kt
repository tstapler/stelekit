package dev.stapler.stelekit.export

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import kotlin.time.Clock
import kotlinx.serialization.json.Json

class JsonExporter : PageExporter {

    override val formatId: String = "json"
    override val displayName: String = "JSON"

    override fun export(page: Page, blocks: List<Block>, resolvedRefs: Map<String, String>): String {
        val pageDto = PageDto(
            uuid = page.uuid,
            name = page.name,
            isJournal = page.isJournal,
            journalDate = page.journalDate?.toString(),
            createdAt = page.createdAt.toString(),
            updatedAt = page.updatedAt.toString(),
            properties = page.properties.filterKeys { it != "id" }
        )

        val sortedBlocks = BlockSorter.sort(blocks)
        val blockDtos = buildBlockTree(sortedBlocks)

        val root = ExportRoot(
            exportedAt = Clock.System.now().toString(),
            page = pageDto,
            blocks = blockDtos
        )

        return Json { prettyPrint = true }.encodeToString(ExportRoot.serializer(), root)
    }

    private fun buildBlockTree(blocks: List<Block>): List<BlockDto> {
        val blocksByParent = blocks.groupBy { it.parentUuid }

        fun buildChildren(parentUuid: String?): List<BlockDto> {
            val children = blocksByParent[parentUuid] ?: return emptyList()
            return children
                .sortedWith(compareBy<Block> { it.position }.thenBy { it.uuid })
                .map { block ->
                    BlockDto(
                        uuid = block.uuid,
                        parentUuid = block.parentUuid,
                        position = block.position,
                        level = block.level,
                        content = block.content,
                        properties = block.properties.filterKeys { it != "id" },
                        children = buildChildren(block.uuid)
                    )
                }
        }

        return buildChildren(null)
    }
}
