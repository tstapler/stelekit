package dev.stapler.stelekit.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One row of `gap-backlog.md`'s pipe-table schema (see that file's own header comment). */
data class BacklogRow(
    val gapId: String,
    val journeyId: String,
    val platform: String,
    val jtbdTier: String,
    val gapDescription: String,
    val targetPhase: String,
    val priority: String,
    val effortEstimate: String,
    val sectionHeading: String,
)

/**
 * Regex/line-based parser for `gap-backlog.md`'s markdown pipe tables — no markdown-table
 * library is on this project's classpath, and the file's schema is simple and stable enough
 * (one header row repeated verbatim across each of its 3 tables) that a hand-rolled parser is
 * the same "read the real file, regex-extract structure" idiom used by
 * [dev.stapler.stelekit.db.MigrationRunnerSchemaSyncTest].
 */
object GapBacklogParser {

    val VALID_PHASES = setOf("C", "D", "E", "F", "G", "G2")
    val VALID_PRIORITIES = setOf("P0", "P1", "P2", "P3")

    private val schemaHeaderCells = listOf(
        "gap_id", "journey_id", "platform", "jtbd_tier",
        "gap_description", "target_phase", "priority", "effort_estimate",
    )

    fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun isHeaderRow(line: String): Boolean {
        val cells = splitRow(line).map { it.lowercase() }
        return cells.size >= schemaHeaderCells.size && cells.take(schemaHeaderCells.size) == schemaHeaderCells
    }

    private fun isSeparatorRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.replace("|", "").replace("-", "").isBlank()
    }

    /** Parses every table in [text] whose header matches the gap-backlog schema, in document order. */
    fun parseRows(text: String): List<BacklogRow> {
        val lines = text.lines()
        val rows = mutableListOf<BacklogRow>()
        var currentHeading = ""
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#")) currentHeading = line.trimStart('#').trim()
            if (isHeaderRow(line)) {
                var j = i + 1
                if (j < lines.size && isSeparatorRow(lines[j])) j++
                while (j < lines.size && lines[j].trimStart().startsWith("|") && !isHeaderRow(lines[j])) {
                    val cells = splitRow(lines[j])
                    if (cells.size >= schemaHeaderCells.size) {
                        rows += BacklogRow(
                            gapId = cells[0],
                            journeyId = cells[1],
                            platform = cells[2],
                            jtbdTier = cells[3],
                            gapDescription = cells[4],
                            targetPhase = cells[5],
                            priority = cells[6],
                            effortEstimate = cells[7],
                            sectionHeading = currentHeading,
                        )
                    }
                    j++
                }
                i = j - 1
            }
            i++
        }
        return rows
    }

    /**
     * A row is a documented exception to the enum rules only when it is explicitly parked under
     * an "Out-of-band"/"not phase-assigned" heading (e.g. `GAP-019`) — never a silent pass for a
     * malformed `target_phase`/`priority` elsewhere in the file.
     */
    fun rowErrors(row: BacklogRow): List<String> {
        val errors = mutableListOf<String>()
        if (row.gapId.isBlank()) errors += "gap_id blank"
        if (row.journeyId.isBlank()) errors += "journey_id blank"
        if (row.platform.isBlank()) errors += "platform blank"
        if (row.jtbdTier.isBlank()) errors += "jtbd_tier blank"
        if (row.gapDescription.isBlank()) errors += "gap_description blank"
        if (row.effortEstimate.isBlank()) errors += "effort_estimate blank"

        val isDocumentedOutOfBand = row.targetPhase.startsWith("N/A") &&
            row.sectionHeading.contains("Out-of-band", ignoreCase = true)

        if (!isDocumentedOutOfBand && row.targetPhase !in VALID_PHASES) {
            errors += "target_phase '${row.targetPhase}' not one of $VALID_PHASES"
        }
        if (!(isDocumentedOutOfBand && row.priority == "—") && row.priority !in VALID_PRIORITIES) {
            errors += "priority '${row.priority}' not one of $VALID_PRIORITIES"
        }
        return errors
    }
}

class GapBacklogSchemaTest {

    @Test
    fun `parseBacklogRow should extractAllSchemaColumns When rowIsWellFormed`() {
        val rows = GapBacklogParser.parseRows(DocRepoLocator.gapBacklogFile.readText())
        val gap001 = rows.single { it.gapId == "GAP-001" }
        assertEquals("toggle-todo", gap001.journeyId)
        assertEquals("all", gap001.platform)
        assertEquals("functional", gap001.jtbdTier)
        assertEquals("C", gap001.targetPhase)
        assertEquals("P0", gap001.priority)
        assertTrue(gap001.gapDescription.isNotBlank())
        assertTrue(gap001.targetPhase in GapBacklogParser.VALID_PHASES)
        assertTrue(gap001.priority in GapBacklogParser.VALID_PRIORITIES)
    }

    @Test
    fun `parseBacklogRow should rejectRow When priorityOutsideP0ToP3OrTargetPhaseUnknown`() {
        val fixture = BacklogRow(
            gapId = "GAP-900", journeyId = "insert-tag", platform = "all", jtbdTier = "functional",
            gapDescription = "fixture", targetPhase = "C", priority = "P4", effortEstimate = "S",
            sectionHeading = "Fixture",
        )
        val badPriorityErrors = GapBacklogParser.rowErrors(fixture)
        assertTrue(badPriorityErrors.any { it.contains("priority") })

        val badPhaseFixture = fixture.copy(priority = "P1", targetPhase = "Z")
        val badPhaseErrors = GapBacklogParser.rowErrors(badPhaseFixture)
        assertTrue(badPhaseErrors.any { it.contains("target_phase") })
    }

    @Test
    fun `everyRealBacklogRow should passSchemaValidation When readFromDisk`() {
        val rows = GapBacklogParser.parseRows(DocRepoLocator.gapBacklogFile.readText())
        assertTrue(rows.isNotEmpty(), "expected at least one row to parse out of gap-backlog.md")
        val failures = rows.flatMap { row -> GapBacklogParser.rowErrors(row).map { "${row.gapId}: $it" } }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `backlogPlatformCoverage should beBalancedAcrossAllFourPlatforms When fullBacklogIsAggregated`() {
        val rows = GapBacklogParser.parseRows(DocRepoLocator.gapBacklogFile.readText())
        val allPlatforms = setOf("desktop", "android", "ios", "web")
        val covered = rows.flatMap { row ->
            if (row.platform.equals("all", ignoreCase = true)) {
                allPlatforms.toList()
            } else {
                row.platform.split(",").map { it.trim().lowercase() }
            }
        }.toSet()
        val missing = allPlatforms - covered
        assertTrue(missing.isEmpty(), "Platforms with zero backlog rows across the whole audit: $missing")
    }
}
