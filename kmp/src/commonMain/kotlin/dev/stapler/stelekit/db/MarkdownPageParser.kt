package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.model.ParsedPage
import dev.stapler.stelekit.model.toDiscriminatorString
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.FileUtils
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.time.Instant

/**
 * Pure, stateless page and block model builder.
 *
 * Handles UUID generation, block-tree processing, and [Page] construction from
 * parsed Markdown output.  All methods are free of database I/O and file I/O —
 * callers supply any data that would otherwise require a DB or filesystem call
 * (e.g. [fileModTime], [existingPage]).
 *
 * Extracted from [GraphLoader] so this logic can be tested in isolation and
 * reused without pulling in repository or coroutine dependencies.
 */
data class PageBuildResult(val page: Page, val firstBlockSkipped: Boolean)
data class PageMetadata(val name: String, val isJournal: Boolean, val journalDate: kotlinx.datetime.LocalDate?)

object MarkdownPageParser {

    /**
     * Derives a stable UUID for a block from its position in the page tree.
     *
     * The UUID is derived from position only (filePath + parentUuid + siblingIndex),
     * making it stable across content edits. If the block already carries an explicit
     * `id` property (written by Logseq or the user), that value is used verbatim.
     */
    fun generateUuid(
        parsedBlock: ParsedBlock,
        pagePath: String,
        blockIndex: Int,
        parentUuid: String? = null,
        sidecarMap: Map<String, SidecarManager.SidecarEntry>? = null,
    ): String {
        val existingId = parsedBlock.properties["id"]
        if (existingId != null && existingId.isNotBlank()) {
            return existingId
        }

        if (sidecarMap != null) {
            val hash = ContentHasher.sha256ForContent(parsedBlock.content)
            val sidecarEntry = sidecarMap[hash]
            if (sidecarEntry != null) return sidecarEntry.uuid
        }

        // Include parentUuid in seed so blocks at different nesting levels with the
        // same sibling index don't collide.
        val seed = "$pagePath:${parentUuid ?: "root"}:$blockIndex"
        return UuidGenerator.generateDeterministic(seed)
    }

    /**
     * Constructs the [Page] model and extracts page-level properties from the first
     * (property) block when applicable.  Returns the built [Page] and whether the
     * property block was consumed (firstBlockSkipped).
     *
     * @param fileModTime  Last-modified timestamp from the filesystem in epoch milliseconds,
     *                     or null if unavailable.  When null the current clock time is used.
     */
    fun buildPageModel(
        filePath: String,
        name: String,
        isJournal: Boolean,
        journalDate: kotlinx.datetime.LocalDate?,
        existingPage: Page?,
        now: Instant,
        mode: ParseMode,
        parsedPage: ParsedPage,
        fileModTime: Long?,
    ): PageBuildResult {
        val updatedAt = if (fileModTime != null && fileModTime != 0L) {
            Instant.fromEpochMilliseconds(fileModTime)
        } else {
            now
        }
        val createdAt = existingPage?.createdAt ?: updatedAt

        var page = Page(
            uuid = existingPage?.uuid ?: UuidGenerator.generateV7(),
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = existingPage?.version ?: 0L,
            properties = emptyMap(),
            isJournal = isJournal,
            journalDate = journalDate,
            filePath = filePath,
            isContentLoaded = mode == ParseMode.FULL
        )

        var firstBlockSkipped = false
        if (parsedPage.blocks.isNotEmpty()) {
            val firstBlock = parsedPage.blocks.first()
            if (firstBlock.content.trim().isEmpty() && firstBlock.properties.isNotEmpty()) {
                page = page.copy(properties = firstBlock.properties)
                firstBlockSkipped = true
            }
        }

        return PageBuildResult(page, firstBlockSkipped)
    }

    private fun ParsedBlock.mergedProperties(): Map<String, String> =
        properties.toMutableMap().apply {
            scheduled?.let { put("scheduled", it) }
            deadline?.let { put("deadline", it) }
        }

    /**
     * Recursively processes [parsedBlocks] into a flat [destinationList] of [Block]s,
     * preserving version counters and existing content hashes for diff-merge.
     */
    fun processParsedBlocks(
        parsedBlocks: List<ParsedBlock>,
        pagePath: String,
        pageUuid: String,
        parentUuid: String?,
        baseLevel: Int,
        now: Instant,
        destinationList: MutableList<Block>,
        mode: ParseMode,
        existingVersions: Map<String, Long> = emptyMap(),
        existingContent: Map<String, String> = emptyMap(),
        sidecarMap: Map<String, SidecarManager.SidecarEntry>? = null,
    ) {
        var previousSiblingUuid: String? = null

        parsedBlocks.forEachIndexed { index, parsedBlock ->
            val blockUuid = generateUuid(parsedBlock, pagePath, index, parentUuid, sidecarMap)
            val currentVersion = existingVersions[blockUuid] ?: 0L
            val oldContent = existingContent[blockUuid]

            val versionToSave = if (oldContent == parsedBlock.content) currentVersion else {
                if (currentVersion > 0) currentVersion + 1 else 0L
            }

            val block = Block(
                uuid = blockUuid,
                pageUuid = pageUuid,
                parentUuid = parentUuid,
                leftUuid = previousSiblingUuid,
                content = parsedBlock.content,
                level = baseLevel,
                position = index,
                createdAt = now,
                updatedAt = now,
                version = versionToSave,
                properties = parsedBlock.mergedProperties(),
                isLoaded = mode == ParseMode.FULL,
                contentHash = ContentHasher.sha256ForContent(parsedBlock.content),
                blockType = parsedBlock.blockType.toDiscriminatorString()
            )

            destinationList.add(block)
            previousSiblingUuid = blockUuid

            if (parsedBlock.children.isNotEmpty()) {
                processParsedBlocks(
                    parsedBlocks = parsedBlock.children,
                    pagePath = pagePath,
                    pageUuid = pageUuid,
                    parentUuid = blockUuid,
                    baseLevel = baseLevel + 1,
                    now = now,
                    destinationList = destinationList,
                    mode = mode,
                    existingVersions = existingVersions,
                    existingContent = existingContent,
                    sidecarMap = sidecarMap,
                )
            }
        }
    }

    /**
     * Creates lightweight stub blocks with [isLoaded] = false for METADATA_ONLY mode.
     *
     * Avoids the DB round-trips that [processParsedBlocks] requires (existing version
     * lookups, content comparisons) so background loading stays fast.
     */
    fun createStubBlocks(
        parsedBlocks: List<ParsedBlock>,
        pagePath: String,
        pageUuid: String,
        parentUuid: String?,
        baseLevel: Int,
        now: Instant,
        destination: MutableList<Block>
    ) {
        parsedBlocks.forEachIndexed { index, parsedBlock ->
            val blockUuid = generateUuid(parsedBlock, pagePath, index, parentUuid)

            destination.add(
                Block(
                    uuid = blockUuid,
                    pageUuid = pageUuid,
                    parentUuid = parentUuid,
                    content = parsedBlock.content,
                    level = baseLevel,
                    position = index,
                    createdAt = now,
                    updatedAt = now,
                    properties = parsedBlock.mergedProperties(),
                    isLoaded = false,
                    contentHash = ContentHasher.sha256ForContent(parsedBlock.content),
                    blockType = parsedBlock.blockType.toDiscriminatorString()
                )
            )

            if (parsedBlock.children.isNotEmpty()) {
                createStubBlocks(parsedBlock.children, pagePath, pageUuid, blockUuid, baseLevel + 1, now, destination)
            }
        }
    }

    /**
     * Derives the page name and journal date from [filePath] and [fileName].
     *
     * Returns a triple of (name, isJournal, journalDate).
     */
    fun extractPageMetadata(
        filePath: String,
        fileName: String,
        stripExtension: (String) -> String,
    ): PageMetadata {
        val name = FileUtils.decodeFileName(stripExtension(fileName))
        val journalDate = if (filePath.contains("/journals/")) JournalUtils.parseJournalDate(name) else null
        val isJournal = journalDate != null
        return PageMetadata(name, isJournal, journalDate)
    }
}
