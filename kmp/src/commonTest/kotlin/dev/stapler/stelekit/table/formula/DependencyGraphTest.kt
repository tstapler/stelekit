package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyGraphTest {
    private fun a(text: String): CellAddress {
        val col = text.takeWhile { it.isLetter() }.fold(0) { acc, c -> acc * 26 + (c.uppercaseChar() - 'A' + 1) }
        return CellAddress(text.dropWhile { it.isLetter() }.toInt(), col)
    }

    private fun graph(vararg formulas: Pair<String, String>): DependencyGraph = DependencyGraph.build(
        formulas.associate { (cell, text) -> a(cell) to (FormulaParser.parse(text) as FormulaParse.Ok).expr },
    )

    private fun names(cells: Collection<CellAddress>) = cells.map { it.toString() }

    @Test
    fun precedents_should_ListCellsAndRangesAsRectangles_When_ExtractedFromExpr() {
        val expr = (FormulaParser.parse("=SUM(B2:B5)+C1*IF(A1>0,D4,\$E\$5)") as FormulaParse.Ok).expr
        val ranges = expr.precedentRanges().map { "${it.topLeft}:${it.bottomRight}" }.toSet()
        assertEquals(setOf("B2:B5", "C1:C1", "A1:A1", "D4:D4", "E5:E5"), ranges)
        assertEquals(emptyList(), (FormulaParser.parse("=#REF!*2") as FormulaParse.Ok).expr.precedentRanges())
        assertEquals(setOf("A1:B2"), (FormulaParser.parse("=SUM(B2:A1)") as FormulaParse.Ok).expr.precedentRanges().map { "${it.topLeft}:${it.bottomRight}" }.toSet())
    }

    @Test
    fun kahn_should_OrderPrecedentsFirst_When_ChainAndRangeDependents() {
        val g = graph("C1" to "=SUM(B2:B5)", "D1" to "=C1*2", "B3" to "=B2+1", "E1" to "=D1+C1")
        val plan = g.plan(listOf(a("B2")))
        assertTrue(plan.cyclic.isEmpty())
        val order = names(plan.order)
        assertEquals(setOf("B3", "C1", "D1", "E1"), order.toSet())
        assertTrue(order.indexOf("B3") < order.indexOf("C1"))
        assertTrue(order.indexOf("C1") < order.indexOf("D1"))
        assertTrue(order.indexOf("D1") < order.indexOf("E1"))
        assertEquals(setOf(a("C1")), g.dependentsOf(a("B4")))
        assertEquals(setOf(a("C1")), g.dependentsOf(a("B3")))
    }

    @Test
    fun plan_should_TouchOnlyTransitiveDependents_When_OneInputChanges() {
        val formulas = (1..12).map { c -> "${CellAddress.columnLabel(c)}201" to "=SUM(${CellAddress.columnLabel(c)}2:${CellAddress.columnLabel(c)}200)" }
        val g = graph(*formulas.toTypedArray())
        assertEquals(1, g.plan(listOf(a("C57"))).size)
        assertEquals(12, g.planAll().size)
        assertEquals(1, g.plan(listOf(a("C201"))).size) // editing the formula cell itself re-evaluates it once
        assertEquals(0, g.plan(listOf(a("Z9"))).size)
    }

    @Test
    fun kahn_should_MarkEveryCycleMemberAndRangeIncludingSelf_When_B5SumsB2toB5() {
        val self = graph("B5" to "=SUM(B2:B5)")
        val selfPlan = self.planAll()
        assertEquals(setOf(a("B5")), selfPlan.cyclic)
        assertTrue(selfPlan.order.isEmpty())

        val pair = graph("B2" to "=C2+1", "C2" to "=B2+1", "D2" to "=C2*2", "A2" to "=1+1", "E2" to "=D2+A2")
        val plan = pair.planAll()
        assertEquals(setOf(a("B2"), a("C2")), plan.cyclic)
        // Downstream of a cycle is still ordered after it and evaluated (it reads #CYCLE!); upstream cells are unaffected.
        assertEquals(listOf("A2", "D2", "E2"), names(plan.order))
        assertEquals(listOf("B2", "C2", "B2"), names(pair.cyclePath(a("B2"), plan.cyclic)))
        assertEquals(listOf("B5", "B5"), names(self.cyclePath(a("B5"), selfPlan.cyclic)))
    }

    @Test
    fun kahn_should_ClearCycle_When_MemberBecomesConstant() {
        val g = graph("B2" to "=C2+1", "C2" to "=B2+1")
        assertEquals(setOf(a("B2"), a("C2")), g.planAll().cyclic)
        g.removeFormula(a("C2")) // C2 set to 4
        val plan = g.plan(listOf(a("C2")))
        assertTrue(plan.cyclic.isEmpty())
        assertEquals(listOf("B2"), names(plan.order))
    }

    @Test
    fun setFormula_should_PatchDependents_When_FormulaReplacedOrRemoved() {
        val g = graph("C1" to "=A1+B1")
        assertEquals(setOf(a("C1")), g.dependentsOf(a("A1")))
        g.setFormula(a("C1"), (FormulaParser.parse("=B1*2") as FormulaParse.Ok).expr.precedentRanges())
        assertTrue(g.dependentsOf(a("A1")).isEmpty())
        assertEquals(setOf(a("C1")), g.dependentsOf(a("B1")))
        g.removeFormula(a("C1"))
        assertTrue(g.dependentsOf(a("B1")).isEmpty())
        assertTrue(g.formulaCells.isEmpty())
    }

    @Test
    fun plan_should_HandleLongChainsIteratively_When_20000Formulas() {
        val formulas = (2..20_000).map { r -> "A$r" to "=A${r - 1}+1" }
        val g = graph(*formulas.toTypedArray())
        val plan = g.plan(listOf(a("A1")))
        assertEquals(19_999, plan.order.size)
        assertEquals("A2", plan.order.first().toString())
        assertEquals("A20000", plan.order.last().toString())
        val cyclic = graph(*(formulas + ("A1" to "=A20000+1")).toTypedArray()).planAll()
        assertEquals(20_000, cyclic.cyclic.size)
    }

    @Test
    fun dependentsOf_should_ExamineOnlyRectanglesNearTheCell_When_ManyRowLocalRanges() {
        val n = 5_000
        val g = graph(*(2..n + 1).map { "C$it" to "=SUM(A$it:B$it)" }.toTypedArray())
        assertEquals(setOf(a("C2500")), g.dependentsOf(a("A2500")))
        assertEquals(emptySet(), g.dependentsOf(a("Z2500")))
        val plan = g.planAll()
        assertEquals(n, plan.order.size)
        assertTrue(plan.cyclic.isEmpty())
        assertEquals(n, g.plan((2..n + 1).map { a("A$it") }).size)
        // Scanning every rectangle per lookup costs n * n = 25M per pass here; an index touches ~log n nodes per lookup.
        assertTrue(g.rectChecks < 2_000_000L, "examined ${g.rectChecks} index nodes")
    }

    @Test
    fun planAll_should_OrderRunningTotalColumn_When_ManyPrefixSums() {
        val n = 1_500
        val g = graph(*(2..n + 1).map { "B$it" to "=SUM(A\$2:A$it)" }.toTypedArray(), "C2" to "=B2+B${n + 1}")
        val plan = g.planAll()
        assertEquals(n + 1, plan.order.size)
        assertTrue(plan.cyclic.isEmpty())
        assertEquals(n - 1, g.dependentsOf(a("A3")).size)
        assertEquals(setOf(a("B${n + 1}")), g.dependentsOf(a("A${n + 1}")))
    }

    @Test
    fun removeFormula_should_BeSubQuadratic_When_FiveThousandRangeOwnersAreRemoved() {
        val n = 5_000
        val g = graph(*(2..n + 1).map { "C$it" to "=SUM(A\$2:A$it)" }.toTypedArray())
        val before = indexOps
        for (i in 2..n + 1) {
            g.removeFormula(a("C$i"))
            if (i % 250 == 0) assertEquals(n + 1 - i, g.dependentsOf(a("A2")).size, "after removing through C$i")
        }
        assertTrue(g.dependentsOf(a("A2")).isEmpty())
        // A scan or full rebuild per removal costs n * n / 2 = 12.5M; lazy tombstones plus amortized rebuilds stay linear-ish.
        assertTrue(indexOps - before < 1_000_000L, "index work ${indexOps - before}")
    }

    @Test
    fun setFormula_should_NotRebuildWholeIndex_When_QueriedAfterEveryAdd() {
        val g = DependencyGraph()
        val before = indexOps
        val n = 3_000
        for (i in 2..n + 1) {
            g.setFormula(a("C$i"), (FormulaParser.parse("=SUM(A$i:A${i + 5})") as FormulaParse.Ok).expr.precedentRanges())
            assertEquals(minOf(i - 1, 6), g.dependentsOf(a("A$i")).size)
        }
        assertTrue(indexOps - before + g.rectChecks < 3_000_000L, "index work ${indexOps - before} plus ${g.rectChecks} checks")
    }
}
