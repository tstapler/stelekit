package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Flags `override suspend fun` methods in `*Repository` implementation classes whose bodies
 * contain SQL write calls (`queries.insert*`, `queries.delete*`, `queries.update*`,
 * `queries.upsert*`, `queries.transaction`, `restricted.*`) but whose class lacks
 * `@OptIn(DirectRepositoryWrite::class)`.
 *
 * This is the authoritative enforcement layer: it inspects what the implementation
 * *actually does* rather than relying on naming conventions. A method named `getStatistics()`
 * that only calls `queries.selectBlockCount()` is correctly left alone; a method named
 * `insertSpan()` that calls `restricted.insertSpan(...)` is flagged if the class is missing
 * the opt-in.
 *
 * The companion rule [MissingDirectRepositoryWriteRule] checks the interface side.
 *
 * Compliant:
 * ```kotlin
 * @OptIn(DirectRepositoryWrite::class)
 * class SqlDelightSpanRepository : SpanRepository {
 *     override suspend fun insertSpan(span: SerializedSpan) {
 *         restricted.insertSpan(...)  // ✓ class has @OptIn
 *     }
 * }
 * ```
 *
 * Non-compliant:
 * ```kotlin
 * class SqlDelightSpanRepository : SpanRepository {
 *     override suspend fun insertSpan(span: SerializedSpan) {
 *         restricted.insertSpan(...)  // ✗ class missing @OptIn(DirectRepositoryWrite::class)
 *     }
 * }
 * ```
 */
class RepositoryWriteCallSiteRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "RepositoryWriteCallSite",
        severity = Severity.Defect,
        description = "Repository override performs SQL writes but its class lacks " +
            "@OptIn(DirectRepositoryWrite::class). Also ensure the interface method carries " +
            "@DirectRepositoryWrite so the compiler rejects direct calls outside DatabaseWriteActor.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        val parentClass = function.containingClassOrObject as? KtClass ?: return
        if (parentClass.isInterface()) return
        if (!isRepositoryClass(parentClass)) return
        if (!function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        if (!function.hasModifier(KtTokens.SUSPEND_KEYWORD)) return

        val bodyText = function.bodyExpression?.text ?: return
        if (!SQL_WRITE_PATTERN.containsMatchIn(bodyText)) return

        val classHasOptIn = parentClass.annotationEntries.any {
            it.shortName?.asString() == "OptIn" && it.text.contains("DirectRepositoryWrite")
        }
        if (!classHasOptIn) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "${parentClass.name}.${function.name}() performs SQL writes. " +
                        "Add @OptIn(DirectRepositoryWrite::class) to ${parentClass.name} and " +
                        "annotate the interface method with @DirectRepositoryWrite.",
                )
            )
        }
    }

    private fun isRepositoryClass(cls: KtClass): Boolean {
        if (cls.name?.endsWith("Repository") == true) return true
        return cls.superTypeListEntries.any { it.text.contains("Repository") }
    }

    companion object {
        // Matches SQL write calls on the queries object or RestrictedDatabaseQueries wrapper.
        // Reads use queries.select*/queries.count* — those are not matched.
        private val SQL_WRITE_PATTERN = Regex(
            """(?:restricted|queries)\s*\.\s*(?:insert|delete|update|upsert|transaction)\w*"""
        )
    }
}
