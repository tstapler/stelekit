package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtClass

/**
 * Flags class-level `@OptIn(DirectRepositoryWrite::class)` on non-repository, non-actor classes.
 *
 * A class-level opt-in grants blanket bypass permission for every call site in the class,
 * silently defeating the per-call enforcement that `@DirectRepositoryWrite` provides. This
 * was the root cause of the block write race bug: `BlockStateManager` had class-level opt-in,
 * so structural ops that should have been routed through `DatabaseWriteActor` compiled without
 * error even though they bypassed the actor.
 *
 * The correct pattern is function-level `@OptIn` on the narrowest private helper that is
 * the designated direct-write bypass. This makes each bypass visible and reviewable.
 *
 * **Allowed** class-level opt-in:
 * - `*Repository*` — repository implementations are the canonical write layer
 * - `DatabaseWriteActor` — the actor is the serialization point
 * - `*Migration*`, `ChangeApplier` — run before the actor exists
 * - `InMemory*`, `Datascript*`, `FakeRepositories`, `Synthetic*` — test doubles
 *
 * **Flagged** (non-exhaustive examples):
 * - `*StateManager` — must route writes through the actor
 * - `*ViewModel` — must route writes through the actor
 * - `*Screen`, `*Panel`, `*Dashboard` — UI components
 *
 * Non-compliant:
 * ```kotlin
 * @OptIn(DirectRepositoryWrite::class)   // ✗ blanket class-level bypass
 * class BlockStateManager(...) {
 *     fun addNewBlock(...) {
 *         blockRepository.splitBlock(...)  // races with actor-queued content writes
 *     }
 * }
 * ```
 *
 * Compliant:
 * ```kotlin
 * class BlockStateManager(...) {
 *     @OptIn(DirectRepositoryWrite::class)  // ✓ narrowest possible scope
 *     private suspend fun writeSplitBlock(...) =
 *         writeActor?.splitBlock(...) ?: blockRepository.splitBlock(...)
 * }
 * ```
 */
class ClassLevelDirectRepositoryWriteOptInRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "ClassLevelDirectRepositoryWriteOptIn",
        severity = Severity.Defect,
        description = "Class-level @OptIn(DirectRepositoryWrite::class) masks missing actor " +
            "routing. Move @OptIn to the narrowest private helper that is the designated " +
            "direct-write bypass. Classes outside the repository/actor/migration layer must " +
            "route writes through DatabaseWriteActor.",
        debt = Debt.TWENTY_MINS,
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isInterface()) return

        val name = klass.name ?: return
        if (ALLOWED_PATTERNS.any { name.contains(it) }) return

        val hasClassLevelOptIn = klass.annotationEntries.any {
            it.shortName?.asString() == "OptIn" && it.text.contains("DirectRepositoryWrite")
        }
        if (!hasClassLevelOptIn) return

        report(
            CodeSmell(
                issue,
                Entity.from(klass),
                "$name uses class-level @OptIn(DirectRepositoryWrite::class), which silently " +
                    "permits all direct repository write calls in the class — including any " +
                    "that should be routed through DatabaseWriteActor. Move @OptIn to each " +
                    "private writeFoo() helper at the narrowest scope.",
            )
        )
    }

    companion object {
        private val ALLOWED_PATTERNS = setOf(
            "Repository",       // *Repository* implementations and interfaces
            "DatabaseWriteActor",
            "Migration",        // migration utilities (run before the actor exists)
            "ChangeApplier",
            "InMemory",         // in-memory test doubles
            "Datascript",       // Datascript repository implementations
            "FakeRepositories", // test fixture file
            "Synthetic",        // benchmark synthetic builders
        )
    }
}
