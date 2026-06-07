// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

class AudiobookNoteWriter(
    private val journalService: JournalService,
    private val settings: AudiobookAutoSettings,
) {
    /** Tracks whether the first note of this session has been written (for heading insertion). */
    private val firstNoteWritten = AtomicBoolean(false)

    suspend fun writeNote(note: AudiobookNote): Either<DomainError, Unit> {
        val bookInfo = note.bookInfo()
        val destination = settings.getNoteDestination()
        val today = LocalDate.now().toString() // ISO-8601: "2026-06-07"

        val formattedContent = when (note) {
            is AudiobookNote.VoiceNote -> AudiobookNoteFormatter.formatVoiceNote(
                text = note.transcribedText,
                bookTitle = bookInfo.title,
                positionMs = bookInfo.positionMs,
                date = today,
            )
            is AudiobookNote.BookmarkNote -> AudiobookNoteFormatter.formatBookmark(
                bookTitle = bookInfo.title,
                chapter = bookInfo.chapter,
                positionMs = bookInfo.positionMs,
            )
            is AudiobookNote.QuickTagNote -> AudiobookNoteFormatter.formatQuickTag(
                tag = note.tag,
                bookTitle = bookInfo.title,
                positionMs = bookInfo.positionMs,
            )
            is AudiobookNote.AudioSnippetBookmarkNote -> AudiobookNoteFormatter.formatAudioSnippetBookmark(
                bookTitle = bookInfo.title,
                positionMs = bookInfo.positionMs,
            )
        }

        // Prepend section heading on first note of session
        val contentWithHeading = if (firstNoteWritten.compareAndSet(false, true)) {
            "## Audiobook Notes\n$formattedContent"
        } else {
            formattedContent
        }

        // Journal write
        if (destination == NoteDestination.JOURNAL_ONLY || destination == NoteDestination.BOTH) {
            try {
                journalService.appendToToday(contentWithHeading)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

        // Book-page write (only when book is active and destination requires it)
        if ((destination == NoteDestination.BOOK_PAGE_ONLY || destination == NoteDestination.BOTH) &&
            bookInfo.isActive && bookInfo.title != null
        ) {
            try {
                journalService.createTranscriptPage(bookInfo.title, formattedContent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

        return Unit.right()
    }

    private fun AudiobookNote.bookInfo(): BookInfo = when (this) {
        is AudiobookNote.VoiceNote -> bookInfo
        is AudiobookNote.BookmarkNote -> bookInfo
        is AudiobookNote.QuickTagNote -> bookInfo
        is AudiobookNote.AudioSnippetBookmarkNote -> bookInfo
    }
}
