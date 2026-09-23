package dev.stapler.stelekit.platform

import java.io.File
import kotlinx.coroutines.CancellationException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

actual class PlatformSettings actual constructor() : Settings {
    private val props = Properties()
    private val prefsFile: File

    init {
        // Set only by `./gradlew run` (see kmp/build.gradle.kts) — dev/test launches must not
        // read or write the same prefs file a real install uses. Without this, GraphManager's
        // persisted "last active graph" (read from this file) silently overrides whatever graph
        // path the dev launch requested, since App.kt intentionally prefers the persisted graph
        // over the passed-in default.
        val devDataDir = System.getProperty("stelekit.devDataDir")
        val steleKitDir = if (devDataDir != null) {
            File(devDataDir)
        } else {
            val userHome = System.getProperty("user.home")
            File(userHome, ".stelekit")
        }
        if (!steleKitDir.exists()) {
            steleKitDir.mkdirs()
        }
        prefsFile = File(steleKitDir, "prefs.properties")
        if (prefsFile.exists()) {
            try {
                FileInputStream(prefsFile).use { props.load(it) }
            } catch (e: CancellationException) {
                throw e
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

    actual override fun containsKey(key: String): Boolean = props.containsKey(key)

    private fun save() {
        try {
            FileOutputStream(prefsFile).use { 
                props.store(it, "SteleKit Preferences")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignore save errors
        }
    }
}
