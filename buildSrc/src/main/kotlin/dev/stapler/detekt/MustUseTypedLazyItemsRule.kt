package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

/**
 * Bans direct `items(key = ...)` and `itemsIndexed(key = ...)` calls in favour of the
 * project wrappers `typedItems`, `typedItemsIndexed`, `typedGridItems`, and
 * `typedGridItemsIndexed` in `TypedLazyItems.kt`.
 *
 * **Why the wrappers exist**
 *
 * Compose's built-in `items(key: ((T) -> Any)?)` accepts any type. On Android,
 * `SaveableStateHolder` validates that item keys are Bundle-safe (String, Int, Long, …).
 * A Kotlin `value class` wrapping a `String` — e.g. `PageUuid`, `BlockUuid` — passes
 * Kotlin's type checker but fails the runtime check, crashing the app on first render with
 * `IllegalArgumentException`. Desktop JVM skips the validation, so the bug is Android-only
 * and invisible to JVM tests.
 *
 * The project wrappers narrow the `key` parameter to `(T) -> String`, making the same
 * mistake a compile error. This rule is the backstop that prevents bypassing the wrappers
 * via a direct `items()` call.
 *
 * **Type-Driven Design gate verdict**
 *
 * A full compile-time solution would require Compose's `key` parameter to accept
 * `(T) -> String` instead of `(T) -> Any` — outside this project's control. The project
 * wrappers provide compile-time enforcement at our call sites; this lint rule catches any
 * direct `items(key=...)` calls that bypass them.
 *
 * **Compliant** (compile-time safe):
 * ```kotlin
 * typedItems(pages, key = { it.uuid.asLazyKey() }) { page -> ... }
 * typedItemsIndexed(blocks, key = { _, b -> b.uuid.asLazyKey() }) { _, b -> ... }
 * typedGridItems(images, key = { it.uuid }) { image -> ... }  // uuid: String here
 * typedGridItemsIndexed(sections, key = { i, s -> "$i:${s.id}" }) { _, s -> ... }
 * ```
 *
 * **Non-compliant** (compile-time silent, runtime crash on Android):
 * ```kotlin
 * items(pages, key = { it.uuid })          // named key arg
 * items(pages, { it.uuid })                // positional key arg — also caught
 * itemsIndexed(blocks, key = { _, b -> b.uuid })
 * ```
 *
 * **Escape hatch** — suppress only when the key is already a Bundle-safe primitive
 * (`String`, `Int`, `Long`, `Boolean`) AND using a typed wrapper is not possible
 * (e.g., inside a third-party scope that does not accept the wrappers). Do NOT suppress
 * for value class keys.
 * ```kotlin
 * @Suppress("MustUseTypedLazyItems")
 * items(sections, key = { it.displayOrder })  // displayOrder: Int — already Bundle-safe
 * ```
 *
 * The `TypedLazyItems.kt` implementation file and test source sets are excluded from this
 * rule in `detekt.yml` because the wrappers themselves call the count-based
 * `items(count, key, …)` overload internally.
 */
class MustUseTypedLazyItemsRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "MustUseTypedLazyItems",
        severity = Severity.Defect,
        description = "Direct items(key=…)/itemsIndexed(key=…) call detected. " +
            "Use typedItems, typedItemsIndexed, typedGridItems, or typedGridItemsIndexed " +
            "from TypedLazyItems.kt — they enforce a String key and prevent Android " +
            "Bundle-safety crashes.",
        debt = Debt.FIVE_MINS,
    )

    private companion object {
        val BANNED_CALLS = setOf("items", "itemsIndexed")
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)

        val calleeName = (expression.calleeExpression as? KtNameReferenceExpression)
            ?.getReferencedName() ?: return
        if (calleeName !in BANNED_CALLS) return

        val args = expression.valueArguments

        // Named argument: items(list, key = { ... })
        val hasNamedKeyArg = args.any {
            it.getArgumentName()?.asName?.identifier == "key"
        }

        // Positional argument: items(list, { ... }) — second arg, no name, is a lambda
        // passed INSIDE the parentheses (not a trailing lambda).
        // KtLambdaArgument is the PSI type for trailing lambdas (outside parens); we
        // exclude those so `items(list) { content }` is not flagged.
        val hasPositionalKeyArg = !hasNamedKeyArg
            && args.size >= 2
            && args[1] !is KtLambdaArgument
            && args[1].getArgumentName() == null
            && args[1].getArgumentExpression() is KtLambdaExpression

        if (!hasNamedKeyArg && !hasPositionalKeyArg) return

        report(
            CodeSmell(
                issue,
                Entity.from(expression),
                "Direct `$calleeName(key = …)` call. Use the project wrapper instead: " +
                    "`typedItems`, `typedItemsIndexed`, `typedGridItems`, or " +
                    "`typedGridItemsIndexed` (dev.stapler.stelekit.ui.components). " +
                    "They enforce a String key at compile time, preventing the " +
                    "Android Bundle-safety crash that happens with value class keys. " +
                    "See @Suppress(\"MustUseTypedLazyItems\") in the rule KDoc for the " +
                    "escape hatch when the key is already a Bundle-safe primitive.",
            )
        )
    }
}
