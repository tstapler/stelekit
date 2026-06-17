package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList

/**
 * Flags a [Migration] call that adds a `CREATE INDEX` on a high-volume table ([WATCHED_TABLES])
 * without a subsequent `ANALYZE` statement in the same migration or the immediately following one.
 *
 * ## Why this matters
 *
 * SQLite's query planner relies on statistics stored in `sqlite_stat1` (populated by `ANALYZE`).
 * Without statistics, it falls back to a heuristic that assumes 1 000 000 rows per table and
 * 10 rows per index entry. On a large graph this causes the planner to prefer a full heap scan
 * (SCAN blocks) over a composite covering index, resulting in ~9 s per blocks:select call.
 *
 * `QueryPlanAuditTest` calls `ANALYZE` manually before checking query plans — this masks the
 * gap in CI and lets the regression reach production. The enforcement ladder for this class of bug:
 *
 * | Level | Mechanism |
 * |---|---|
 * | 3 Unit test | `MigrationRunner all contains analyze_blocks` — catches removal of specific migration |
 * | 3 Unit test | `large blocks table uses composite index after ANALYZE` — documents the regression |
 * | 1b Runtime | `PRAGMA optimize` in `MigrationRunner.applyAll` — ongoing automatic maintenance |
 * | **2 Lint** | **This rule** — catches future index migrations that forget ANALYZE at author time |
 *
 * ## What is checked
 *
 * Any call matching `Migration(name = ..., statements = listOf(...))` where:
 * - One of the statements contains `CREATE INDEX` (or `CREATE UNIQUE INDEX`) targeting a table
 *   in [WATCHED_TABLES] (`blocks`, `pages`)
 * - **And** neither the same migration nor the immediately following sibling migration contains
 *   any statement with the keyword `ANALYZE`
 *
 * ## Compliant
 *
 * ```kotlin
 * // ANALYZE in the same migration
 * Migration(
 *     name = "covering_indexes_page_blocks",
 *     statements = listOf(
 *         "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)",
 *         "ANALYZE blocks",
 *     )
 * )
 *
 * // ANALYZE in the immediately following migration
 * Migration(
 *     name = "covering_indexes_page_blocks",
 *     statements = listOf(
 *         "CREATE INDEX IF NOT EXISTS idx_blocks_page_position ON blocks(page_uuid, position)",
 *     )
 * ),
 * Migration(
 *     name = "analyze_blocks",
 *     statements = listOf("ANALYZE blocks"),
 * )
 * ```
 *
 * ## Non-compliant
 *
 * ```kotlin
 * // No ANALYZE anywhere nearby
 * Migration(
 *     name = "add_blocks_covering_index",
 *     statements = listOf(
 *         "CREATE INDEX IF NOT EXISTS idx_blocks_page_hash ON blocks(page_uuid, uuid, content_hash)",
 *     )
 * )
 * ```
 *
 * Suppress with `@Suppress("IndexWithoutAnalyze")` only when a separate `PRAGMA optimize`
 * call is guaranteed to run before any significant read workload (document why in a comment).
 */
class IndexWithoutAnalyzeRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "IndexWithoutAnalyze",
        severity = Severity.Performance,
        description = "Migration adds CREATE INDEX on a high-volume table (blocks/pages) without a " +
            "subsequent ANALYZE statement. Without ANALYZE, SQLite uses default heuristics and can " +
            "choose a full heap scan (~9 s/query on large graphs). Add 'ANALYZE <table>' to this " +
            "migration or the immediately following one.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Only care about Migration(...) call expressions
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.getReferencedName() != "Migration") return

        val statements = extractStatements(expression)
        if (statements.isEmpty()) return

        // Check if any statement targets a watched table with CREATE INDEX
        val watchedTable = statements
            .mapNotNull { s -> HIGH_VOLUME_INDEX_PATTERN.find(s)?.groupValues?.get(1) }
            .firstOrNull() ?: return

        // Compliant: ANALYZE in the same migration
        if (statements.any { it.contains("ANALYZE", ignoreCase = true) }) return

        // Compliant: ANALYZE in the immediately following sibling Migration call
        if (nextSiblingMigrationHasAnalyze(expression)) return

        val migrationName = extractName(expression) ?: "<unknown>"
        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Migration '$migrationName' adds CREATE INDEX on '$watchedTable' but has no ANALYZE " +
                    "statement, and neither does the immediately following migration. " +
                    "Add 'ANALYZE $watchedTable' to this migration or the next one to ensure the " +
                    "query planner uses the new index on large production graphs.",
            )
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private fun extractStatements(migrationCall: KtCallExpression): List<String> {
        val statementsArg = migrationCall.valueArguments
            .find { it.getArgumentName()?.asName?.asString() == "statements" }
            ?: migrationCall.valueArguments.getOrNull(1)
            ?: return emptyList()

        val statementsListCall = statementsArg.getArgumentExpression() as? KtCallExpression
            ?: return emptyList()
        val statementsCallee = statementsListCall.calleeExpression as? KtNameReferenceExpression
        if (statementsCallee?.getReferencedName() != "listOf") return emptyList()

        return statementsListCall.valueArguments.mapNotNull { arg ->
            (arg.getArgumentExpression() as? KtStringTemplateExpression)
                ?.entries?.joinToString("") { it.text }
        }
    }

    private fun extractName(migrationCall: KtCallExpression): String? {
        val nameArg = migrationCall.valueArguments
            .find { it.getArgumentName()?.asName?.asString() == "name" }
            ?: migrationCall.valueArguments.getOrNull(0)
            ?: return null
        return (nameArg.getArgumentExpression() as? KtStringTemplateExpression)
            ?.entries?.joinToString("") { it.text }
    }

    private fun nextSiblingMigrationHasAnalyze(migrationCall: KtCallExpression): Boolean {
        // Walk: Migration(...) → KtValueArgument → KtValueArgumentList → outer listOf(...)
        val containingArg = migrationCall.parent as? KtValueArgument ?: return false
        val containingArgList = containingArg.parent as? KtValueArgumentList ?: return false
        val outerListOf = containingArgList.parent as? KtCallExpression ?: return false
        val outerCallee = outerListOf.calleeExpression as? KtNameReferenceExpression ?: return false
        if (outerCallee.getReferencedName() != "listOf") return false

        val allArgs = containingArgList.arguments
        val myIndex = allArgs.indexOf(containingArg)
        if (myIndex < 0 || myIndex + 1 >= allArgs.size) return false

        val nextCall = allArgs[myIndex + 1].getArgumentExpression() as? KtCallExpression ?: return false
        val nextCallee = nextCall.calleeExpression as? KtNameReferenceExpression ?: return false
        if (nextCallee.getReferencedName() != "Migration") return false

        return extractStatements(nextCall).any { it.contains("ANALYZE", ignoreCase = true) }
    }

    companion object {
        /**
         * Tables large enough that missing ANALYZE after index creation causes the query
         * planner to regress to full heap scans on production graphs.
         *
         * - `blocks` — up to ~100 k rows on a large graph; each page load does a blocks:select
         * - `pages` — up to ~8 k rows; backlink recomputes and namespace scans are frequent
         */
        private val WATCHED_TABLES = setOf("blocks", "pages")

        private val HIGH_VOLUME_INDEX_PATTERN = Regex(
            """CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?\S+\s+ON\s+(${WATCHED_TABLES.joinToString("|")})\s*\(""",
            RegexOption.IGNORE_CASE,
        )
    }
}
