// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.docs.DocRepoLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the audit found in `accuracy-study.md`; [violations] empty means the doc is honest. */
data class StudyDocAudit(val verdict: String?, val photos: Int, val doorFrame: Boolean, val setB: Int, val minDistances: Int, val violations: List<String>)

/** Doc audit behind I-E3-13: SM3b cannot be claimed met without the real evidence table. */
object AccuracyStudyAudit {
    const val MET = "SM3 met: shipped default tier has lower mean absolute error than single scale on the real hold-out"
    const val NOT_MET = "SM3 not met for distortion: default stays SCALE-with-LOO (a noise-averaging gain only); the owner was told"
    const val OPEN = "SM3 OPEN - awaiting owner photos"
    const val MIN_PHOTOS = 5
    const val MIN_DISTANCES = 6

    private val VERDICT_PREFIXES = listOf("SM3 met", "SM3 not met", "SM3 OPEN")
    private val TABLE_ROW = Regex("""^\|\s*([^|]+?)\s*\|\s*([AB])\s*\|\s*(yes|no)\s*\|\s*(\d+)\s*\|""", RegexOption.IGNORE_CASE)

    fun audit(text: String): StudyDocAudit {
        val lines = text.lines().map { it.trimEnd() }
        val verdicts = lines.filter { line -> VERDICT_PREFIXES.any { line.startsWith(it) } }
        val last = lines.lastOrNull { it.isNotBlank() }
        val rows = lines.mapNotNull { TABLE_ROW.find(it)?.groupValues }
        val photos = rows.size
        val doorFrame = rows.any { it[3].equals("yes", ignoreCase = true) }
        val setB = rows.count { it[2].equals("B", ignoreCase = true) }
        val minDistances = rows.minOfOrNull { it[4].toInt() } ?: 0

        val violations = mutableListOf<String>()
        val verdict = verdicts.singleOrNull()
        when {
            verdicts.size != 1 -> violations += "doc must contain exactly one verdict line, found ${verdicts.size}"
            verdict != last -> violations += "the verdict line must be the last line of the doc"
            verdict !in setOf(MET, NOT_MET, OPEN) -> violations += "verdict line is not one of the three allowed texts: $verdict"
        }
        if (verdict == MET) {
            if (photos < MIN_PHOTOS) violations += "SM3 met needs at least $MIN_PHOTOS real photos, found $photos"
            if (minDistances < MIN_DISTANCES) violations += "SM3 met needs at least $MIN_DISTANCES tape-measured distances per photo, minimum was $minDistances"
            if (!doorFrame) violations += "SM3 met needs the door-frame photo in the evidence table"
            if (setB == 0) violations += "SM3 met needs a non-empty hold-out set B"
        }
        return StudyDocAudit(verdict, photos, doorFrame, setB, minDistances, violations)
    }
}

/** I-E3-13: the verdict doc may say OPEN or not-met on thin evidence, but never "met". */
class AccuracyStudyVerdictTest {
    private fun doc(verdict: String, rows: List<String> = emptyList()) = buildString {
        appendLine("# Accuracy study")
        appendLine()
        appendLine("| Photo | Set | Door frame | Tape distances | Single-scale MAE (cm) | Default-tier MAE (cm) |")
        appendLine("|---|---|---|---|---|---|")
        rows.forEach { appendLine(it) }
        appendLine()
        appendLine(verdict)
    }

    private fun row(id: String, set: String = "A", door: String = "no", n: Int = 6) = "| $id | $set | $door | $n | 0.4 | 0.2 |"

    private fun fullRows() = listOf(
        row("door", "A", "yes"), row("p2", "A"), row("p3", "A"), row("p4", "B"), row("p5", "B"),
    )

    @Test
    fun accuracyStudyDoc_should_EndWithExactlyOneOfTheTwoVerdictsAndListAtLeast5RealPhotosWith6DistancesEach() {
        // Bazel bundles the doc as a classpath resource (repoRoot cannot resolve there); Gradle reads the real file.
        val text = DocRepoLocator::class.java.classLoader.getResourceAsStream("accuracy-study.md")?.bufferedReader()?.readText()
            ?: DocRepoLocator.repoRoot
                .resolve("project_plans/sync-lock-and-image-annotator/implementation/accuracy-study.md")
                .takeIf { it.isFile }?.readText()
            ?: error("accuracy-study.md not found")
        val audit = AccuracyStudyAudit.audit(text)
        assertEquals(emptyList(), audit.violations)
    }

    @Test
    fun audit_should_AcceptTheOpenPlaceholderWithNoEvidence() {
        assertEquals(emptyList(), AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.OPEN)).violations)
    }

    @Test
    fun audit_should_AcceptNotMetOnThinEvidence() {
        assertEquals(emptyList(), AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.NOT_MET, listOf(row("door", "A", "yes")))).violations)
    }

    @Test
    fun audit_should_RejectMet_When_NoEvidenceTable() {
        val violations = AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.MET)).violations
        assertTrue(violations.any { "real photos" in it }, violations.toString())
    }

    @Test
    fun audit_should_RejectMet_When_FewerThanFivePhotos() {
        val rows = fullRows().take(4)
        assertTrue(AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.MET, rows)).violations.any { "real photos" in it })
    }

    @Test
    fun audit_should_RejectMet_When_AnyPhotoHasFewerThanSixDistances() {
        val rows = fullRows().dropLast(1) + row("p5", "B", n = 5)
        assertTrue(AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.MET, rows)).violations.any { "per photo" in it })
    }

    @Test
    fun audit_should_RejectMet_When_DoorFramePhotoMissing() {
        val rows = fullRows().map { it.replace("| yes |", "| no |") }
        assertTrue(AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.MET, rows)).violations.any { "door-frame" in it })
    }

    @Test
    fun audit_should_RejectMet_When_SetBIsEmpty() {
        val rows = fullRows().map { it.replace("| B |", "| A |") }
        assertTrue(AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.MET, rows)).violations.any { "set B" in it })
    }

    @Test
    fun audit_should_AcceptMet_When_EvidenceIsComplete() {
        assertEquals(emptyList(), AccuracyStudyAudit.audit(doc(AccuracyStudyAudit.MET, fullRows())).violations)
    }

    @Test
    fun audit_should_RejectADocWhoseVerdictIsNotLast_OrDuplicated_OrUnknown() {
        val notLast = doc(AccuracyStudyAudit.OPEN) + "Trailing prose\n"
        assertTrue(AccuracyStudyAudit.audit(notLast).violations.any { "last line" in it })
        val twice = doc(AccuracyStudyAudit.OPEN) + AccuracyStudyAudit.OPEN + "\n"
        assertTrue(AccuracyStudyAudit.audit(twice).violations.any { "exactly one" in it })
        assertTrue(AccuracyStudyAudit.audit(doc("SM3 met: probably")).violations.any { "allowed texts" in it })
        assertTrue(AccuracyStudyAudit.audit("# nothing").violations.any { "exactly one" in it })
    }
}
