package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Flags `remember { SomeClass(capturedVar) }` calls that capture outer-scope variables
 * through constructor arguments without listing them as explicit keys.
 *
 * When a `remember { }` block has no key arguments, Compose returns the same instance
 * across recompositions. If the instance is constructed from an outer variable that can
 * change (e.g. a rebuilt `VoicePipelineConfig`), the ViewModel or object will hold a
 * stale reference indefinitely.
 *
 * The fix is to list the captured variable as a key:
 * `remember(capturedVar) { SomeClass(capturedVar) }`.
 *
 * Note: [io.nlopez.compose.rules] `RememberContentMissing` covers direct property reads
 * inside remember but does not trace through constructor argument lists — this rule
 * closes that gap.
 *
 * Compliant:
 * ```kotlin
 * val vm = remember(pipeline) { ViewModel(pipeline, service) }  // key listed ✓
 * val vm = remember { ViewModel() }                              // no captured args ✓
 * ```
 *
 * Non-compliant:
 * ```kotlin
 * val vm = remember { ViewModel(pipeline, service) }  // pipeline/service not listed as keys ✗
 * ```
 */
class RememberKeyForConstructorArgsRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "RememberKeyForConstructorArgs",
        severity = Severity.Defect,
        description = "remember { } captures outer variables through constructor args without listing them " +
            "as keys — the instance will never be recreated when those variables change.",
        debt = Debt.TEN_MINS,
    )

    override fun visitCallExpression(call: KtCallExpression) {
        super.visitCallExpression(call)

        // Only match bare `remember { }` — callee text is "remember", no explicit key args in
        // parentheses. Note: `call.valueArguments` includes trailing lambdas, so we must check
        // `valueArgumentList?.arguments` (in-paren args only) to detect key presence correctly.
        if (call.calleeExpression?.text != "remember") return
        if (call.valueArgumentList?.arguments?.isNotEmpty() == true) return

        val lambdaBody = call.lambdaArguments
            .firstOrNull()
            ?.getLambdaExpression()
            ?.bodyExpression ?: return

        // Walk all call expressions in the lambda looking for constructor-like calls
        // (callee starts with uppercase letter, per Kotlin convention) whose argument list
        // includes at least one bare name reference — indicating a captured outer variable.
        val violation = PsiTreeUtil.findChildrenOfType(lambdaBody, KtCallExpression::class.java)
            .any { inner ->
                val callee = inner.calleeExpression?.text ?: return@any false
                if (callee.firstOrNull()?.isUpperCase() != true) return@any false
                inner.valueArguments.any { it.getArgumentExpression() is KtNameReferenceExpression }
            }

        if (violation) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(call),
                    "remember { } closes over constructor arguments without listing them as keys. " +
                        "Use remember(arg1, arg2) { ... } so the instance is recreated when args change.",
                )
            )
        }
    }
}
