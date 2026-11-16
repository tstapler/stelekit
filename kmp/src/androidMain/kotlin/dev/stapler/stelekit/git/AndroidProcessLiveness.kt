// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Android liveness over raw `/proc/<pid>/stat` ticks. ENOENT is Dead only with the same non-null pid namespace
 * (enforced by [LivenessPolicy]); EACCES or a parse failure is Unknown.
 */
class AndroidProcessLiveness(
    private val selfPidNs: String? = ProcfsProbe.selfPidNamespace(),
) : ProcessLiveness {
    override fun probe(pid: Long, startTicks: Long?, pidNs: String?, host: String): Liveness =
        LivenessPolicy.judge(StartStampBasis.ProcTicks, startTicks, pidNs, selfPidNs, ProcfsProbe.probe(pid))

    companion object {
        /** The app-private shadow directory is single-host, so the host is a constant. */
        const val HOST = "android"

        fun selfStartTicks(): Long? = ProcfsProbe.selfStartTicks()

        fun selfPidNamespace(): String? = ProcfsProbe.selfPidNamespace()

        /** `boot_id` where readable (denied on some Android 8.x), else the caller-supplied `Settings.Global.BOOT_COUNT`, else null. */
        fun bootId(bootCountFallback: () -> String? = { null }): String? = ProcfsProbe.bootId() ?: bootCountFallback()
    }
}
