package dev.stapler.stelekit.editor

import dev.stapler.stelekit.editor.text.TextRange
import dev.stapler.stelekit.editor.text.TextSelection
import kotlin.time.Instant

// EditorState, EditorConfig, EditorMode, EditorTheme moved to dev.stapler.stelekit.editor.state
// TextState, TextSelection, TextRange moved to dev.stapler.stelekit.editor.text

/**
 * Text affinity for selection cursor
 * Kept here if needed, but TextSelection in editor.text uses SelectionDirection instead.
 * If this is unused, it can be removed. Keeping for now just in case.
 */
enum class TextAffinity {
    UPSTREAM,
    DOWNSTREAM
}

/**
 * Text formatting attributes
 */
data class FormatState(
    val currentFormat: TextFormat = TextFormat(),
    val availableFormats: Set<TextFormat> = emptySet(),
    val syntaxErrors: List<ValidationError> = emptyList()
)

/**
 * Search state for editor
 */
data class SearchState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val currentIndex: Int = -1,
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false
)

/**
 * Search result
 */
data class SearchResult(
    val blockId: String,
    val content: String,
    val matchRanges: List<TextRange>,
    val contextBefore: String = "",
    val contextAfter: String = ""
)

/**
 * Cursor state including focus
 */
data class CursorState(
    val blockUuid: String? = null,
    val position: Int = 0,
    val selection: TextSelection? = null,
    val isFocused: Boolean = false
)

/**
 * Text formatting attributes
 */
data class TextFormat(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val quote: Boolean = false,
    val link: String? = null,
    val highlight: Boolean = false,
    val strike: Boolean = false
)

/**
 * Validation error in text
 */
data class ValidationError(
    val message: String,
    val range: TextRange,
    val severity: ValidationSeverity = ValidationSeverity.ERROR
)

enum class ValidationSeverity {
    INFO, WARNING, ERROR
}
