package dev.stapler.stelekit.table.formula

/** Source offsets into the full cell text (including the leading `=`); [end] is exclusive. */
data class Span(val start: Int, val end: Int)

/** Closed formula AST (ADR-002). Every node carries its source [span] for splice-based rewriting. */
sealed interface Expr {
    val span: Span

    data class Num(val value: Double, override val span: Span) : Expr
    data class Str(val value: String, override val span: Span) : Expr

    /** 1-based [col] and [row]; the `$` flags are kept so rewriting and printing preserve them. */
    data class Ref(
        val col: Int,
        val row: Int,
        val absCol: Boolean,
        val absRow: Boolean,
        override val span: Span,
    ) : Expr

    data class Range(val from: Ref, val to: Ref, override val span: Span) : Expr
    data class Bin(val op: BinOp, val left: Expr, val right: Expr, override val span: Span) : Expr
    data class Neg(val operand: Expr, override val span: Span) : Expr
    data class Pct(val operand: Expr, override val span: Span) : Expr

    /** [name] is upper-cased and always an allow-listed function. */
    data class Call(val name: String, val args: List<Expr>, override val span: Span) : Expr

    /** The literal `#REF!` written by reference rewriting; evaluates to the `#REF!` error. */
    data class RefError(override val span: Span) : Expr
}

enum class BinOp(val symbol: String) {
    Add("+"), Sub("-"), Mul("*"), Div("/"), Pow("^"),
    Eq("="), Ne("<>"), Lt("<"), Le("<="), Gt(">"), Ge(">="),
}
