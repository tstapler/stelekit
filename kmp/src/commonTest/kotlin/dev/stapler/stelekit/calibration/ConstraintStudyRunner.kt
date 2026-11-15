// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.calibration

import dev.stapler.stelekit.model.NormalizedPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** A tapped point as a fraction of the photo's width and height, the same normalisation the editor stores. */
@Serializable
data class StudyPoint(val x: Double, val y: Double)

/** One tape-measured distance: where it was tapped and its true length in metres. */
@Serializable
data class StudyDistance(val id: String, val p1: StudyPoint, val p2: StudyPoint, val trueMeters: Double)

/** [set] is "A" (tunes thresholds) or "B" (never seen by tuning, decides the verdict). */
@Serializable
data class StudyPhoto(
    val id: String,
    val set: String,
    val doorFrame: Boolean = false,
    val widthPx: Double,
    val heightPx: Double,
    val exifFocal35mmEq: Double? = null,
    val distances: List<StudyDistance>,
)

@Serializable
data class StudyFixture(val photos: List<StudyPhoto>)

/** One withheld distance: its true length and the absolute error of a solver's prediction, both in metres. */
data class StudySample(val trueMeters: Double, val errMeters: Double)

/** Leave-one-out samples per solver for one photo; a solver that declined a distance has no sample for it. */
data class PhotoResult(val photo: StudyPhoto, val samples: Map<String, List<StudySample>>) {
    fun maeMetres(solver: String): Double? = samples[solver]?.takeIf { it.isNotEmpty() }?.map { it.errMeters }?.average()

    fun maePct(solver: String): Double? =
        samples[solver]?.takeIf { it.isNotEmpty() }?.map { it.errMeters / it.trueMeters * PERCENT }?.average()

    private companion object {
        const val PERCENT = 100.0
    }
}

/** SM3b PROPOSED tolerance (OC-13): 2 percent of the true length, with a 0.5 cm floor for objects under 25 cm. */
object StudyTolerance {
    const val FRACTION = 0.02
    const val FLOOR_METRES = 0.005
    const val FLOOR_BELOW_METRES = 0.25

    fun metres(trueMeters: Double): Double =
        if (trueMeters <= FLOOR_BELOW_METRES) max(FRACTION * trueMeters, FLOOR_METRES) else FRACTION * trueMeters
}

data class StudyVerdict(val met: Boolean, val reasons: List<String>)

class StudyReport(val photos: List<PhotoResult>) {
    private fun inSet(set: String) = photos.filter { it.photo.set.equals(set, ignoreCase = true) }

    private fun mae(group: List<PhotoResult>, solver: String): Double? =
        group.flatMap { it.samples[solver].orEmpty() }.takeIf { it.isNotEmpty() }?.map { it.errMeters }?.average()

    /** The tier solver with the lowest set A error: the choice "tuning" would make, then judged on set B. */
    private fun setAWinner(): String? = TIER_NAMES.mapNotNull { name -> mae(inSet("A"), name)?.let { name to it } }.minByOrNull { it.second }?.first

    @Suppress("CyclomaticComplexMethod")
    fun verdict(): StudyVerdict {
        val b = inSet("B")
        if (b.isEmpty()) return StudyVerdict(false, listOf("set B is empty, so nothing was held out"))
        val reasons = mutableListOf<String>()
        val def = mae(b, ConstraintStudyRunner.DEFAULT_NAME)
        val single = mae(b, ConstraintStudyRunner.SINGLE_SCALE_NAME)
        if (def == null || single == null) {
            reasons += "set B has no comparable default-tier and single-scale samples"
        } else {
            if (def >= single) reasons += "default tier MAE ${fmt(def)} is not below single-scale MAE ${fmt(single)} on set B"
            val tolerance = b.flatMap { it.samples[ConstraintStudyRunner.DEFAULT_NAME].orEmpty() }.map { StudyTolerance.metres(it.trueMeters) }.average()
            if (def > tolerance) reasons += "default tier MAE ${fmt(def)} exceeds the proposed tolerance ${fmt(tolerance)} on set B"
        }
        for (p in photos) {
            val d = p.maeMetres(ConstraintStudyRunner.DEFAULT_NAME)
            val s = p.maeMetres(ConstraintStudyRunner.SINGLE_SCALE_NAME)
            if (d != null && s != null && d > s * WORSE_LIMIT) reasons += "photo ${p.photo.id} default MAE is more than 10% worse than single-scale"
        }
        return StudyVerdict(reasons.isEmpty(), reasons)
    }

    fun format(): String = buildString {
        appendLine("Held-out accuracy study: leave-one-out per photo, MAE in cm (percent of true length)")
        appendLine("solvers: " + ConstraintStudyRunner.SOLVER_NAMES.joinToString(" | "))
        for (p in photos) {
            append(p.photo.id.padEnd(ID_WIDTH)).append(" set ").append(p.photo.set)
            append(if (p.photo.doorFrame) " door-frame" else "           ")
            append(" n=").append(p.photo.distances.size)
            for (name in ConstraintStudyRunner.SOLVER_NAMES) {
                val m = p.maeMetres(name)
                append("  ").append(name).append('=')
                append(if (m == null) "declined" else "${fmt(m * CM)}cm(${fmt(p.maePct(name)!!)}%)")
            }
            appendLine()
        }
        for (set in listOf("A", "B")) {
            append("set ").append(set).append(":")
            for (name in ConstraintStudyRunner.SOLVER_NAMES) {
                append("  ").append(name).append('=').append(mae(inSet(set), name)?.let { "${fmt(it * CM)}cm" } ?: "n/a")
            }
            appendLine()
        }
        val winner = setAWinner()
        val bWinner = winner?.let { mae(inSet("B"), it) }
        val bSingle = mae(inSet("B"), ConstraintStudyRunner.SINGLE_SCALE_NAME)
        appendLine(
            "A->B transfer: set A picks ${winner ?: "n/a"}; on set B it scores " +
                "${bWinner?.let { fmt(it * CM) + "cm" } ?: "n/a"} vs single-scale ${bSingle?.let { fmt(it * CM) + "cm" } ?: "n/a"}",
        )
        val v = verdict()
        append(if (v.met) "verdict: SM3b met on these photos" else "verdict: SM3b NOT met: " + v.reasons.joinToString("; "))
    }

    private fun fmt(v: Double) = (round(v * ROUND) / ROUND).toString()

    private companion object {
        const val WORSE_LIMIT = 1.1
        const val CM = 100.0
        const val ROUND = 1000.0
        const val ID_WIDTH = 18
        val TIER_NAMES = listOf(ConstraintStudyRunner.MEAN_SCALE_NAME, ConstraintStudyRunner.DEFAULT_NAME, ConstraintStudyRunner.GRADIENT_NAME)
    }
}

/**
 * Dev-only study runner (Task 3.8a). Feed it the owner's tapped coordinates and tape-measured truth as JSON
 * ([StudyFixture]); it fits every solver on N-1 distances per photo and grades the withheld one.
 */
object ConstraintStudyRunner {
    const val SINGLE_SCALE_NAME = "single-scale"
    const val MEAN_SCALE_NAME = "mean-scale"
    const val DEFAULT_NAME = "default-tier"
    const val GRADIENT_NAME = "gradient-tier"
    val SOLVER_NAMES = listOf(SINGLE_SCALE_NAME, MEAN_SCALE_NAME, DEFAULT_NAME, GRADIENT_NAME)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun parse(text: String): StudyFixture = json.decodeFromString(StudyFixture.serializer(), text)

    fun toJson(fixture: StudyFixture): String = json.encodeToString(StudyFixture.serializer(), fixture)

    /** The shipped default (`FitOptions()` follows [ConstraintTuning.GRADIENT_DEFAULT_ON]) and the GRADIENT tier forced on. */
    private fun calibrated(options: FitOptions) = HeldOutSolver { scene, train, target ->
        ConstraintCalibrator.fit(train.map { it.toConstraint() }, scene.frame(), options).effective?.measure(target, scene)
            ?.takeIf { it.isFinite() }
    }

    private val solvers: Map<String, HeldOutSolver> = linkedMapOf(
        SINGLE_SCALE_NAME to ConstraintAccuracyHarness.SINGLE_SCALE,
        MEAN_SCALE_NAME to ConstraintAccuracyHarness.MEAN_SCALE,
        DEFAULT_NAME to calibrated(FitOptions()),
        GRADIENT_NAME to calibrated(FitOptions(gradientEnabled = true)),
    )

    fun run(fixture: StudyFixture): StudyReport = StudyReport(fixture.photos.map(::leaveOneOut))

    private fun leaveOneOut(photo: StudyPhoto): PhotoResult {
        val segments = photo.distances.map { GoldenSegment(it.id, it.p1.toNormalized(), it.p2.toNormalized(), it.trueMeters) }
        val samples = solvers.keys.associateWith { mutableListOf<StudySample>() }
        for (i in segments.indices) {
            val train = segments.filterIndexed { j, _ -> j != i }
            if (train.isEmpty()) continue
            val scene = GoldenScene(SceneFamily.IN_MODEL, photo.widthPx, photo.heightPx, 0.0, 0.0, 0.0, 0.0, train, segments[i])
            for ((name, solver) in solvers) {
                val predicted = solver.predict(scene, train, segments[i]) ?: continue
                samples.getValue(name) += StudySample(segments[i].trueMeters, abs(predicted - segments[i].trueMeters))
            }
        }
        return PhotoResult(photo, samples)
    }

    private fun StudyPoint.toNormalized() = NormalizedPoint(x, y)
}
