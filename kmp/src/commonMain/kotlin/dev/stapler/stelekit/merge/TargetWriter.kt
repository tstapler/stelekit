package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import okio.ByteString.Companion.encodeUtf8

/** Identifies a page within one target graph. */
data class PageKey(val name: String, val isJournal: Boolean = false)

/** Why a writer refused; carried by [DomainError.MergeError.WriteRefused]. A refusal writes nothing. */
sealed interface WriteRefusedReason {
    val message: String

    data class PathOutsideGraph(val path: String) : WriteRefusedReason {
        override val message = "Path is outside the graph: $path"
    }

    data class InvalidPageName(val name: String) : WriteRefusedReason {
        override val message = "Invalid page name for a file path"
    }

    data class JournalFormatUnsupported(val format: String) : WriteRefusedReason {
        override val message = "Unsupported journal file-name format: $format"
    }

    data class NotRoundTrippable(val detail: dev.stapler.stelekit.merge.NotRoundTrippable) : WriteRefusedReason {
        override val message = detail.message
    }

    /** The target cannot be written off-graph (capability changed, e.g. a grant was lost mid-run). */
    data class Unwritable(val reason: WriteCapabilityReason) : WriteRefusedReason {
        override val message = reason.userText
    }
}

sealed interface WriteOutcome {
    /** [contentHash] is [TargetWriter.fileHash] of the new file, for the undo manifest. */
    data class Created(val path: String, val contentHash: String) : WriteOutcome

    /**
     * [skippedPropertyKeys] are page properties whose merged value was NOT written (the file keeps its own);
     * the off-graph writer only adds missing keys, so an alias/tags union lands here. The active writer applies the whole merged set.
     */
    data class Updated(
        val path: String,
        val contentHash: String,
        val insertedBlocks: Int,
        val skippedPropertyKeys: List<String> = emptyList(),
    ) : WriteOutcome

    /** The result was byte-identical to disk; nothing was written. */
    data object Unchanged : WriteOutcome
}

/**
 * [removed] are the uuids actually spliced out, [skippedEdited] failed the hash check (edited
 * since the copy) and were left in place, [missing] are no longer in the file.
 */
data class RemoveReport(
    val removed: Set<String>,
    val skippedEdited: Set<String>,
    val missing: Set<String>,
)

/** Hash of a block subtree (uuid, content, sorted properties, children); the manifest stores it for undo. */
object BlockContentHash {
    fun of(block: MergeBlock): String = canonical(block).encodeUtf8().sha256().hex()

    private fun canonical(b: MergeBlock): String = buildString {
        append('[').append(b.uuid.orEmpty()).append('|').append(b.content)
        b.properties.entries.sortedBy { it.key }.forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        b.children.forEach { append(canonical(it)) }
        append(']')
    }
}

/**
 * Writes merged pages into one target graph. Every method returns [DomainError]; a refusal is
 * [DomainError.MergeError.WriteRefused]. The inbox is never this port's concern.
 */
interface TargetWriter {
    /** Parsed existing page, or null when it has no file. Also refuses pages that fail the round-trip guard. */
    suspend fun readExisting(page: PageKey): Either<DomainError, MergePage?>

    /** [merged] is the full page after `mergePage`; only blocks absent from the file are inserted. */
    suspend fun write(page: PageKey, merged: MergePage): Either<DomainError, WriteOutcome>

    /** Deletes the page file only if its hash is still [expectedHash]; absent file is success. */
    suspend fun deletePageFile(page: PageKey, expectedHash: String): Either<DomainError, Unit>

    /** SHA-256 hex of the page file bytes, or null when there is no file. */
    suspend fun fileHash(page: PageKey): Either<DomainError, String?>

    /** Splices out [uuids] whose subtree still hashes to [expectedContentHashes]; others are reported, not touched. */
    suspend fun removeBlocks(
        page: PageKey,
        uuids: Set<String>,
        expectedContentHashes: Map<String, String>,
    ): Either<DomainError, RemoveReport>
}
