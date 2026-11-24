package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.CellAddress

/**
 * Canonical text for formula trees (ADR-002); used for newly built nodes. Edits to existing
 * formulas splice by [Span] instead, so untouched text stays byte-identical.
 *
 * Iterative (explicit work stack): parsed trees can be ~500 deep for flat chains, and no engine
 * function may rely on call-stack depth.
 */
object FormulaPrinter {
    private const val P_COMPARE = 10
    private const val P_ADD = 20
    private const val P_MUL = 30
    private const val P_UNARY = 40
    private const val P_POW = 50
    private const val P_PERCENT = 60
    private const val P_ATOM = 100

    /** Full cell text including the leading `=`. */
    fun print(expr: Expr): String = "=" + body(expr)

    fun body(expr: Expr): String {
        val out = StringBuilder()
        val stack = ArrayList<Any>()
        stack.add(expr)
        while (stack.isNotEmpty()) {
            when (val item = stack.removeAt(stack.size - 1)) {
                is String -> out.append(item)
                is Expr -> expand(item, stack, out)
            }
        }
        return out.toString()
    }

    private fun expand(e: Expr, stack: ArrayList<Any>, out: StringBuilder) {
        when (e) {
            is Expr.Num -> out.append(number(e.value))
            is Expr.Str -> out.append('"').append(e.value.replace("\"", "\"\"")).append('"')
            is Expr.Ref -> out.append(ref(e))
            is Expr.Range -> out.append(ref(e.from)).append(':').append(ref(e.to))
            is Expr.RefError -> out.append("#REF!")
            is Expr.Neg -> push(stack, "-", operand(e.operand, prec(e.operand) < P_UNARY))
            is Expr.Pct -> push(stack, operand(e.operand, prec(e.operand) < P_PERCENT), "%")
            is Expr.Bin -> {
                val p = binPrec(e.op)
                val leftParens = prec(e.left) < p || (e.op == BinOp.Pow && prec(e.left) == p)
                val rightParens = prec(e.right) < p || (e.op != BinOp.Pow && prec(e.right) == p)
                push(stack, operand(e.left, leftParens), e.op.symbol, operand(e.right, rightParens))
            }
            is Expr.Call -> {
                val parts = ArrayList<Any>()
                parts.add(e.name)
                parts.add("(")
                e.args.forEachIndexed { i, a ->
                    if (i > 0) parts.add(",")
                    parts.add(a)
                }
                parts.add(")")
                push(stack, *parts.toTypedArray())
            }
        }
    }

    /** Pushes [parts] so they pop in the given order. */
    private fun push(stack: ArrayList<Any>, vararg parts: Any) {
        for (i in parts.indices.reversed()) {
            when (val p = parts[i]) {
                is Operand -> {
                    if (p.parens) stack.add(")")
                    stack.add(p.expr)
                    if (p.parens) stack.add("(")
                }
                else -> stack.add(p)
            }
        }
    }

    private class Operand(val expr: Expr, val parens: Boolean)

    private fun operand(e: Expr, parens: Boolean): Any = Operand(e, parens)

    private fun binPrec(op: BinOp): Int = when (op) {
        BinOp.Add, BinOp.Sub -> P_ADD
        BinOp.Mul, BinOp.Div -> P_MUL
        BinOp.Pow -> P_POW
        else -> P_COMPARE
    }

    private fun prec(e: Expr): Int = when (e) {
        is Expr.Bin -> binPrec(e.op)
        is Expr.Neg -> P_UNARY
        is Expr.Pct -> P_PERCENT
        is Expr.Num -> if (e.value < 0.0) P_UNARY else P_ATOM
        else -> P_ATOM
    }

    private fun ref(r: Expr.Ref): String =
        (if (r.absCol) "$" else "") + CellAddress.columnLabel(r.col) + (if (r.absRow) "$" else "") + r.row

    private fun number(v: Double): String =
        if (v == 0.0) "0" else if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
