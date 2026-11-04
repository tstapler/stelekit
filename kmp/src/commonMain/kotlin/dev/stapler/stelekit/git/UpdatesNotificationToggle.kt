// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform seam for the notification permission; only Android has one. */
interface NotificationPermissionAccess {
    fun state(): NotifyPermissionState

    /** Shows the system permission dialog and returns the state once the user answered. */
    suspend fun request(): NotifyPermissionState

    fun openSystemSettings()
}

/**
 * Collapses platform facts into [NotifyPermissionState]. Android reports a plain denial and a permanent one the same
 * way; once asked, no rationale to show means the system will no longer show the dialog.
 */
fun resolveNotifyPermissionState(
    runtimePermissionRequired: Boolean,
    granted: Boolean,
    askedBefore: Boolean,
    shouldShowRationale: Boolean,
): NotifyPermissionState = when {
    !runtimePermissionRequired -> NotifyPermissionState.NotRequired
    granted -> NotifyPermissionState.Granted
    !askedBefore -> NotifyPermissionState.NotAsked
    shouldShowRationale -> NotifyPermissionState.DeniedOnce
    else -> NotifyPermissionState.PermanentlyDenied
}

/** The Settings toggle behind [UpdatesNotificationPolicy]'s `enabled`; default on, as the notifier already reads it. */
class UpdatesNotificationPreference(private val settings: Settings) {
    private val _enabled = MutableStateFlow(read())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(value: Boolean) {
        try {
            settings.putBoolean(SETTING_KEY, value)
        } catch (_: Exception) {
            // Persisting failed; keep the in-memory choice for this session only.
        }
        _enabled.value = value
    }

    private fun read(): Boolean = try {
        settings.getBoolean(SETTING_KEY, true)
    } catch (_: Exception) {
        true
    }

    companion object {
        const val SETTING_KEY = "git_sync_notify_enabled"

        fun isEnabled(settings: Settings): Boolean = settings.getBoolean(SETTING_KEY, true)
    }
}

sealed interface ToggleOutcome {
    data object Enabled : ToggleOutcome
    data object Disabled : ToggleOutcome

    /** The caller shows the rationale, then calls [UpdatesNotificationToggle.continueAfterRationale]. */
    data object NeedsRationale : ToggleOutcome
    data object OpenedSystemSettings : ToggleOutcome
    data class Denied(val state: NotifyPermissionState) : ToggleOutcome
}

/** Drives the Settings toggle through [NotifyPermissionFlow]: the switch turns on only once a post can succeed. */
class UpdatesNotificationToggle(
    private val access: NotificationPermissionAccess,
    private val preference: UpdatesNotificationPreference,
) {
    fun onToggle(on: Boolean): ToggleOutcome {
        if (!on) {
            preference.setEnabled(false)
            return ToggleOutcome.Disabled
        }
        return when (NotifyPermissionFlow.onToggleOn(access.state())) {
            NotifyToggleAction.Enable -> enable()
            NotifyToggleAction.ShowRationaleThenRequest -> ToggleOutcome.NeedsRationale
            NotifyToggleAction.OpenSystemSettings -> {
                access.openSystemSettings()
                ToggleOutcome.OpenedSystemSettings
            }
        }
    }

    suspend fun continueAfterRationale(): ToggleOutcome {
        val after = access.request()
        return if (NotifyPermissionFlow.canPost(after)) enable() else ToggleOutcome.Denied(after)
    }

    private fun enable(): ToggleOutcome {
        preference.setEnabled(true)
        return ToggleOutcome.Enabled
    }
}
