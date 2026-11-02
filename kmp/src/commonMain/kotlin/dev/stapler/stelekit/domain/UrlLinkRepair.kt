// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

/**
 * Undoes the corruption older capture builds wrote when they auto-linked a page name found inside
 * a URL: `https://github.com/x` became `https://[[GitHub]].com/x`.
 *
 * Pure and idempotent. Text without a `[[name]]` inside a URL span is returned unchanged
 * (same instance), so callers can use `repaired != text` as the "needs a write" test.
 *
 * The auto-linker replaced the matched text with the page's canonical name, losing the original
 * case. Host names are case-insensitive and conventionally lowercase, so a name unwrapped in the
 * host is lowercased; in a path or query the canonical case is kept (the original is unknowable).
 */
object UrlLinkRepair {

    // A single-token wiki link: no whitespace, brackets or alias bar (URLs cannot contain spaces).
    private val INJECTED_LINK = Regex("""\[\[([^\s\[\]|]+)]]""")

    fun repair(text: String): String {
        if (!text.contains("[[")) return text
        var current = text
        while (true) {
            val next = repairOnce(current)
            if (next == current) return current
            current = next
        }
    }

    fun needsRepair(text: String): Boolean = repair(text) != text

    private fun repairOnce(text: String): String {
        val urls = LinkProtectedSpans.urlSpans(text).filter { text.substring(it.first, it.last + 1).contains("[[") }
        if (urls.isEmpty()) return text
        val sb = StringBuilder()
        var cursor = 0
        for (span in urls) {
            sb.append(text, cursor, span.first)
            sb.append(repairUrl(text.substring(span.first, span.last + 1)))
            cursor = span.last + 1
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun repairUrl(url: String): String {
        val hostStart = url.indexOf("://").let { if (it >= 0) it + 3 else 0 }
        val hostEnd = url.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it < 0) url.length else it }
        return INJECTED_LINK.replace(url) { m ->
            val inHost = m.range.first in hostStart until hostEnd
            if (inHost) m.groupValues[1].lowercase() else m.groupValues[1]
        }
    }
}
