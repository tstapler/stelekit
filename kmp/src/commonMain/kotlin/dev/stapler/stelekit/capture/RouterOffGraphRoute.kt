// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLocator
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.AssetCopier
import dev.stapler.stelekit.merge.MergeBlock
import dev.stapler.stelekit.merge.MergePage
import dev.stapler.stelekit.merge.PageKey
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.merge.UuidRemap
import dev.stapler.stelekit.merge.WriteCapabilityReason
import dev.stapler.stelekit.merge.WriteOutcome
import dev.stapler.stelekit.merge.WriteRefusedReason
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Off-graph capture constants shared with the share overlay. */
object OffGraphCapture {
    /** Shown in place of link suggestions when the target graph is not open (Story 4.1.3). */
    const val LINK_SUGGESTIONS_NOTE = "Link suggestions aren't available when saving to a graph that isn't open"

    const val REASON_PERMISSION = "permission"
    const val REASON_NOT_ROUND_TRIPPABLE = "not-round-trippable"
    const val REASON_TARGET_BUSY = "target-busy"
    const val REASON_UNWRITABLE = "unwritable"
    const val REASON_WRITE_REFUSED = "write-refused"
    const val REASON_WRITE_FAILED = "write-failed"

    private val UUID_FORM = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Same uuid the active path gives the block (`BlockUuid(captureId)`) when [captureId] is a uuid, else a stable hash. */
    fun blockUuid(captureId: String): String =
        if (UUID_FORM.matches(captureId)) captureId.lowercase() else UuidRemap.uuidFor(GraphId("capture"), captureId)
}

/**
 * Appends a share to the journal of an inactive graph through the ONE [TargetWriterRouter], so lock,
 * readiness and capability decisions stay in the router. The block is one-block `MergePage` content
 * whose uuid derives from the `captureId`, so a replay finds it and reports [AppendOutcome.AlreadyPresent].
 *
 * The page is built by appending to the parsed existing page rather than via `mergePage`: that
 * remaps uuids and adds `src-id`, which a first-party capture must not carry.
 *
 * Nothing is queued here: a refusal returns [AppendOutcome.Deferred] (see [InboxFallbackAppender]).
 * When the target is the ready graph the router hands out its active writer, which the app wires.
 *
 * @param assetCopier places a share's image under the TARGET graph's `assets/` (containment + dedupe)
 */
class RouterOffGraphRoute(
    private val router: TargetWriterRouter,
    private val locator: GraphLocator,
    private val assetCopier: AssetCopier,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : OffGraphContentRoute {

    override suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?): AppendOutcome {
        val id = captureId ?: UuidGenerator.generateV7()
        if (content.text.isBlank() && content.image == null) return AppendOutcome.Failed("Nothing to save")
        val root = locator.locate(graphId).fold({ return AppendOutcome.Failed(it.message) }, { it.path })
        val date = today()
        val page = PageKey(JournalUtils.formatDateForJournal(date), isJournal = true)
        val uuid = OffGraphCapture.blockUuid(id)

        val result = router.withWriter(graphId) { writer ->
            val existing = writer.readExisting(page).fold({ return@withWriter it.left() }, { it })
            if (existing != null && containsUuid(existing.blocks, uuid)) return@withWriter Written.Already.right()
            val block = buildBlock(uuid, content, root).fold({ return@withWriter it.left() }, { it })
            val base = existing ?: MergePage(page.name, isJournal = true, journalDate = date)
            val merged = base.copy(blocks = base.blocks + block)
            writer.write(page, merged).map { outcome -> Written.New(outcome.path()) }
        }
        return result.fold(::deferral) { written ->
            when (written) {
                Written.Already -> AppendOutcome.AlreadyPresent
                is Written.New -> AppendOutcome.AppendedOffGraph(graphId, written.path)
            }
        }
    }

    private fun buildBlock(uuid: String, content: ShareContent, root: String): Either<DomainError, MergeBlock> {
        val image = content.image ?: return MergeBlock(uuid, content.text).right()
        val name = "share-${ContentHasher.sha256(image).take(HASH_LEN)}.${extensionFor(content.imageMimeType)}"
        return assetCopier.store(name, image, root).map { rel ->
            MergeBlock(uuid, listOf(content.text, "![image](../assets/$rel)").filter { it.isNotBlank() }.joinToString("\n"))
        }
    }

    private fun containsUuid(blocks: List<MergeBlock>, uuid: String): Boolean =
        blocks.any { it.uuid.equals(uuid, ignoreCase = true) || containsUuid(it.children, uuid) }

    private fun WriteOutcome.path(): String = when (this) {
        is WriteOutcome.Created -> path
        is WriteOutcome.Updated -> path
        WriteOutcome.Unchanged -> ""
    }

    private sealed interface Written {
        data object Already : Written
        data class New(val path: String) : Written
    }

    private fun deferral(error: DomainError): AppendOutcome = when (error) {
        is DomainError.MergeError.Retryable -> AppendOutcome.Deferred(OffGraphCapture.REASON_TARGET_BUSY)
        is DomainError.MergeError.WriteRefused -> when (val r = error.reason) {
            is WriteRefusedReason.NotRoundTrippable ->
                AppendOutcome.Deferred(OffGraphCapture.REASON_NOT_ROUND_TRIPPABLE, permanent = true)
            is WriteRefusedReason.Unwritable -> when (r.reason) {
                is WriteCapabilityReason.NoGrant, WriteCapabilityReason.SafInboxOnly ->
                    AppendOutcome.Deferred(OffGraphCapture.REASON_PERMISSION)
                else -> AppendOutcome.Deferred(OffGraphCapture.REASON_UNWRITABLE)
            }
            else -> AppendOutcome.Deferred(OffGraphCapture.REASON_WRITE_REFUSED, permanent = true)
        }
        is DomainError.DatabaseError.NotFound -> AppendOutcome.Failed(error.message)
        else -> AppendOutcome.Deferred(OffGraphCapture.REASON_WRITE_FAILED)
    }

    private fun extensionFor(mime: String?): String = when (mime?.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "img"
    }

    private companion object {
        const val HASH_LEN = 8
    }
}
