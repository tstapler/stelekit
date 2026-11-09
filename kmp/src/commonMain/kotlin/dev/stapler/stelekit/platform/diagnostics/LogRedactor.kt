// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

import dev.stapler.stelekit.security.Redactor

/**
 * Redaction for text a user may paste publicly (Logs [Copy]). Replaces absolute paths, `file://` /
 * `content://` URIs and the names of the active graph and its pages; keeps class names, reason names
 * and numbers. Pure and idempotent: its own placeholders are never re-matched.
 */
object LogRedactor {
    const val PATH = "<path>"
    const val GRAPH = "<graph>"
    const val NAME = "<name>"

    /** Names shorter than this are ignored: replacing "a" would shred ordinary text. */
    const val MIN_NAME_LENGTH = 3

    /** Most DiagnosticsSample lines [selectShareLines] includes. */
    const val MAX_SHARED_SAMPLES = 3

    private val URI = Regex("""(?i)\b[a-z][a-z0-9+.\-]*://[^\s,;)\]'"<>]*""")
    private val WINDOWS_PATH = Regex("""(?<![\w])[A-Za-z]:\\[^\s,;)\]'"<>]*""")
    private val UNIX_PATH = Regex("""(?<![\w:.<>/])/[^\s,;)\]'"<>]+""")

    fun redactForShare(
        text: String,
        graphNames: Collection<String> = emptyList(),
        pageNames: Collection<String> = emptyList(),
    ): String {
        var out = URI.replace(text, PATH)
        out = WINDOWS_PATH.replace(out, PATH)
        out = UNIX_PATH.replace(out, PATH)
        out = replaceNames(out, graphNames, GRAPH)
        out = replaceNames(out, pageNames, NAME)
        return Redactor.redact(out)
    }

    private fun replaceNames(text: String, names: Collection<String>, placeholder: String): String {
        var out = text
        for (name in names.filter { it.length >= MIN_NAME_LENGTH }.distinct().sortedByDescending { it.length }) {
            // Whole-token match so a page named "app" does not shred "mapping"; the "<" / ">" lookarounds
            // skip our own placeholders (a page literally named "path").
            val pattern = Regex("(?<![<A-Za-z0-9_])" + Regex.escape(name) + "(?![>A-Za-z0-9_])", RegexOption.IGNORE_CASE)
            out = pattern.replace(out, placeholder)
        }
        return out
    }

    /**
     * Picks what [Logs Copy] may include from [displayedMessages] (the Logs screen's visible rows, any
     * order): every exit line and at most [MAX_SHARED_SAMPLES] sample lines, newest first as given.
     * Never the whole log.
     */
    fun selectShareLines(displayedMessages: List<String>): List<String> {
        val exits = displayedMessages.filter { it.contains(EXIT_LINE_PREFIX) }
        val samples = displayedMessages
            .filter { it.contains(DiagnosticsSample.SAMPLE_PREFIX + " cpuMsDelta=") }
            .take(MAX_SHARED_SAMPLES)
        return exits + samples
    }

    /** The redacted text for Logs [Copy], or null when no exit record is displayed. */
    fun buildShareText(
        displayedMessages: List<String>,
        graphNames: Collection<String> = emptyList(),
        pageNames: Collection<String> = emptyList(),
    ): String? {
        val lines = selectShareLines(displayedMessages)
        if (lines.none { it.contains(EXIT_LINE_PREFIX) }) return null
        return redactForShare(lines.joinToString("\n"), graphNames, pageNames)
    }

    const val COPIED_CONFIRMATION = "Copied. Review before posting publicly."
    const val NOTHING_TO_COPY = "No exit record to copy."
}
