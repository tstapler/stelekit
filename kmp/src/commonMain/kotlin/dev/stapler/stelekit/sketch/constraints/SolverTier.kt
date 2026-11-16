package dev.stapler.stelekit.sketch.constraints

/** The targets whose tier is decided separately (D-114): the JVM stands for desktop and Android. */
enum class SolverTarget { Jvm, WasmJs }

/** What the solver may offer on one target: everything, the five geometric kinds only, or nothing (D-114). */
enum class SolverTier { Full, GeometricOnly, Hidden }

/** The time and residual measurements of one target; the geometric-only set is re-measured when the full set misses. */
data class TargetMeasurement(
    val fullSetTimeMet: Boolean,
    val geometricOnlySetTimeMet: Boolean,
    val geometricOnlySetResidualMet: Boolean,
)

/**
 * The per-target tier rule of ADR-003 item 7. A miss of a target-independent criterion (residual bar, redundant/conflict
 * classification, branch flip) selects `GeometricOnly` on every target; a time miss only on its own target, so a
 * wasmJs-only miss leaves the JVM on `Full`. A target whose geometric-only set also misses is `Hidden`.
 */
object SolverTierPolicy {
    fun decide(targetIndependentCriteriaMet: Boolean, measurements: Map<SolverTarget, TargetMeasurement>): Map<SolverTarget, SolverTier> =
        measurements.mapValues { (_, m) ->
            val fallbackHolds = m.geometricOnlySetTimeMet && m.geometricOnlySetResidualMet
            when {
                targetIndependentCriteriaMet && m.fullSetTimeMet -> SolverTier.Full
                fallbackHolds -> SolverTier.GeometricOnly
                else -> SolverTier.Hidden
            }
        }
}
