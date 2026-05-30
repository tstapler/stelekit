package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Flags in-memory pagination via `.drop(n).take(m)` or `.take(m).drop(n)` on a collection.
 *
 * This pattern loads the entire source collection into memory and discards most of it —
 * equivalent to fetching every SQL row and throwing away all but `m`. Pagination should be
 * pushed to the data layer (SQL LIMIT/OFFSET, Room PositionalDataSource, etc.).
 *
 * Non-compliant:
 * ```kotlin
 * // Loads ALL matching blocks, then discards them
 * queries.selectBlocks(pattern).executeAsList()
 *     .drop(offset).take(limit)
 *
 * // Inside a Flow.map — same problem
 * flow.map { list -> list.drop(offset).take(limit) }
 * ```
 *
 * Compliant:
 * ```kotlin
 * // Pagination at the SQL layer
 * queries.selectBlocksPaginated(pattern, limit.toLong(), offset.toLong()).executeAsList()
 *
 * // Lazy sequences — no memory issue (the chain is not materialised)
 * sequence.drop(n).take(m).toList()
 * ```
 *
 * Suppress with `@Suppress("InMemoryPagination")` when the pattern is intentional
 * (e.g. slicing a small already-materialised list in a test helper).
 */
class InMemoryPaginationRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "InMemoryPagination",
        severity = Severity.Performance,
        description = "In-memory pagination via .drop(n).take(m) loads the full collection before " +
            "discarding most of it. Push LIMIT/OFFSET to the SQL query instead.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeName = (expression.calleeExpression as? KtNameReferenceExpression)
            ?.getReferencedName() ?: return

        // Look for .take(x) whose receiver is .drop(y), or .drop(x) whose receiver is .take(y).
        val isPagination = when (calleeName) {
            "take" -> receiverCallName(expression) == "drop"
            "drop" -> receiverCallName(expression) == "take"
            else -> return
        }
        if (!isPagination) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "In-memory pagination via .drop().take() detected. " +
                    "Use SQL LIMIT/OFFSET (or a paginated query variant) instead of loading the " +
                    "full result set into memory.",
            )
        )
    }

    private fun receiverCallName(call: KtCallExpression): String? {
        val dotExpr = call.parent as? KtDotQualifiedExpression ?: return null
        // The receiver may be a bare KtCallExpression (e.g. drop(n).take(m)) or a
        // KtDotQualifiedExpression whose selector is the actual call (e.g. list.drop(n).take(m)).
        val receiverCall = when (val recv = dotExpr.receiverExpression) {
            is KtCallExpression -> recv
            is KtDotQualifiedExpression -> recv.selectorExpression as? KtCallExpression
            else -> null
        } ?: return null
        return (receiverCall.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    }
}
