package dev.stapler.stelekit.db

/**
 * Detects git conflict markers in a file's content before import.
 *
 * Git inserts three types of markers on separate lines when it cannot auto-merge:
 * - `<<<<<<<` (conflict start)
 * - `=======` (separator)
 * - `>>>>>>>` (conflict end)
 *
 * Note: `=======` alone is NOT treated as a conflict marker because YAML front matter
 * commonly uses `---` dividers, and some markdown content includes `===` separator
 * lines. Only detect `=======` when it appears between `<<<<<<<` and `>>>>>>>`.
 *
 * Returns [true] if the content contains git conflict markers and should NOT be imported.
 */
object ConflictMarkerDetector {

    private val CONFLICT_START = Regex("^<{7}", RegexOption.MULTILINE)
    private val CONFLICT_END   = Regex("^>{7}", RegexOption.MULTILINE)

    /**
     * Returns `true` if [content] contains git conflict markers.
     *
     * Only flags a conflict if BOTH a start marker (`<<<<<<<`) AND an end marker
     * (`>>>>>>>`) are present, to avoid false positives on files that happen to
     * contain `<<<<<<<` in code blocks or prose.
     */
    fun hasConflictMarkers(content: String): Boolean =
        CONFLICT_START.containsMatchIn(content) && CONFLICT_END.containsMatchIn(content)
}
