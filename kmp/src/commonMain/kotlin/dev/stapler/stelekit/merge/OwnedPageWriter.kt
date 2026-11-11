package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Scoped exception to "never re-render an existing file" (ADR-006): replaces a page only when it is
 * an app-owned page whose canonical hash ([OwnedHash]) still matches its marker ([OwnedFileGuard]).
 * Separate from [TargetWriter] so no other writer changes; the active-graph writer is the only
 * production implementer. Callers reach it through `TargetWriterRouter.replaceOwnedPage`, which
 * runs the guard and the replace in one `withWriter` block under the target's `GraphWriteLock`.
 */
interface OwnedPageWriter {
    /**
     * Creates or replaces [page] with [owned] (block 0 carries the marker, see [OwnedMarker]).
     * [expectedPriorHash] is the [OwnedHash] the caller last wrote, or null for "no page yet".
     * Outcomes: `Created`, `Updated` (replaced), `Unchanged`; refusals are
     * `WriteRefused(NotOwned | EditedByUser | Conflicted)`; transient states are `Retryable`.
     */
    suspend fun replaceOwnedPage(
        page: PageKey,
        owned: MergePage,
        expectedPriorHash: String?,
    ): Either<DomainError, WriteOutcome>
}
