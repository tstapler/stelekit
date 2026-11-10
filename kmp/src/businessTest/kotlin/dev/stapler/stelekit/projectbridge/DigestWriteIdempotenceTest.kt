// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.merge.ActiveTargetWriterContractTest.ActiveHarness
import dev.stapler.stelekit.merge.OwnedHash
import dev.stapler.stelekit.merge.OwnedMarker
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.merge.WriteRefusedReason
import dev.stapler.stelekit.model.GraphId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 5.4c against the real active writer: create, replace, no-op, and refusal. */
class DigestWriteIdempotenceTest {
    private val title = "Sample Digest"
    private val graph = "graph-a"
    private val check = SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones)

    private fun input(tablesText: String = ClosetFusion.tables): DigestInput {
        val result = fuseObservations(
            FusionInput(ClosetFusion.observations(tablesText = tablesText), ClosetFusion.constraints()), ClosetFusion.registry,
        ).fold({ error("fusion failed: $it") }, { it })
        return DigestInput("proj-sample", result, ClosetFusion.registry, listOf(check))
    }

    private fun changed() = input(ClosetFusion.tables.replace("**33 7/16\"**", "**33 3/8\"**"))

    private fun writerOver(h: ActiveHarness, hasWriter: Boolean = true) = DigestWriter(
        replace = { _, key, page -> h.writer.replaceOwnedPage(key, page, null) },
        readBack = { _, key -> h.writer.readExisting(key) },
        hasWriter = { hasWriter },
    )

    @Test
    fun createsThenReplacesTheOwnedPage() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            val w = writerOver(h)
            assertEquals(DigestWriteResult.Written(created = true), w.write(graph, title, input()))
            val first = checkNotNull(h.fileText(PageKey(title)))
            assertTrue(OwnedMarker.OWNED in first && OwnedMarker.HASH in first, first)

            assertEquals(DigestWriteResult.Written(created = false), w.write(graph, title, changed()))
            assertNotEquals(first, h.fileText(PageKey(title)))
        } finally {
            h.close()
        }
    }

    @Test
    fun runningTwiceWritesNothingTheSecondTime() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            val w = writerOver(h)
            w.write(graph, title, input())
            val before = h.fileText(PageKey(title))
            assertEquals(DigestWriteResult.Unchanged, w.write(graph, title, input()))
            assertEquals(before, h.fileText(PageKey(title)))
        } finally {
            h.close()
        }
    }

    @Test
    fun aUserEditedDigestPageIsRefusedAndKept() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            val w = writerOver(h)
            w.write(graph, title, input())
            val key = PageKey(title)
            val firstBody = checkNotNull(h.snapshot(key)).blocks.drop(1).first { !it.content.startsWith("#") }.content
            h.editContent(key, firstBody, "my own note")

            val result = w.write(graph, title, changed())
            assertIs<DigestWriteResult.Refused>(result)
            assertTrue("edited" in result.note, result.note)
            assertTrue("my own note" in checkNotNull(h.fileText(key)))
        } finally {
            h.close()
        }
    }

    @Test
    fun aPageTheAppDidNotCreateIsNeverReplaced() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            val key = PageKey(title)
            h.seed(key, "- my own page\n")
            val result = writerOver(h).write(graph, title, input())
            assertIs<DigestWriteResult.Refused>(result)
            assertEquals("- my own page\n", h.fileText(key))
        } finally {
            h.close()
        }
    }

    @Test
    fun noWriterOnThisPlatformIsUnavailable() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            assertEquals(DigestWriteResult.Unavailable, writerOver(h, hasWriter = false).write(graph, title, input()))
        } finally {
            h.close()
        }
    }

    @Test
    fun aWriteThatDoesNotReadBackAsWrittenFails() = runBlocking<Unit> {
        val w = DigestWriter(
            replace = { _, _, _ -> WriteOutcome.Created("p", "h").right() },
            readBack = { _, _ -> null.right() },
            hasWriter = { true },
        )
        assertIs<DigestWriteResult.Failed>(w.write(graph, title, input()))
    }

    @Test
    fun aThrowingWriterBecomesFailedNotACrash() = runBlocking<Unit> {
        val w = DigestWriter(replace = { _, _, _ -> error("boom") }, readBack = { _, _ -> null.right() }, hasWriter = { true })
        assertIs<DigestWriteResult.Failed>(w.write(graph, title, input()))
    }

    @Test
    fun outcomesAreClassifiedForTheJobsList() {
        val retry = DigestWriter.classify(DomainError.MergeError.Retryable("graph switching").left())
        assertEquals(DigestWriteResult.Deferred("graph switching"), retry)
        val conflicted = DigestWriter.classify(DomainError.MergeError.WriteRefused(WriteRefusedReason.Conflicted("p")).left())
        assertTrue("resolve the sync conflict" in conflicted.note)
        val notOwned = DigestWriter.classify(DomainError.MergeError.WriteRefused(WriteRefusedReason.NotOwned("p")).left())
        assertTrue("not app-generated" in notOwned.note)
        assertIs<DigestWriteResult.Failed>(DigestWriter.classify(DomainError.DatabaseError.WriteFailed("x").left()))
        assertEquals(DigestWriteResult.Unchanged, DigestWriter.classify(WriteOutcome.Unchanged.right()))
    }

    @Test
    fun builtPageCarriesTheMarkerAndAStableHashAndUuids() {
        val page = DigestWriter.buildPage(graph, title, input())
        val marker = checkNotNull(OwnedMarker.of(page))
        assertEquals(OwnedHash.of(page), marker.properties[OwnedMarker.HASH])
        assertEquals("proj-sample", OwnedMarker.slug(marker))
        assertEquals(page, DigestWriter.buildPage(graph, title, input()), "same input, same page")
        assertEquals(page.blocks.size, page.blocks.mapNotNull { it.uuid }.toSet().size)
        assertNotEquals(page.blocks[1].uuid, DigestWriter.buildPage("graph-b", title, input()).blocks[1].uuid)
        assertTrue(page.blocks.drop(1).none { it.content.startsWith("- ") })
        assertEquals(GraphId(graph).value, graph)
    }
}
