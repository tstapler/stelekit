// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfirmPolicyTest {
    private fun measure(table: String = "pages", rows: Long? = 100, changes: Long, total: Long = changes, others: List<TableDelta> = emptyList()) =
        DryRunMeasurement(table, rows, changes, total, others)

    private fun confirm(m: DryRunMeasurement?, nondeterministic: Boolean = false) =
        assertIs<ConfirmDecision.Confirm>(ConfirmPolicy.decide(m, nondeterministic))

    @Test
    fun decide_should_BeStandard_When_FewRowsOfALargeTable() {
        val c = confirm(measure(rows = 1_000, changes = 7))
        assertEquals(ConfirmTier.Standard, c.tier)
        assertEquals(7, c.count)
        assertFalse(c.triggered)
    }

    @Test
    fun decide_should_RequireTypedCount_When_ThousandRowsOrHalfTheTable() {
        assertEquals(ConfirmTier.TypedCount(1_000), confirm(measure(rows = 50_000, changes = 1_000)).tier)
        assertEquals(ConfirmTier.Standard, confirm(measure(rows = 50_000, changes = 999)).tier)
        assertEquals(ConfirmTier.TypedCount(50), confirm(measure(rows = 100, changes = 50)).tier)
        assertEquals(ConfirmTier.Standard, confirm(measure(rows = 100, changes = 49)).tier)
    }

    @Test
    fun decide_should_NotEscalateTinyTables_When_FewerThanTenRowsChange() {
        assertEquals(ConfirmTier.Standard, confirm(measure(rows = 2, changes = 2)).tier)
        assertEquals(ConfirmTier.Standard, confirm(measure(rows = 18, changes = 9)).tier)
        assertEquals(ConfirmTier.TypedCount(10), confirm(measure(rows = 20, changes = 10)).tier)
    }

    @Test
    fun decide_should_SkipShareRule_When_TableSizeUnknown() {
        assertEquals(ConfirmTier.Standard, confirm(measure(rows = null, changes = 500)).tier)
        assertEquals(ConfirmTier.TypedCount(1_000), confirm(measure(rows = null, changes = 1_000)).tier)
    }

    @Test
    fun decide_should_Refuse_When_NoCountWasObtained() {
        val refused = assertIs<ConfirmDecision.Refuse>(ConfirmPolicy.decide(null, nondeterministic = false))
        assertEquals("Can't count the rows this would change, so it won't run. Nothing was changed.", refused.reason)
    }

    @Test
    fun decide_should_RequireTypedTotalAndListTables_When_TriggersOrCascadesChangeOtherTables() {
        val others = listOf(TableDelta("blocks", -30), TableDelta("properties", -4))
        val c = confirm(measure(table = "pages", rows = 1_000, changes = 2, total = 36, others = others))
        assertEquals(ConfirmTier.TypedCount(36), c.tier)
        assertEquals(36, c.count)
        assertTrue(c.triggered)
        assertEquals(others, c.tables)
    }

    @Test
    fun decide_should_TreatFtsMaintenanceAsOverhead_When_IndexedTableAlone() {
        val c = confirm(measure(table = "blocks", rows = 150_000, changes = 7, total = 21))
        assertEquals(ConfirmTier.Standard, c.tier)
        assertEquals(7, c.count)
    }

    @Test
    fun decide_should_Escalate_When_UnexplainedExtraChangesOnTableWithoutIndex() {
        val c = confirm(measure(table = "properties", rows = 1_000, changes = 3, total = 5))
        assertEquals(ConfirmTier.TypedCount(5), c.tier)
        assertTrue(c.triggered)
    }

    @Test
    fun decide_should_RequireTypedCount_When_StatementIsNondeterministic() {
        assertEquals(ConfirmTier.TypedCount(3), confirm(measure(rows = 1_000, changes = 3), nondeterministic = true).tier)
    }

    @Test
    fun isStale_should_RollBack_When_StandardConfirmNowNeedsTypedCount() {
        val c = confirm(measure(rows = 1_000, changes = 7))
        assertFalse(ConfirmPolicy.isStale(c, "pages", 1_000, 7, 7))
        assertFalse(ConfirmPolicy.isStale(c, "pages", 1_000, 12, 12), "a different count that still needs no typing is fine")
        assertTrue(ConfirmPolicy.isStale(c, "pages", 1_000, 1_000, 1_000))
        assertFalse(ConfirmPolicy.isStale(c, "pages", 1_000, 7, 9), "pages carries an FTS index, so extra changes are index maintenance")
        val plain = confirm(measure(table = "properties", rows = 1_000, changes = 7))
        assertTrue(ConfirmPolicy.isStale(plain, "properties", 1_000, 7, 9), "a trigger now reaches other rows")
    }

    @Test
    fun isStale_should_RollBack_When_TypedCountDiffers() {
        val c = confirm(measure(rows = 100, changes = 60))
        assertFalse(ConfirmPolicy.isStale(c, "pages", 100, 60, 60))
        assertTrue(ConfirmPolicy.isStale(c, "pages", 100, 61, 61))
        assertEquals(61, ConfirmPolicy.actualCount(c, 61, 61))
    }

    @Test
    fun isStale_should_CompareTotals_When_ConfirmWasTriggered() {
        val c = confirm(measure(table = "pages", rows = 100, changes = 2, total = 36, others = listOf(TableDelta("blocks", -34))))
        assertFalse(ConfirmPolicy.isStale(c, "pages", 100, 2, 36))
        assertTrue(ConfirmPolicy.isStale(c, "pages", 100, 2, 40))
        assertEquals(40, ConfirmPolicy.actualCount(c, 2, 40))
    }

    @Test
    fun tierFor_should_BeMonotonic_When_ChangesGrow() = runTest {
        checkAll(Arb.long(0L..5_000L), Arb.long(0L..5_000L), Arb.long(1L..10_000L)) { a, b, rows ->
            val (small, large) = if (a <= b) a to b else b to a
            val smallTyped = ConfirmPolicy.tierFor(small, rows) is ConfirmTier.TypedCount
            val largeTyped = ConfirmPolicy.tierFor(large, rows) is ConfirmTier.TypedCount
            assertTrue(!smallTyped || largeTyped, "TypedCount at $small of $rows but Standard at $large")
        }
    }

    @Test
    fun satisfies_should_RequireExactTrimmedCount_When_TypedCountTier() {
        val tier = ConfirmTier.TypedCount(120)
        assertTrue(ConfirmAnswer.Proceed(" 120 ").satisfies(tier))
        assertFalse(ConfirmAnswer.Proceed("12").satisfies(tier))
        assertFalse(ConfirmAnswer.Proceed(null).satisfies(tier))
        assertFalse(ConfirmAnswer.Cancel.satisfies(ConfirmTier.Standard))
        assertTrue(ConfirmAnswer.Proceed().satisfies(ConfirmTier.Standard))
    }
}
