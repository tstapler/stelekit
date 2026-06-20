package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Flags direct subclasses of a sealed `Screen` class that carry neither `@HelpPage` nor `@HelpExempt`.
 *
 * Every user-navigable `Screen` entry must be tagged with one of:
 * - `@HelpPage(docs = SomeDocs::class)` — links to a Diataxis documentation class and a
 *   corresponding demo-graph page, enforcing the full documentation coverage chain.
 * - `@HelpExempt(reason = "...")` — explicitly exempts the screen from the requirement with a
 *   mandatory written justification (the `reason` parameter is non-nullable and non-defaulted,
 *   so a blank exemption does not compile).
 *
 * The rule only fires on direct children of a class named `Screen` that is also `sealed`.
 * This prevents false positives from unrelated `Screen`-named types elsewhere in the codebase.
 * The `Screen` class itself, and classes nested inside Screen subclasses, are not reported.
 *
 * Compliant:
 * ```kotlin
 * @HelpPage(docs = JournalsDocs::class)
 * data object Journals : Screen()
 *
 * @HelpExempt(reason = "Developer diagnostics; not reachable from user nav")
 * data object Logs : Screen()
 * ```
 *
 * Non-compliant:
 * ```kotlin
 * data object NewFeature : Screen()  // missing @HelpPage or @HelpExempt ✗
 * ```
 */
class MissingHelpPageAnnotationRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "MissingHelpPageAnnotation",
        severity = Severity.Defect,
        description = "Screen subclasses must carry @HelpPage or @HelpExempt(reason = \"...\") " +
            "to ensure every user-facing screen has documentation coverage.",
        debt = Debt.TEN_MINS,
    )

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)

        // Skip the Screen class itself
        if (classOrObject.name == "Screen") return

        val parent = classOrObject.containingClassOrObject ?: return

        // Only fire on direct children of a sealed class named "Screen"
        if (parent.name != "Screen") return
        if (!parent.hasModifier(KtTokens.SEALED_KEYWORD)) return

        val annotations = classOrObject.annotationEntries
            .mapNotNull { it.shortName?.asString() }
            .toSet()

        if ("HelpPage" !in annotations && "HelpExempt" !in annotations) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(classOrObject),
                    "${classOrObject.name} is a Screen subclass but has neither @HelpPage nor " +
                        "@HelpExempt(reason = \"...\"). Add @HelpPage to link it to documentation, " +
                        "or @HelpExempt with a written justification if this screen is internal-only.",
                )
            )
        }
    }
}
