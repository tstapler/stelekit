package dev.stapler.stelekit.platform

import kotlinx.browser.localStorage

actual class PlatformSettings actual constructor() {
    actual fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val value = localStorage.getItem(key)
        return value?.toBoolean() ?: defaultValue
    }

    actual fun putBoolean(key: String, value: Boolean) {
        localStorage.setItem(key, value.toString())
    }

    actual fun getString(key: String, defaultValue: String): String {
        return localStorage.getItem(key) ?: defaultValue
    }

    actual fun putString(key: String, value: String) {
        localStorage.setItem(key, value)
    }
}
