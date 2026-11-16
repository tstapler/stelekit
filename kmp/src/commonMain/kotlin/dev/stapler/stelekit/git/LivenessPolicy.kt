// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlin.math.abs

/** What reading one pid's start stamp on this machine produced. */
sealed interface ProbeResult {
    /** No such pid (ENOENT / no process handle). */
    data object Absent : ProbeResult

    /** The pid could not be inspected (EACCES, parse failure, no start time). */
    data object Unreadable : ProbeResult

    data class Present(val startStamp: Long) : ProbeResult
}

/** How two start stamps are compared. */
enum class StartStampBasis {
    /** Linux/Android: raw `/proc/<pid>/stat` field 22; no wall clock involved, so equality is exact. */
    ProcTicks,

    /** macOS/Windows: `ProcessHandle.info().startInstant()` as epoch ms, compared within [LivenessPolicy.INSTANT_TOLERANCE_MS]. */
    InstantMs,
}

/** Pure liveness decision; the platform adapters only gather the [ProbeResult] and their own pid namespace. */
object LivenessPolicy {
    const val INSTANT_TOLERANCE_MS = 2_000L

    fun judge(
        basis: StartStampBasis,
        recordedStamp: Long?,
        recordedPidNs: String?,
        selfPidNs: String?,
        probe: ProbeResult,
    ): Liveness {
        // A pid number only identifies a process inside one pid namespace (sandboxed app vs host CLI).
        val sameNamespace = recordedPidNs != null && recordedPidNs == selfPidNs
        return when (probe) {
            ProbeResult.Unreadable -> Liveness.Unknown
            ProbeResult.Absent -> if (sameNamespace) Liveness.Dead else Liveness.Unknown
            is ProbeResult.Present -> judgePresent(basis, recordedStamp, sameNamespace, probe.startStamp)
        }
    }

    private fun judgePresent(basis: StartStampBasis, recordedStamp: Long?, sameNamespace: Boolean, observed: Long): Liveness =
        when {
            recordedStamp == null -> Liveness.Unknown
            sameStamp(basis, recordedStamp, observed) -> Liveness.Alive
            // A different process now owns that pid number (reuse); provable only inside one namespace.
            sameNamespace -> Liveness.Dead
            else -> Liveness.Unknown
        }

    private fun sameStamp(basis: StartStampBasis, a: Long, b: Long): Boolean = when (basis) {
        StartStampBasis.ProcTicks -> a == b
        StartStampBasis.InstantMs -> abs(a - b) <= INSTANT_TOLERANCE_MS
    }
}
