// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

import dev.stapler.stelekit.platform.EphemeralSettingsMode
import kotlinx.browser.localStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * wasmJs actual: encrypts values at rest with AES-256-GCM (WebCrypto), backed by `localStorage`.
 *
 * Threat model (project_plans/web-credential-persistence/decisions/ADR-001-web-credential-key-material-threat-model.md):
 * protects against casual snooping (plaintext-in-devtools, plaintext in a browser data
 * export/backup) but not against a determined attacker with script execution in the same origin
 * (XSS) or direct `localStorage` file access — that attacker can read the encryption key sitting
 * next to the ciphertext (a browser script has no durable, non-guessable per-device identifier to
 * derive a key from instead, unlike the JVM actual's `user.name`+`os.name`-derived key). This
 * matches, not exceeds, JVM's own documented threat model — both defend a
 * plaintext-in-devtools/plaintext-in-backup read, not a co-resident attacker.
 *
 * [Companion] holds all real state (in-memory cache, imported key, background persist scope) as a
 * process-wide singleton, since every call site constructs `CredentialStore()` ad hoc with no
 * injection seam (`GraphManager.kt`, `App.kt`, `GitCredentialConnectionStore`, `LlmCredentialStore`)
 * — an instance-held cache would be empty on every one of those constructions.
 */
@OptIn(ExperimentalEncodingApi::class)
actual class CredentialStore actual constructor() : CredentialAccess {

    actual override fun store(key: String, value: String) {
        decryptedCache[key] = value
        trackPendingJob(scope.launch { persistCredential(key, value) })
    }

    actual override fun retrieve(key: String): String? = decryptedCache[key]

    actual override fun delete(key: String) {
        decryptedCache.remove(key)
        trackPendingJob(
            scope.launch {
                try {
                    if (!EphemeralSettingsMode.active) localStorage.removeItem(CIPHERTEXT_KEY_PREFIX + key)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    println("[SteleKit] CredentialStore delete failed for key '$key': ${e.message}")
                }
            },
        )
    }

    override fun isAvailable(): Boolean = subtleCryptoAvailable()

    /**
     * Always `false` — WebCrypto has no synchronous encrypt and Wasm can't block on a `Promise`,
     * so durability can never be honestly confirmed before this non-`suspend` call returns. See
     * ADR-002 (project_plans/web-credential-persistence/decisions/ADR-002-storeblocking-durability-signal-on-wasmjs.md).
     * [store] still runs — the value is cached and best-effort persisted in the background.
     */
    override fun storeBlocking(key: String, value: String): Boolean {
        store(key, value)
        return false
    }

    companion object {
        internal const val CREDENTIAL_KEY_STORAGE_KEY = "stelekit_credential_key"
        internal const val CIPHERTEXT_KEY_PREFIX = "credential_enc."

        private val decryptedCache = mutableMapOf<String, String>()

        // Awaited by persistCredential rather than a bare nullable `JsAny?` + `!!` — a store()/
        // persistCredential() call reached before preload() has finished (or if it never runs at
        // all) self-heals into a harmless wait instead of an NPE that silently drops the write
        // (architecture-review.md Concern 3).
        private var cryptoKeyDeferred: CompletableDeferred<JsAny>? = null

        // Internally owned — never a caller-supplied rememberCoroutineScope() (this repo's
        // coroutine-scope-ownership rule; see CLAUDE.md).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val pendingJobs = mutableListOf<Job>()

        // Removes a job from pendingJobs once it finishes, so a long browser session with many
        // credential writes doesn't leak Job references for the life of the tab — the list would
        // otherwise only ever grow outside the test-only resetForTest()/flushForTest() helpers.
        private fun trackPendingJob(job: Job) {
            pendingJobs.add(job)
            job.invokeOnCompletion { pendingJobs.remove(job) }
        }

        /**
         * Boot-time-only: loads-or-generates the AES key from `localStorage`, then decrypts every
         * existing `credential_enc.*` entry into [decryptedCache]. **Must be called as a direct,
         * awaited `suspend` call on the boot coroutine — never wrapped in a detached
         * `scope.launch { ... }`** — see `browser/Main.kt`'s two boot paths, which call this
         * before building `configResolver` or mounting `ComposeViewport`. Never throws: any
         * failure (unavailable `SubtleCrypto`, malformed stored key, quota/private-mode error) is
         * caught, logged, and leaves [decryptedCache] empty / every [retrieve] returning `null`
         * for the rest of the session — not a crash.
         */
        internal suspend fun preload() {
            val deferred = CompletableDeferred<JsAny>()
            cryptoKeyDeferred = deferred
            try {
                val cryptoKey = importAesKey(loadOrGenerateKeyBytes())
                deferred.complete(cryptoKey)
                // Ephemeral sessions never read real localStorage, not even to attempt-and-fail a
                // decrypt — the in-memory-only key above can't decrypt real entries anyway.
                if (!EphemeralSettingsMode.active) decryptExistingEntries(cryptoKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("[SteleKit] CredentialStore: preload failed, credentials unavailable this session: ${e.message}")
                deferred.completeExceptionally(e)
                cryptoKeyDeferred = null
            }
        }

        private fun loadOrGenerateKeyBytes(): ByteArray {
            if (EphemeralSettingsMode.active) return randomBytes(32)
            val existing = localStorage.getItem(CREDENTIAL_KEY_STORAGE_KEY)
            if (existing != null) return Base64.Default.decode(existing)
            val generated = randomBytes(32)
            localStorage.setItem(CREDENTIAL_KEY_STORAGE_KEY, Base64.Default.encode(generated))
            return generated
        }

        private suspend fun decryptExistingEntries(cryptoKey: JsAny) {
            for (i in 0 until localStorageLength()) {
                val k = localStorageKeyAt(i) ?: continue
                if (!k.startsWith(CIPHERTEXT_KEY_PREFIX)) continue
                try {
                    val payload = localStorage.getItem(k) ?: continue
                    val parts = payload.split(":", limit = 2)
                    require(parts.size == 2) { "malformed credential_enc payload" }
                    val iv = Base64.Default.decode(parts[0])
                    val ciphertext = Base64.Default.decode(parts[1])
                    val plaintext = subtleDecrypt(cryptoKey, iv, ciphertext).decodeToString()
                    decryptedCache[k.removePrefix(CIPHERTEXT_KEY_PREFIX)] = plaintext
                } catch (e: Throwable) {
                    println("[SteleKit] CredentialStore: decrypt failed for stored key '$k', skipping (not fatal)")
                }
            }
        }

        /** Fire-and-forget background encrypt+persist launched from [store]. Never throws. */
        private suspend fun persistCredential(key: String, value: String) {
            if (EphemeralSettingsMode.active) return
            try {
                val cryptoKey = cryptoKeyDeferred?.await() ?: return
                val iv = randomBytes(12)
                val ciphertext = subtleEncrypt(cryptoKey, iv, value.encodeToByteArray())
                val payload = "${Base64.Default.encode(iv)}:${Base64.Default.encode(ciphertext)}"
                localStorage.setItem(CIPHERTEXT_KEY_PREFIX + key, payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                println("[SteleKit] CredentialStore encrypt+persist failed for key '$key': ${e.message}")
            }
        }

        /**
         * Test-only. Clears in-memory state **and** every real `localStorage` entry this class
         * wrote — the companion object is a process-wide singleton shared across every test file
         * that touches [CredentialStore] within the same browser session/`localStorage` origin.
         */
        internal fun resetForTest() {
            resetInMemoryOnlyForTest()
            localStorage.removeItem(CREDENTIAL_KEY_STORAGE_KEY)
            val keysToRemove = (0 until localStorageLength()).mapNotNull { localStorageKeyAt(it) }
                .filter { it.startsWith(CIPHERTEXT_KEY_PREFIX) }
            keysToRemove.forEach { localStorage.removeItem(it) }
        }

        /**
         * Test-only. Clears only in-memory state, leaving real `localStorage` untouched — used to
         * simulate a page reload against existing `localStorage` content (a fresh `preload()` call
         * afterward should repopulate [decryptedCache] from what's still on disk).
         */
        internal fun resetInMemoryOnlyForTest() {
            decryptedCache.clear()
            cryptoKeyDeferred = null
            pendingJobs.clear()
        }

        /** Test-only. Awaits every in-flight [persistCredential]/delete background job. */
        internal suspend fun flushForTest() {
            val jobs = pendingJobs.toList()
            pendingJobs.clear()
            jobs.forEach { it.join() }
        }
    }
}
