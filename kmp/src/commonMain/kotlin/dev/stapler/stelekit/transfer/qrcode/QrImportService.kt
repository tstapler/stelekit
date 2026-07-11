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
 * turns a proof-gated [VerifiedTransferPayload] into a saved page.
 *
 * Accepts ONLY [VerifiedTransferPayload] — never a raw [String] — so "this markdown passed the
 * CRC32 proof gate" is a compile-time-visible fact at every call site (Parse-Don't-Validate).
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
 * succeeds, this service deletes the orphaned page row before returning `Left`, mirroring
 * [dev.stapler.stelekit.db.ImageImportService]'s compensating-action pattern — no page with zero
 * blocks is ever left visible.
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
        payload: VerifiedTransferPayload,
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

        val (parsedPage, parsedBlocks) = graphLoader.importMarkdownString(payload.markdown, finalName).fold(
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
            ifLeft = { return it.left() },
            ifRight = { /* continue */ },
        )

        writeActor.saveBlocks(blocksToSave).fold(
            ifLeft = { err ->
                // Compensating rollback (Story 3.2.1 AC): savePage succeeded but saveBlocks
                // failed — delete the orphaned zero-block page so it never becomes visible.
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
