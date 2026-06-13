// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

sealed class AudiobookNote {
    data class VoiceNote(
        val transcribedText: String,
        val bookInfo: BookInfo,
    ) : AudiobookNote()

    data class BookmarkNote(
        val bookInfo: BookInfo,
    ) : AudiobookNote()

    data class QuickTagNote(
        val tag: String,
        val bookInfo: BookInfo,
    ) : AudiobookNote()

    data class AudioSnippetBookmarkNote(
        val bookInfo: BookInfo,
    ) : AudiobookNote()
}
