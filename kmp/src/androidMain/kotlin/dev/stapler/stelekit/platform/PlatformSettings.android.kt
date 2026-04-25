package dev.stapler.stelekit.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SteleKitContext {
    private var _context: Context? = null
    val context: Context
        get() = _context ?: error("SteleKitContext not initialized. Call init(context) first.")

    fun init(context: Context) {
        _context = context.applicationContext
    }
}

actual class PlatformSettings actual constructor() : Settings {
    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(SteleKitContext.context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                SteleKitContext.context,
                "stelekit_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // Fallback to plain prefs if keystore fails (e.g., corrupted keystore after device wipe)
            Log.w("PlatformSettings", "EncryptedSharedPreferences unavailable, falling back to plain prefs", e)
            SteleKitContext.context.getSharedPreferences("stelekit_prefs", Context.MODE_PRIVATE)
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return try {
            prefs.getBoolean(key, defaultValue)
        } catch (e: Exception) { defaultValue }
    }

    override fun putBoolean(key: String, value: Boolean) {
        try {
            prefs.edit().putBoolean(key, value).apply()
        } catch (e: Exception) { }
    }

    override fun getString(key: String, defaultValue: String): String {
        return try {
            prefs.getString(key, defaultValue) ?: defaultValue
        } catch (e: Exception) { defaultValue }
    }

    override fun putString(key: String, value: String) {
        try {
            prefs.edit().putString(key, value).apply()
        } catch (e: Exception) { }
    }
}
