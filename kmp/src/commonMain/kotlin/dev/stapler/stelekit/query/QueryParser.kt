package dev.stapler.stelekit.query

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hand-rolled recursive-descent parser for the simple-query s-expression subset of Logseq's
 * `{{query ...}}` macro. Entry point is [parse], which never throws.
 *
 * Failure classification: [DomainError.ParseError.UnsupportedForm] when a recognized head symbol
 * carries an argument shape outside the v1 grammar; [DomainError.ParseError.InvalidSyntax] for
 * everything else.
 */
object QueryParser {
    private val markers = setOf("TODO", "DONE", "NOW", "LATER", "WAITING", "CANCELLED", "DOING", "WAIT", "STARTED")
    private val headSymbols = setOf("task", "todo", "page-property", "between", "and", "or", "not")

    private sealed interface Sexp {
        data class Atom(val text: String, val quoted: Boolean = false) : Sexp
        data class Group(val items: List<Sexp>) : Sexp
    }

    private class Failure(val error: DomainError.ParseError) : Exception()

    private fun invalid(msg: String): Nothing = throw Failure(DomainError.ParseError.InvalidSyntax(msg))
    private fun unsupported(msg: String): Nothing = throw Failure(DomainError.ParseError.UnsupportedForm(msg))

    fun parse(raw: String): Either<DomainError.ParseError, SimpleQuery> =
        try {
            val tokens = tokenize(raw)
            if (tokens.isEmpty()) invalid("empty query")
            if (tokens.size != 1) invalid("expected a single query form")
            toQuery(tokens[0]).right()
        } catch (e: Failure) {
            e.error.left()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.ParseError.InvalidSyntax("malformed query: ${e.message}").left()
        }

    // ── tokenizer / reader ───────────────────────────────────────────────

    private fun tokenize(raw: String): List<Sexp> {
        var i = 0
        val s = raw.trim()
        fun readItems(depth: Int): List<Sexp> {
            val out = mutableListOf<Sexp>()
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c == '(' -> {
                        if (depth > 8) invalid("query nested too deeply")
                        i++
                        val items = readItems(depth + 1)
                        if (i >= s.length || s[i] != ')') invalid("unbalanced parentheses")
                        i++
                        out += Sexp.Group(items)
                    }
                    c == ')' -> return out
                    c == '"' -> {
                        val end = s.indexOf('"', i + 1)
                        if (end < 0) invalid("unterminated string")
                        out += Sexp.Atom(s.substring(i + 1, end), quoted = true)
                        i = end + 1
                    }
                    s.startsWith("[[", i) || s.startsWith("#[[", i) -> {
                        val open = if (s[i] == '#') i + 3 else i + 2
                        val end = s.indexOf("]]", open)
                        if (end < 0) invalid("unterminated page reference")
                        out += Sexp.Atom(s.substring(i, end + 2))
                        i = end + 2
                    }
                    else -> {
                        val start = i
                        while (i < s.length && !isAtomBoundary(s[i])) i++
                        out += Sexp.Atom(s.substring(start, i))
                    }
                }
            }
            return out
        }
        val items = readItems(0)
        if (i < s.length) invalid("unbalanced parentheses")
        return items
    }

    private fun isAtomBoundary(c: Char): Boolean = c.isWhitespace() || c == '(' || c == ')' || c == '"'

    // ── grammar ──────────────────────────────────────────────────────────

    private fun toQuery(sexp: Sexp): SimpleQuery {
        pageRefOrNull(sexp)?.let { return it }
        val group = sexp as? Sexp.Group ?: invalid("expected a query form")
        val head = headOf(group)
        return when (head) {
            "and" -> {
                val args = group.items.drop(1)
                if (args.size != 2) unsupported("and expects exactly two operands")
                val l = parseOperand(args[0])
                val r = parseOperand(args[1])
                if (l is Not && r is Not) unsupported("and needs at least one non-negated operand")
                And(l, r)
            }
            "or" -> {
                val args = group.items.drop(1)
                if (args.size != 2) unsupported("or expects exactly two operands")
                Or(parseFilterOperand(args[0]), parseFilterOperand(args[1]))
            }
            "not" -> unsupported("top-level not is not supported; use it inside and")
            else -> parseFilter(group, head)
        }
    }

    private fun headOf(group: Sexp.Group): String {
        val h = (group.items.firstOrNull() as? Sexp.Atom)?.takeIf { !it.quoted }?.text?.lowercase()
            ?: invalid("missing head symbol")
        if (h !in headSymbols) invalid("unknown query form: $h")
        return h
    }

    private fun pageRefOrNull(sexp: Sexp): QueryFilter.PageRef? {
        val atom = sexp as? Sexp.Atom ?: return null
        if (atom.quoted) return null
        val t = atom.text.removePrefix("#").takeIf { atom.text.startsWith("#[[") } ?: atom.text
        return when {
            t.startsWith("[[") && t.endsWith("]]") && t.length > 4 -> QueryFilter.PageRef(t.substring(2, t.length - 2))
            t.startsWith("#") && t.length > 1 && !t.startsWith("#[") -> QueryFilter.PageRef(t.substring(1))
            else -> null
        }
    }

    private fun parseOperand(sexp: Sexp): QueryOperand {
        pageRefOrNull(sexp)?.let { return it }
        val group = sexp as? Sexp.Group ?: invalid("expected a filter operand")
        return when (val head = headOf(group)) {
            "and", "or" -> unsupported("nested $head is not supported")
            "not" -> {
                val args = group.items.drop(1)
                if (args.size != 1) unsupported("not expects exactly one operand")
                val inner = parseFilterOperand(args[0])
                Not(inner)
            }
            else -> parseFilter(group, head)
        }
    }

    private fun parseFilterOperand(sexp: Sexp): QueryFilter {
        pageRefOrNull(sexp)?.let { return it }
        val group = sexp as? Sexp.Group ?: invalid("expected a filter operand")
        return when (val head = headOf(group)) {
            "and", "or" -> unsupported("nested $head is not supported")
            "not" -> unsupported("not is only supported as an operand of and")
            else -> parseFilter(group, head)
        }
    }

    private fun parseFilter(group: Sexp.Group, head: String): QueryFilter {
        val args = group.items.drop(1)
        return when (head) {
            "task", "todo" -> {
                if (args.isEmpty()) invalid("$head requires at least one marker")
                val set = args.map { a ->
                    val text = (a as? Sexp.Atom)?.text ?: invalid("$head expects marker names")
                    text.uppercase().also { if (it !in markers) invalid("unknown task marker: $text") }
                }.toSet()
                QueryFilter.Task(set)
            }
            "page-property" -> {
                if (args.size != 2) invalid("page-property expects a key and a value")
                val key = (args[0] as? Sexp.Atom)?.text ?: invalid("page-property key must be a word")
                val value = (args[1] as? Sexp.Atom)?.text ?: invalid("page-property value must be a word")
                QueryFilter.PageProperty(key, value)
            }
            "between" -> {
                fun pageName(a: Sexp): String? =
                    (a as? Sexp.Atom)?.takeIf { !it.quoted && it.text.startsWith("[[") }?.let { pageRefOrNull(it)?.target }
                val start = args.getOrNull(0)?.let(::pageName)
                val end = args.getOrNull(1)?.let(::pageName)
                if (args.size != 2 || start == null || end == null) {
                    unsupported("between supports only two [[journal page]] arguments")
                }
                QueryFilter.Between(start, end)
            }
            else -> invalid("unknown query form: $head")
        }
    }
}
