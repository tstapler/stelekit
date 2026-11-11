// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.jobs.schedule.NetworkGate
import dev.stapler.stelekit.jobs.schedule.NetworkGateResult
import dev.stapler.stelekit.jobs.schedule.NetworkPolicy
import dev.stapler.stelekit.jobs.schedule.NetworkPolicyCopy
import dev.stapler.stelekit.platform.Settings

/**
 * Limits on LFS downloads. An object over [maxObjectBytes] stays on the server (the pointer text stays in the
 * working tree) and the job reports it; [deferOnMetered] holds every download until the connection is unmetered.
 */
data class LfsTransferPolicy(
    val maxObjectBytes: Long = DEFAULT_MAX_OBJECT_BYTES,
    val deferOnMetered: Boolean = false,
) {
    /**
     * Whether downloads wait for an unmetered network, through the shared [NetworkGate] (LFS runs inside an
     * already-online sync, so `online` is true). Where the platform cannot detect metering the policy is a no-op,
     * so Desktop users are never blocked; where it can, an unknown reading fails closed.
     */
    fun defers(meteredDetectionSupported: Boolean, isMetered: Boolean?): Boolean =
        deferOnMetered && meteredDetectionSupported &&
            NetworkGate.evaluateMetered(NetworkPolicy.Unmetered, isMetered) is NetworkGateResult.Skip

    companion object {
        const val DEFAULT_MAX_OBJECT_BYTES: Long = 256L * 1024 * 1024
    }
}

/** Status shown while LFS downloads are held by [LfsTransferPolicy.defers]. */
const val LFS_DEFERRAL_STATUS: String = NetworkPolicyCopy.WAITING_FOR_UNMETERED

/** Reads [LfsTransferPolicy] from app settings; a missing or non-positive size falls back to the default cap. */
class LfsTransferPolicySource(private val settings: Settings?) {
    fun save(policy: LfsTransferPolicy) {
        settings?.putString(KEY_MAX_OBJECT_MB, (policy.maxObjectBytes / BYTES_PER_MB).toString())
        settings?.putBoolean(KEY_DEFER_ON_METERED, policy.deferOnMetered)
    }

    fun current(): LfsTransferPolicy {
        val mb = settings?.getString(KEY_MAX_OBJECT_MB, "")?.toLongOrNull()?.takeIf { it > 0 }
        return LfsTransferPolicy(
            maxObjectBytes = mb?.let { it * BYTES_PER_MB } ?: LfsTransferPolicy.DEFAULT_MAX_OBJECT_BYTES,
            deferOnMetered = settings?.getBoolean(KEY_DEFER_ON_METERED, false) ?: false,
        )
    }

    companion object {
        const val KEY_MAX_OBJECT_MB = "git_lfs_max_object_mb"
        const val KEY_DEFER_ON_METERED = "git_lfs_defer_on_metered"
        private const val BYTES_PER_MB = 1024L * 1024
    }
}
