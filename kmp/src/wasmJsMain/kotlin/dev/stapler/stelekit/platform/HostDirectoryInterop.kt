// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.await

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.

/**
 * `web-local-folder-livesync` (Epic 1.5) browser interop primitives — IndexedDB
 * `FileSystemDirectoryHandle` persistence, `queryPermission()`/`requestPermission()`, the
 * `FileSystemObserver` construction/observe surface, `File.lastModified`/`size` accessors, the
 * visibility-visible promise (inverse of [jsVisibilityHiddenPromise]), and
 * `navigator.storage.persist()`.
 *
 * Hand-rolled `js()` + `.await()`, matching [OpfsInterop.kt]'s established idiom rather than
 * pulling in an IndexedDB wrapper dependency — see
 * `project_plans/web-local-folder-livesync/decisions/ADR-001-indexeddb-handle-persistence.md`
 * for the rationale.
 */

// ---------------------------------------------------------------------------------------------
// IndexedDB: open the `stelekit-host-handles` database / put / get a handle (Story 1.5.1)
// ---------------------------------------------------------------------------------------------

private const val HOST_HANDLE_DB_NAME = "stelekit-host-handles"
private const val HOST_HANDLE_DB_VERSION = 1

private fun idbOpenPromise(name: String, version: Int): kotlin.js.Promise<JsAny> = js(
    "new Promise(function(res, rej) { var r = indexedDB.open(name, version); r.onupgradeneeded = function(e) { e.target.result.createObjectStore('handles'); }; r.onsuccess = function(e) { res(e.target.result); }; r.onerror = function(e) { rej(e); }; })",
)

private fun idbPutHandlePromise(db: JsAny, key: String, handle: JsAny): kotlin.js.Promise<JsAny> = js(
    "new Promise(function(res, rej) { var tx = db.transaction('handles', 'readwrite'); tx.objectStore('handles').put(handle, key); tx.oncomplete = function() { res(handle); }; tx.onerror = function(e) { rej(e); }; })",
)

private fun idbGetHandlePromise(db: JsAny, key: String): kotlin.js.Promise<JsAny?> = js(
    "new Promise(function(res) { var tx = db.transaction('handles', 'readonly'); var req = tx.objectStore('handles').get(key); req.onsuccess = function() { res(req.result || null); }; req.onerror = function() { res(null); }; })",
)

/**
 * Opens (creating on first use) the `stelekit-host-handles` IndexedDB database and its single
 * `handles` object store. Unlike the read/write helpers below, a failure here leaves the caller
 * with nothing usable, so it is logged and rethrown rather than swallowed.
 */
internal suspend fun idbOpenHandleDb(): JsAny = try {
    idbOpenPromise(HOST_HANDLE_DB_NAME, HOST_HANDLE_DB_VERSION).await()
} catch (e: Throwable) {
    println("[SteleKit] IndexedDB open failed for $HOST_HANDLE_DB_NAME: ${e.message}")
    throw e
}

/** Write path — log-and-return on failure, matching [opfsWriteFile]'s convention. */
internal suspend fun idbPutHandle(db: JsAny, key: String, handle: JsAny) {
    try {
        idbPutHandlePromise(db, key, handle).await()
    } catch (e: Throwable) {
        println("[SteleKit] IndexedDB put failed for key=$key: ${e.message}")
    }
}

/** Read path — returns null on failure or when the key is absent, never throws. */
internal suspend fun idbGetHandle(db: JsAny, key: String): JsAny? = try {
    idbGetHandlePromise(db, key).await()
} catch (e: Throwable) {
    println("[SteleKit] IndexedDB get failed for key=$key: ${e.message}")
    null
}

// ---------------------------------------------------------------------------------------------
// Permission query/request (Story 1.5.3) — fail-closed ("denied"), never open
// ---------------------------------------------------------------------------------------------

private fun queryPermissionPromise(handle: JsAny, mode: String): kotlin.js.Promise<JsAny> =
    js("handle.queryPermission({ mode: mode })")

private fun requestPermissionPromise(handle: JsAny, mode: String): kotlin.js.Promise<JsAny> =
    js("handle.requestPermission({ mode: mode })")

private fun jsStringValue(v: JsAny): String = js("String(v)")

/**
 * `internal` — Epic 2.2's `HostDirectorySync.reconnectHostDirectory`/`requestHostDirectoryAccess`
 * reuse this to decode the JSON-string `HostHandleEnvelope` read back from IndexedDB
 * (`HostDirectorySync.kt`, same package, different file). Named distinctly from this file's own
 * file-private [jsStringValue] (and `OpfsInterop.kt`'s identically-named file-private helper) —
 * `internal` top-level functions are package-visible, so reusing the exact same name would collide
 * with `OpfsInterop.kt`'s helper as a "conflicting overloads" compile error.
 */
internal fun jsAnyToUtf8String(v: JsAny): String = js("String(v)")

internal suspend fun queryHandlePermission(handle: JsAny, mode: String = "readwrite"): String = try {
    jsStringValue(queryPermissionPromise(handle, mode).await())
} catch (e: Throwable) {
    println("[SteleKit] queryPermission failed: ${e.message}")
    "denied"
}

internal suspend fun requestHandlePermission(handle: JsAny, mode: String = "readwrite"): String = try {
    jsStringValue(requestPermissionPromise(handle, mode).await())
} catch (e: Throwable) {
    println("[SteleKit] requestPermission failed: ${e.message}")
    "denied"
}

// ---------------------------------------------------------------------------------------------
// FileSystemObserver construction + observe (Story 1.5.4)
// ---------------------------------------------------------------------------------------------

/** Mirrors [showDirectoryPickerSupported]'s feature-detect idiom. */
internal fun fileSystemObserverSupported(): Boolean = js("typeof FileSystemObserver === 'function'")

internal fun newFileSystemObserver(callback: (JsAny) -> Unit): JsAny =
    js("new FileSystemObserver(function(records) { callback(records); })")

private fun observePromise(observer: JsAny, handle: JsAny, recursive: Boolean): kotlin.js.Promise<JsAny> =
    js("observer.observe(handle, { recursive: recursive })")

internal suspend fun observeHandle(observer: JsAny, handle: JsAny, recursive: Boolean = true) {
    try {
        observePromise(observer, handle, recursive).await()
    } catch (e: Throwable) {
        println("[SteleKit] FileSystemObserver.observe failed: ${e.message}")
    }
}

internal fun changeRecordType(record: JsAny): String = js("record.type")

private fun changeRecordRelativePathArray(record: JsAny): JsAny = js("record.relativePathComponents")
private fun jsArrayLength(arr: JsAny): Int = js("arr.length | 0")
private fun jsArrayGetString(arr: JsAny, index: Int): String = js("arr[index]")

internal fun changeRecordRelativePath(record: JsAny): List<String> {
    val arr = changeRecordRelativePathArray(record)
    val length = jsArrayLength(arr)
    return (0 until length).map { jsArrayGetString(arr, it) }
}

// ---------------------------------------------------------------------------------------------
// File.lastModified / size, and the visibility-visible promise (Story 1.5.5)
// ---------------------------------------------------------------------------------------------

internal fun fileLastModified(file: JsAny): Long = js("BigInt(file.lastModified)")
internal fun fileSize(file: JsAny): Long = js("BigInt(file.size)")

/**
 * Resolves to `null` the instant `document.visibilityState` becomes `"visible"` (tab regains
 * focus) — the inverse of [jsVisibilityHiddenPromise]. In environments with no `document` (e.g.
 * some test runners) the returned promise simply never resolves — a safe no-op, not a crash.
 */
internal fun jsVisibilityVisiblePromise(): kotlin.js.Promise<JsAny?> = js(
    """
    (function() {
        return new Promise(function(resolve) {
            if (typeof document === 'undefined' || typeof document.addEventListener !== 'function') {
                return;
            }
            function handler() {
                if (document.visibilityState === 'visible') {
                    document.removeEventListener('visibilitychange', handler);
                    resolve(null);
                }
            }
            document.addEventListener('visibilitychange', handler);
        });
    })()
    """,
)

// ---------------------------------------------------------------------------------------------
// navigator.storage.persist() (Story 1.5.6) — best-effort, never throws, never blocks the caller
// ---------------------------------------------------------------------------------------------

/** Mirrors [showDirectoryPickerSupported]'s feature-detect idiom. */
private fun storagePersistSupported(): Boolean =
    js("typeof navigator.storage !== 'undefined' && typeof navigator.storage.persist === 'function'")

private fun jsStoragePersistPromise(): kotlin.js.Promise<JsAny> = js("navigator.storage.persist()")
private fun jsBooleanValue(v: JsAny): Boolean = js("v === true")

/**
 * Best-effort request that the origin's storage not be LRU-evicted under pressure. Returns the
 * browser's actual grant decision (`true`/`false`), or `false` if the Storage API isn't
 * supported at all, or `false` (never throws) if the underlying call rejects.
 */
internal suspend fun requestStoragePersistence(): Boolean {
    if (!storagePersistSupported()) return false
    return try {
        jsBooleanValue(jsStoragePersistPromise().await())
    } catch (e: Throwable) {
        println("[SteleKit] storage.persist() request failed: ${e.message}")
        false
    }
}
