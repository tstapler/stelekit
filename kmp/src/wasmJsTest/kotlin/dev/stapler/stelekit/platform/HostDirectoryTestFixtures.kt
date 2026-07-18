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
    override fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = null
}
