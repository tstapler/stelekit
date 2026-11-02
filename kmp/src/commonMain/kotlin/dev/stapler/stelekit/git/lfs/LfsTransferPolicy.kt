// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.platform.Settings

/**
 * Limits on LFS downloads. An object over [maxObjectBytes] stays on the server (the pointer text stays in the
 * working tree) and the job reports it; [deferOnMetered] holds every download until the connection is unmetered.
 */
data class LfsTransferPolicy(
    val maxObjectBytes: Long = DEFAULT_MAX_OBJECT_BYTES,
    val deferOnMetered: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_OBJECT_BYTES: Long = 256L * 1024 * 1024
    }
}

/** Reads [LfsTransferPolicy] from app settings; a missing or non-positive size falls back to the default cap. */
class LfsTransferPolicySource(private val settings: Settings?) {
    fun current(): LfsTransferPolicy {
        val mb = settings?.getString(KEY_MAX_OBJECT_MB, "")?.toLongOrNull()?.takeIf { it > 0 }
        return LfsTransferPolicy(
            maxObjectBytes = mb?.let { it * 1024 * 1024 } ?: LfsTransferPolicy.DEFAULT_MAX_OBJECT_BYTES,
            deferOnMetered = settings?.getBoolean(KEY_DEFER_ON_METERED, false) ?: false,
        )
    }

    companion object {
        const val KEY_MAX_OBJECT_MB = "git_lfs_max_object_mb"
        const val KEY_DEFER_ON_METERED = "git_lfs_defer_on_metered"
    }
}
