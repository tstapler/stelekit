package dev.stapler.stelekit.table.formula

import kotlin.math.abs
import kotlin.math.pow

/** Read-only view of the table the evaluator reads through; 1-based, row 1 is the header row. */
interface CellResolver {
    val rowCount: Int
    val colCount: Int

    /** Current value of an in-bounds cell (a formula cell returns its already computed value). */
    fun valueAt(col: Int, row: Int): CellValue
}

/** An evaluated function argument: a single value or the cells of a clamped range. */
sealed interface EvalArg {
    /** [fromRef] marks a single-cell reference, which aggregates treat like a one-cell range. */
    data class Scalar(val value: CellValue, val fromRef: Boolean = false) : EvalArg
    data class Block(val values: List<CellValue>) : EvalArg
}

fun interface FunctionImpl {
    fun call(args: List<EvalArg>): CellValue
}

/** Implementations for the allow-listed names (`IF` is lazy and handled by the evaluator itself). */
class FunctionRegistry(private val impls: Map<String, FunctionImpl>) {
    fun lookup(name: String): FunctionImpl? = impls[name]
    fun with(name: String, impl: FunctionImpl) = FunctionRegistry(impls + (name to impl))

    companion object {
        val default = FunctionRegistry(
            mapOf(
                "SUM" to FunctionImpl { aggregate(it) { v -> v.sum() } },
                "AVERAGE" to FunctionImpl { args ->
                    aggregate(args, emptyResult = CellValue.Err(CellError.DivZero)) { v -> v.sum() / v.size }
                },
                "MIN" to FunctionImpl { aggregate(it) { v -> v.min() } },
                "MAX" to FunctionImpl { aggregate(it) { v -> v.max() } },
                "COUNT" to FunctionImpl { count(it, numbersOnly = true) },
                "COUNTA" to FunctionImpl { count(it, numbersOnly = false) },
                "ROUND" to FunctionImpl { round(it) },
                "ABS" to FunctionImpl { args -> scalarNumber(args[0]).mapNum { abs(it) } },
            ),
        )

        /** Collects numbers; text in references is skipped, a text literal or any error is not. */
        private fun aggregate(
            args: List<EvalArg>,
            emptyResult: CellValue = CellValue.Num(0.0),
            reduce: (List<Double>) -> Double,
        ): CellValue {
            val nums = ArrayList<Double>()
            for (arg in args) {
                when (arg) {
                    is EvalArg.Block -> for (v in arg.values) when (v) {
                        is CellValue.Num -> nums.add(v.value)
                        is CellValue.Err -> return v
                        else -> Unit
                    }
                    is EvalArg.Scalar -> when (val v = arg.value) {
                        is CellValue.Num -> nums.add(v.value)
                        is CellValue.Err -> return v
                        is CellValue.Text -> if (!arg.fromRef) return CellValue.Err(CellError.Value)
                        CellValue.Blank -> Unit
                    }
                }
            }
            if (nums.isEmpty()) return emptyResult
            return finite(reduce(nums))
        }

        private fun count(args: List<EvalArg>, numbersOnly: Boolean): CellValue {
            var n = 0
            fun tally(v: CellValue) {
                if (if (numbersOnly) v is CellValue.Num else v !is CellValue.Blank) n++
            }
            for (arg in args) {
                when (arg) {
                    is EvalArg.Block -> arg.values.forEach(::tally)
                    is EvalArg.Scalar -> {
                        // An error from a computed expression propagates; one read from a cell is just counted.
                        if (arg.value is CellValue.Err && !arg.fromRef) return arg.value
                        tally(arg.value)
                    }
                }
            }
            return CellValue.Num(n.toDouble())
        }

        private fun round(args: List<EvalArg>): CellValue {
            val x = scalarNumber(args[0])
            if (x !is CellValue.Num) return x
            val d = if (args.size > 1) scalarNumber(args[1]) else CellValue.Num(0.0)
            if (d !is CellValue.Num) return d
            val digits = d.value.toInt().coerceIn(-1000, 1000) // truncates toward zero
            if (x.value == 0.0) return CellValue.Num(0.0)
            // Decimal rounding on the shortest digits (no binary scaling, so no overflow and no magnitude-dependent
            // nudge): first to the 15 significant digits a spreadsheet keeps (2.675 and 1.0049999999999999 behave like
            // 2.675 and 1.005), then half away from zero at [digits] decimals.
            val (all, pos) = NumberDisplay.shortestDigits(abs(x.value))
            val (sig, sigPos) = NumberDisplay.roundDigits(all, pos, SIGNIFICANT_DIGITS)
            val (kept, keptPos) = NumberDisplay.roundDigits(sig, sigPos, sigPos + digits)
            if (kept.isEmpty()) return CellValue.Num(0.0)
            val magnitude = (kept + "e" + (keptPos - kept.length)).toDouble()
            return finite(if (x.value < 0) -magnitude else magnitude)
        }

        private const val SIGNIFICANT_DIGITS = 15

        private fun scalarNumber(arg: EvalArg): CellValue = when (arg) {
            is EvalArg.Block -> CellValue.Err(CellError.Value)
            is EvalArg.Scalar -> when (val v = arg.value) {
                is CellValue.Num, is CellValue.Err -> v
                CellValue.Blank -> CellValue.Num(0.0)
                is CellValue.Text -> CellValue.Err(CellError.Value)
            }
        }

        private fun CellValue.mapNum(f: (Double) -> Double): CellValue =
            if (this is CellValue.Num) CellValue.Num(f(value)) else this
    }
}

/** A non-finite result (NaN, or overflow to infinity) is `#NUM!`, as in Excel. */
private fun finite(v: Double): CellValue =
    if (v.isNaN() || v.isInfinite()) CellValue.Err(CellError.Num) else CellValue.Num(v)

/**
 * Evaluates one parsed formula against [CellResolver]. Iterative (explicit work and value stacks) so
 * left-deep chains of ~500 nodes never depend on call-stack depth. Never throws for any [Expr].
 */
class FormulaEvaluator(private val registry: FunctionRegistry = FunctionRegistry.default) {

    private sealed interface Work {
        class Eval(val expr: Expr, val asArg: Boolean) : Work
        class ApplyBin(val expr: Expr.Bin) : Work
        class ApplyNeg(val expr: Expr.Neg) : Work
        class ApplyPct(val expr: Expr.Pct) : Work
        class ApplyCall(val expr: Expr.Call) : Work
        class ApplyIf(val expr: Expr.Call) : Work
    }

    private class LimitExceeded : RuntimeException()

    private class Run(val cells: CellResolver) {
        var reads = 0L
        val work = ArrayList<Work>()
        val values = ArrayList<EvalArg>()

        fun charge(n: Long) {
            reads += n
            if (reads > OPERAND_READ_BUDGET) throw LimitExceeded()
        }

        fun pushValue(v: CellValue) {
            values.add(EvalArg.Scalar(v))
        }

        fun popArg(): EvalArg = values.removeAt(values.size - 1)

        fun popValue(): CellValue = when (val a = popArg()) {
            is EvalArg.Scalar -> a.value
            is EvalArg.Block -> CellValue.Err(CellError.Value)
        }
    }

    fun evaluate(expr: Expr, cells: CellResolver): CellValue {
        val run = Run(cells)
        run.work.add(Work.Eval(expr, asArg = false))
        try {
            while (run.work.isNotEmpty()) step(run, run.work.removeAt(run.work.size - 1))
        } catch (e: LimitExceeded) {
            return CellValue.Err(CellError.Limit)
        }
        val result = run.popValue()
        return if (result === CellValue.Blank) CellValue.Num(0.0) else result
    }

    private fun step(run: Run, w: Work) {
        when (w) {
            is Work.Eval -> evalNode(run, w.expr, w.asArg)
            is Work.ApplyBin -> {
                val right = run.popValue()
                val left = run.popValue()
                run.pushValue(binary(w.expr.op, left, right))
            }
            is Work.ApplyNeg -> run.pushValue(
                when (val v = numeric(run.popValue())) {
                    is CellValue.Num -> CellValue.Num(0.0 - v.value)
                    else -> v
                },
            )
            is Work.ApplyPct -> run.pushValue(
                when (val v = numeric(run.popValue())) {
                    is CellValue.Num -> CellValue.Num(v.value / 100.0)
                    else -> v
                },
            )
            is Work.ApplyCall -> applyCall(run, w.expr)
            is Work.ApplyIf -> applyIf(run, w.expr)
        }
    }

    private fun evalNode(run: Run, e: Expr, asArg: Boolean) {
        when (e) {
            is Expr.Num -> run.pushValue(CellValue.Num(e.value))
            is Expr.Str -> run.values.add(EvalArg.Scalar(CellValue.Text(e.value)))
            is Expr.RefError -> run.pushValue(CellValue.Err(CellError.Ref))
            is Expr.Ref -> {
                if (e.col > run.cells.colCount || e.row > run.cells.rowCount) {
                    run.pushValue(CellValue.Err(CellError.Ref))
                } else {
                    run.charge(1)
                    run.values.add(EvalArg.Scalar(run.cells.valueAt(e.col, e.row), fromRef = true))
                }
            }
            is Expr.Range -> evalRange(run, e, asArg)
            is Expr.Bin -> {
                run.work.add(Work.ApplyBin(e))
                run.work.add(Work.Eval(e.right, false))
                run.work.add(Work.Eval(e.left, false))
            }
            is Expr.Neg -> {
                run.work.add(Work.ApplyNeg(e))
                run.work.add(Work.Eval(e.operand, false))
            }
            is Expr.Pct -> {
                run.work.add(Work.ApplyPct(e))
                run.work.add(Work.Eval(e.operand, false))
            }
            is Expr.Call -> startCall(run, e)
        }
    }

    private fun evalRange(run: Run, e: Expr.Range, asArg: Boolean) {
        if (!asArg) {
            run.pushValue(CellValue.Err(CellError.Value))
            return
        }
        val c1 = minOf(e.from.col, e.to.col)
        val c2 = minOf(maxOf(e.from.col, e.to.col), run.cells.colCount)
        val r1 = minOf(e.from.row, e.to.row)
        val r2 = minOf(maxOf(e.from.row, e.to.row), run.cells.rowCount)
        if (c1 > c2 || r1 > r2) {
            // A range wholly outside the table is empty, as in Excel; a single-cell ref stays #REF!.
            run.values.add(EvalArg.Block(emptyList()))
            return
        }
        run.charge((c2 - c1 + 1).toLong() * (r2 - r1 + 1))
        val out = ArrayList<CellValue>((c2 - c1 + 1) * (r2 - r1 + 1))
        for (r in r1..r2) for (c in c1..c2) out.add(run.cells.valueAt(c, r))
        run.values.add(EvalArg.Block(out))
    }

    private fun startCall(run: Run, e: Expr.Call) {
        val spec = FormulaFunctions.lookup(e.name)
        if (spec == null || e.args.size < spec.minArgs || e.args.size > spec.maxArgs) {
            run.pushValue(CellValue.Err(if (spec == null) CellError.Name else CellError.Parse))
            return
        }
        if (e.name == "IF") {
            run.work.add(Work.ApplyIf(e))
            run.work.add(Work.Eval(e.args[0], false))
            return
        }
        if (registry.lookup(e.name) == null) {
            run.pushValue(CellValue.Err(CellError.Name))
            return
        }
        run.work.add(Work.ApplyCall(e))
        for (i in e.args.indices.reversed()) run.work.add(Work.Eval(e.args[i], true))
    }

    private fun applyCall(run: Run, e: Expr.Call) {
        val args = ArrayList<EvalArg>(e.args.size)
        repeat(e.args.size) { args.add(0, run.popArg()) }
        run.pushValue(registry.lookup(e.name)!!.call(args))
    }

    private fun applyIf(run: Run, e: Expr.Call) {
        val cond = run.popValue()
        val truthy = when (cond) {
            is CellValue.Err -> {
                run.pushValue(cond)
                return
            }
            is CellValue.Text -> {
                run.pushValue(CellValue.Err(CellError.Value))
                return
            }
            is CellValue.Num -> cond.value != 0.0
            CellValue.Blank -> false
        }
        val branch = if (truthy) e.args[1] else e.args.getOrNull(2)
        if (branch == null) run.pushValue(CellValue.Num(0.0)) else run.work.add(Work.Eval(branch, false))
    }

    private fun numeric(v: CellValue): CellValue = when (v) {
        is CellValue.Num, is CellValue.Err -> v
        CellValue.Blank -> CellValue.Num(0.0)
        is CellValue.Text -> CellValue.Err(CellError.Value)
    }

    private fun binary(op: BinOp, left: CellValue, right: CellValue): CellValue {
        if (left is CellValue.Err) return left
        if (right is CellValue.Err) return right
        return when (op) {
            BinOp.Eq, BinOp.Ne, BinOp.Lt, BinOp.Le, BinOp.Gt, BinOp.Ge -> compare(op, left, right)
            else -> arithmetic(op, numeric(left), numeric(right))
        }
    }

    private fun arithmetic(op: BinOp, l: CellValue, r: CellValue): CellValue {
        if (l is CellValue.Err) return l
        if (r is CellValue.Err) return r
        val a = (l as CellValue.Num).value
        val b = (r as CellValue.Num).value
        return when (op) {
            BinOp.Add -> finite(a + b)
            BinOp.Sub -> finite(a - b)
            BinOp.Mul -> finite(a * b)
            BinOp.Div -> if (b == 0.0) CellValue.Err(CellError.DivZero) else finite(a / b)
            BinOp.Pow -> if (a == 0.0 && b < 0.0) CellValue.Err(CellError.DivZero) else finite(a.pow(b))
            else -> CellValue.Err(CellError.Value)
        }
    }

    /** Comparison yields 1 or 0 (no boolean type); numbers sort before text, blank adopts the other side's type. */
    private fun compare(op: BinOp, left: CellValue, right: CellValue): CellValue {
        val l = if (left === CellValue.Blank && right is CellValue.Text) CellValue.Text("") else left
        val r = if (right === CellValue.Blank && left is CellValue.Text) CellValue.Text("") else right
        val c = when {
            l is CellValue.Text && r is CellValue.Text -> l.value.lowercase().compareTo(r.value.lowercase())
            l is CellValue.Text -> 1
            r is CellValue.Text -> -1
            else -> {
                val a = (numeric(l) as CellValue.Num).value
                val b = (numeric(r) as CellValue.Num).value
                a.compareTo(b).let { if (a == b) 0 else it }
            }
        }
        val holds = when (op) {
            BinOp.Eq -> c == 0
            BinOp.Ne -> c != 0
            BinOp.Lt -> c < 0
            BinOp.Le -> c <= 0
            BinOp.Gt -> c > 0
            else -> c >= 0
        }
        return CellValue.Num(if (holds) 1.0 else 0.0)
    }

    companion object {
        /** Per-formula operand cell reads after range clamping (ADR-002). */
        const val OPERAND_READ_BUDGET = 10_000L
    }
}
