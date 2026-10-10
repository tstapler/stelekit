package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.model.GraphInfo

enum class SourceKind { Page, Journal }

/**
 * Listing projection of one source file: no content. [fileName] is relative to the graph root
 * (`pages/Foo.md`), [name] is the decoded page name.
 */
data class SourceEntry(
    val name: String,
    val kind: SourceKind,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long?,
) {
    val isJournal: Boolean get() = kind == SourceKind.Journal
}

sealed interface ReadError {
    val message: String

    data object NoGrant : ReadError {
        override val message = "No permission to read this graph's folder"
    }

    data object FolderMissing : ReadError {
        override val message = "Graph folder not found"
    }

    data object Encrypted : ReadError {
        override val message = "Graph is encrypted"
    }

    data class TooLarge(val sizeBytes: Long, val limitBytes: Long) : ReadError {
        override val message = "File is $sizeBytes bytes (limit $limitBytes)"
    }

    /** One file could not be read or parsed (missing, outside the graph, non-UTF-8, parser failure). */
    data class Unreadable(val reason: String) : ReadError {
        override val message = reason
    }
}

/**
 * Read-only, bounded view of a graph's markdown that is NOT open. Implementations never open a
 * database, register the graph or watch files.
 */
interface SourceGraphReader {
    /** Up to [limit] (<= [MAX_PAGE_SIZE]) entries in name order strictly after [afterName] (null = from the start). */
    suspend fun listEntries(graph: GraphInfo, afterName: String?, limit: Int): Either<ReadError, List<SourceEntry>>

    /** Reads and parses exactly one file. */
    suspend fun readPage(graph: GraphInfo, entry: SourceEntry): Either<ReadError, StagedPage>

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
