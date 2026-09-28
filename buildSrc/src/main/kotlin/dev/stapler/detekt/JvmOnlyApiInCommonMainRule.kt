package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

/**
 * Flags references to [kotlin.text.Charsets] in Kotlin Multiplatform shared source sets.
 *
 * `Charsets.UTF_8` resolves to a JVM-only class. Using it in `commonMain`, `commonTest`, or
 * `businessTest` breaks compilation on WASM/JS targets with "Unresolved reference 'Charsets'".
 * Use KMP-safe stdlib alternatives instead:
 *
 * - `bytes.toString(Charsets.UTF_8)` → `bytes.decodeToString()`
 * - `content.toByteArray(Charsets.UTF_8)` → `content.encodeToByteArray()`
 *
 * The rule fires on any `Charsets` name reference. JVM-only source sets (`jvmMain`, `jvmTest`,
 * `androidMain`, `androidUnitTest`) are excluded via `detekt.yml` — they are valid hosts for
 * JVM-specific APIs.
 *
 * **Why types can't close this gap**: `Charsets` is a JVM stdlib class; there is no
 * Kotlin-level mechanism to block its use in KMP shared code. The lint rule is the
 * earliest practical enforcement layer.
 *
 * Non-compliant (in commonMain/commonTest/businessTest):
 * ```kotlin
 * val text = bytes.toString(Charsets.UTF_8)      // breaks WASM/JS compile
 * val raw  = content.toByteArray(Charsets.UTF_8) // breaks WASM/JS compile
 * ```
 *
 * Compliant:
 * ```kotlin
 * val text = bytes.decodeToString()      // KMP stdlib — all targets
 * val raw  = content.encodeToByteArray() // KMP stdlib — all targets
 * ```
 */
class JvmOnlyApiInCommonMainRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "JvmOnlyApiInCommonMain",
        severity = Severity.Defect,
        description = "Charsets is JVM-only and breaks WASM/JS compilation. " +
            "Use decodeToString() / encodeToByteArray() instead.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
        super.visitSimpleNameExpression(expression)
        if (expression !is KtNameReferenceExpression) return
        if (expression.getReferencedName() == "Charsets") {
            report(
                CodeSmell(
                    issue,
                    Entity.from(expression),
                    "Charsets is a JVM-only API and will not compile on WASM/JS targets. " +
                        "Replace bytes.toString(Charsets.UTF_8) with bytes.decodeToString(), " +
                        "and content.toByteArray(Charsets.UTF_8) with content.encodeToByteArray().",
                )
            )
        }
    }
}
