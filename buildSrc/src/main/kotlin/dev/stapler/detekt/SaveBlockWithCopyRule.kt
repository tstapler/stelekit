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
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Flags `saveBlock(block.copy(contentField = ...))` calls where only content-only
 * or properties-only fields are changed, preventing clobbering of structural fields
 * (parentUuid, position, level, leftUuid) during concurrent indent/outdent/move operations.
 *
 * `saveBlock` does INSERT OR REPLACE on ALL columns. If the local snapshot of the block
 * has stale structural fields from a concurrent operation, the write silently clobbers
 * the DB hierarchy. Targeted update methods avoid this race:
 *
 * - content fields (content, version, updatedAt, searchContent, contentHash, blockType)
 *   → use `updateBlockContentOnly(uuid, content)`
 * - properties → use `updateBlockPropertiesOnly(uuid, properties)`
 *
 * Structural fields (parentUuid, pageUuid, leftUuid, position, order, level,
 * isCollapsed, childrenCount, depth, uuid, createdAt) are allowed because structural
 * changes by definition require the full block state.
 *
 * Note: this rule detects by argument name only. Positional copy() calls are not flagged
 * (they're rare in practice and require type resolution to identify safely).
 *
 * Fields are linked to Block model fields in GraphRepository.kt.
 * Keep these sets in sync when new Block fields are added.
 */
class SaveBlockWithCopyRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "SaveBlockWithCopy",
        severity = Severity.Defect,
        description = "saveBlock(block.copy(...)) with only content/properties fields clobbers " +
            "structural fields during concurrent structural operations. " +
            "Use updateBlockContentOnly() or updateBlockPropertiesOnly() instead.",
        debt = Debt.TEN_MINS,
    )

    // Fields that can be safely updated via updateBlockContentOnly()
    // Linked to: Block data class in model/Models.kt
    private val contentOnlyFields = setOf(
        "content", "version", "updatedAt", "searchContent", "contentHash", "blockType"
    )

    // Fields that can be safely updated via updateBlockPropertiesOnly()
    private val propertiesOnlyFields = setOf("properties")

    // Structural fields that require full-row writes — changes to these are safe in saveBlock
    private val structuralFields = setOf(
        "parentUuid", "pageUuid", "leftUuid", "position", "order", "level",
        "isCollapsed", "childrenCount", "depth", "uuid", "createdAt"
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        // Match the callee name as "saveBlock"
        val callee = expression.calleeExpression as? KtNameReferenceExpression ?: return
        if (callee.getReferencedName() != "saveBlock") return

        // Find a .copy(...) in the argument
        val args = expression.valueArgumentList?.arguments ?: return
        if (args.size != 1) return
        val arg = args[0]

        val copyCall = findCopyCall(arg) ?: return
        val copyArgs = copyCall.valueArgumentList?.arguments ?: return

        // Collect named copy arguments (positional args are too hard to classify without type resolution)
        val namedArgs = copyArgs.mapNotNull { it.getArgumentName()?.asName?.identifier }
        if (namedArgs.isEmpty()) return

        // If any structural field is being set, the full saveBlock is appropriate — skip
        if (namedArgs.any { it in structuralFields }) return

        // All changed fields are content-only or properties-only — this is a violation
        val changedContentFields = namedArgs.filter { it in contentOnlyFields }
        val changedPropertyFields = namedArgs.filter { it in propertiesOnlyFields }
        val unknownFields = namedArgs.filter { it !in contentOnlyFields && it !in propertiesOnlyFields }

        val suggestion = when {
            unknownFields.isEmpty() && changedPropertyFields.isEmpty() ->
                "Use updateBlockContentOnly(uuid, content) instead."
            unknownFields.isEmpty() && changedContentFields.isEmpty() ->
                "Use updateBlockPropertiesOnly(uuid, properties) instead."
            else ->
                "Use updateBlockContentOnly() or updateBlockPropertiesOnly() instead of a full saveBlock()."
        }

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "saveBlock(block.copy(${namedArgs.joinToString()})) modifies only non-structural fields. $suggestion",
            )
        )
    }

    private fun findCopyCall(arg: KtValueArgument): KtCallExpression? {
        // Pattern: saveBlock(block.copy(...))
        val dotExpr = arg.getArgumentExpression() as? KtDotQualifiedExpression
        if (dotExpr != null) {
            val call = dotExpr.selectorExpression as? KtCallExpression ?: return null
            val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
            if (callee.getReferencedName() == "copy") return call
        }
        // Pattern: saveBlock(copy(...))  — less common but worth covering
        val directCall = arg.getArgumentExpression() as? KtCallExpression
        if (directCall != null) {
            val callee = directCall.calleeExpression as? KtNameReferenceExpression ?: return null
            if (callee.getReferencedName() == "copy") return directCall
        }
        return null
    }
}
