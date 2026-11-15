// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I-E3-12: the only evidence for SM3b. Reads the owner's tapped pixels and tape-measured truth from
 * the JSON files in `accuracy-fixtures/real` (or `$STELEKIT_STUDY_FIXTURES`); those files are never committed, so the real
 * test is skipped when they are absent and `AccuracyStudyVerdictTest` is what blocks an "SM3 met" claim without them.
 */
class RealPhotoHoldOutStudyTest {
    private fun fixtureDir(): File? {
        System.getenv(ENV)?.takeIf { it.isNotBlank() }?.let { return File(it).takeIf(File::isDirectory) }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, DEFAULT_DIR).takeIf(File::isDirectory)?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun holdOut_should_ShowShippedDefaultTierLowerMaeThanSingleScale_OnOwnerRealPhotos() {
        val dir = fixtureDir()
        assumeTrue("no owner fixtures at $DEFAULT_DIR or \$$ENV; see owner-verification-E3.md", dir != null)
        val files = dir!!.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assumeTrue("no *.json fixtures in $dir", files.isNotEmpty())
        val fixture = StudyFixture(files.flatMap { ConstraintStudyRunner.parse(it.readText()).photos })
        val report = ConstraintStudyRunner.run(fixture)
        val text = report.format()
        println(text)
        File("build/reports").apply { mkdirs() }.resolve("accuracy-study-table.txt").writeText(text)
        val verdict = report.verdict()
        assertTrue(verdict.met, "SM3b not met on the owner's photos: ${verdict.reasons.joinToString("; ")}")
    }

    @Test
    fun runner_should_ReadSyntheticFixtureFilesFromDisk() {
        val fixture = StudyFixtures.synthetic(photos = 4, distancesPerPhoto = 6, noisePx = 1.0, seed = 21)
        val file = File.createTempFile("study", ".json")
        try {
            file.writeText(ConstraintStudyRunner.toJson(fixture))
            val report = ConstraintStudyRunner.run(ConstraintStudyRunner.parse(file.readText()))
            assertEquals(4, report.photos.size)
            assertTrue(report.format().isNotBlank())
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ENV = "STELEKIT_STUDY_FIXTURES"
        const val DEFAULT_DIR = "accuracy-fixtures/real"
    }
}
