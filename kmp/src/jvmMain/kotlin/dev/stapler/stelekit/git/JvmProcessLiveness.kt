// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Desktop/CLI liveness. Linux reads raw `/proc` ticks exactly as Android does (the JDK's `startInstant()` adds ticks
 * to a `btime` read once per JVM, so a wall-clock step would make two JVMs disagree); macOS and Windows compare
 * `startInstant()` within [LivenessPolicy.INSTANT_TOLERANCE_MS].
 */
class JvmProcessLiveness(
    private val useProc: Boolean = ProcfsProbe.isAvailable(),
    private val selfPidNs: String? = if (useProc) ProcfsProbe.selfPidNamespace() else null,
) : ProcessLiveness {
    override fun probe(pid: Long, startTicks: Long?, pidNs: String?, host: String): Liveness =
        if (useProc) {
            LivenessPolicy.judge(StartStampBasis.ProcTicks, startTicks, pidNs, selfPidNs, ProcfsProbe.probe(pid))
        } else {
            LivenessPolicy.judge(StartStampBasis.InstantMs, startTicks, pidNs, selfPidNs, handleProbe(pid))
        }

    private fun handleProbe(pid: Long): ProbeResult {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return ProbeResult.Absent
        val start = handle.info().startInstant().orElse(null) ?: return ProbeResult.Unreadable
        return ProbeResult.Present(start.toEpochMilli())
    }

    companion object {
        /** Start stamp for this process in the same basis [probe] compares, for writing into the owner record. */
        fun selfStartStamp(): Long? =
            if (ProcfsProbe.isAvailable()) {
                ProcfsProbe.selfStartTicks()
            } else {
                ProcessHandle.current().info().startInstant().orElse(null)?.toEpochMilli()
            }

        fun selfPidNamespace(): String? = ProcfsProbe.selfPidNamespace()

        fun bootId(): String? = ProcfsProbe.bootId()
    }
}
