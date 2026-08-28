package dev.stapler.stelekit.platform

import kotlinx.browser.localStorage

/**
 * Global switch flipped once, at startup, by `browser/Main.kt`'s ephemeral boot branch. Every
 * `PlatformSettings()` in this codebase is instantiated ad hoc (there is no injection seam — see
 * e.g. `persistWebGitCredentials`, `GitCredentialConnectionStore`'s call sites) backed directly by
 * `localStorage`, so redirecting each *instance* individually to an in-memory store for an
 * ephemeral session isn't practical. Checking this flag inside [PlatformSettings] itself instead
 * means every current and future `PlatformSettings()` usage is automatically ephemeral-aware with
 * no call-site changes — without it, configuring git sync during an "open temporarily" session
 * would silently write real credentials to permanent `localStorage`, defeating the entire feature.
 */
object EphemeralSettingsMode {
    var active: Boolean = false
        private set

    fun enable() {
        active = true
    }

    /** Shared in-memory backing store for every [PlatformSettings] instance while [active]. */
    internal val store = mutableMapOf<String, String>()
}

actual class PlatformSettings actual constructor() : Settings {
    actual override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val value = if (EphemeralSettingsMode.active) EphemeralSettingsMode.store[key] else localStorage.getItem(key)
        return value?.toBoolean() ?: defaultValue
    }

    actual override fun putBoolean(key: String, value: Boolean) {
        if (EphemeralSettingsMode.active) {
            EphemeralSettingsMode.store[key] = value.toString()
        } else {
            localStorage.setItem(key, value.toString())
        }
    }

    actual override fun getString(key: String, defaultValue: String): String {
        val value = if (EphemeralSettingsMode.active) EphemeralSettingsMode.store[key] else localStorage.getItem(key)
        return value ?: defaultValue
    }

    actual override fun putString(key: String, value: String) {
        if (EphemeralSettingsMode.active) {
            EphemeralSettingsMode.store[key] = value
        } else {
            localStorage.setItem(key, value)
        }
    }

    actual override fun containsKey(key: String): Boolean {
        return if (EphemeralSettingsMode.active) {
            EphemeralSettingsMode.store.containsKey(key)
        } else {
            localStorage.getItem(key) != null
        }
    }
}
