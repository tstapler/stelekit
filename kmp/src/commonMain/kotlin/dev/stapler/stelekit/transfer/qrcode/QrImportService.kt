package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.Validation
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Receive-side service (Story 3.2.1, models [dev.stapler.stelekit.db.ImageImportService]):
 * turns a decoded page name + markdown into a saved page.
 *
 * Takes a plain [String] for [import]'s `markdown`, not a [VerifiedTransferPayload], as of the
 * page-name-envelope fix: [QrTransferCoordinator] now unwraps [TransferPayloadEnvelope] on the
 * reassembled payload BEFORE calling this service, to recover the real `(pageName, markdown)`
 * pair — and [VerifiedTransferPayload]'s `internal` constructor is intentionally mintable ONLY by
 * [ChunkBuffer.reassemble]'s success branch, so [TransferPayloadEnvelope.unwrap] cannot re-wrap
 * its stripped-envelope markdown into a second one without weakening that invariant. The CRC32
 * proof gate this service used to gate on still ran — just one call earlier, in
 * `ChunkBuffer.reassemble()` — before [TransferPayloadEnvelope.unwrap] ever produced the `String`
 * this method receives. [QrTransferCoordinator] is this service's only production caller.
 *
 * Pipeline:
 * 1. Validate/normalize [targetName] ([Validation.validateName] — rejects directory-traversal
 *    segments like `../etc`; never used to construct a raw filesystem path, since every write
 *    below goes exclusively through [DatabaseWriteActor] DB rows).
 * 2. Detect a name collision via [findCollision] and resolve per [collisionChoice].
 * 3. Parse via [GraphLoader.importMarkdownString] (no [dev.stapler.stelekit.platform.FileSystem]
 *    read — in-memory entry point).
 * 4. Write via [DatabaseWriteActor.savePage] then [DatabaseWriteActor.saveBlocks] — never raw
 *    `SteleDatabaseQueries`.
 *
 * [DatabaseWriteActor.savePage] and [DatabaseWriteActor.saveBlocks] are separate, non-atomic
 * calls (no single atomic page+blocks write exists). If `saveBlocks` fails after `savePage`
 * succeeds for a brand-new page (no collision), this service deletes the orphaned page row before
 * returning `Left`, mirroring [dev.stapler.stelekit.db.ImageImportService]'s compensating-action
 * pattern — no page with zero blocks is ever left visible. On the OVERWRITE path, `pageToSave`'s
 * UUID is the PRE-EXISTING page's UUID (reused, not newly created), so that same delete would
 * destroy real user content that existed before this call — the page row is left in place instead
 * and [dev.stapler.stelekit.error.DomainError.QrTransferError.OverwriteFailedPreviousContentAffected]
 * is returned so the caller can warn the user their previous content may be affected. This applies
 * to a `savePage` failure just as much as a `saveBlocks` failure on the overwrite path — both run
 * after [DatabaseWriteActor.deleteBlocksForPage] has already cleared the old blocks, so either one
 * failing leaves the same blocks-empty pre-existing page and gets the same distinct error.
 */
class QrImportService(
    private val graphLoader: GraphLoader,
    private val pageRepository: PageRepository,
    private val writeActor: DatabaseWriteActor,
) {

    /** How to resolve a page-name collision detected by [findCollision] (Story 3.2.4, S11). */
    enum class CollisionChoice {
        /** Write the incoming page under a disambiguated name (e.g. "Meeting Notes (2)"). */
        KEEP_BOTH,

        /** Replace the existing page's content, reusing its UUID. */
        OVERWRITE,
    }

    /**
     * Pure DB read: returns the existing page named [targetName], or `null` if none exists.
     * The UI (S11 collision dialog) calls this before deciding whether to prompt the user or
     * call [import] directly — [import] itself never blocks on user input.
     */
    suspend fun findCollision(targetName: PageName): Page? =
        pageRepository.getPageByName(targetName.value).first().getOrNull()

    /**
     * Parses and persists [payload] as a page named [targetName] (post-normalization).
     *
     * [collisionChoice] defaults to [CollisionChoice.KEEP_BOTH] (the non-destructive default,
     * matching S11's never-silently-overwrite requirement) — callers that already know there is
     * no collision (or want the default) can omit it entirely.
     */
    suspend fun import(
        markdown: String,
        targetName: PageName,
        collisionChoice: CollisionChoice = CollisionChoice.KEEP_BOTH,
    ): Either<DomainError, PageName> {
        val normalizedName = try {
            PageName(Validation.validateName(targetName.value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return DomainError.ValidationError.ConstraintViolation(
                e.message ?: "invalid page name",
            ).left()
        }

        val existing = findCollision(normalizedName)
        val overwriting = existing != null && collisionChoice == CollisionChoice.OVERWRITE
        val finalName = when {
            existing == null -> normalizedName
            overwriting -> normalizedName
            else -> disambiguate(normalizedName)
        }

        val (parsedPage, parsedBlocks) = graphLoader.importMarkdownString(markdown, finalName).fold(
            ifLeft = { return it.left() },
            ifRight = { it },
        )

        // Reuse the existing page's UUID on overwrite so this is a true replace, not a duplicate
        // row; blocks are re-pointed to match.
        val pageToSave = if (overwriting) parsedPage.copy(uuid = existing!!.uuid) else parsedPage
        val blocksToSave = parsedBlocks.map { it.copy(pageUuid = pageToSave.uuid) }

        if (overwriting) {
            // Clear stale blocks from the previous version before writing the new set — otherwise
            // a shrinking block count would leave orphaned rows from the old content behind.
            writeActor.deleteBlocksForPage(pageToSave.uuid).fold(
                ifLeft = { return it.left() },
                ifRight = { /* continue */ },
            )
        }

        writeActor.savePage(pageToSave).fold(
            ifLeft = { err ->
                // Same hazard as the saveBlocks failure branch below: on the overwrite path,
                // deleteBlocksForPage above already cleared the pre-existing page's old blocks
                // before this call, so a savePage failure here also leaves the user's real page
                // blocks-empty, not just a failed new-page write. Surface the same distinct error
                // so the caller/UI warns the user consistently regardless of which write failed.
                return if (overwriting) {
                    DomainError.QrTransferError
                        .OverwriteFailedPreviousContentAffected(pageToSave.uuid.value)
                        .left()
                } else {
                    err.left()
                }
            },
            ifRight = { /* continue */ },
        )

        writeActor.saveBlocks(blocksToSave).fold(
            ifLeft = { err ->
                if (overwriting) {
                    // Do NOT delete pageToSave here: on the overwrite path pageToSave.uuid is the
                    // PRE-EXISTING page's UUID (reused above), not a newly-created row. Its blocks
                    // were already cleared by deleteBlocksForPage before this write was attempted,
                    // so deleting the page too would make a real, previously-imported page vanish
                    // entirely rather than just lose its latest content — strictly worse than
                    // leaving a blocks-empty page behind. Surface a distinct error instead so the
                    // caller/UI can tell the user their previous content may be affected.
                    // ponytail: a full pre-image restore (snapshot blocksToSave's pre-overwrite
                    // state before deleteBlocksForPage and re-save it here on failure) would make
                    // this fully lossless, but is deferred as future hardening — out of scope for
                    // this fix.
                    return DomainError.QrTransferError
                        .OverwriteFailedPreviousContentAffected(pageToSave.uuid.value)
                        .left()
                }
                // New page (not overwriting): pageToSave.uuid is a freshly-created row — nothing
                // pre-existed, so deleting it is a clean compensating rollback, not data loss.
                writeActor.deletePage(pageToSave.uuid)
                return err.left()
            },
            ifRight = { /* continue */ },
        )

        return PageName(pageToSave.name).right()
    }

    /** Appends " (2)", " (3)", ... until an unused name is found. */
    private suspend fun disambiguate(name: PageName): PageName {
        var suffix = 2
        var candidate = PageName("${name.value} ($suffix)")
        while (findCollision(candidate) != null) {
            suffix += 1
            candidate = PageName("${name.value} ($suffix)")
        }
        return candidate
    }
}
