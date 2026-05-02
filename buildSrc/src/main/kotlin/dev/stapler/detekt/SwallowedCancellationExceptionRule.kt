package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTryExpression

/**
 * Flags `catch(Exception)` or `catch(Throwable)` blocks inside `suspend` functions or coroutine
 * builder lambdas that do not rethrow [kotlinx.coroutines.CancellationException].
 *
 * Swallowing `CancellationException` prevents structured concurrency from propagating
 * cancellation, causing coroutines to keep running (and potentially logging false errors)
 * after their job has been cancelled. This is the most common source of "StandaloneCoroutine
 * was cancelled" log spam on Android, where `onTrimMemory` triggers `cancelBackgroundWork()`.
 *
 * **Compliant** — preceding catch clause:
 * ```kotlin
 * suspend fun load() {
 *     try {
 *         parseFile(path)
 *     } catch (e: CancellationException) {
 *         throw e
 *     } catch (e: Exception) {
 *         logger.error("parse failed: ${e.message}")
 *     }
 * }
 * ```
 *
 * **Compliant** — inline guard:
 * ```kotlin
 * suspend fun load() {
 *     try {
 *         parseFile(path)
 *     } catch (e: Exception) {
 *         if (e is CancellationException) throw e
 *         logger.error("parse failed: ${e.message}")
 *     }
 * }
 * ```
 *
 * **Non-compliant**:
 * ```kotlin
 * suspend fun load() {
 *     try {
 *         parseFile(path)
 *     } catch (e: Exception) {           // ← flagged
 *         logger.error("parse failed: ${e.message}")
 *     }
 * }
 *
 * scope.launch {
 *     try {
 *         doWork()
 *     } catch (e: Exception) { }         // ← flagged
 * }
 * ```
 *
 * **Not flagged** — regular (non-suspend) function:
 * ```kotlin
 * fun migrate() {
 *     try {
 *         readFile()
 *     } catch (e: Exception) { }         // ← not flagged (no coroutine context)
 * }
 * ```
 */
class SwallowedCancellationExceptionRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "SwallowedCancellationException",
        severity = Severity.Defect,
        description = "catch(Exception) or catch(Throwable) in a suspend/coroutine context swallows " +
            "CancellationException, breaking structured cancellation.",
        debt = Debt.TEN_MINS,
    )

    override fun visitTryExpression(expression: KtTryExpression) {
        super.visitTryExpression(expression)

        if (!isInSuspendOrCoroutineContext(expression)) return

        expression.catchClauses.forEachIndexed { index, catchClause ->
            val param = catchClause.catchParameter ?: return@forEachIndexed
            val typeName = param.typeReference?.text ?: return@forEachIndexed
            if (typeName != "Exception" && typeName != "Throwable") return@forEachIndexed

            // Safe if a preceding catch clause already handles CancellationException
            val hasPrecedingCancellationCatch = expression.catchClauses.take(index).any { prev ->
                prev.catchParameter?.typeReference?.text?.contains("CancellationException") == true
            }
            if (hasPrecedingCancellationCatch) return@forEachIndexed

            // Safe if the body already rethrows CancellationException
            val bodyText = catchClause.catchBody?.text ?: return@forEachIndexed
            val paramName = param.name ?: return@forEachIndexed
            if (bodyText.contains("CancellationException") || bodyText.contains("throw $paramName")) {
                return@forEachIndexed
            }

            report(
                CodeSmell(
                    issue,
                    Entity.from(catchClause),
                    "catch($typeName) swallows CancellationException — add " +
                        "`catch (e: CancellationException) { throw e }` before this clause, " +
                        "or add `if ($paramName is CancellationException) throw $paramName` " +
                        "inside the catch body.",
                )
            )
        }
    }

    /**
     * Returns true when [element] is inside a `suspend fun` or a lambda passed directly to a
     * known coroutine builder (`launch`, `async`, `withContext`, etc.).
     *
     * The search stops at the first enclosing [KtNamedFunction]: if that function is `suspend`
     * we return true, otherwise false. A coroutine-builder lambda discovered before reaching
     * a named function also triggers a true result.
     */
    private fun isInSuspendOrCoroutineContext(element: KtTryExpression): Boolean {
        var current = element.parent
        while (current != null) {
            when (current) {
                is KtNamedFunction -> return current.hasModifier(KtTokens.SUSPEND_KEYWORD)
                is KtLambdaExpression -> {
                    val callExpr = (current.parent as? KtLambdaArgument)?.parent as? KtCallExpression
                    val callee = callExpr?.calleeExpression?.text?.substringAfterLast(".")
                    if (callee in COROUTINE_BUILDERS) return true
                }
            }
            current = current.parent
        }
        return false
    }

    companion object {
        private val COROUTINE_BUILDERS = setOf(
            "launch", "async", "withContext", "coroutineScope", "supervisorScope",
            "runBlocking", "flow", "channelFlow", "callbackFlow",
        )
    }
}
