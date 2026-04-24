package dev.stapler.stelekit.platform

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

actual class PlatformSettings actual constructor() : Settings {
    private val props = Properties()
    private val prefsFile: File

    init {
        val userHome = System.getProperty("user.home")
        val steleKitDir = File(userHome, ".stelekit")
        if (!steleKitDir.exists()) {
            steleKitDir.mkdirs()
        }
        prefsFile = File(steleKitDir, "prefs.properties")
        if (prefsFile.exists()) {
            try {
                FileInputStream(prefsFile).use { props.load(it) }
            } catch (e: Exception) {
                // Ignore load errors, start with empty
            }
        }
    }

    actual override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        val value = props.getProperty(key)
        return value?.toBoolean() ?: defaultValue
    }

    actual override fun putBoolean(key: String, value: Boolean) {
        props.setProperty(key, value.toString())
        save()
    }

    actual override fun getString(key: String, defaultValue: String): String {
        return props.getProperty(key, defaultValue)
    }

    actual override fun putString(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    private fun save() {
        try {
            FileOutputStream(prefsFile).use { 
                props.store(it, "SteleKit Preferences")
            }
        } catch (e: Exception) {
            // Ignore save errors
        }
    }
}
