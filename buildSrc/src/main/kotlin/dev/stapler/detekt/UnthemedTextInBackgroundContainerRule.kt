package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression

/**
 * Flags `Text(...)` calls without an explicit `color` argument that are direct children
 * of a `Box`, `Row`, or `Column` whose `modifier` argument contains `.background(`.
 *
 * The Compose `.background()` modifier changes the drawn surface colour but does **not**
 * update `LocalContentColor`. `Text` without an explicit `color` inherits
 * `LocalContentColor` from an ancestor `Surface`, which may not contrast with the
 * background applied by the modifier — producing unreadable text on theme switches.
 *
 * The fix is to either:
 * - Use `Surface(color = …) { … }` so `LocalContentColor` is automatically paired, or
 * - Pass `color = MaterialTheme.colorScheme.onXxx` explicitly to every `Text` inside the container.
 *
 * **Compliant**:
 * ```kotlin
 * // Option 1: Surface manages LocalContentColor automatically
 * Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
 *     Text("label")
 * }
 *
 * // Option 2: explicit color when .background() is necessary
 * Column(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
 *     Text("label", color = MaterialTheme.colorScheme.onSurfaceVariant)
 * }
 * ```
 *
 * **Non-compliant**:
 * ```kotlin
 * Column(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
 *     Text("label")   // ← flagged: no color; inherits LocalContentColor from ancestor
 * }
 * ```
 *
 * ## Known detection gaps
 *
 * **Extracted composable**: The rule only inspects direct `Text(...)` call-expressions in
 * the container's trailing lambda. If the `Text` is inside a helper composable called from
 * the lambda, the helper is responsible for its own colour but this rule will not flag it:
 * ```kotlin
 * Column(modifier = Modifier.background(headerColor)) {
 *     TableCell("value")   // ← NOT flagged; rule cannot see inside TableCell
 * }
 * // Fix: ensure TableCell passes explicit color = ... to its Text call.
 * ```
 *
 * **Modifier variable**: If the `.background()` call is stored in a variable and then
 * passed as `modifier = myModifier`, the rule will not detect it because it checks argument
 * text rather than resolved types:
 * ```kotlin
 * val m = Modifier.background(color)
 * Column(modifier = m) { Text("label") }   // ← NOT flagged
 * ```
 */
class UnthemedTextInBackgroundContainerRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "UnthemedTextInBackgroundContainer",
        severity = Severity.Defect,
        description = "Text() without an explicit color inside a Box/Row/Column that uses " +
            ".background() — LocalContentColor is not updated by .background(), so text " +
            "colour may not contrast with the surface in all themes.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(call: KtCallExpression) {
        super.visitCallExpression(call)

        val callee = call.calleeExpression?.text ?: return
        if (callee !in CONTAINER_COMPOSABLES) return

        // Check if any value argument contains ".background(" (Modifier chaining convention)
        val hasBackgroundModifier = call.valueArguments.any { arg ->
            arg.text.contains(".background(")
        }
        if (!hasBackgroundModifier) return

        // Walk only direct statements in the trailing lambda — avoids false positives
        // from nested composable functions that manage their own colours.
        val lambdaBody = call.lambdaArguments
            .firstOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression ?: return

        lambdaBody.statements.forEach { stmt ->
            val textCall = stmt as? KtCallExpression ?: return@forEach
            if (textCall.calleeExpression?.text != "Text") return@forEach

            val hasColorArg = textCall.valueArguments.any { arg ->
                arg.getArgumentName()?.asName?.identifier == "color"
            }
            if (!hasColorArg) {
                report(
                    CodeSmell(
                        issue,
                        Entity.from(textCall),
                        "Text() has no explicit color inside a ${callee} with .background(). " +
                            "Pass color = MaterialTheme.colorScheme.onXxx, or replace " +
                            ".background() with a Surface composable.",
                    )
                )
            }
        }
    }

    companion object {
        private val CONTAINER_COMPOSABLES = setOf("Box", "Row", "Column")
    }
}
