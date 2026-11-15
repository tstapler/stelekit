// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import kotlin.random.Random

/** Synthetic study inputs only: the owner's real photos and tape measurements are never committed to the public repo. */
object StudyFixtures {
    /** [photos] synthetic photos, the first [setB] counted from the end assigned to set B (default: the last two). */
    @Suppress("LongParameterList")
    fun synthetic(
        photos: Int,
        distancesPerPhoto: Int,
        noisePx: Double,
        seed: Int,
        tiltDeg: Double = 5.0,
        setB: Int = if (photos >= 4) 2 else 1,
    ): StudyFixture {
        val rnd = Random(seed)
        val list = (0 until photos).map { i ->
            val scene = checkNotNull(GoldenSceneGenerator.random(SceneFamily.IN_MODEL, rnd, distancesPerPhoto - 1, tiltDeg, noisePx)) {
                "no synthetic layout for photo $i"
            }
            val all = scene.training + scene.heldOut
            StudyPhoto(
                id = "synthetic-${i + 1}",
                set = if (i >= photos - setB) "B" else "A",
                doorFrame = i == 0,
                widthPx = scene.widthPx,
                heightPx = scene.heightPx,
                distances = all.map { StudyDistance(it.id, StudyPoint(it.p1.x, it.p1.y), StudyPoint(it.p2.x, it.p2.y), it.trueMeters) },
            )
        }
        return StudyFixture(list)
    }

    /** A report whose per-solver errors are given directly, to exercise the verdict arithmetic. */
    fun reportWithErrors(
        setBDefaultMetres: Double,
        setBSingleMetres: Double,
        worsePhotoDefaultMetres: Double? = null,
        worsePhotoSingleMetres: Double? = null,
    ): StudyReport {
        fun photo(id: String, set: String, default: Double, single: Double): PhotoResult {
            val segs = (0 until 6).map { StudyDistance("d$it", StudyPoint(0.1, 0.1), StudyPoint(0.2, 0.2), TRUE_M) }
            val source = StudyPhoto(id, set, doorFrame = id == "a1", 4000.0, 3000.0, distances = segs)
            fun samples(err: Double) = segs.map { StudySample(TRUE_M, err) }
            return PhotoResult(
                source,
                mapOf(
                    ConstraintStudyRunner.SINGLE_SCALE_NAME to samples(single),
                    ConstraintStudyRunner.MEAN_SCALE_NAME to samples(default),
                    ConstraintStudyRunner.DEFAULT_NAME to samples(default),
                    ConstraintStudyRunner.GRADIENT_NAME to samples(default),
                ),
            )
        }
        return StudyReport(
            listOf(
                photo("a1", "A", 0.003, 0.004),
                photo("a2", "A", 0.003, 0.004),
                photo("b1", "B", setBDefaultMetres, setBSingleMetres),
                photo("b2", "B", worsePhotoDefaultMetres ?: setBDefaultMetres, worsePhotoSingleMetres ?: setBSingleMetres),
            ),
        )
    }

    private const val TRUE_M = 0.18
}
