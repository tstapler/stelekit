// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.content.Context

enum class NoteDestination {
    JOURNAL_ONLY,
    BOOK_PAGE_ONLY,
    BOTH,
}

class AudiobookAutoSettings(context: Context) {

    private val prefs = context.getSharedPreferences("audiobook_auto_prefs", Context.MODE_PRIVATE)

    fun getQuickTags(): List<String> {
        val json = prefs.getString(KEY_QUICK_TAGS, null) ?: return DEFAULT_QUICK_TAGS
        return try {
            json.split(TAG_SEPARATOR).filter { it.isNotEmpty() }
        } catch (e: Exception) {
            DEFAULT_QUICK_TAGS
        }
    }

    fun setQuickTags(tags: List<String>) {
        prefs.edit().putString(KEY_QUICK_TAGS, tags.joinToString(TAG_SEPARATOR)).apply()
    }

    fun getSnippetDurationSeconds(): Int {
        return prefs.getInt(KEY_SNIPPET_DURATION, DEFAULT_SNIPPET_DURATION)
    }

    fun setSnippetDurationSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_SNIPPET_DURATION, seconds).apply()
    }

    fun getNoteDestination(): NoteDestination {
        val name = prefs.getString(KEY_NOTE_DESTINATION, NoteDestination.BOTH.name)
        return try {
            NoteDestination.valueOf(name ?: NoteDestination.BOTH.name)
        } catch (e: IllegalArgumentException) {
            NoteDestination.BOTH
        }
    }

    fun setNoteDestination(destination: NoteDestination) {
        prefs.edit().putString(KEY_NOTE_DESTINATION, destination.name).apply()
    }

    companion object {
        private const val KEY_QUICK_TAGS = "quick_tags"
        private const val KEY_SNIPPET_DURATION = "snippet_duration_seconds"
        private const val KEY_NOTE_DESTINATION = "note_destination"
        private const val TAG_SEPARATOR = "|||"
        private const val DEFAULT_SNIPPET_DURATION = 30
        val DEFAULT_QUICK_TAGS = listOf("Key insight", "Follow up", "Quote", "Action item")
    }
}
