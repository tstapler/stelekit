// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.mcp

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Default/ceiling for [registerReadPageTool]'s `blockLimit` — a page's blocks are bounded reads, never a full-tree dump. */
internal const val DEFAULT_BLOCK_LIMIT = 50
private const val MAX_BLOCK_LIMIT = 50

private const val KEY_UUID = "uuid"
private const val KEY_NAME = "name"
private const val KEY_DATE = "date"
private const val KEY_BLOCK_LIMIT = "blockLimit"
private const val KEY_BLOCK_OFFSET = "blockOffset"

private val readPageInputSchema = ToolSchema(
    properties = buildJsonObject {
        putJsonObject(KEY_UUID) {
            put("type", "string")
            put("description", "Exact page UUID. Mutually exclusive with name and date.")
        }
        putJsonObject(KEY_NAME) {
            put("type", "string")
            put(
                "description",
                "Page title, matched case-insensitively. Mutually exclusive with uuid and date. " +
                    "Returns an error if the name matches more than one page (page names are only " +
                    "unique per section, not globally).",
            )
        }
        putJsonObject(KEY_DATE) {
            put("type", "string")
            put(
                "description",
                "Journal page date as YYYY-MM-DD, resolved via the journal date index regardless of " +
                    "the page's on-disk filename format. Mutually exclusive with uuid and name.",
            )
        }
        putJsonObject(KEY_BLOCK_LIMIT) {
            put("type", "integer")
            put("description", "Maximum number of blocks to return (default $DEFAULT_BLOCK_LIMIT, max $MAX_BLOCK_LIMIT).")
        }
        putJsonObject(KEY_BLOCK_OFFSET) {
            put("type", "integer")
            put("description", "Number of blocks to skip before the returned page (default 0).")
        }
    },
)

/**
 * `read_page` — read a page's full block content by uuid, name, or journal date.
 *
 * Registered with [Server.addTimedTool] (never the SDK's raw `addTool`) so a misbehaving handler
 * can't escape the timeout/exception guard.
 */
fun Server.registerReadPageTool(graphManager: GraphManager) {
    addTimedTool(
        name = "read_page",
        description = "Read a page's full block content by uuid, name, or journal date. " +
            "Exactly one of uuid, name, or date is required. Blocks are paginated via blockLimit/blockOffset " +
            "and never returned in full for a large page. Read-only.",
        inputSchema = readPageInputSchema,
        annotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false),
    ) { request ->
        val args = request.arguments
        val lookup = PageLookup(
            uuid = args.stringOrNull(KEY_UUID),
            name = args.stringOrNull(KEY_NAME),
            date = args.stringOrNull(KEY_DATE),
        )

        val providedCount = listOf(lookup.uuid, lookup.name, lookup.date).count { it != null }
        if (providedCount != 1) {
            return@addTimedTool CallToolResult(
                content = listOf(
                    TextContent(
                        "read_page requires exactly one of uuid, name, or date; received $providedCount.",
                    ),
                ),
                isError = true,
            )
        }

        val blockLimit = (args.intOrNull(KEY_BLOCK_LIMIT) ?: DEFAULT_BLOCK_LIMIT).coerceIn(1, MAX_BLOCK_LIMIT)
        val blockOffset = (args.intOrNull(KEY_BLOCK_OFFSET) ?: 0).coerceAtLeast(0)

        buildReadPageResult(graphManager, lookup, blockLimit, blockOffset)
    }
}

/** The caller-supplied, exactly-one-of-three page identifier for [registerReadPageTool]. */
internal data class PageLookup(val uuid: String?, val name: String?, val date: String?)

/** Internal (not private) so [registerPageResource]'s `note://{uuid}` resource reuses this exact rendering. */
internal suspend fun buildReadPageResult(
    graphManager: GraphManager,
    lookup: PageLookup,
    blockLimit: Int,
    blockOffset: Int,
): CallToolResult {
    val page = when (
        val resolved = resolvePage(graphManager, uuid = lookup.uuid, name = lookup.name, date = lookup.date)
    ) {
        is Either.Left -> return resolved.value.toCallToolResult()
        is Either.Right -> resolved.value ?: return CallToolResult(
            content = listOf(TextContent("No page found for ${describeLookup(lookup)}.")),
            isError = true,
        )
    }
    val repositorySet = graphManager.getActiveRepositorySet()
        ?: return McpToolError.GraphNotOpen.toCallToolResult()

    val allBlocks = when (val blocksResult = repositorySet.blockRepository.getBlocksForPage(page.uuid).first()) {
        is Either.Left -> return McpToolError.Internal(blocksResult.value).toCallToolResult()
        is Either.Right -> blocksResult.value
    }
    val pagedBlocks = allBlocks.drop(blockOffset).take(blockLimit)
    val hasMoreBlocks = allBlocks.size > blockOffset + blockLimit
    val resultJson = renderReadPageResult(page, pagedBlocks, blockOffset, blockLimit, hasMoreBlocks)
    return CallToolResult(content = listOf(TextContent(resultJson.toString())))
}

private fun describeLookup(lookup: PageLookup): String = when {
    lookup.uuid != null -> "uuid '${lookup.uuid}'"
    lookup.name != null -> "name '${lookup.name}'"
    else -> "date '${lookup.date}'"
}

private fun renderReadPageResult(
    page: Page,
    blocks: List<Block>,
    blockOffset: Int,
    blockLimit: Int,
    hasMoreBlocks: Boolean,
): JsonObject = buildJsonObject {
    putJsonObject("page") {
        put("uuid", page.uuid.value)
        put("name", page.name)
        page.namespace?.let { put("namespace", it) }
        put("isJournal", page.isJournal)
        page.journalDate?.let { put("journalDate", it.toString()) }
        put("isFavorite", page.isFavorite)
    }
    putJsonArray("blocks") {
        blocks.forEach { block ->
            addJsonObject {
                put("uuid", block.uuid.value)
                block.parentUuid?.let { put("parentUuid", it.value) }
                put("level", block.level)
                put("position", block.position)
                put("content", block.content)
            }
        }
    }
    put("blockOffset", blockOffset)
    put("blockLimit", blockLimit)
    put("hasMoreBlocks", hasMoreBlocks)
}

/**
 * Resolves a page by exactly one of [uuid], [name], or [date] (journal lookup). Shared verbatim by
 * `read_page` and Epic 2.3's planned `note://` resource resolver — do not duplicate this logic.
 *
 * `pages.name` is only `UNIQUE(name, section_id)` (`SteleDatabase.sq:19`), never globally, so a bare
 * `getPageByName` is never safe on its own: two pages in different sections can share a name. The
 * `name` branch therefore always runs a bounded disambiguation search first.
 *
 * Returns `Right(null)` when the lookup is well-formed but nothing matches (callers decide how to
 * report "not found"); `Left` only for adapter-level failures — no graph open, an ambiguous name, a
 * malformed [date], or a database error.
 */
internal suspend fun resolvePage(
    graphManager: GraphManager,
    uuid: String? = null,
    name: String? = null,
    date: String? = null,
): Either<McpToolError, Page?> {
    val repositorySet = graphManager.getActiveRepositorySet() ?: return McpToolError.GraphNotOpen.left()

    return when {
        uuid != null ->
            repositorySet.pageRepository.getPageByUuid(PageUuid(uuid)).first()
                .mapLeft { McpToolError.Internal(it) }

        date != null -> {
            val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
                ?: return McpToolError.Internal(
                    DomainError.ValidationError.ConstraintViolation("Invalid date '$date': expected YYYY-MM-DD"),
                ).left()
            repositorySet.pageRepository.getJournalPageByDate(parsedDate).first()
                .mapLeft { McpToolError.Internal(it) }
        }

        name != null ->
            when (val searchResult = repositorySet.searchRepository.searchPagesByTitle(name, limit = 5).first()) {
                is Either.Left -> McpToolError.Internal(searchResult.value).left()
                is Either.Right -> {
                    val exactMatches = searchResult.value.filter { it.name.equals(name, ignoreCase = true) }
                    when (exactMatches.size) {
                        0 -> null.right()
                        1 -> exactMatches.first().right()
                        else -> McpToolError.AmbiguousPageName(name, exactMatches.size).left()
                    }
                }
            }

        else -> McpToolError.Internal(
            DomainError.ValidationError.ConstraintViolation("Exactly one of uuid, name, or date is required"),
        ).left()
    }
}

internal fun JsonObject?.stringOrNull(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.intOrNull(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull
