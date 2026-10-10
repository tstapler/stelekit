package dev.stapler.stelekit.merge

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoundTripGuardTest {
    private val path = MergeFixtures.PATH
    private val root = SpliceRequest(listOf(BlockInsertion(emptyList(), MergeBlock("44444444-4444-4444-4444-444444444444", "new"))))

    private fun probe(text: String) = RoundTripGuard.probe(text, path, false)

    @Test
    fun cleanFixturesPass() {
        listOf(
            MergeFixtures.REAL_CRLF_CLEAN, MergeFixtures.UNLABELED_FLAT, MergeFixtures.UNLABELED_NESTED,
            MergeFixtures.MIXED_LABELED, MergeFixtures.SPACE_INDENTED, "",
        ).forEach { assertEquals(null, probe(it).leftOrNull()?.message, "should pass: ${it.take(30)}") }
    }

    @Test
    fun fenceWithDashLinesSwallowsTheAppendedBulletAndIsRefused() {
        val failure = RoundTripGuard.splice(MergeFixtures.FENCE_WITH_DASH_LINES, root, path, false).leftOrNull()
        assertIs<NotRoundTrippable.AppendAbsorbed>(failure)
        assertIs<NotRoundTrippable.AppendAbsorbed>(probe(MergeFixtures.FENCE_WITH_DASH_LINES).leftOrNull())
    }

    @Test
    fun theFullCrlfFixtureWithDashLinesInsideFenceAndQuoteIsRefused() {
        assertIs<NotRoundTrippable>(probe(MergeFixtures.REAL_CRLF_TAB).leftOrNull())
    }

    @Test
    fun crlfFileWithIdPropertyIsRefusedBecauseTheParserKeepsTheCarriageReturnInTheValue() {
        val text = "- a\r\n  id:: 11111111-1111-1111-1111-111111111111"
        assertIs<NotRoundTrippable.ExistingContentChanged>(RoundTripGuard.splice(text, root, path, false).leftOrNull())
    }

    @Test
    fun guardedSpliceReturnsTheVerifiedText() {
        val ok = assertIs<Either.Right<SpliceResult>>(RoundTripGuard.splice(MergeFixtures.MIXED_LABELED, root, path, false)).value
        assertEquals(4, ok.text.lines().count { it.startsWith("- ") })
        assertEquals(1, ok.insertedBlocks)
    }

    @Test
    fun unresolvableParentIsTyped() {
        val bad = SpliceRequest(listOf(BlockInsertion(listOf(9), MergeBlock(null, "x"))))
        assertEquals(NotRoundTrippable.UnresolvableParent(listOf(9)), RoundTripGuard.splice(MergeFixtures.UNLABELED_FLAT, bad, path, false).leftOrNull())
    }

    @Test
    fun probeIgnoresFilesWithNoBulletToNestUnder() {
        assertTrue(probe("alias:: x\n").isRight())
    }
}
