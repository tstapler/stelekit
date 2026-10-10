package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** The real content of an index-only stub page, parsed from its file. [blocks] are not in the DB yet. */
class UnloadedPage(val blocks: List<Block>, val properties: Map<String, String>)

/**
 * Reads an index-only stub page (a row and a file, no blocks in the DB) from disk so a writer can merge against
 * what the user really has. Shared by [ActiveTargetWriter] and quick capture: writing a stub's file from the DB
 * alone would erase every existing block.
 */
object UnloadedPageReader {
    /** Null when the page has no file or the file is blank; a typed refusal when the file does not round-trip. */
    suspend fun read(fs: FileSystem, row: Page): Either<DomainError, UnloadedPage?> {
        val path = row.filePath?.takeIf { it.isNotBlank() } ?: return null.right()
        val text = withContext(PlatformDispatcher.IO) { if (fs.fileExists(path)) fs.readFile(path) ?: "" else null }
            ?: return null.right()
        if (text.isBlank()) return null.right()
        RoundTripGuard.probe(text, path, row.isJournal).onLeft { return refusal(it).left() }
        val parsed = try {
            MergeConverters.parseMarkdown(text, path, row.name, row.isJournal, row.journalDate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return refusal(NotRoundTrippable.ParseFailed("${e::class.simpleName}: ${e.message?.take(120)}")).left()
        }
        val now = Clock.System.now()
        val blocks = parsed.blocks.map { it.copy(pageUuid = row.uuid, createdAt = now, updatedAt = now) }
        return UnloadedPage(blocks, parsed.page.properties).right()
    }

    private fun refusal(reason: NotRoundTrippable): DomainError =
        DomainError.MergeError.WriteRefused(WriteRefusedReason.NotRoundTrippable(reason))
}
