// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

object AudiobookNoteFormatter {

    fun formatPositionMs(positionMs: Long): String {
        val totalSeconds = positionMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }

    /**
     * Formats a voice note per FR-3.1.
     * @param date ISO 8601 date string e.g. "2026-06-07"
     */
    fun formatVoiceNote(
        text: String,
        bookTitle: String?,
        positionMs: Long?,
        date: String,
    ): String = buildString {
        append("- 🎙️ \"$text\" #audiobook-note")
        val hasExtras = bookTitle != null || positionMs != null
        if (hasExtras) {
            append("\n  ")
            val parts = mutableListOf<String>()
            if (bookTitle != null) parts.add("[:book: [[$bookTitle]]]")
            if (positionMs != null) parts.add("[:timer: ${formatPositionMs(positionMs)}]")
            parts.add("[:calendar: $date]")
            append(parts.joinToString(" "))
        }
    }

    /** Formats a bookmark per FR-3.3. */
    fun formatBookmark(
        bookTitle: String?,
        chapter: String?,
        positionMs: Long?,
    ): String = buildString {
        append("- 🔖 Bookmark")
        if (bookTitle != null || chapter != null || positionMs != null) {
            append(" —")
            if (bookTitle != null) append(" [[$bookTitle]]")
            if (chapter != null) append(", $chapter")
            if (positionMs != null) append(", ${formatPositionMs(positionMs)}")
        }
        append(" #audiobook-note")
    }

    /** Formats a quick-tag note per FR-3.4. */
    fun formatQuickTag(
        tag: String,
        bookTitle: String?,
        positionMs: Long?,
    ): String = buildString {
        append("- 🏷️ #$tag")
        if (bookTitle != null || positionMs != null) {
            append(" —")
            if (bookTitle != null) append(" [[$bookTitle]]")
            if (positionMs != null) append(" at ${formatPositionMs(positionMs)}")
        }
        append(" #audiobook-note")
    }

    /**
     * Formats an audio snippet (degraded to bookmark) per FR-3.2 degraded path.
     * Does not use "audio snippet" wording per E5.S4 requirements.
     */
    fun formatAudioSnippetBookmark(
        bookTitle: String?,
        positionMs: Long?,
    ): String = buildString {
        append("- 🔖 Bookmark")
        if (bookTitle != null || positionMs != null) {
            append(" —")
            if (bookTitle != null) append(" [[$bookTitle]]")
            if (positionMs != null) append(", ${formatPositionMs(positionMs)}")
        }
        append(" #audiobook-note")
    }
}
