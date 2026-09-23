package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.KtWhileExpression

/**
 * Flags [DatabaseWriteActor] write method calls (e.g. [deleteBlock], [saveBlock], [savePage])
 * inside loop bodies ([forEach], [for], [while], etc.).
 *
 * ## Why this matters
 *
 * Each call to a [DatabaseWriteActor] shorthand method (other than [execute]) is a separate
 * actor round-trip: a channel send plus a [CompletableDeferred.await]. During graph import the
 * actor queue backs up with hundreds of pending page saves; each individual write call then
 * waits its turn in the queue — turning a logical O(N) operation into N sequential suspensions.
 *
 * The regression in [GraphLoader.dispatchFullBlockWrites] ran N × `writeActor.deleteBlock(uuid)`
 * in a `forEach` loop, causing 100–250 seconds of completely untraced delay per page during
 * graph import on a large graph. [TC-BATCH-01] in [GraphLoaderBatchWriteTest] covers that
 * specific site; this rule catches the pattern structurally before it can recur in any new code.
 *
 * ## Why the type system cannot close this gap
 *
 * `DatabaseWriteActor.deleteBlock(BlockUuid)` is a valid and correct API for single-block
 * deletes from the editor. The wrong pattern is calling it inside a loop — a structural
 * position that cannot be expressed as a type constraint. The `execute { }` API makes the
 * correct path ergonomically available, but does not prevent the wrong path syntactically.
 * Type-driven design verdict: no alternative type, reshape, or typestate is applicable.
 * Lint (Level 2 on the enforcement ladder) is the highest achievable level.
 *
 * ## Compliant
 *
 * ```kotlin
 * // Batch all deletes + saves in one execute — 1 actor round-trip total
 * writeActor.execute {
 *     toDelete.forEach { uuid -> blockRepository.deleteBlock(uuid) }
 *     pageRepository.savePage(page)
 * }
 *
 * // Single write outside a loop — 1 actor round-trip
 * writeActor.deleteBlock(uuid)
 * ```
 *
 * ## Non-compliant
 *
 * ```kotlin
 * // N separate round-trips — each iteration suspends in the actor queue
 * toDelete.forEach { uuid -> writeActor.deleteBlock(uuid) }
 *
 * for (block in blocks) {
 *     writeActor.saveBlock(block)
 * }
 * ```
 */
class ActorWriteInLoopRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "ActorWriteInLoop",
        severity = Severity.Performance,
        description = "DatabaseWriteActor write method called inside a loop — N separate actor " +
            "round-trips (one channel send+await per iteration). Batch all writes into a single " +
            "writeActor.execute { } call instead.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.getReferencedName() !in ACTOR_WRITE_METHODS) return

        // Only flag dot-qualified calls (writeActor.METHOD()) — not free-standing calls
        val dotExpr = expression.parent as? KtDotQualifiedExpression ?: return
        // Receiver must reference an actor (heuristic: name contains "actor", case-insensitive).
        // This excludes repository calls with the same method names (e.g. blockRepository.deleteBlock).
        val receiverText = dotExpr.receiverExpression.text
        if (!receiverText.contains("actor", ignoreCase = true)) return

        if (isInsideLoop(expression)) {
            val methodName = callee.getReferencedName()
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "'$receiverText.$methodName()' inside a loop — each iteration is a separate " +
                        "actor round-trip (channel send + await). " +
                        "Batch all writes into a single writeActor.execute { ... } call.",
                )
            )
        }
    }

    /**
     * Walks the PSI parent chain upward from [start], returning true if the call is inside
     * a loop construct (`for`, `while`, `do-while`, `forEach`, `map`, etc.).
     *
     * Stops early at:
     * - [KtNamedFunction] — crossed a function boundary, not in a loop of this function
     * - A [KtFunctionLiteral] whose enclosing call is `execute` or `executeBatch` — writes
     *   inside a single `execute { }` are already batched and are not a problem
     */
    private fun isInsideLoop(start: KtCallExpression): Boolean {
        var current = start.parent
        while (current != null) {
            when (current) {
                is KtForExpression, is KtWhileExpression, is KtDoWhileExpression -> return true
                is KtFunctionLiteral -> {
                    val lambdaExpr = current.parent as? KtLambdaExpression
                    if (lambdaExpr != null) {
                        val callExpr: KtCallExpression? = when (val arg = lambdaExpr.parent) {
                            is KtLambdaArgument -> arg.parent as? KtCallExpression
                            is KtValueArgument -> (arg.parent as? KtValueArgumentList)?.parent as? KtCallExpression
                            else -> null
                        }
                        val calleeName = (callExpr?.calleeExpression as? KtNameReferenceExpression)
                            ?.getReferencedName()
                        when {
                            calleeName in LOOP_LIKE_METHODS -> return true
                            calleeName == "execute" || calleeName == "executeBatch" -> return false
                        }
                    }
                    // Not a recognized lambda call — continue walking upward
                }
                is KtNamedFunction -> return false
            }
            current = current.parent
        }
        return false
    }

    companion object {
        /** Actor write methods that each cost one channel send + await round-trip. */
        private val ACTOR_WRITE_METHODS = setOf(
            "deleteBlock",
            "deleteBlockStructural",
            "saveBlock",
            "saveBlocks",
            "saveBlocksDiff",
            "savePage",
            "savePages",
            "deleteBlocksForPage",
            "deleteBlocksForPages",
            "updateBlockContentOnly",
            "updateBlockPropertiesOnly",
            "splitBlock",
            "mergeBlocks",
        )

        /**
         * Lambda-accepting methods whose body is executed once per element — equivalent to
         * a loop for the purposes of actor round-trip counting.
         */
        private val LOOP_LIKE_METHODS = setOf(
            "forEach",
            "forEachIndexed",
            "map",
            "mapIndexed",
            "mapNotNull",
            "flatMap",
            "flatMapIndexed",
            "filter",
            "filterIndexed",
            "onEach",
            "onEachIndexed",
            "fold",
            "reduce",
        )
    }
}
