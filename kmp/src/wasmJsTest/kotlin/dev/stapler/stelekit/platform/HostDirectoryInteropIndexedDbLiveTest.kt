// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// js() calls must be top-level functions in Kotlin/Wasm — not inside a class or companion object.

private fun fakeEnvelopeShapedValue(graphId: String, dirName: String, storedAtMillis: Double): JsAny =
    js("({ graphId: graphId, dirName: dirName, storedAtMillis: storedAtMillis })")

private fun jsGraphId(v: JsAny): String = js("v.graphId")
private fun jsDirName(v: JsAny): String = js("v.dirName")
private fun jsStoredAtMillis(v: JsAny): Double = js("v.storedAtMillis")

/**
 * Epic 1.5 (Story 1.5.1): real-browser IndexedDB round-trip integration test for
 * `idbPutHandle`/`idbGetHandle`, distinct from `HostDirectoryInteropTest.kt`'s smoke-level
 * open/put/get coverage. Runs against the real `indexedDB` global in headless Chrome
 * (`wasmJsBrowserTest`), following `WasmGitWriteServiceLiveTest.kt`'s "Live" naming convention for
 * tests that exercise a real browser API end-to-end rather than a fake/stub — see
 * `WebLockTest.kt` for the sibling "Live" precedent for the Web Locks API.
 *
 * The stored value here is a plain, structured-clone-able object shaped like
 * `dev.stapler.stelekit.git.model.HostHandleEnvelope`'s three fields (`graphId`/`dirName`/
 * `storedAtMillis`) rather than the Kotlin `@Serializable` class itself — `idbPutHandle`/
 * `idbGetHandle` persist opaque `JsAny` structured-clone values (matching how a real
 * `FileSystemDirectoryHandle` would be stored), not JSON text, so this proves the IndexedDB
 * plumbing round-trips arbitrary structured-clone shapes correctly across a fresh database
 * connection (simulating a new tab/session reading back what a previous one wrote).
 */
class HostDirectoryInteropIndexedDbLiveTest {

    private fun freshKey(): String = "live-rt-${Random.nextInt(0, Int.MAX_VALUE)}"

    @Test
    fun idbPutHandle_then_idbGetHandle_should_RoundTripHostHandleEnvelope_When_RunAgainstRealBrowserIndexedDb() = runTest {
        val key = freshKey()
        val graphId = "a1b2c3d4"
        val dirName = "my-notes"
        val storedAtMillis = 1752500000000.0

        val writeDb = idbOpenHandleDb()
        idbPutHandle(writeDb, key, fakeEnvelopeShapedValue(graphId, dirName, storedAtMillis))

        // Re-open the database as a fresh connection rather than reusing `writeDb`, mirroring a
        // new tab/session reading back a handle a previous one persisted.
        val readDb = idbOpenHandleDb()
        val retrieved = idbGetHandle(readDb, key)

        assertNotNull(retrieved)
        assertEquals(graphId, jsGraphId(retrieved))
        assertEquals(dirName, jsDirName(retrieved))
        assertEquals(storedAtMillis, jsStoredAtMillis(retrieved))
    }
}
