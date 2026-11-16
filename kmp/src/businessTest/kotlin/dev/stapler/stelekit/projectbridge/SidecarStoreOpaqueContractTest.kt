// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 1.1.3b: the store-level `opaque`/`expectedSha` contract of `write`, `writeNow` and `flushPending`. No slug lock
 * is taken anywhere here: the contract has to hold even when a caller bypasses it.
 */
class SidecarStoreOpaqueContractTest {
    private val slug = "kitchen"
    private val v2Path = "sheets/s2.v2.json"
    private val b0 = OldReaderFixtures.s2
    private val b1 = "{\"schema\":2,\"sheetUuid\":\"s2\",\"scene\":{\"n\":1}}\n".encodeToByteArray()
    private val v1Files = mapOf("sheets/s1.json" to OldReaderFixtures.s1)

    private fun model(vararg refs: Pair<String, String?>, files: Map<String, ByteArray>) = OldReaderFixtures.model(refs.toList(), files)

    private fun p(rel: String) = sidecarPath(slug, rel)

    private fun seedMixed(fs: SidecarFs, v2: ByteArray = b0) {
        val files = v1Files + (v2Path to v2)
        fs.seed(p("sheets/s1.json"), OldReaderFixtures.s1)
        fs.seed(p(v2Path), v2)
        fs.seed(p(SidecarCodec.MODEL_PATH), model("s1" to null, "s2" to v2Path, files = files))
    }

    private fun newModel(renamed: String = "n") =
        model("s1" to null, "s2" to v2Path, files = v1Files + (v2Path to b0)).decodeToString().replace("kitchen", renamed).encodeToByteArray()

    @Test
    fun write_should_LeaveOpaquePathsAlone_AndWriteTheModelThatNamesThem() = runBlocking<Unit> {
        val fs = SidecarFs().also { seedMixed(it) }
        val store = newStore(fs)
        val files = mapOf(
            "sheets/s1.json" to OldReaderFixtures.s1,
            v2Path to "must not be written".encodeToByteArray(),
            SidecarCodec.MODEL_PATH to newModel(),
        )

        assertEquals(SidecarWriteOutcome.Written, store.write(slug, files, opaque = mapOf(v2Path to ContentHasher.sha256(b0))))

        assertContentEquals(b0, fs.readFileBytes(p(v2Path)))
        assertContentEquals(newModel(), fs.readFileBytes(p(SidecarCodec.MODEL_PATH)))
    }

    @Test
    fun write_should_ReturnStaleOpaqueAndWriteNothing_WhenTheV2FileChangedUnderTheCaller() = runBlocking<Unit> {
        val fs = SidecarFs().also { seedMixed(it, v2 = b1) }
        val before = fs.readFileBytes(p(SidecarCodec.MODEL_PATH))!!
        val store = newStore(fs)
        val writes = fs.writeCount

        val outcome = store.write(slug, mapOf(SidecarCodec.MODEL_PATH to newModel()), opaque = mapOf(v2Path to ContentHasher.sha256(b0)))

        assertEquals(SidecarWriteOutcome.StaleOpaque, outcome)
        assertEquals(writes, fs.writeCount, "nothing was written")
        assertContentEquals(before, fs.readFileBytes(p(SidecarCodec.MODEL_PATH)))
        assertContentEquals(b1, fs.readFileBytes(p(v2Path)))
    }

    @Test
    fun write_should_ReturnStaleOpaque_WhenTheV2FileIsGone() = runBlocking<Unit> {
        val fs = SidecarFs()
        val outcome = newStore(fs).write(slug, mapOf(SidecarCodec.MODEL_PATH to newModel()), opaque = mapOf(v2Path to ContentHasher.sha256(b0)))
        assertEquals(SidecarWriteOutcome.StaleOpaque, outcome)
        assertTrue(fs.allFilePaths().none { it.endsWith("project-model.json") })
    }

    @Test
    fun writeNow_should_VerifyTheContract_AndNeverPark() = runBlocking<Unit> {
        var busy = true
        val fs = SidecarFs().also { seedMixed(it) }
        val store = newStore(fs) { busy }
        val files = mapOf(SidecarCodec.MODEL_PATH to newModel())
        val opaque = mapOf(v2Path to ContentHasher.sha256(b0))

        assertEquals(SidecarWriteOutcome.Deferred, store.writeNow(slug, files, opaque))
        busy = false
        assertEquals(0, store.flushPending(), "writeNow kept nothing to replay")

        fs.seed(p(v2Path), b1)
        assertEquals(SidecarWriteOutcome.StaleOpaque, store.writeNow(slug, files, opaque))
        assertEquals(SidecarWriteOutcome.Written, store.writeNow(slug, files, mapOf(v2Path to ContentHasher.sha256(b1))))
    }

    @Test
    fun flushPending_should_DropAParkedWrite_WhenItsOpaqueFileChangedWhileParked() = runBlocking<Unit> {
        var busy = true
        val fs = SidecarFs().also { seedMixed(it) }
        val store = newStore(fs) { busy }
        val before = fs.readFileBytes(p(SidecarCodec.MODEL_PATH))!!

        assertEquals(
            SidecarWriteOutcome.Deferred,
            store.write(slug, mapOf(SidecarCodec.MODEL_PATH to newModel()), opaque = mapOf(v2Path to ContentHasher.sha256(b0))),
        )
        fs.seed(p(v2Path), b1) // a saver wrote a newer scene while the write was parked
        busy = false

        assertEquals(0, store.flushPending())
        assertEquals(1, store.staleOpaqueDropped)
        assertContentEquals(before, fs.readFileBytes(p(SidecarCodec.MODEL_PATH)))
        assertContentEquals(b1, fs.readFileBytes(p(v2Path)))
        assertEquals(0, store.flushPending(), "the dropped entry is gone, not retried")
    }

    @Test
    fun flushPending_should_DropAParkedV1ShapedWrite_WhenTheModelNowReferencesAV2File() = runBlocking<Unit> {
        var busy = true
        val fs = SidecarFs()
        fs.seed(p("sheets/s1.json"), OldReaderFixtures.s1)
        fs.seed(p(SidecarCodec.MODEL_PATH), model("s1" to null, files = v1Files))
        val store = newStore(fs) { busy }
        val parkedModel = model("s1" to null, files = v1Files).decodeToString().replace("kitchen", "edited").encodeToByteArray()
        assertEquals(SidecarWriteOutcome.Deferred, store.write(slug, mapOf("sheets/s1.json" to OldReaderFixtures.s1, SidecarCodec.MODEL_PATH to parkedModel)))

        // The converter flips s1 to its v2 file while the write is parked.
        fs.seed(p("sheets/s1.v2.json"), b0)
        val flipped = model("s1" to "sheets/s1.v2.json", files = mapOf("sheets/s1.v2.json" to b0))
        fs.seed(p(SidecarCodec.MODEL_PATH), flipped)
        busy = false

        assertEquals(0, store.flushPending())
        assertEquals(1, store.staleOpaqueDropped)
        assertContentEquals(flipped, fs.readFileBytes(p(SidecarCodec.MODEL_PATH)))
        assertContentEquals(b0, fs.readFileBytes(p("sheets/s1.v2.json")))
        assertTrue(fs.fileExists(p("sheets/s1.json")), "the v1 file is not deleted by a dropped replay")
    }

    @Test
    fun flushPending_should_StillReplayAnUnaffectedParkedWrite() = runBlocking<Unit> {
        var busy = true
        val fs = SidecarFs().also { seedMixed(it) }
        val store = newStore(fs) { busy }
        val files = mapOf(SidecarCodec.MODEL_PATH to newModel("replayed"))
        store.write(slug, files, opaque = mapOf(v2Path to ContentHasher.sha256(b0)))
        busy = false

        assertEquals(1, store.flushPending())
        assertEquals(0, store.staleOpaqueDropped)
        assertContentEquals(files.getValue(SidecarCodec.MODEL_PATH), fs.readFileBytes(p(SidecarCodec.MODEL_PATH)))
        assertContentEquals(b0, fs.readFileBytes(p(v2Path)))
    }
}
