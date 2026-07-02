package dev.stapler.stelekit.db

/**
 * Lightweight SQLite statement tokenizer for migration safety analysis.
 *
 * Classifies statements by their leading SQL keywords after stripping comments
 * and handling string literals — so `BEGIN` inside a trigger body is never
 * confused with a standalone transaction-control statement.
 *
 * Used by [MigrationRunner.Migration]'s init block and unit-tested in
 * MigrationStaticAnalysisTest.
 */
internal object SqliteStatementAnalyzer {

    private val TX_KEYWORDS = setOf("BEGIN", "COMMIT", "ROLLBACK")

    /**
     * True when [sql] is a standalone transaction-control statement.
     *
     * Correctly handles:
     * - `BEGIN TRANSACTION`, `BEGIN IMMEDIATE`, `BEGIN DEFERRED`, `BEGIN EXCLUSIVE`
     * - `COMMIT TRANSACTION`, `COMMIT WORK`
     * - `ROLLBACK TRANSACTION`, `ROLLBACK TO SAVEPOINT foo`
     * - Leading `-- comment` or `/* comment */` before the keyword
     *
     * Never flags `BEGIN` inside a `CREATE TRIGGER ... BEGIN ... END` body — the
     * first keyword of such a statement is `CREATE`, not `BEGIN`.
     */
    fun isTransactionControl(sql: String): Boolean =
        leadingKeywords(sql, limit = 1).firstOrNull() in TX_KEYWORDS

    /**
     * Returns up to [limit] uppercase leading keywords from [sql], after stripping
     * SQL comments. Stops at the first parenthesis, semicolon, or string/identifier
     * literal so that body content (trigger bodies, SQL expressions) is never included.
     *
     * Examples:
     * - `"CREATE TABLE IF NOT EXISTS foo"` → `["CREATE", "TABLE", "IF", "NOT"]`
     * - `"-- drop first\nDROP TABLE IF EXISTS foo"` → `["DROP", "TABLE", "IF", "EXISTS"]`
     * - `"BEGIN TRANSACTION"` → `["BEGIN", "TRANSACTION"]`
     * - `"PRAGMA foreign_keys=OFF"` → `["PRAGMA"]`  (= stops the token)
     */
    fun leadingKeywords(sql: String, limit: Int = 4): List<String> {
        val tokens = mutableListOf<String>()
        val text = stripComments(sql).trimStart()
        var i = 0
        while (i < text.length && tokens.size < limit) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                // Stop at parenthesis, semicolon, or assignment — body/value content follows
                c == '(' || c == ';' || c == '=' -> break
                // Stop at string or quoted identifier — content is not a keyword
                c == '\'' || c == '"' || c == '`' -> break
                else -> {
                    val start = i
                    while (i < text.length && !text[i].isWhitespace() &&
                        text[i] != '(' && text[i] != ';' && text[i] != '=') i++
                    if (i > start) tokens.add(text.substring(start, i).uppercase())
                }
            }
        }
        return tokens
    }

    /**
     * Strips single-line (`--`) and multi-line (`/* */`) SQL comments from [sql].
     * Single-quoted string literals are passed through verbatim so their content
     * cannot interfere with keyword extraction.
     */
    fun stripComments(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            when {
                // Single-line comment: skip to end of line
                i + 1 < sql.length && sql[i] == '-' && sql[i + 1] == '-' -> {
                    while (i < sql.length && sql[i] != '\n') i++
                }
                // Multi-line comment: skip to closing */
                i + 1 < sql.length && sql[i] == '/' && sql[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < sql.length && !(sql[i] == '*' && sql[i + 1] == '/')) i++
                    if (i + 1 < sql.length) i += 2
                }
                // Single-quoted string literal: pass through verbatim ('' = escaped quote)
                sql[i] == '\'' -> {
                    sb.append(sql[i++])
                    while (i < sql.length) {
                        sb.append(sql[i])
                        if (sql[i] == '\'') {
                            i++
                            if (i < sql.length && sql[i] == '\'') {
                                // Escaped quote '' — consume the second ' and continue
                                sb.append(sql[i++])
                            } else {
                                break // end of string literal
                            }
                        } else {
                            i++
                        }
                    }
                }
                else -> sb.append(sql[i++])
            }
        }
        return sb.toString()
    }
}
