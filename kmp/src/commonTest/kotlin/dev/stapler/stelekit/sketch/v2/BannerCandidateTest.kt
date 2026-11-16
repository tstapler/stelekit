package dev.stapler.stelekit.sketch.v2

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRejection
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6.4, Task 3.6.4a: every figure and string comes from the parsed chrome-table fixture, never typed here. */
class BannerCandidateTest {
    private val table = ChromeTableParser.parseFixture()

    private open inner class TableStrings : BannerStringSource {
        private fun row(id: BannerRowId) = table.banners.single { it.id == id.name }
        override fun summary(row: BannerRowId) = row(row).summary
        override fun shortTitle(row: BannerRowId) = row(row).shortTitle
        override fun primaryLabel(row: BannerRowId) = row(row).primaryLabel ?: ""
        override fun moreItems(row: BannerRowId) = row(row).moreItems
    }

    @Test
    fun rowIds_should_MatchTheBannerTableInRankOrder_When_ParsedFromFixture() {
        assertEquals(table.banners.map { it.id }, BannerRowId.entries.map { it.name })
        assertEquals(table.banners.map { it.tier }, BannerRowId.entries.map { it.tier.name })
        assertEquals(table.banners.map { it.shortTitle == null }, BannerRowId.entries.map { !it.shortTitled })
    }

    @Test
    fun port_should_RetainEveryRow_When_AllFourteenRowsAreRaised() {
        val port = InMemoryDiagnosticsPort(TableStrings())
        for (row in BannerRowId.entries) {
            assertTrue(port.raise(row, details = "ids: c1").isRight(), "$row refused")
        }
        assertEquals(BannerRowId.entries, port.diagnostics.value.entries.map { it.row })
        assertTrue(port.diagnostics.value.hasBlocking)
    }

    @Test
    fun port_should_ReplaceTheEarlierEntry_When_SameRowIsRaisedAgainAndDropOnResolve() {
        val port = InMemoryDiagnosticsPort(TableStrings())
        port.raise(BannerRowId.SaveFailed, "first")
        port.raise(BannerRowId.SaveFailed, "second")
        assertEquals(listOf("second"), port.diagnostics.value.entries.map { it.details })
        port.resolve(BannerRowId.SaveFailed)
        assertTrue(port.diagnostics.value.entries.isEmpty())
    }

    @Test
    fun only_BlockingRowsShouldAnnounceAssertively_When_TiersAreChecked() {
        val strings = TableStrings()
        for (row in BannerRowId.entries) {
            val candidate = BannerCandidate.of(row, strings).getOrElse { error("$row refused: $it") }
            assertEquals(row.name in setOf("SaveFailed", "SaveConflict"), candidate.announcesAssertively, row.name)
        }
    }

    @Test
    fun of_should_Refuse_When_SummaryWrapsToMoreLinesThanTheRow() {
        val long = object : TableStrings() {
            override fun summary(row: BannerRowId) = "word ".repeat(BannerTextBudget.summaryCharsPerLine).trim()
        }
        val result = BannerCandidate.of(BannerRowId.SaveFailed, long)
        assertIs<BannerRejection.SummaryOverBudget>(result.leftOrNull())
    }

    @Test
    fun of_should_Refuse_When_AWordIsWiderThanALine() {
        val wide = object : TableStrings() {
            override fun shortTitle(row: BannerRowId) = "x".repeat(BannerTextBudget.shortTitleCharsPerLine + 1)
        }
        val result = BannerCandidate.of(BannerRowId.SaveFailed, wide)
        assertEquals(BannerRejection.ShortTitleOverBudget(BannerRowId.SaveFailed, null), result.leftOrNull())
    }

    @Test
    fun of_should_Refuse_When_SummaryHoldsAPlaceholder() {
        val placeholder = object : TableStrings() {
            override fun summary(row: BannerRowId) = "Removed {n:3}."
        }
        assertIs<BannerRejection.PlaceholderInSummary>(BannerCandidate.of(BannerRowId.SaveFailed, placeholder).leftOrNull())
    }

    @Test
    fun of_should_Refuse_When_LabelBreaksTheLabelRule() {
        val tooLong = object : TableStrings() {
            override fun primaryLabel(row: BannerRowId) = "x".repeat(BannerTextBudget.labelChars + 1)
        }
        val threeWords = object : TableStrings() {
            override fun primaryLabel(row: BannerRowId) = "a b c"
        }
        val longWord = object : TableStrings() {
            override fun primaryLabel(row: BannerRowId) = "x".repeat(BannerTextBudget.labelWordChars + 1)
        }
        for (strings in listOf(tooLong, threeWords, longWord)) {
            assertIs<BannerRejection.LabelBreaksRule>(BannerCandidate.of(BannerRowId.SaveFailed, strings).leftOrNull())
        }
    }

    @Test
    fun of_should_AcceptNoShortTitle_When_RowIsTheConsentRow() {
        val candidate = BannerCandidate.of(BannerRowId.UpgradeChip, TableStrings()).getOrElse { error("refused: $it") }
        assertNull(candidate.shortTitle)
    }

    @Test
    fun budget_should_EqualTheBudgetTheChromeTableComputes_When_ParamsAreRead() {
        val p = table
        fun perLine(scale: Double): Int {
            val available = 320 - 2 * p.param("gutter") - p.param("bannerPrimary") - p.param("bannerMore") -
                (p.param("bannerIcon") + 3 * p.param("bannerGap"))
            return (available / (p.param("bannerCharDp") * scale)).toInt()
        }
        assertEquals(perLine(1.0), BannerTextBudget.summaryCharsPerLine)
        assertEquals(perLine(2.0), BannerTextBudget.shortTitleCharsPerLine)
        assertEquals(p.param("bannerLines"), BannerTextBudget.BANNER_LINES)
        assertEquals((p.param("bannerPrimary") - 2 * p.param("btnPad")) / p.param("bannerCharDp"), BannerTextBudget.labelChars)
        assertEquals(p.param("bannerCharDp"), BannerTextBudget.CHAR_DP)
        assertEquals(p.param("gutter"), BannerTextBudget.GUTTER_DP)
        assertEquals(p.param("bannerMore"), BannerTextBudget.MORE_DP)
        assertEquals(p.param("bannerIcon"), BannerTextBudget.ICON_DP)
        assertEquals(p.param("bannerGap"), BannerTextBudget.GAP_DP)
        assertEquals(p.param("btnPad"), BannerTextBudget.BUTTON_PAD_DP)
        assertEquals(p.param("minWindowWidth"), BannerTextBudget.NARROWEST_WIDTH_DP)
    }
}
