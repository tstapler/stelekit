package dev.stapler.stelekit.export

import dev.stapler.stelekit.platform.Settings

/** Persisted share-dialog preferences (same [Settings] mechanism as `LlmSettings`). */
class ShareExportSettings(private val settings: Settings) {
    fun getStripTrackingParams(): Boolean = settings.getBoolean(KEY_STRIP_TRACKING_PARAMS, false)

    fun setStripTrackingParams(enabled: Boolean) {
        settings.putBoolean(KEY_STRIP_TRACKING_PARAMS, enabled)
    }

    companion object {
        const val KEY_STRIP_TRACKING_PARAMS = "share.strip_tracking_params"
    }
}
