// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android implementation of [CredentialStore] using [EncryptedSharedPreferences].
 * Credentials are encrypted with AES-256-GCM and stored in the app's private storage.
 *
 * Note: This class requires an Android Context. Since it is an expect/actual class with
 * no-arg constructor in the expect declaration, this implementation retrieves the application
 * context via a companion object that must be initialized from the Application class.
 */
actual class CredentialStore actual constructor() {

    companion object {
        private var applicationContext: Context? = null

        /**
         * Must be called from Application.onCreate() before any CredentialStore usage.
         */
        fun init(context: Context) {
            applicationContext = context.applicationContext
        }
    }

    private val prefs: SharedPreferences by lazy {
        val ctx = requireNotNull(applicationContext) {
            "CredentialStore.init(context) must be called before using CredentialStore"
        }
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            "stelekit_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun store(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun retrieve(key: String): String? = prefs.getString(key, null)

    actual fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }
}
