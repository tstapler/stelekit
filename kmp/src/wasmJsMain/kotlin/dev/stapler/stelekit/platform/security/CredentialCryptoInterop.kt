// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

import dev.stapler.stelekit.platform.toJsArrayBuffer
import dev.stapler.stelekit.platform.toKotlinByteArray
import kotlinx.coroutines.await

/**
 * Hand-rolled `external`/`js()` bindings over `crypto.subtle`/`crypto.getRandomValues`, matching
 * `OpfsInterop.kt`'s established idiom (no new dependency — see
 * project_plans/web-credential-persistence/research/build-vs-buy.md). Every `Promise` created
 * here is `.await()`ed by the caller ([CredentialStore]'s `preload()`/`persistCredential()`),
 * inside a `try`/`catch` at that call site — not left detached.
 */

internal fun subtleCryptoAvailable(): Boolean =
    js("typeof crypto !== 'undefined' && typeof crypto.subtle !== 'undefined'")

private fun randomBytesJs(length: Int): JsAny = js("crypto.getRandomValues(new Uint8Array(length))")

internal fun randomBytes(length: Int): ByteArray = randomBytesJs(length).toKotlinByteArray()

private fun importAesKeyPromise(rawKey: JsAny): kotlin.js.Promise<JsAny> =
    js("crypto.subtle.importKey('raw', rawKey, { name: 'AES-GCM' }, false, ['encrypt', 'decrypt'])")

internal suspend fun importAesKey(rawKeyBytes: ByteArray): JsAny =
    importAesKeyPromise(rawKeyBytes.toJsArrayBuffer()).await()

private fun subtleEncryptPromise(key: JsAny, iv: JsAny, data: JsAny): kotlin.js.Promise<JsAny> =
    js("crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, data)")

internal suspend fun subtleEncrypt(key: JsAny, iv: ByteArray, data: ByteArray): ByteArray =
    subtleEncryptPromise(key, iv.toJsArrayBuffer(), data.toJsArrayBuffer()).await<JsAny>().toKotlinByteArray()

private fun subtleDecryptPromise(key: JsAny, iv: JsAny, data: JsAny): kotlin.js.Promise<JsAny> =
    js("crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv }, key, data)")

internal suspend fun subtleDecrypt(key: JsAny, iv: ByteArray, data: ByteArray): ByteArray =
    subtleDecryptPromise(key, iv.toJsArrayBuffer(), data.toJsArrayBuffer()).await<JsAny>().toKotlinByteArray()

// ponytail: `localStorage.length`/`.key(i)` enumeration goes through raw js() rather than the
// kotlinx-browser Storage type — avoids depending on an unverified typed-binding surface for two
// one-line calls; upgrade to the typed API if/when it's confirmed available.
internal fun localStorageLength(): Int = js("localStorage.length")
internal fun localStorageKeyAt(index: Int): String? = js("localStorage.key(index)")
