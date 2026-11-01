// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.commands

/**
 * Hard-deny classifier for `sql!` input only (a confirm cannot override it). Plain `sql` has no
 * classifier: the read-only connection and `SqlStatementGuard` decide there.
 *
 * Accepts exactly one INSERT, UPDATE, DELETE or REPLACE statement against an ordinary table. Everything
 * else (DDL, ATTACH, VACUUM, PRAGMA, WITH, load_extension, writes to FTS index tables, `git_config` and
 * migration bookkeeping) is denied with a reason. Where Spike 0.3e = NO there is no engine-level denial of
 * `git_config`, so this classifier plus the confirm modal is the stated limit.
 */
internal object SqlWriteGuard {
    enum class Verb { Insert, Update, Delete, Replace }

    sealed interface Verdict {
        data class Allowed(
            val statement: String,
            val verb: Verb,
            val table: String,
            /** `INSERT OR REPLACE`, `REPLACE INTO` or `DELETE`: removes rows the owner may not expect to lose. */
            val destructive: Boolean,
            /** `random()` or "now" in the statement: the real write may differ from the dry run. */
            val nondeterministic: Boolean,
        ) : Verdict

        data class Denied(val reason: String) : Verdict
    }

    private val deniedTables = setOf("git_config", "migration_changelog", "metadata")
    private val ftsTables = setOf("blocks", "pages")
    private val neverVerbs = setOf("create", "drop", "alter", "attach", "detach", "vacuum", "pragma", "reindex", "analyze")
    private val nondeterministicMarkers = listOf("random(", "randomblob(", "'now'", "current_timestamp", "current_date", "current_time")

    private val insertTarget = Regex("""^insert\s+(?:or\s+(\w+)\s+)?into\s+([\w."`\[\]]+)""")
    private val replaceTarget = Regex("""^replace\s+into\s+([\w."`\[\]]+)""")
    private val updateTarget = Regex("""^update\s+(?:or\s+(\w+)\s+)?([\w."`\[\]]+)""")
    private val deleteTarget = Regex("""^delete\s+from\s+([\w."`\[\]]+)""")

    fun check(input: String): Verdict {
        val code = stripComments(input).trim().trimEnd(';').trim()
        if (code.isEmpty()) return Verdict.Denied("Nothing to run. Usage: sql! \"<insert|update|delete statement>\"")
        if (hasSecondStatement(code)) return Verdict.Denied("One statement at a time.")
        val lower = code.lowercase()
        val verb = lower.takeWhile { it.isLetter() }
        if (maskLiterals(lower).contains("load_extension")) return Verdict.Denied("load_extension is never allowed.")
        if (verb in neverVerbs) return Verdict.Denied("${verb.uppercase()} is never allowed in sql!.")
        if (verb !in setOf("insert", "update", "delete", "replace")) {
            return Verdict.Denied("sql! accepts INSERT, UPDATE, DELETE and REPLACE only; use plain sql to read.")
        }
        val (parsedVerb, table, orClause) = target(lower)
            ?: return Verdict.Denied("Couldn't find the table this writes to. Name it directly after INTO, UPDATE or FROM.")
        deniedTable(table)?.let { return Verdict.Denied(it) }
        val orReplace = parsedVerb == Verb.Replace || orClause == "replace"
        if (orReplace && table in ftsTables) {
            return Verdict.Denied("REPLACE on $table would leave stale full-text rows (replaced rows fire no delete trigger). Use UPDATE, or DELETE then INSERT.")
        }
        return Verdict.Allowed(
            statement = code,
            verb = parsedVerb,
            table = table,
            destructive = parsedVerb == Verb.Delete || orReplace,
            nondeterministic = nondeterministicMarkers.any { lower.contains(it) },
        )
    }

    private fun deniedTable(table: String): String? = when {
        table.startsWith("sqlite_") -> "$table is SQLite's own bookkeeping."
        table.contains("_fts") -> "$table is a full-text index the app keeps in sync through triggers; write the source table instead."
        table in deniedTables -> "$table is not writable from the console."
        else -> null
    }

    private fun target(lower: String): Triple<Verb, String, String?>? {
        insertTarget.find(lower)?.let { return Triple(Verb.Insert, cleanTable(it.groupValues[2]) ?: return null, it.groupValues[1].ifEmpty { null }) }
        replaceTarget.find(lower)?.let { return Triple(Verb.Replace, cleanTable(it.groupValues[1]) ?: return null, null) }
        updateTarget.find(lower)?.let { return Triple(Verb.Update, cleanTable(it.groupValues[2]) ?: return null, it.groupValues[1].ifEmpty { null }) }
        deleteTarget.find(lower)?.let { return Triple(Verb.Delete, cleanTable(it.groupValues[1]) ?: return null, null) }
        return null
    }

    /** Strips quoting and a `main.` prefix; any other schema (`temp.`, an attached name) is not accepted. */
    private fun cleanTable(raw: String): String? {
        val parts = raw.split('.').map { it.trim('"', '`', '[', ']') }
        return when {
            parts.size == 1 -> parts[0].ifEmpty { null }
            parts.size == 2 && parts[0] == "main" -> parts[1].ifEmpty { null }
            else -> null
        }
    }

    /** Removes `-- line` and `/* block */` comments outside string literals. */
    private fun stripComments(sql: String): String {
        val out = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < sql.length) {
            val c = sql[i]
            val q = quote
            when {
                q != null -> {
                    out.append(c)
                    if (c == q) quote = null
                    i++
                }
                c == '\'' || c == '"' || c == '`' -> { quote = c; out.append(c); i++ }
                sql.startsWith("--", i) -> i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                sql.startsWith("/*", i) -> { i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }; out.append(' ') }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** True when a `;` remains outside any quote; the caller has already trimmed a trailing one. */
    private fun hasSecondStatement(code: String): Boolean {
        var quote: Char? = null
        for (c in code) {
            val q = quote
            when {
                q != null -> if (c == q) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == ';' -> return true
            }
        }
        return false
    }

    private fun maskLiterals(lower: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        for (c in lower) {
            val q = quote
            when {
                q != null -> {
                    if (c == q) quote = null
                    out.append(' ')
                }
                c == '\'' || c == '"' -> { quote = c; out.append(' ') }
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
