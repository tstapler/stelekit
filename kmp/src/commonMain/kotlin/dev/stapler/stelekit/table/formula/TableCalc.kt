package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.TableEdit

/**
 * Computed values for one [TableDoc] (ADR-002). Plain cells are read from the doc on demand; only formula
 * cells hold a cached value. [update] re-evaluates just the changed cells and their transitive dependents.
 * Build with [orNull] (or the constructor) only at or below [CELL_CAP]; above it formulas are off.
 */
class TableCalc(
    doc: TableDoc,
    private val registry: FunctionRegistry = FunctionRegistry.default,
    private val logger: Logger = Logger("TableCalc"),
) {
    private var doc: TableDoc = doc
    private val evaluator = FormulaEvaluator(registry)
    private var graph = DependencyGraph()
    private val exprs = HashMap<CellAddress, Expr>()
    private val failures = HashMap<CellAddress, CellError>()
    private val values = HashMap<CellAddress, CellValue>()

    /** Number of formula cells evaluated so far (cycle members are marked, not evaluated). */
    var evaluationCount: Int = 0
        private set

    private val resolver = object : CellResolver {
        override val rowCount: Int get() = this@TableCalc.doc.rowCount
        override val colCount: Int get() = this@TableCalc.doc.columnCount
        override fun valueAt(col: Int, row: Int): CellValue = this@TableCalc.valueAt(CellAddress(row, col))
    }

    init {
        rebuild(doc)
    }

    /** Value of an in-bounds cell; out-of-bounds addresses are [CellValue.Blank]. */
    fun valueAt(address: CellAddress): CellValue {
        values[address]?.let { return it }
        val text = doc.cell(address)?.value ?: return CellValue.Blank
        return if (isFormula(text)) CellValue.Blank else CellValue.fromRaw(text)
    }

    /** The cell's number when it evaluates to one (text, blanks and errors are not numbers). */
    fun numberAt(address: CellAddress): Double? = (valueAt(address) as? CellValue.Num)?.value

    /** What the grid shows for the cell: formatted number, text, empty, or the error token. */
    fun displayAt(address: CellAddress): String = when (val v = valueAt(address)) {
        is CellValue.Num -> NumberDisplay.format(v.value)
        is CellValue.Text -> v.value
        is CellValue.Blank -> ""
        is CellValue.Err -> v.error.display
    }

    /** The stored text (formula source for formula cells), for the "as formulas" copy. */
    fun rawAt(address: CellAddress): String = doc.cell(address)?.value ?: ""

    /** Recomputes after cell edits: [newDoc] has the same shape as before and only [changed] cells differ. */
    fun update(newDoc: TableDoc, changed: Collection<CellAddress>) {
        if (newDoc.rowCount != doc.rowCount || newDoc.columnCount != doc.columnCount) {
            rebuild(newDoc)
            return
        }
        doc = newDoc
        for (cell in changed) register(cell)
        run(graph.plan(changed))
    }

    /** Recomputes after [edit] produced [newDoc]; only a single-cell edit is incremental, the rest rebuild. */
    fun update(newDoc: TableDoc, edit: TableEdit) {
        if (edit is TableEdit.SetCell) update(newDoc, listOf(edit.address)) else rebuild(newDoc)
    }

    /** Full recompute for a structural edit or a whole new document. */
    fun rebuild(newDoc: TableDoc) {
        doc = newDoc
        graph = DependencyGraph()
        exprs.clear()
        failures.clear()
        values.clear()
        for (row in 1..doc.rowCount) for (col in 1..doc.columnCount) register(CellAddress(row, col))
        run(graph.planAll())
    }

    private fun isFormula(text: String) = FormulaParser.isFormulaShaped(text)

    private fun register(cell: CellAddress) {
        graph.removeFormula(cell)
        exprs.remove(cell)
        failures.remove(cell)
        values.remove(cell)
        val text = doc.cell(cell)?.value ?: return
        if (!isFormula(text)) return
        when (val parsed = FormulaParser.parse(text)) {
            is FormulaParse.Ok -> {
                exprs[cell] = parsed.expr
                graph.setFormula(cell, parsed.expr.precedentRanges())
            }
            is FormulaParse.Failure -> {
                failures[cell] = parsed.error.toCellError()
                graph.setFormula(cell, emptyList())
            }
        }
    }

    private fun run(plan: EvalPlan) {
        for (cell in plan.cyclic) values[cell] = CellValue.Err(CellError.Cycle)
        for (cell in plan.order) {
            evaluationCount++
            values[cell] = failures[cell]?.let { CellValue.Err(it) } ?: guarded(cell)
        }
    }

    private fun guarded(cell: CellAddress): CellValue {
        val expr = exprs[cell] ?: return CellValue.Err(CellError.Parse)
        return try {
            evaluator.evaluate(expr, resolver)
        } catch (e: Throwable) {
            logger.error("formula evaluation failed at $cell", e)
            CellValue.Err(if (e is Error) CellError.Limit else CellError.Value)
        }
    }

    companion object {
        /** Tables above this many cells get no formulas (Story 2.2). */
        const val CELL_CAP = 20_000

        fun orNull(doc: TableDoc, registry: FunctionRegistry = FunctionRegistry.default): TableCalc? =
            if (doc.rowCount.toLong() * doc.columnCount > CELL_CAP) null else TableCalc(doc, registry)
    }
}
