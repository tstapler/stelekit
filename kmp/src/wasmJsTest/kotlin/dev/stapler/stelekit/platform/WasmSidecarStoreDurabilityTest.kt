// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.FileSystemSketchDurability
import dev.stapler.stelekit.projectbridge.SheetCodec
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Task 1.4.7c (D-119, D-126): the real [FileProjectSidecarStore] over the real wasm [PlatformFileSystem] and the real OPFS
 * of the browser. A "reload" is a second [PlatformFileSystem] that preloads the same graph, so nothing survives from the
 * first one's caches. This is one browser engine (the one the test runner starts); Firefox and Safari are Task 1.4.7d.
 */
class WasmSidecarStoreDurabilityTest {
    private val slug = "proj"

    private fun freshGraph(): String = "/stelekit/it-durability-${Random.nextInt(0, Int.MAX_VALUE)}"

    private fun sheetPath(graph: String) = "${SidecarLiveSet.rootOf(graph)}/$slug/sheets/a.v2.json"

    private fun modelPath(graph: String) = "${SidecarLiveSet.rootOf(graph)}/$slug/${SidecarCodec.MODEL_PATH}"

    /** The browser's own verdict, from the real startup probe; a browser that fails it fails these tests with its reason. */
    private suspend fun realProbe(): OpfsProbeResult {
        val result = OpfsCapability().probe()
        assertIs<OpfsStorageVerdict.Available>(result.verdict, "the real probe must be Available in the test browser: ${result.facts}")
        return result
    }

    private suspend fun readyFs(graph: String): PlatformFileSystem {
        val fs = PlatformFileSystem()
        fs.recordProbe(realProbe())
        fs.preload(graph)
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        return fs
    }

    private fun storeOf(fs: PlatformFileSystem, graph: String) =
        FileProjectSidecarStore(fs, graph, AtomicFileWriter(fs, { it }, ".tmp"), AlwaysHeldSketchWriterLease()) { false }

    private suspend fun <T> inBrowser(body: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { body() } }

    @Test
    fun write_should_SurviveDroppingTheCachesAndPreloadingAgainAfterAwaitDurable() = runTest {
        val graph = freshGraph()
        val fs = readyFs(graph)
        val model = """{"schema":1,"name":"kitchen"}""".encodeToByteArray()
        // Sheets are UTF-8 JSON: the preload reads a `.json` file as text, so the payload must round-trip as text.
        val sheet = """{"schema":2,"pad":"${"x".repeat(SHEET_PAD)}"}""".encodeToByteArray()

        val outcome = storeOf(fs, graph).write(slug, mapOf(SidecarCodec.MODEL_PATH to model, "sheets/a.v2.json" to sheet))
        assertEquals(SidecarWriteOutcome.Written, outcome)
        val durable = inBrowser { FileSystemSketchDurability(fs) { graph }.awaitPersisted(slug, "sheets/a.v2.json") }
        assertTrue(durable.isRight(), "awaitDurable of the sheet and the model: $durable")

        val reloaded = readyFs(graph)
        assertContentEquals(sheet, reloaded.readFileBytes(sheetPath(graph)), "the sheet bytes survive a reload")
        assertContentEquals(model, reloaded.readFileBytes(modelPath(graph)), "the model survives a reload")
    }

    @Test
    fun interruptedReplace_should_ReadTheOldBytes() = runTest {
        val graph = freshGraph()
        val fs = readyFs(graph)
        val old = """{"v":"old"}""".encodeToByteArray()
        val replacement = """{"v":"new and longer"}""".encodeToByteArray()
        val path = sheetPath(graph)
        fs.createDirectory(SidecarLiveSet.rootOf(graph))
        fs.createDirectory("${SidecarLiveSet.rootOf(graph)}/$slug")
        fs.createDirectory("${SidecarLiveSet.rootOf(graph)}/$slug/sheets")
        val writer = AtomicFileWriter(fs, { it }, ".tmp")
        assertTrue(writer.write(path, old).isRight())
        assertTrue(inBrowser { fs.awaitDurable(path) }.isRight())

        // The page dies after the temp is written and before the replace: the temp exists, the target was never touched.
        val temp = "$path.${UuidGenerator.generateV7()}.tmp"
        assertTrue(fs.writeTempBytes(temp, replacement))
        inBrowser { fs.awaitDurable(temp) }
        // A second writer whose precondition fails stops before the replace as well.
        val refused = writer.write(path, replacement, original = old, precondition = { arrow.core.Either.Right(false) })
        assertIs<DomainError.MergeError.Retryable>(refused.leftOrNull())

        val reloaded = readyFs(graph)
        assertContentEquals(old, reloaded.readFileBytes(path), "an unfinished replace leaves the old bytes")
    }

    @Test
    fun atomicFileWriter_should_BeInAtomicReplaceMode() = runTest {
        val graph = freshGraph()
        val before = PlatformFileSystem()
        assertEquals(AtomicWriteMode.UNSUPPORTED, AtomicFileWriter.mode(before, sheetPath(graph)), "before the probe nothing is written")

        val fs = readyFs(graph)
        assertEquals(AtomicWriteMode.ATOMIC_REPLACE, AtomicFileWriter.mode(fs, sheetPath(graph)))
        assertEquals(AtomicWriteMode.ATOMIC_REPLACE, AtomicFileWriter(fs, { it }, ".tmp").mode(modelPath(graph)))
        assertEquals(AtomicWriteMode.TEMP_RENAME, AtomicFileWriter.mode(fs, "$graph/pages/a.md"), "a page keeps its plain write on the web")
    }

    @Test
    fun fiftyPrefixReadsOverTwoMegabyteSheets_should_FitTenMilliseconds() = runTest {
        val graph = freshGraph()
        val fs = PlatformFileSystem()
        fs.markEphemeral()
        fs.recordProbe(OpfsProbeResult(OpfsProbeFacts(true, true, true, true, true), null, "/stelekit-probe-timing", emptyList()))
        fs.preload(graph)
        val store = storeOf(fs, graph)
        val header = """{"schema":2,"sheet":""".encodeToByteArray()
        repeat(SHEET_COUNT) { i ->
            val bytes = ByteArray(TWO_MEGABYTES)
            header.copyInto(bytes)
            bytes[header.size] = i.toByte()
            assertTrue(fs.writeFileBytes("${SidecarLiveSet.rootOf(graph)}/$slug/sheets/s$i.v2.json", bytes))
        }
        assertEquals(null, store.readSheetPrefix(slug, "sheets/missing.v2.json", SheetCodec.HEADER_PROBE_BYTES))
        store.readSheetPrefix(slug, "sheets/s0.v2.json", SheetCodec.HEADER_PROBE_BYTES)

        val start = TimeSource.Monotonic.markNow()
        val sizes = (0 until SHEET_COUNT).map { i -> store.readSheetPrefix(slug, "sheets/s$i.v2.json", SheetCodec.HEADER_PROBE_BYTES)?.size }
        val elapsedMs = start.elapsedNow().inWholeMicroseconds / MICROS_PER_MS

        assertTrue(sizes.all { it == SheetCodec.HEADER_PROBE_BYTES }, "every prefix read returned the probe window: $sizes")
        assertTrue(elapsedMs < BUDGET_MS, "50 prefix reads took $elapsedMs ms, budget $BUDGET_MS ms (D-126)")
    }

    private companion object {
        const val AWAIT_MS = 20_000L
        const val SHEET_PAD = 4000
        const val SHEET_COUNT = 50
        const val TWO_MEGABYTES = 2 * 1024 * 1024
        const val BUDGET_MS = 10.0
        const val MICROS_PER_MS = 1000.0
    }
}
