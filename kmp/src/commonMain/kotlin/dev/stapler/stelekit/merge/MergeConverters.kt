package dev.stapler.stelekit.merge

import dev.stapler.stelekit.db.MarkdownPageParser
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockPropertyKeys
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** Disk-side parse result: the model the DB would hold for this file (flat, pre-order). */
class ParsedMarkdown(val page: Page, val blocks: List<Block>) {
    val mergePage: MergePage by lazy { MergeConverters.toMergePage(page, blocks) }
}

/**
 * Converts between DB/disk models and the pure [MergePage]. A [MergeBlock.uuid] is non-null only
 * for blocks with an explicit `id::` property, because only those survive a re-parse; the rest
 * have positional uuids that the merge must not treat as identity.
 */
object MergeConverters {
    // Parsing needs a timestamp but nothing here reads it; a constant keeps results deterministic.
    private val EPOCH = Instant.fromEpochMilliseconds(0)

    fun toMergePage(page: Page, blocks: List<Block>): MergePage {
        val byParent = blocks.groupBy { it.parentUuid }
        fun build(parent: BlockUuid?): List<MergeBlock> =
            byParent[parent].orEmpty().sortedBy { it.position }.map { b ->
                val explicit = b.properties[BlockPropertyKeys.ID]?.takeIf { it.isNotBlank() }
                MergeBlock(
                    uuid = explicit,
                    content = b.content,
                    properties = b.properties - BlockPropertyKeys.ID,
                    children = build(b.uuid),
                )
            }
        return MergePage(
            name = page.name,
            isJournal = page.isJournal,
            journalDate = page.journalDate,
            properties = page.properties,
            blocks = build(null),
        )
    }

    fun toPage(page: MergePage, pagePath: String): Page = Page(
        uuid = PageUuid(UuidGenerator.generateV7()),
        name = page.name,
        filePath = pagePath,
        createdAt = EPOCH,
        updatedAt = EPOCH,
        properties = page.properties,
        isJournal = page.isJournal,
        journalDate = page.journalDate,
    )

    /**
     * Explicit uuids become an `id` property (what the serializer writes); unlabeled blocks get
     * the same positional uuid the loader would derive from [pagePath].
     */
    fun toBlocks(page: MergePage, pageUuid: PageUuid, pagePath: String): List<Block> {
        val out = mutableListOf<Block>()
        fun walk(blocks: List<MergeBlock>, parent: String?, level: Int) {
            var prevPosition: String? = null
            var prevUuid: String? = null
            blocks.forEachIndexed { index, b ->
                val uuid = b.uuid ?: MarkdownPageParser.generateUuid(
                    ParsedBlock(b.content, b.properties, level), pagePath, index, parent,
                )
                val position = FractionalIndexing.generateKeyBetween(prevPosition, null)
                out += Block(
                    uuid = BlockUuid(uuid),
                    pageUuid = pageUuid,
                    parentUuid = parent?.let(::BlockUuid),
                    leftUuid = prevUuid?.let(::BlockUuid),
                    content = b.content,
                    level = level,
                    position = position,
                    createdAt = EPOCH,
                    updatedAt = EPOCH,
                    properties = if (b.uuid != null) mapOf(BlockPropertyKeys.ID to uuid) + b.properties else b.properties,
                )
                walk(b.children, uuid, level + 1)
                prevPosition = position
                prevUuid = uuid
            }
        }
        walk(page.blocks, null, 0)
        return out
    }

    /**
     * Parses [text] with the production parser. [pagePath] must be the string `GraphLoader` passes
     * for this file (see `PageFileResolver` KDoc) or positional uuids will not match the DB's.
     */
    fun parseMarkdown(
        text: String,
        pagePath: String,
        name: String,
        isJournal: Boolean,
        journalDate: LocalDate? = null,
    ): ParsedMarkdown {
        val parsed = MarkdownParser().parsePage(text, ParseMode.FULL)
        val built = MarkdownPageParser.buildPageModel(
            filePath = pagePath, name = name, isJournal = isJournal, journalDate = journalDate,
            existingPage = null, now = EPOCH, mode = ParseMode.FULL, parsedPage = parsed, fileModTime = null,
        )
        val roots = if (built.firstBlockSkipped) parsed.blocks.drop(1) else parsed.blocks
        val blocks = mutableListOf<Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = roots, pagePath = pagePath, pageUuid = built.page.uuid, parentUuid = null,
            baseLevel = 0, now = EPOCH, destinationList = blocks, mode = ParseMode.FULL,
        )
        return ParsedMarkdown(built.page, blocks)
    }
}
