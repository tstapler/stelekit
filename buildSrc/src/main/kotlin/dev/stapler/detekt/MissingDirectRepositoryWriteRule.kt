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
 * Flags `suspend fun` methods in `*Repository` interfaces that lack `@DirectRepositoryWrite`.
 *
 * The project serializes all SQLite writes through [DatabaseWriteActor] to prevent
 * SQLITE_BUSY contention. `@DirectRepositoryWrite` (`@RequiresOptIn(level=ERROR)`) enforces
 * this at the call site: the compiler rejects direct calls from code that hasn't declared
 * `@OptIn(DirectRepositoryWrite::class)`.
 *
 * This rule catches the gap where someone adds a new write method to a repository interface
 * and forgets the annotation — which would silently allow direct writes to bypass the actor.
 *
 * Reads return `Flow<>` and are exempt. Non-suspend functions are exempt.
 * Methods whose names begin with a read-only prefix (`get`, `find`, `is`, `has`, `count`,
 * `validate`, `check`, `calculate`, `select`) are also exempt — they are reads that happen
 * to use `suspend fun` rather than `Flow`. Methods prefixed with `cache` are in-memory-only
 * operations (e.g. `cacheEvictAll`, `cacheEvictPage`) with no database interaction.
 * Use [RepositoryWriteCallSiteRule] to verify that these exempt methods don't contain SQL writes.
 *
 * Compliant:
 * ```kotlin
 * interface BlockRepository {
 *     fun getBlockByUuid(uuid: String): Flow<Result<Block?>>  // read — exempt
 *     @DirectRepositoryWrite
 *     suspend fun saveBlock(block: Block): Result<Unit>        // write — annotated ✓
 * }
 * ```
 *
 * Non-compliant:
 * ```kotlin
 * interface SpanRepository {
 *     suspend fun insertSpan(span: SerializedSpan)  // write — missing @DirectRepositoryWrite ✗
 * }
 * ```
 */
class MissingDirectRepositoryWriteRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "MissingDirectRepositoryWrite",
        severity = Severity.Defect,
        description = "Suspend write methods in *Repository interfaces must carry @DirectRepositoryWrite " +
            "so the compiler rejects direct calls outside DatabaseWriteActor.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        val parent = function.containingClassOrObject as? KtClass ?: return
        if (!parent.isInterface()) return
        if (parent.name?.endsWith("Repository") != true) return
        if (!function.hasModifier(KtTokens.SUSPEND_KEYWORD)) return

        // Read methods return Flow — they don't require the annotation
        val returnType = function.typeReference?.text ?: ""
        if (returnType.startsWith("Flow")) return

        // Methods with read-only name prefixes are exempt: they are reads that happen to use
        // suspend fun instead of Flow. RepositoryWriteCallSiteRule provides a safety net by
        // verifying that these methods don't actually contain SQL write calls.
        val name = function.name ?: return
        if (READ_PREFIXES.any { name.startsWith(it) }) return

        val hasAnnotation = function.annotationEntries.any {
            it.shortName?.asString() == "DirectRepositoryWrite"
        }
        if (!hasAnnotation) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "${parent.name}.${function.name}() is a suspend write method and must carry " +
                        "@DirectRepositoryWrite to enforce routing through DatabaseWriteActor.",
                )
            )
        }
    }

    companion object {
        private val READ_PREFIXES = setOf(
            "get", "find", "is", "has", "count", "validate", "check", "calculate", "select",
            // In-memory cache management (cache* prefix) — no database interaction, no actor routing needed
            "cache",
        )
    }
}
