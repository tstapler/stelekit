package dev.stapler.stelekit.query

/** Parsed `{{query ...}}` body. Nesting beyond one level is unrepresentable by construction. */
sealed interface SimpleQuery

/** What an [And] operand may be: a bare filter or its negation. Never an [And]/[Or]. */
sealed interface QueryOperand

sealed interface QueryFilter : SimpleQuery, QueryOperand {
    /** [markers] are uppercase, from the canonical vocabulary in `InlineParser.parseTextOrTaskMarker`. */
    data class Task(val markers: Set<String>) : QueryFilter
    data class PageProperty(val key: String, val value: String) : QueryFilter
    data class Between(val startPage: String, val endPage: String) : QueryFilter
    data class PageRef(val target: String) : QueryFilter
}

/** Negation of one base filter. Not a [SimpleQuery] (no top-level `not`) and only usable under [And]. */
data class Not(val filter: QueryFilter) : QueryOperand

data class And(val left: QueryOperand, val right: QueryOperand) : SimpleQuery

/**
 * Operands are plain [QueryFilter]: `Or(x, Not(y))` would need a complement against every block
 * in the graph, which has no reactive implementation, so it is deliberately unrepresentable.
 */
data class Or(val left: QueryFilter, val right: QueryFilter) : SimpleQuery
