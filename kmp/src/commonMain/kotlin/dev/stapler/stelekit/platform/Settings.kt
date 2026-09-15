package dev.stapler.stelekit.platform

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
