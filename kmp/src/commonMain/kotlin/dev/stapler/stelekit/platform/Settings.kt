package dev.stapler.stelekit.platform

/** Boolean setting, default off: check the remote on a timer while the app is open (and, on Android, in the background). */
const val SCHEDULED_SYNC_ENABLED_KEY = "scheduled_sync_enabled"

interface Settings {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, defaultValue: String): String
    fun putString(key: String, value: String)

    /**
     * Whether [key] has ever been explicitly stored, independent of what the typed getters
     * would return as a default. Needed to distinguish "key was never set" from "key is
     * absent so the typed getter returned its default" — e.g. a boolean explicitly stored as
     * `false` must not be indistinguishable from a key that was never put at all.
     */
    fun containsKey(key: String): Boolean
}
