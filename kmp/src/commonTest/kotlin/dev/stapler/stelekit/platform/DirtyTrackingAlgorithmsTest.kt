// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-Kotlin double of the wasmJs `PlatformFileSystem`'s `recordDirty()`/`DOWNLOAD_PREFIX`-guard
 * logic (see `kmp/src/wasmJsMain/.../platform/PlatformFileSystem.kt`). The real class is
 * `wasmJsMain`-only (OPFS/JS interop dependent), so — per this repo's established
 * `WasmSectionSyncServiceTest` precedent — the pure path-derivation/overwrite algorithm is
 * reimplemented here so it's exercisable from `commonTest`/`jvmTest` without a browser. Keep this
 * double's logic in lockstep with the real `recordDirty` if that method's derivation ever changes.
 */
private class DirtyTrackingDouble {
    private val dirtySet = mutableMapOf<String, DirtyEntry>()
    private var clock = 0L

    /** Returns true if an entry was recorded, false if the path was guarded out (DOWNLOAD_PREFIX). */
    fun recordDirty(path: String, op: DirtyOp): Boolean {
        if (path.startsWith(DOWNLOAD_PREFIX)) return false
        val repoRelative = path.removePrefix("/stelekit/").substringAfter("/")
        if (repoRelative.isEmpty()) return false
        dirtySet[repoRelative] = DirtyEntry(op, clock++)
        return true
    }

    fun snapshot(): Map<String, DirtyEntry> = dirtySet.toMap()

    companion object {
        const val DOWNLOAD_PREFIX = "/_wasm_dl_/"
    }
}

/**
 * Pure-Kotlin double of the wasmJs `PlatformFileSystem`'s `getContentBytes()` precedence logic
 * (BLOCKER fix — see `PlatformFileSystem.kt`'s `getContentBytes` doc comment). Mirrors the real
 * method exactly: check `bytesCache` (paranoid-mode/encrypted content, written by
 * `writeFileBytes`) first, falling back to UTF-8-encoding the plain-text `cache` map. A
 * paranoid-mode path is never also present in `cache`, but the precedence order itself is the
 * property under test — this is what makes `readFile()` (which only ever consults `cache`)
 * unable to see paranoid-mode dirty content, the root cause of the bug this fix addresses.
 */
private class ContentBytesDouble {
    private val cache = mutableMapOf<String, String>()
    private val bytesCache = mutableMapOf<String, ByteArray>()

    fun writeFile(path: String, content: String) {
        cache[path] = content
    }

    fun writeFileBytes(path: String, data: ByteArray) {
        bytesCache[path] = data
    }

    fun getContentBytes(path: String): ByteArray? = bytesCache[path] ?: cache[path]?.encodeToByteArray()
}

class DirtyTrackingAlgorithmsTest {

    // ── BLOCKER fix regression: paranoid-mode (bytesCache-only) content must be retrievable ────

    @Test
    fun `BLOCKER-1 getContentBytes returns bytesCache content for a paranoid-mode-only path (never in cache)`() {
        val double = ContentBytesDouble()
        val encryptedBytes = byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x7F, 0x80.toByte())

        double.writeFileBytes("/stelekit/default/pages/Secret.md.stek", encryptedBytes)

        val result = double.getContentBytes("/stelekit/default/pages/Secret.md.stek")
        assertEquals(true, result != null)
        assertEquals(encryptedBytes.toList(), result?.toList())
    }

    @Test
    fun `BLOCKER-1 getContentBytes falls back to UTF-8-encoded cache content for a plain-text path`() {
        val double = ContentBytesDouble()
        double.writeFile("/stelekit/default/pages/Foo.md", "# Foo\n")

        val result = double.getContentBytes("/stelekit/default/pages/Foo.md")

        assertEquals("# Foo\n".encodeToByteArray().toList(), result?.toList())
    }

    @Test
    fun `BLOCKER-1 getContentBytes prefers bytesCache over cache when both happen to hold an entry for the same path`() {
        val double = ContentBytesDouble()
        val path = "/stelekit/default/pages/Both.md"
        double.writeFile(path, "plain text (must be ignored)")
        val bytesContent = byteArrayOf(1, 2, 3)
        double.writeFileBytes(path, bytesContent)

        val result = double.getContentBytes(path)

        assertEquals(bytesContent.toList(), result?.toList(), "bytesCache must take precedence over cache")
    }

    @Test
    fun `BLOCKER-1 getContentBytes returns null when the path is in neither cache`() {
        val double = ContentBytesDouble()

        assertEquals(null, double.getContentBytes("/stelekit/default/pages/Missing.md"))
    }

    @Test
    fun `TC-2_1_1-A recordDirty derives repo-relative path and records a WRITE entry`() {
        val double = DirtyTrackingDouble()

        val recorded = double.recordDirty("/stelekit/default/pages/Foo.md", DirtyOp.WRITE)

        assertEquals(true, recorded)
        val snapshot = double.snapshot()
        assertEquals(setOf("pages/Foo.md"), snapshot.keys)
        assertEquals(DirtyOp.WRITE, snapshot.getValue("pages/Foo.md").op)
    }

    @Test
    fun `TC-2_1_1-B DOWNLOAD_PREFIX paths and DELETE-overwrites-WRITE are handled correctly`() {
        val double = DirtyTrackingDouble()

        // The DOWNLOAD_PREFIX export path is never recorded as dirty — it's a browser file-save,
        // not a graph write.
        val recorded = double.recordDirty("/_wasm_dl_/export.md", DirtyOp.WRITE)
        assertEquals(false, recorded)
        assertEquals(emptyMap(), double.snapshot())

        // A prior WRITE entry for a path is overwritten (not merged) by a subsequent DELETE.
        double.recordDirty("/stelekit/default/pages/Foo.md", DirtyOp.WRITE)
        double.recordDirty("/stelekit/default/pages/Foo.md", DirtyOp.DELETE)

        val snapshot = double.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(DirtyOp.DELETE, snapshot.getValue("pages/Foo.md").op)
    }
}
