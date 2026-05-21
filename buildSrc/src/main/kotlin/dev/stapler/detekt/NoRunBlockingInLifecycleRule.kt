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
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtVisitorVoid

/**
 * Flags `runBlocking { }` calls inside Android lifecycle callbacks.
 *
 * Calling `runBlocking` in `onCreate`, `onStart`, `onResume`, or similar lifecycle methods
 * parks the main thread until the coroutine finishes. On devices with large SQLite databases
 * or slow storage, this exceeds the 5-second ANR threshold. The migration that introduced
 * the v0.24 ANR was exactly this pattern: `runBlocking { MigrationRunner.applyAll(driver) }`
 * was reachable from `Application.onCreate` via `switchGraph`.
 *
 * Compliant alternatives:
 * - `appScope.async(IO) { ... }` — returns a `Deferred<Unit>` that can be awaited later
 * - Launch as a background job and expose results via `StateFlow`
 *
 * Non-compliant:
 * ```kotlin
 * override fun onCreate() {
 *     runBlocking { fileSystem.flushPendingWrites() }  // blocks main thread ✗
 * }
 * ```
 *
 * Compliant:
 * ```kotlin
 * override fun onCreate() {
 *     startupFlushJob = appScope.async(Dispatchers.IO) { fileSystem.flushPendingWrites() }
 * }
 * ```
 */
class NoRunBlockingInLifecycleRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "NoRunBlockingInLifecycle",
        severity = Severity.Defect,
        description = "runBlocking inside an Android lifecycle callback blocks the main thread and can cause ANR. " +
            "Use appScope.async(IO) { ... } and await the Deferred before accessing the result.",
        debt = Debt.TWENTY_MINS,
    )

    private val lifecycleCallbacks = setOf(
        "onCreate", "onStart", "onResume", "onRestart",
        "onBind", "onStartCommand", "onHandleIntent",
        "onAttachedToWindow", "onCreateView", "onViewCreated",
    )

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)

        if (!function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return
        if (function.name !in lifecycleCallbacks) return

        val body = function.bodyBlockExpression ?: return

        var found = false
        body.accept(object : KtVisitorVoid() {
            override fun visitCallExpression(call: KtCallExpression) {
                super.visitCallExpression(call)
                if (call.calleeExpression?.text == "runBlocking") {
                    found = true
                }
            }
        })

        if (found) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(function),
                    "runBlocking in override fun ${function.name}() blocks the calling thread. " +
                        "Use appScope.async(Dispatchers.IO) { ... } and expose results via StateFlow or Deferred.",
                )
            )
        }
    }
}
