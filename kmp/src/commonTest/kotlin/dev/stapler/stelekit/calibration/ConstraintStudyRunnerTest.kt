// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Task 3.8a: the dev-only study runner. Fixtures here are synthetic; owner photos never enter the public repo. */
class ConstraintStudyRunnerTest {
    @Test
    fun parse_should_RoundTripFixtureJson() {
        val fixture = StudyFixtures.synthetic(photos = 2, distancesPerPhoto = 6, noisePx = 1.0, seed = 3)
        val parsed = ConstraintStudyRunner.parse(ConstraintStudyRunner.toJson(fixture))
        assertEquals(fixture, parsed)
    }

    @Test
    fun run_should_ReportZeroError_When_NoiseFreeFrontoParallelPhotos() {
        val fixture = StudyFixtures.synthetic(photos = 2, distancesPerPhoto = 6, noisePx = 0.0, seed = 5, tiltDeg = 0.0)
        val report = ConstraintStudyRunner.run(fixture)
        for (photo in report.photos) {
            for (solver in ConstraintStudyRunner.SOLVER_NAMES) {
                assertEquals(0.0, photo.maePct(solver)!!, 1e-6, "${photo.photo.id}/$solver")
            }
        }
    }

    @Test
    fun run_should_LeaveEachDistanceOutOncePerPhoto() {
        val fixture = StudyFixtures.synthetic(photos = 1, distancesPerPhoto = 6, noisePx = 1.0, seed = 9)
        val photo = ConstraintStudyRunner.run(fixture).photos.single()
        assertEquals(6, photo.samples.getValue(ConstraintStudyRunner.SINGLE_SCALE_NAME).size)
    }

    @Test
    fun run_should_CompareTheShippedDefaultAgainstSingleScaleAndTheGradientTier() {
        val names = ConstraintStudyRunner.SOLVER_NAMES
        assertTrue(ConstraintStudyRunner.SINGLE_SCALE_NAME in names)
        assertTrue(ConstraintStudyRunner.DEFAULT_NAME in names)
        assertTrue(ConstraintStudyRunner.GRADIENT_NAME in names)
    }

    @Test
    fun format_should_PrintOneRowPerPhotoAndSolver_AndTheSetSummary() {
        val fixture = StudyFixtures.synthetic(photos = 4, distancesPerPhoto = 6, noisePx = 1.0, seed = 11)
        val text = ConstraintStudyRunner.run(fixture).format()
        println(text)
        fixture.photos.forEach { assertTrue(it.id in text, "missing ${it.id}") }
        ConstraintStudyRunner.SOLVER_NAMES.forEach { assertTrue(it in text, "missing $it") }
        assertTrue("set A" in text && "set B" in text)
        assertTrue("A->B" in text)
    }

    @Test
    fun tolerance_should_BeTwoPercentWithHalfCentimetreFloorUnder25cm() {
        assertEquals(0.005, StudyTolerance.metres(0.18), 1e-12)
        assertEquals(0.005, StudyTolerance.metres(0.24), 1e-12)
        assertEquals(0.005, StudyTolerance.metres(0.25), 1e-12)
        assertEquals(0.01, StudyTolerance.metres(0.5), 1e-12)
    }

    @Test
    fun verdict_should_BeNotMet_When_SetBIsEmpty() {
        val fixture = StudyFixtures.synthetic(photos = 3, distancesPerPhoto = 6, noisePx = 1.0, seed = 2, setB = 0)
        val verdict = ConstraintStudyRunner.run(fixture).verdict()
        assertFalse(verdict.met)
        assertTrue(verdict.reasons.any { "set B" in it })
    }

    @Test
    fun verdict_should_BeNotMet_When_DefaultIsNoBetterThanSingleScaleOnSetB() {
        val report = StudyFixtures.reportWithErrors(setBDefaultMetres = 0.004, setBSingleMetres = 0.004)
        val verdict = report.verdict()
        assertFalse(verdict.met)
        assertTrue(verdict.reasons.any { "single" in it })
    }

    @Test
    fun verdict_should_BeNotMet_When_AnyPhotoIsMoreThanTenPercentWorseThanSingleScale() {
        val report = StudyFixtures.reportWithErrors(setBDefaultMetres = 0.002, setBSingleMetres = 0.004, worsePhotoDefaultMetres = 0.0045, worsePhotoSingleMetres = 0.004)
        val verdict = report.verdict()
        assertFalse(verdict.met)
        assertTrue(verdict.reasons.any { "10%" in it })
    }

    @Test
    fun verdict_should_BeNotMet_When_DefaultMaeExceedsTolerance() {
        val report = StudyFixtures.reportWithErrors(setBDefaultMetres = 0.02, setBSingleMetres = 0.03)
        val verdict = report.verdict()
        assertFalse(verdict.met)
        assertTrue(verdict.reasons.any { "tolerance" in it })
    }

    @Test
    fun verdict_should_BeMet_When_DefaultBeatsSingleOnSetBWithinToleranceAndNoPhotoMuchWorse() {
        val report = StudyFixtures.reportWithErrors(setBDefaultMetres = 0.002, setBSingleMetres = 0.004)
        val verdict = report.verdict()
        assertTrue(verdict.met, verdict.reasons.joinToString())
        assertNotNull(verdict)
    }
}
