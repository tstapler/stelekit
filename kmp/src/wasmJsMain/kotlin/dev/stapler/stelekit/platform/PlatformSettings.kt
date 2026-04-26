package dev.stapler.stelekit.platform

import kotlinx.browser.localStorage

actual class PlatformSettings actual constructor() : Settings {
    actual override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val value = localStorage.getItem(key)
        return value?.toBoolean() ?: defaultValue
    }

    actual override fun putBoolean(key: String, value: Boolean) {
        localStorage.setItem(key, value.toString())
    }

    actual override fun getString(key: String, defaultValue: String): String {
        return localStorage.getItem(key) ?: defaultValue
    }

    actual override fun putString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
}
