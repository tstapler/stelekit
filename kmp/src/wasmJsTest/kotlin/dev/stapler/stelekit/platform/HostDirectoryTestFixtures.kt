// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.Deferred

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object
// (mirrors HostDirectorySyncHandleRetentionTest.kt's established idiom for this codebase).

// ── Fake FileSystemDirectoryHandle/FileSystemFileHandle tree builders ─────────────────────────
// Mirrors the `listOpfsEntries`/`isFileEntry`/`isDirectoryEntry`/`getFile().text()`/
// `getFile().arrayBuffer()`/`getFile().lastModified`/`getFile().size` surface
// `runHostReconciliation`/`PlatformFileSystem.pickDirectoryAsync` actually consume
// (OpfsInterop.kt/HostDirectoryInterop.kt) — a minimal test double for the real browser API,
// following PlatformFileSystemDirtyTrackingIntegrationTest.kt/HostDirectorySyncHandleRetentionTest.kt's
// precedent of testing against the real wasmJs interop surface (headless Chrome,
// `wasmJsBrowserTest`) rather than injecting a mock traversal function.
//
// Extracted here (Task 3.4.3a, mirroring Task 5.5.1a's not-yet-implemented fixture-generator
// intent) so HostDirectorySyncReconciliationTest.kt (Epic 3.1-3.3) and
// HostDirectorySyncReconciliationBenchmarkTest.kt (Epic 3.4) share one builder instead of
// duplicating it.

internal fun newJsArray(): JsAny = js("[]")
internal fun jsArrayPush(arr: JsAny, item: JsAny): Unit = js("arr.push(item)")

internal fun toJsArray(items: List<JsAny>): JsAny {
    val arr = newJsArray()
    for (item in items) jsArrayPush(arr, item)
    return arr
}

/**
 * Epic 3.4 (Task 3.4.1b/3.4.3a): shared call-count box threaded through a whole fixture tree so
 * tests can assert exactly how many `.text()`/`.arrayBuffer()` content reads occurred across many
 * files — the pre-filter's zero/N-read acceptance criteria. `null` (the default everywhere this
 * isn't passed explicitly) means "don't count" — existing pre-Epic-3.4 tests that don't care about
 * read counts are unaffected.
 */
internal fun newReadCounter(): JsAny = js("({ count: 0 })")
internal fun readCounterValue(counter: JsAny): Int = js("counter.count | 0")

private fun fakeTextFileEntryRaw(name: String, content: String, lastModified: Long, size: Long, counter: JsAny?): JsAny = js(
    """
    ({
        kind: 'file',
        name: name,
        getFile: function() {
            return Promise.resolve({
                lastModified: Number(lastModified),
                size: Number(size),
                text: function() {
                    if (counter) { counter.count = counter.count + 1; }
                    return Promise.resolve(content);
                }
            });
        }
    })
    """,
)

internal fun fakeTextFileEntry(
    name: String,
    content: String,
    lastModified: Long = 0L,
    size: Long = content.length.toLong(),
    counter: JsAny? = null,
): JsAny = fakeTextFileEntryRaw(name, content, lastModified, size, counter)

private fun fakeBytesFileEntryRaw(name: String, buffer: JsAny, lastModified: Long, size: Long, counter: JsAny?): JsAny = js(
    """
    ({
        kind: 'file',
        name: name,
        getFile: function() {
            return Promise.resolve({
                lastModified: Number(lastModified),
                size: Number(size),
                arrayBuffer: function() {
                    if (counter) { counter.count = counter.count + 1; }
                    return Promise.resolve(buffer);
                }
            });
        }
    })
    """,
)

internal fun fakeBytesFileEntry(
    name: String,
    buffer: JsAny,
    lastModified: Long = 0L,
    size: Long = 0L,
    counter: JsAny? = null,
): JsAny = fakeBytesFileEntryRaw(name, buffer, lastModified, size, counter)

internal fun fakeDirEntry(name: String, children: JsAny): JsAny = js(
    """
    ({
        kind: 'directory',
        name: name,
        values: function() {
            var idx = 0;
            return {
                next: function() {
                    if (idx < children.length) {
                        return Promise.resolve({ done: false, value: children[idx++] });
                    }
                    return Promise.resolve({ done: true, value: undefined });
                }
            };
        }
    })
    """,
)

/** Used by the `connectHostDirectory`/`runHostReconciliation` error-path test. */
internal fun fakeThrowingDirEntry(name: String): JsAny = js(
    """
    ({
        kind: 'directory',
        name: name,
        values: function() { throw new Error('boom: directory unreadable'); }
    })
    """,
)

internal sealed interface Entry
internal data class TextFile(
    val name: String,
    val content: String,
    val lastModified: Long = 0L,
    val size: Long = content.length.toLong(),
) : Entry
internal data class BytesFile(
    val name: String,
    val bytes: ByteArray,
    val lastModified: Long = 0L,
    val size: Long = bytes.size.toLong(),
) : Entry
internal data class Dir(val name: String, val children: List<Entry> = emptyList()) : Entry

internal fun buildEntry(e: Entry, counter: JsAny? = null): JsAny = when (e) {
    is TextFile -> fakeTextFileEntry(e.name, e.content, e.lastModified, e.size, counter)
    is BytesFile -> fakeBytesFileEntry(e.name, e.bytes.toJsArrayBuffer(), e.lastModified, e.size, counter)
    is Dir -> fakeDirEntry(e.name, toJsArray(e.children.map { buildEntry(it, counter) }))
}

/** Builds a fake root [FileSystemDirectoryHandle]-shaped `JsAny` from a declarative [Entry] tree. */
internal fun rootDir(vararg children: Entry, counter: JsAny? = null): JsAny =
    buildEntry(Dir("root", children.toList()), counter)

internal fun emptyRootDir(): JsAny = fakeDirEntry("root", newJsArray())

// ── Shared CacheAccess fake ────────────────────────────────────────────────────────────────────

/**
 * In-memory [HostDirectorySync.CacheAccess] fake shared by [HostDirectorySyncReconciliationTest]
 * and [HostDirectorySyncReconciliationBenchmarkTest] (Task 3.4.3a — extracted rather than
 * duplicated).
 */
internal class FakeCacheAccess : HostDirectorySync.CacheAccess {
    val textStore = mutableMapOf<String, String>()
    val bytesStore = mutableMapOf<String, ByteArray>()
    var getCallCount = 0
    var setCallCount = 0
    var getBytesCallCount = 0
    var setBytesCallCount = 0
    val mirrorWrites = mutableListOf<Pair<String, String>>()
    val mirrorBytesWrites = mutableListOf<Pair<String, ByteArray>>()

    /**
     * Epic 4.1/4.5 (Task 4.5.1a's crash-recovery await-mechanism tests): settable per-path
     * [Deferred], mirroring [PlatformFileSystem.opfsWriteDeferredFor]'s real map — defaults to
     * "nothing in flight" (`null`) for every path unless a test explicitly registers one via
     * [setDeferred].
     */
    private val deferredStore = mutableMapOf<String, Deferred<Unit>>()

    fun setDeferred(path: String, deferred: Deferred<Unit>) {
        deferredStore[path] = deferred
    }

    override fun get(path: String): String? {
        getCallCount++
        return textStore[path]
    }
    override fun set(path: String, content: String) {
        setCallCount++
        textStore[path] = content
    }
    override fun remove(path: String) {
        textStore.remove(path)
    }
    override fun getBytes(path: String): ByteArray? {
        getBytesCallCount++
        return bytesStore[path]
    }
    override fun setBytes(path: String, data: ByteArray) {
        setBytesCallCount++
        bytesStore[path] = data
    }
    override fun removeBytes(path: String) {
        bytesStore.remove(path)
    }
    override fun keysUnder(opfsPath: String): Set<String> =
        (textStore.keys + bytesStore.keys).filter { it.startsWith("$opfsPath/") }.toSet()
    override fun writeOpfsMirror(path: String, content: String) {
        mirrorWrites += path to content
    }
    override fun writeOpfsMirrorBytes(path: String, data: ByteArray) {
        mirrorBytesWrites += path to data
    }
    override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = deferredStore[path]
}

// ── Epic 4.1-4.5: writable host-directory fixture (flat — top-level files only) ────────────────
//
// HostDirectorySync.flushHostWrite resolves entries via getDirectoryHandle/getFileHandle/
// createWritable/removeEntry against hostDirHandle — a different (write-capable) surface than the
// read-only values()/getFile() surface the fixtures above build. Deliberately flat (no
// getDirectoryHandle support) — every test path used against this fixture is a top-level filename
// (e.g. "Foo.md", not "pages/Foo.md"), which keeps this fixture small while still exercising
// flushHostWrite's real getFileHandle/createWritable/write/close/removeEntry call sequence.

/**
 * Builds a fake writable [FileSystemDirectoryHandle]-shaped `JsAny` root. [permission] is what
 * `queryPermission`/`requestPermission` resolve to on every call unless [permission] is
 * `"granted-then-denied"`, in which case the *first* call resolves `"granted"` and every
 * subsequent call resolves `"denied"` — used by the `NotAllowedError` re-query test (Task 4.5.1d)
 * to simulate permission being revoked mid-write.
 */
internal fun makeWritableHostRoot(permission: String = "granted"): JsAny = js(
    """
    (function() {
        var files = {};
        var permissionCallCount = 0;
        var createWritableCallCount = 0;
        function currentPermission() {
            permissionCallCount++;
            if (permission === 'granted-then-denied') {
                return permissionCallCount === 1 ? 'granted' : 'denied';
            }
            return permission;
        }
        return {
            kind: 'directory',
            name: 'root',
            getFileHandle: function(name, opts) {
                if (!(name in files)) {
                    if (opts && opts.create) {
                        files[name] = { content: null, buffer: null };
                    } else {
                        return Promise.reject(new Error('NotFoundError: no such file'));
                    }
                }
                var entry = files[name];
                return Promise.resolve({
                    kind: 'file',
                    name: name,
                    getFile: function() {
                        return Promise.resolve({
                            lastModified: 0,
                            size: 0,
                            text: function() { return Promise.resolve(entry.content == null ? '' : entry.content); },
                            arrayBuffer: function() { return Promise.resolve(entry.buffer || new ArrayBuffer(0)); }
                        });
                    },
                    createWritable: function() {
                        createWritableCallCount++;
                        return Promise.resolve({
                            write: function(data) {
                                if (typeof data === 'string') { entry.content = data; } else { entry.buffer = data; }
                                return Promise.resolve();
                            },
                            close: function() { return Promise.resolve(); }
                        });
                    }
                });
            },
            removeEntry: function(name) {
                if (!(name in files)) return Promise.reject(new Error('NotFoundError: no such entry'));
                delete files[name];
                return Promise.resolve();
            },
            queryPermission: function() { return Promise.resolve(currentPermission()); },
            requestPermission: function() { return Promise.resolve(currentPermission()); },
            _setContent: function(name, content) {
                if (!files[name]) files[name] = { content: null, buffer: null };
                files[name].content = content;
            },
            _getContent: function(name) { return (files[name] && files[name].content != null) ? files[name].content : null; },
            _hasBuffer: function(name) { return !!(files[name] && files[name].buffer); },
            _hasFile: function(name) { return name in files; },
            _createWritableCallCount: function() { return createWritableCallCount; }
        };
    })()
    """,
)

/**
 * Builds a fake writable root whose [getFileHandle]/`removeEntry` calls always reject with
 * [errorMessage] (e.g. an `"Error: NotFoundError: ..."`-shaped message) — used by Task 4.5.1d's
 * write-failure classification tests. `queryPermission`/`requestPermission` always resolve
 * [permission] (default `"granted"`, so the proactive check passes and the write is actually
 * attempted).
 */
internal fun makeThrowingWritableHostRoot(errorMessage: String, permission: String = "granted"): JsAny = js(
    """
    (function() {
        return {
            kind: 'directory',
            name: 'root',
            getFileHandle: function() { return Promise.reject(new Error(errorMessage)); },
            removeEntry: function() { return Promise.reject(new Error(errorMessage)); },
            queryPermission: function() { return Promise.resolve(permission); },
            requestPermission: function() { return Promise.resolve(permission); },
            _createWritableCallCount: function() { return 0; }
        };
    })()
    """,
)

internal fun writableRootSetContent(root: JsAny, name: String, content: String): Unit = js("root._setContent(name, content)")
internal fun writableRootGetContent(root: JsAny, name: String): String? = js("root._getContent(name)")
internal fun writableRootHasBuffer(root: JsAny, name: String): Boolean = js("root._hasBuffer(name)")
internal fun writableRootHasFile(root: JsAny, name: String): Boolean = js("root._hasFile(name)")
internal fun writableRootCreateWritableCallCount(root: JsAny): Int = js("root._createWritableCallCount()")
