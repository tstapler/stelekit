@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.transfer

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.LogseqPageSerializer
import dev.stapler.stelekit.db.MarkdownPageParser
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Phase 0 Story 0.1.3 spike (ADR-002): verified UUID behaviour that idempotent merge remap rests on.
 * Findings are asserted as observed facts; a failure here means ADR-002's "Spike result" needs revisiting.
 *
 * Q1: serializer -> parser round trip of a block uuid / `id::`.
 * Q2: saving a second block with an existing uuid on a different page.
 * Q3: QrImportService UUID handling (see comment above the last test).
 */
class MergeUuidRoundTripSpikeTest {

    private val targetUuid = "22222222-2222-2222-2222-222222222222"
    private val pageUuid = "11111111-1111-1111-1111-111111111111"

    private fun now() = Clock.System.now()

    private fun page(uuid: String, name: String) =
        Page(uuid = PageUuid(uuid), name = name, createdAt = now(), updatedAt = now())

    private fun block(
        uuid: String,
        pageUuid: String,
        content: String = "x",
        parentUuid: String? = null,
        level: Int = 0,
        properties: Map<String, String> = emptyMap(),
    ) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        parentUuid = parentUuid?.let { BlockUuid(it) },
        content = content,
        level = level,
        position = "a0",
        createdAt = now(),
        updatedAt = now(),
        properties = properties,
    )

    /** Same parse path as GraphLoader.importMarkdownString (MarkdownParser + processParsedBlocks). */
    private fun parseBack(markdown: String): List<Block> {
        val parsed = MarkdownParser().parsePage(markdown, ParseMode.FULL)
        val out = mutableListOf<Block>()
        MarkdownPageParser.processParsedBlocks(
            parsedBlocks = parsed.blocks,
            pagePath = pageUuid,
            pageUuid = PageUuid(pageUuid),
            parentUuid = null,
            baseLevel = 0,
            now = now(),
            destinationList = out,
            mode = ParseMode.FULL,
        )
        return out
    }

    // ---------------------------------------------------------------- Q1

    @Test
    fun roundTrip_serializer_does_not_emit_id_from_uuid_so_uuid_is_lost() {
        val md = LogseqPageSerializer.serialize(page(pageUuid, "P"), listOf(block(targetUuid, pageUuid)))
        println("SPIKE Q1a serialized (no id property): ${md.replace("\n", "\\n")}")
        assertFalse(md.contains("id::"), "serializer emits id:: from Block.uuid: $md")

        val parsed = parseBack(md)
        println("SPIKE Q1a parsed uuid=${parsed.single().uuid.value} (original $targetUuid)")
        assertNotEquals(targetUuid, parsed.single().uuid.value)
    }

    @Test
    fun roundTrip_preserves_uuid_when_id_is_in_block_properties() {
        val md = LogseqPageSerializer.serialize(
            page(pageUuid, "P"),
            listOf(block(targetUuid, pageUuid, properties = mapOf("id" to targetUuid))),
        )
        println("SPIKE Q1b serialized (id in properties): ${md.replace("\n", "\\n")}")
        assertTrue(md.contains("id:: $targetUuid"))

        val parsed = parseBack(md).single()
        println("SPIKE Q1b parsed uuid=${parsed.uuid.value} content='${parsed.content}' properties=${parsed.properties}")
        assertEquals(targetUuid, parsed.uuid.value)
        assertEquals("x", parsed.content)
    }

    @Test
    fun roundTrip_nested_children_keep_explicit_ids() {
        val childUuid = "33333333-3333-3333-3333-333333333333"
        val blocks = listOf(
            block(targetUuid, pageUuid, properties = mapOf("id" to targetUuid)),
            block(childUuid, pageUuid, content = "child", parentUuid = targetUuid, level = 1,
                properties = mapOf("id" to childUuid)),
        )
        val parsed = parseBack(LogseqPageSerializer.serialize(page(pageUuid, "P"), blocks))
        println("SPIKE Q1c parsed uuids=${parsed.map { it.uuid.value }} parents=${parsed.map { it.parentUuid?.value }}")
        assertEquals(setOf(targetUuid, childUuid), parsed.map { it.uuid.value }.toSet())
        assertEquals(targetUuid, parsed.single { it.uuid.value == childUuid }.parentUuid?.value)
    }

    // ---------------------------------------------------------------- Q2

    private class Fixture {
        val driver: SqlDriver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val blocks = SqlDelightBlockRepository(database)
        val pages = SqlDelightPageRepository(database)

        fun scalar(sql: String): Long? = driver.executeQuery(null, sql, { c ->
            QueryResult.Value(if (c.next().value) c.getLong(0) else null)
        }, 0).value
    }

    @Test
    fun duplicateUuid_on_other_page_replaces_row_and_cascades_children_and_references() = runBlocking {
        val f = Fixture()
        val p1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val p2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        val child = "33333333-3333-3333-3333-333333333333"
        val referrer = "44444444-4444-4444-4444-444444444444"
        f.pages.savePage(page(p1, "P1"))
        f.pages.savePage(page(p2, "P2"))
        assertTrue(f.blocks.saveBlock(block(targetUuid, p1, content = "original")).isRight())
        assertTrue(f.blocks.saveBlock(block(child, p1, content = "child", parentUuid = targetUuid, level = 1)).isRight())
        assertTrue(f.blocks.saveBlock(block(referrer, p1, content = "refers")).isRight())
        f.driver.execute(null,
            "INSERT INTO block_references (from_block_uuid, to_block_uuid, created_at) VALUES ('$referrer', '$targetUuid', 0)",
            0)
        println("SPIKE Q2 PRAGMA foreign_keys=${f.scalar("PRAGMA foreign_keys")}")

        val result = f.blocks.saveBlock(block(targetUuid, p2, content = "incoming"))
        println("SPIKE Q2 second saveBlock(uuid=U, page=P2) result=$result")
        assertTrue(result.isRight(), "expected silent replace, got $result")

        val u = f.blocks.getBlockByUuid(BlockUuid(targetUuid)).first().getOrNull()
        println("SPIKE Q2 U now: page=${u?.pageUuid?.value} content=${u?.content}")
        assertEquals(p2, u?.pageUuid?.value, "row moved to P2 (replaced)")
        assertEquals("incoming", u?.content)

        val p1Blocks = f.blocks.getBlocksForPage(PageUuid(p1)).first().getOrNull()!!.map { it.content }
        println("SPIKE Q2 P1 blocks after replace: $p1Blocks")
        val childSurvives = f.blocks.getBlockByUuid(BlockUuid(child)).first().getOrNull()
        println("SPIKE Q2 child (parent=U) survives: ${childSurvives != null}")
        val refCount = f.scalar("SELECT COUNT(*) FROM block_references WHERE to_block_uuid = '$targetUuid'")
        println("SPIKE Q2 block_references -> U remaining: $refCount")

        assertNull(childSurvives, "child block cascade-deleted by REPLACE")
        assertEquals(0L, refCount, "reference to U cascade-deleted by REPLACE")
        assertEquals(listOf("refers"), p1Blocks, "P1 lost its original U and its child; only referrer remains")
    }

    // ---------------------------------------------------------------- Q3
    // QrImportService.import (transfer/qrcode/QrImportService.kt) never reads uuids itself: it calls
    // GraphLoader.importMarkdownString, which runs the same MarkdownPageParser.processParsedBlocks
    // path as Q1. So:
    //  - a payload block carrying `id:: <uuid>` keeps that uuid (Q1b);
    //  - a block without id gets generateDeterministic("<pageUuid>:<parent|root>:<index>") where
    //    pageUuid is freshly generated per import, so ids without `id::` never collide across imports;
    //  - on OVERWRITE the page uuid is reused and old blocks are deleted first, then saveBlocks
    //    (INSERT OR REPLACE) writes the new set;
    //  - a payload `id::` equal to an existing block on ANOTHER page goes through the same
    //    INSERT OR REPLACE and hits the Q2 clobber; QrImportService has no guard against it.
    @Test
    fun qrImport_path_uses_same_parser_so_explicit_id_is_consumed_and_unguarded() {
        val parsed = parseBack("- x\n  id:: $targetUuid\n").single()
        println("SPIKE Q3 payload-with-id parsed uuid=${parsed.uuid.value}")
        assertEquals(targetUuid, parsed.uuid.value)
    }
}
