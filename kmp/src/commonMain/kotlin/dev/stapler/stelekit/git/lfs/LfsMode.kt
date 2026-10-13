// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

import dev.stapler.stelekit.platform.Settings

/** Per-graph Git LFS behaviour. Phase 1 only ever acts on the guard; `AUTO` is reserved for transfer support. */
enum class LfsMode {
    /** No LFS handling beyond the safety floor. */
    OFF,

    /** Never transfer LFS objects; hold back raw binaries at tracked paths (the safety floor). */
    GUARD_ONLY,

    /** Convert and transfer LFS objects where the platform supports it. */
    AUTO,
}

/**
 * What the platform can do. A capability, not a user setting: a stored [LfsMode.AUTO] is ignored
 * (the effective mode is [LfsMode.GUARD_ONLY]) whenever the platform cannot honour it.
 */
data class LfsPlatformCapabilities(val transferSupported: Boolean, val androidLfsEnabled: Boolean) {
    companion object {
        /** iOS, web, and anything that has not declared LFS support. */
        val GUARD_ONLY_PLATFORM = LfsPlatformCapabilities(transferSupported = false, androidLfsEnabled = false)
    }
}

/** Reads and writes the stored per-graph mode (`git_lfs_mode_<graphId>`); no SQL migration needed. */
class LfsModeSource(private val settings: Settings?) {
    fun get(graphId: String): LfsMode =
        settings?.getString(key(graphId), "")
            ?.let { stored -> LfsMode.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_MODE

    fun set(graphId: String, mode: LfsMode) {
        settings?.putString(key(graphId), mode.name)
    }

    companion object {
        /** Phase 1 owns the type but transfers nothing, so the default is the safety floor. */
        val DEFAULT_MODE = LfsMode.GUARD_ONLY

        fun key(graphId: String) = "git_lfs_mode_$graphId"
    }
}

/** Pure: collapses (stored mode, platform capability) to the mode actually in force. */
object LfsModeResolver {
    fun effective(stored: LfsMode, capabilities: LfsPlatformCapabilities, isAndroid: Boolean = false): LfsMode = when {
        stored == LfsMode.OFF -> LfsMode.OFF
        stored == LfsMode.GUARD_ONLY -> LfsMode.GUARD_ONLY
        !capabilities.transferSupported -> LfsMode.GUARD_ONLY
        isAndroid && !capabilities.androidLfsEnabled -> LfsMode.GUARD_ONLY
        else -> LfsMode.AUTO
    }
}
