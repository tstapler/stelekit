// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.6.8, Task 3.6.8d (UX-95): user-visible text in the v2 UI package comes from `ui/sketch/v2/strings/SketchStrings.kt`,
 * not from a literal in a composable or a model. The audit reads the production sources under `ui/sketch/v2/` (the string
 * table itself is exempt) and flags a word-like string literal on any line that is not a test tag, a diagnostic message
 * (`require`, `check`, `error`, a logger) or a comment. Literals that predate the table are listed in
 * `sketch-hardcoded-strings-baseline.txt`; the list may only shrink: a line that no longer matches a source fails the test,
 * and so does a baseline longer than [MAX_BASELINE_ENTRIES]. Move a string into the table, then delete its baseline line.
 */
class HardcodedStringAuditTest {
    private val scope = "ui/sketch/v2/"
    private val tableDirectory = "ui/sketch/v2/strings/"

    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val wordLike = Regex("""[A-Za-z]{2,}""")
    private val identifierLike = Regex("""[a-z0-9_.:\-/]+""")
    private val interpolation = Regex("""\$\{[^}]*}|\$\w+""")
    private val exemptLine = Regex("""testTag\(|\brequire\(|\bcheck\(|\berror\(|\bcheckNotNull\(|Logger|\blog\??\.|Regex\(|@Suppress|^import |^package """)

    /** The user-visible string literals of one source line, or none for a comment, a diagnostic or a key. */
    private fun literalsOf(line: String): List<String> {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || exemptLine.containsMatchIn(t)) return emptyList()
        return literal.findAll(t).map { it.groupValues[1].replace(interpolation, "") }.filter { wordLike.containsMatchIn(it) && !identifierLike.matches(it) }.toList()
    }

    private fun found(): Set<String> {
        val sources = WiringInputs.productionSources
        assertTrue(sources.isNotEmpty(), "no production sources loaded")
        val out = linkedSetOf<String>()
        for ((key, text) in sources.toSortedMap()) {
            val rel = key.substringAfter("dev/stapler/stelekit/", "")
            if (!rel.startsWith(scope) || rel.startsWith(tableDirectory)) continue
            for (line in text.lineSequence()) if (literalsOf(line).isNotEmpty()) out += "$rel :: ${line.trim()}"
        }
        return out
    }

    private fun baseline(): Set<String> = WiringInputs.dataFile("sketch-hardcoded-strings-baseline.txt").lineSequence()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    @Test
    fun noNewLiteral_should_AppearOutsideTheStringTable_When_V2UiSourcesAreScanned() {
        val unlisted = found() - baseline()
        assertTrue(unlisted.isEmpty(), "user-visible literal outside SketchStrings (move it into the table):\n" + unlisted.joinToString("\n"))
    }

    @Test
    fun baseline_should_OnlyShrink_When_EveryLineStillMatchesASource() {
        val stale = baseline() - found()
        assertTrue(stale.isEmpty(), "stale sketch-hardcoded-strings-baseline.txt lines (delete them):\n" + stale.joinToString("\n"))
        assertTrue(baseline().size <= MAX_BASELINE_ENTRIES, "the baseline grew past $MAX_BASELINE_ENTRIES; it may only shrink")
    }

    @Test
    fun theStringTable_should_BeScanned_AndItsOwnLiteralsExempt() {
        val keys = WiringInputs.productionSources.keys.map { it.substringAfter("dev/stapler/stelekit/", "") }
        assertTrue(keys.any { it.startsWith(tableDirectory) }, "the string table was not loaded")
        assertTrue(found().none { it.startsWith(tableDirectory) })
    }

    @Test
    fun detector_should_FlagPlantedLiterals_AndIgnoreKeysTagsAndDiagnostics() {
        val flagged = listOf(
            """Text("Hello there")""",
            """contentDescription = "More options"""",
            """StripButton(c, "undo", StripTags.UNDO, "Undo", "↶")""",
            """val reason = "Select an element first"""",
            """Text("Zoom ${'$'}{pct}%")""",
        )
        for (line in flagged) assertTrue(literalsOf(line).isNotEmpty(), "missed: $line")
        val benign = listOf(
            """Modifier.testTag("sketch.strip")""",
            """const val STRIP = "sketch.strip"""",
            """require(zoom > 0) { "zoom must be positive" }""",
            """// Text("Commented out")""",
            """ * "Quoted in a doc comment"""",
            """val glyph = "↶"""",
            """val colour = "#1e1e1e"""",
            """fun action(id: Id) = "sketch.arrange.${'$'}{id.name.lowercase()}"""",
            """import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings""",
        )
        for (line in benign) assertEquals(emptyList(), literalsOf(line), "wrongly flagged: $line")
    }

    private companion object {
        /** The size of the baseline when the audit landed; it may only shrink. */
        const val MAX_BASELINE_ENTRIES = 277
    }
}
