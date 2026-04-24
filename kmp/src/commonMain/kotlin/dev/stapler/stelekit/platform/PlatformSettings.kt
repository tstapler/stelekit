package dev.stapler.stelekit.platform

expect class PlatformSettings() : Settings {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean
    override fun putBoolean(key: String, value: Boolean)
    override fun getString(key: String, defaultValue: String): String
    override fun putString(key: String, value: String)
}
