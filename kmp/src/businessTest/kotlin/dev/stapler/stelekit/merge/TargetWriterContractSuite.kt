package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a [TargetWriterContractSuite] needs from the target under test. */
interface TargetHarness {
    val writer: TargetWriter

    /** Puts [text] on disk as the page's file, visible to the target the way an existing file is. */
    suspend fun seed(key: PageKey, text: String)

    /** Like [seed], but an index-only stub: the page is known and has a file, yet its blocks are not loaded. */
    suspend fun seedUnloaded(key: PageKey, text: String) = seed(key, text)

    /** Replaces block text in the target as a user edit would. */
    suspend fun editContent(key: PageKey, from: String, to: String)

    /** Block data of the written state, or null with no file. The active target also asserts file == DB here. */
    suspend fun snapshot(key: PageKey): MergePage?

    /** Pre-order uuids of every block, positional ones included. */
    suspend fun blockUuids(key: PageKey): List<String>

    suspend fun close()
}

/**
 * One suite, run against every [TargetWriter]. Cases compare BLOCK DATA (uuids, contents,
 * properties), not file bytes: the active path re-renders the whole page (ADR-001 rev. 3).
 */
abstract class TargetWriterContractSuite {
    protected abstract fun newHarness(): TargetHarness

    private val source = GraphId("src")
    private val policy = MergePolicy(source, "Source")
    private val key = PageKey("Target")
    private val opened = mutableListOf<TargetHarness>()

    protected fun harness(): TargetHarness = newHarness().also { opened += it }

    @AfterTest
    fun closeHarnesses() {
        runBlocking { opened.forEach { it.close() } }
        opened.clear()
    }

    protected fun <T> Either<DomainError, T>.ok(): T = fold({ error("expected Right but was $it") }, { it })

    private fun uuid(s: String) = UuidRemap.uuidFor(source, s)

    private val incoming = MergePage(
        "Target",
        blocks = listOf(
            MergeBlock("s1", "alpha see ((s2))", children = listOf(MergeBlock(null, "unlabeled child"))),
            MergeBlock("s2", "beta"),
            MergeBlock(null, "gamma"),
        ),
    )

    private class Copy(val outcome: MergeOutcome, val written: WriteOutcome?)

    private suspend fun TargetHarness.copy(from: MergePage = incoming): Copy {
        val existing = writer.readExisting(key).ok()
        val outcome = mergePage(existing, from, policy)
        val merged = when (outcome) {
            is MergeOutcome.New -> outcome.page
            is MergeOutcome.Merged -> outcome.page
            MergeOutcome.Unchanged -> return Copy(outcome, null)
        }
        return Copy(outcome, writer.write(key, merged).ok())
    }

    private fun MergeOutcome.page(): MergePage = when (this) {
        is MergeOutcome.New -> page
        is MergeOutcome.Merged -> page
        MergeOutcome.Unchanged -> error("no page")
    }

    private fun flat(blocks: List<MergeBlock>): List<MergeBlock> = blocks.flatMap { listOf(it) + flat(it.children) }

    @Test
    fun newPageIsCreatedWithRemappedUuidsAndSrcIds() = runBlocking {
        val h = harness()
        assertNull(h.writer.readExisting(key).ok())

        val copy = h.copy()

        assertIs<MergeOutcome.New>(copy.outcome)
        assertIs<WriteOutcome.Created>(copy.written)
        val snap = assertNotNull(h.snapshot(key))
        assertEquals(copy.outcome.page().blocks, snap.blocks)
        val s1 = snap.blocks[0]
        assertEquals(uuid("s1"), s1.uuid)
        assertEquals("src:s1", s1.properties[MergePropertyKeys.SRC_ID])
        assertEquals("alpha see ((${uuid("s2")}))", s1.content)
        assertEquals(uuid("s2"), snap.blocks[1].uuid)
        assertNull(snap.blocks[2].uuid)
    }

    @Test
    fun refsStillResolveAfterReload() = runBlocking {
        val h = harness()
        h.copy()

        val snap = assertNotNull(h.snapshot(key))

        val ref = Regex("\\(\\(([^()]+)\\)\\)").find(snap.blocks[0].content)!!.groupValues[1]
        assertTrue(flat(snap.blocks).any { it.uuid == ref }, "dangling ref $ref")
    }

    @Test
    fun mergeIntoExistingKeepsExistingBlocksAndAppendsNewOnes() = runBlocking {
        val h = harness()
        h.seed(key, "- local one\n- local two")
        val before = h.blockUuids(key)
        val from = MergePage("Target", blocks = listOf(MergeBlock("s1", "local one"), MergeBlock("s2", "fresh")))

        val copy = h.copy(from)

        val merged = assertIs<MergeOutcome.Merged>(copy.outcome)
        assertEquals(1, merged.added)
        assertEquals(1, assertIs<WriteOutcome.Updated>(copy.written).insertedBlocks)
        val snap = assertNotNull(h.snapshot(key))
        assertEquals(merged.page.blocks, snap.blocks)
        assertEquals(listOf("local one", "local two", "fresh"), snap.blocks.map { it.content })
        assertEquals(before, h.blockUuids(key).take(2), "existing blocks must keep their uuids")
    }

    @Test
    fun repeatCopyIsUnchangedAndWritesNothing() = runBlocking {
        val h = harness()
        val first = h.copy()
        val hash = h.writer.fileHash(key).ok()

        val second = h.copy()

        assertEquals(MergeOutcome.Unchanged, second.outcome)
        assertEquals(WriteOutcome.Unchanged, h.writer.write(key, first.outcome.page()).ok())
        assertEquals(hash, h.writer.fileHash(key).ok())
    }

    @Test
    fun differingBlockWithSameUuidBecomesAConflictSibling() = runBlocking {
        val h = harness()
        h.seed(key, "- target version\n  id:: s1\n- other")
        val from = MergePage("Target", blocks = listOf(MergeBlock("s1", "source version")))

        val copy = h.copy(from)

        val merged = assertIs<MergeOutcome.Merged>(copy.outcome)
        assertEquals(1, merged.conflicts.size)
        assertEquals(0, merged.added)
        val snap = assertNotNull(h.snapshot(key))
        assertEquals(merged.page.blocks, snap.blocks)
        val sibling = snap.blocks.single { it.properties[MergePropertyKeys.CONFLICT] == "true" }
        assertEquals("source version", sibling.content)
        assertEquals("src:s1", sibling.properties[MergePropertyKeys.SRC_ID])
        assertEquals("target version", snap.blocks.first { it.uuid == "s1" }.content)
    }

    @Test
    fun removeBlocksTakesOnlyBlocksWhoseHashStillMatches() = runBlocking {
        val h = harness()
        h.copy()
        val before = assertNotNull(h.snapshot(key))
        val s2 = before.blocks[1]

        val report = h.writer.removeBlocks(key, setOf(s2.uuid!!), mapOf(s2.uuid!! to BlockContentHash.of(s2))).ok()

        assertEquals(setOf(s2.uuid!!), report.removed)
        val after = assertNotNull(h.snapshot(key))
        assertEquals(listOf(before.blocks[0], before.blocks[2]), after.blocks)
    }

    @Test
    fun removeBlocksSkipsEditedAndReportsMissing() = runBlocking {
        val h = harness()
        h.copy()
        val before = assertNotNull(h.snapshot(key))
        val s2 = before.blocks[1].uuid!!

        val report = h.writer.removeBlocks(key, setOf(s2, "gone"), mapOf(s2 to "not-the-hash")).ok()

        assertEquals(emptySet(), report.removed)
        assertEquals(setOf(s2), report.skippedEdited)
        assertEquals(setOf("gone"), report.missing)
        assertEquals(before.blocks, assertNotNull(h.snapshot(key)).blocks)
    }

    @Test
    fun deletePageFileIsHashGated() = runBlocking {
        val h = harness()
        h.copy()
        val hash = assertNotNull(h.writer.fileHash(key).ok())

        val refused = h.writer.deletePageFile(key, "stale-hash")

        assertIs<DomainError.ConflictError.ConcurrentWrite>((refused as Either.Left).value)
        assertEquals(hash, h.writer.fileHash(key).ok())
        h.writer.deletePageFile(key, hash).ok()
        assertNull(h.writer.fileHash(key).ok())
        assertNull(h.writer.readExisting(key).ok())
        h.writer.deletePageFile(key, hash).ok()
    }

    @Test
    fun unlabeledTargetBlocksKeepTheirUuidsWhenLabeledOnesAreCopiedIn() = runBlocking {
        val h = harness()
        h.seed(key, "- a\n- b")
        val before = h.blockUuids(key)
        val from = MergePage("Target", blocks = listOf(MergeBlock("s1", "a"), MergeBlock("s2", "fresh"), MergeBlock("s3", "b")))

        val copy = h.copy(from)

        assertEquals(1, assertIs<MergeOutcome.Merged>(copy.outcome).added)
        val snap = assertNotNull(h.snapshot(key))
        assertEquals(listOf("a", "b", "fresh"), snap.blocks.map { it.content })
        assertNull(snap.blocks[0].uuid)
        assertNull(snap.blocks[1].uuid)
        assertEquals(before, h.blockUuids(key).take(2))
    }

    @Test
    fun editedCopyIsReCopiedAsOneConflictSiblingThenSettles() = runBlocking {
        val h = harness()
        h.copy()
        h.editContent(key, "beta", "beta edited")

        val second = h.copy()

        val merged = assertIs<MergeOutcome.Merged>(second.outcome)
        assertEquals(1, merged.conflicts.size)
        val snap = assertNotNull(h.snapshot(key))
        assertEquals(merged.page.blocks, snap.blocks)
        assertEquals(1, snap.blocks.count { it.properties[MergePropertyKeys.CONFLICT] == "true" })
        assertTrue(snap.blocks.any { it.content == "beta edited" })
        assertEquals(MergeOutcome.Unchanged, h.copy().outcome)
    }

    @Test
    fun unloadedStubPageKeepsEveryExistingBlock() = runBlocking {
        val h = harness()
        h.seedUnloaded(key, (1..3).joinToString("\n") { "- keep me $it" })

        val copy = h.copy()

        assertIs<WriteOutcome.Updated>(copy.written)
        val contents = assertNotNull(h.snapshot(key)).blocks.map { it.content }
        assertEquals(listOf("keep me 1", "keep me 2", "keep me 3"), contents.take(3))
        assertTrue("alpha see" in contents[3].substringBefore("(("))
        assertEquals(6, contents.size)
    }

    @Test
    fun unloadedLargeStubPageKeepsEveryExistingBlock() = runBlocking {
        val h = harness()
        h.seedUnloaded(key, (1..80).joinToString("\n") { "- keep me $it\n  - child $it" })

        h.copy()

        val contents = flat(assertNotNull(h.snapshot(key)).blocks).map { it.content }
        assertEquals((1..80).flatMap { listOf("keep me $it", "child $it") }, contents.take(160))
    }

    @Test
    fun blockMarkupSurvivesTheWriteAndTheDbMatchesTheFile() = runBlocking {
        val h = harness()
        h.seed(key, "- local")
        val from = MergePage(
            "Target",
            blocks = listOf(
                MergeBlock("s1", "## Heading"),
                MergeBlock("s2", "TODO t\nSCHEDULED: <2026-01-01 Thu>"),
                MergeBlock("s3", "DOING x\n:LOGBOOK:\nCLOCK: [2026-01-01 Thu 10:00]--[2026-01-01 Thu 11:00] =>  01:00:00\n:END:"),
                MergeBlock("s4", "| a | b |\n|---|---|\n| 1 | 2 |"),
                MergeBlock("s5", "```kotlin\nfun a() {\n  x()\n}\n```"),
            ),
        )

        val copy = h.copy(from)

        val merged = assertIs<MergeOutcome.Merged>(copy.outcome)
        assertEquals(5, merged.added)
        assertEquals(merged.page.blocks, assertNotNull(h.snapshot(key)).blocks)
        assertEquals(MergeOutcome.Unchanged, h.copy(from).outcome)
    }

    @Test
    fun lossyBlockIsRefusedAndNothingIsWritten() = runBlocking {
        val h = harness()
        h.seed(key, "- local")
        val before = h.writer.fileHash(key).ok()
        val from = MergePage("Target", blocks = listOf(MergeBlock("s1", "intro\nkey:: value-looking text")))
        val outcome = mergePage(h.writer.readExisting(key).ok(), from, policy)

        val result = h.writer.write(key, (outcome as MergeOutcome.Merged).page)

        assertIs<DomainError.MergeError.WriteRefused>((result as Either.Left).value)
        assertEquals(before, h.writer.fileHash(key).ok())
    }
}
