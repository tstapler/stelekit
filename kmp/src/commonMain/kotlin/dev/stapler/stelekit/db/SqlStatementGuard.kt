// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

/**
 * Leading-verb allowlist in front of the console's read-only handle.
 *
 * The engine (`OPEN_READONLY`) is the mutation guard; this is not a table-name classifier and
 * inspects nothing but the first keyword. It exists because the Story 0.3 spikes showed the engine
 * leaves two gaps on a read-only handle: `VACUUM INTO '<path>'` writes a new file, and a `PRAGMA x=y`
 * assignment succeeds without effect (so it would not even report an error). Allowlisting the verb
 * (SELECT, WITH, VALUES, EXPLAIN, read-form PRAGMA) fails closed on anything else, including
 * statements that only the engine would have rejected.
 */
internal object SqlStatementGuard {
    data class Verdict(val allowed: Boolean, val verb: String, val isWrite: Boolean)

    private val allowedVerbs = setOf("select", "with", "values", "explain")
    private val writeVerbs = setOf("insert", "update", "delete", "replace", "create", "drop", "alter")

    // PRAGMAs that only read when called without an argument.
    private val bareReadPragmas = setOf(
        "database_list", "compile_options", "page_count", "page_size", "freelist_count", "user_version",
        "schema_version", "journal_mode", "encoding", "function_list", "collation_list", "module_list",
        "pragma_list", "table_list",
    )

    // PRAGMAs whose `(arg)` form is a query, not a setter.
    private val argReadPragmas = setOf(
        "table_info", "table_xinfo", "index_list", "index_info", "index_xinfo", "foreign_key_list",
        "foreign_key_check", "integrity_check", "quick_check",
    )

    fun check(sql: String): Verdict {
        val body = stripLeadingComments(sql)
        val verb = body.takeWhile { it.isLetter() }.lowercase()
        // The driver would run only the first statement and report OK, hiding the rest; refuse instead.
        trailingStatement(body)?.let { rest ->
            val restVerb = rest.takeWhile { it.isLetter() }.lowercase()
            return Verdict(false, restVerb.ifEmpty { rest.take(12) }, restVerb in writeVerbs)
        }
        return when {
            verb in allowedVerbs -> Verdict(true, verb, false)
            verb == "pragma" -> Verdict(isReadPragma(body.drop(verb.length)), verb, false)
            else -> Verdict(false, verb.ifEmpty { body.take(12) }, verb in writeVerbs)
        }
    }

    private fun isReadPragma(afterKeyword: String): Boolean {
        val text = afterKeyword.trim().trimEnd(';').trim()
        if (text.contains('=') || text.contains(';')) return false
        val name = text.substringBefore('(').trim().substringAfterLast('.').lowercase()
        if (!text.contains('(')) return name in bareReadPragmas || name in argReadPragmas
        return name in argReadPragmas && text.endsWith(")")
    }

    /** The text of a second statement after an unquoted `;`, or null when [body] holds exactly one statement. */
    private fun trailingStatement(body: String): String? {
        var i = 0
        var quote: Char? = null
        while (i < body.length) {
            val c = body[i]
            val q = quote
            when {
                q != null -> if (c == q) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '[' -> quote = ']'
                body.startsWith("--", i) || body.startsWith("/*", i) -> i = skipComment(body, i) - 1
                c == ';' -> return stripLeadingComments(body.substring(i + 1)).trimStart(';', ' ', '\n', '\t', '\r').ifEmpty { null }
            }
            i++
        }
        return null
    }

    private fun skipComment(sql: String, start: Int): Int =
        if (sql.startsWith("--", start)) sql.indexOf('\n', start).let { if (it < 0) sql.length else it + 1 }
        else sql.indexOf("*/", start + 2).let { if (it < 0) sql.length else it + 2 }

    /** Removes whitespace, `-- line` and `/* block */` comments before the first token, as SQLite's tokenizer does. */
    private fun stripLeadingComments(sql: String): String {
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                sql.startsWith("--", i) || sql.startsWith("/*", i) -> i = skipComment(sql, i)
                else -> return sql.substring(i)
            }
        }
        return ""
    }
}
