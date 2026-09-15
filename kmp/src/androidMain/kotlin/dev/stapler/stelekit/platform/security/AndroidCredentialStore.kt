// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

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
 *
 * [prefsFactory] is a test-only seam (see the `internal constructor`) — production code must
 * always use the no-arg [actual constructor], which wires [prefsFactory] to
 * [createEncryptedPrefs]. This lets tests inject a fake/mock [SharedPreferences] (to assert
 * `storeBlocking()` calls `commit()` not `apply()`) or a factory that throws (to lock in the
 * fail-loud-on-Keystore-failure contract) without needing to fight Robolectric's Keystore
 * shadow behavior.
 */
actual class CredentialStore internal constructor(
    private val prefsFactory: () -> SharedPreferences,
) : CredentialAccess {

    actual constructor() : this({ createEncryptedPrefs() })

    companion object {
        private var applicationContext: Context? = null

        /**
         * Must be called from Application.onCreate() before any CredentialStore usage.
         */
        fun init(context: Context) {
            applicationContext = context.applicationContext
        }

        private fun createEncryptedPrefs(): SharedPreferences {
            val ctx = requireNotNull(applicationContext) {
                "CredentialStore.init(context) must be called before using CredentialStore"
            }
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                "stelekit_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    // Deliberately no try/catch here — a Keystore/EncryptedSharedPreferences init failure must
    // propagate (fail loud) rather than silently degrade to plaintext storage. See
    // AndroidCredentialStoreFailsLoudTest.
    private val prefs: SharedPreferences by lazy { prefsFactory() }

    actual override fun store(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual override fun retrieve(key: String): String? = prefs.getString(key, null)

    actual override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    /**
     * Synchronous, durable-before-return write for migration use only.
     *
     * [store] uses [SharedPreferences.Editor.apply], which updates the in-memory
     * `SharedPreferences` cache synchronously but flushes to disk *asynchronously*
     * afterward. An immediate read-back after `apply()` reads the in-memory cache and
     * reports success regardless of whether the disk write has actually landed — a
     * process crash in the window between `apply()` returning and its async disk flush
     * completing would silently lose the write even though a read-back check passed.
     *
     * This override uses [SharedPreferences.Editor.commit] instead, which blocks the
     * calling thread until the write is durable on disk (or definitively failed) before
     * returning. This is the correct tradeoff *only* for the one-shot credential
     * migration path (a handful of writes at app startup) — do not "simplify" this back
     * to `apply()` for consistency with [store]; the two methods have intentionally
     * different durability contracts. Do not route interactive git-credential entry
     * (or any other frequent, main-thread call site) through this method — `commit()`
     * blocking the calling thread is unacceptable UX outside a rare migration path.
     */
    override fun storeBlocking(key: String, value: String): Boolean =
        prefs.edit().putString(key, value).commit()
}
