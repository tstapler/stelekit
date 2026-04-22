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
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Flags construction of Regex from a **literal** pattern inside a lambda expression.
 *
 * Literal patterns can be extracted to a companion object or top-level val and compiled
 * once. Dynamic patterns (containing `${...}` interpolation) cannot, so they are ignored.
 *
 * Compliant:
 * ```kotlin
 * private val DIGITS = Regex("\\d+")
 * list.filter { DIGITS.containsMatchIn(it) }
 *
 * // Dynamic pattern — not flagged (can't precompile)
 * list.filter { "\\b${Regex.escape(name)}\\b".toRegex().containsMatchIn(it) }
 * ```
 *
 * Non-compliant:
 * ```kotlin
 * list.filter { Regex("\\d+").containsMatchIn(it) }
 * list.map { it.split("\\s+".toRegex()) }
 * ```
 */
class RegexInLambdaRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "RegexInLambda",
        severity = Severity.Performance,
        description = "Regex with a literal pattern is constructed inside a lambda — it will be compiled " +
            "on every invocation. Move it to a companion object or top-level val.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        if (!isRegexConstruction(expression)) return
        if (!hasLiteralPattern(expression)) return
        if (expression.getParentOfType<KtLambdaExpression>(strict = true) == null) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Regex with a literal pattern constructed inside a lambda will be recompiled on every call. " +
                    "Extract it to a companion object or top-level val.",
            )
        )
    }

    private fun isRegexConstruction(call: KtCallExpression): Boolean {
        val calleeName = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (calleeName == "Regex") return true

        // "pattern".toRegex() via dot-qualified expression
        val calleeText = call.calleeExpression?.text ?: return false
        return calleeText.endsWith(".toRegex") || calleeText == "toRegex"
    }

    /**
     * Returns true when the pattern argument is a plain string literal with no interpolation.
     * Dynamic patterns (e.g. `"\\b${Regex.escape(name)}\\b"`) cannot be precompiled,
     * so we skip them to avoid false positives.
     */
    private fun hasLiteralPattern(call: KtCallExpression): Boolean {
        val patternArg = when {
            // Regex("pattern", ...) — first value argument
            call.calleeExpression?.text == "Regex" ->
                call.valueArguments.firstOrNull()?.getArgumentExpression()

            // "pattern".toRegex(...) — the receiver of the dot-qualified expression
            else -> (call.parent as? KtDotQualifiedExpression)?.receiverExpression
        } ?: return false

        val template = patternArg as? KtStringTemplateExpression ?: return false
        return template.entries.none { it is KtStringTemplateEntryWithExpression }
    }
}
