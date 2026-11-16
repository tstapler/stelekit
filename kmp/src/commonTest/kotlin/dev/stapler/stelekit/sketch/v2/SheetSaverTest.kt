// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveSamples
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverConfig
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 1.4.4: the single-writer saver, over an in-memory store with scripted latency and failures and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SheetSaverTest {
    /** Stands in for an `OutOfMemoryError`: an `Error`, which a plain `catch (e: Exception)` would miss. */
    private class FakeHeapExhausted : Error("heap")

    private val strings = object : BannerStringSource {
        override fun summary(row: BannerRowId) = "Couldn't save."
        override fun shortTitle(row: BannerRowId) = "Not saved"
        override fun primaryLabel(row: BannerRowId) = "Retry"
        override fun moreItems(row: BannerRowId) = emptyList<String>()
    }

    private val sheet = SheetUuid("s2")
    private val slug = "proj-0a1b2c"
    private val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)

    private fun rect(id: String, x: Int) = RectElement(
        ElementBase(ElementId(id), zKey = ZKey("a$id")),
        SceneUnit.ofInt(x), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
    )

    private fun docOf(vararg ids: String): SketchDocumentV2 = SketchDocumentV2(
        scene = ids.foldIndexed(SketchSceneV2.empty()) { i, scene, id -> scene.add(rect(id, i * 20)).getOrNull()!! },
        sheetUuid = sheet.value,
    )

    private fun bytesOf(doc: SketchDocumentV2): ByteArray = SheetCodecV2.encode(doc).getOrNull()!!

    private inner class Rig(scope: TestScope, latencyMs: Long = 0) {
        val repos: SketchRepositories = inMemorySketchRepositories()
        val store = ScriptedSidecarStore(latency = { if (latencyMs > 0) delay(latencyMs) })
        val diagnostics = InMemoryDiagnosticsPort(strings)
        val logLines = mutableListOf<String>()
        val loaded = docOf("e0")
        var encoder: (SketchDocumentV2) -> arrow.core.Either<dev.stapler.stelekit.sketch.v2.codec.SheetError, ByteArray> =
            { doc -> SheetCodecV2.encode(doc) }
        var clock = 0L
        val samples = mutableListOf<Triple<Int, Long, Int>>()
        lateinit var saver: SheetSaver
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)

        suspend fun start() {
            val project = SketchProject(ProjectUuid("p1"), slug, "Kitchen", 1L)
            val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16, schemaVersion = SheetPaths.SCHEMA_V2)
            val loadedBytes = bytesOf(loaded)
            val bundle = ProjectBundle(project, listOf(row), emptyList(), emptyList(), mapOf(sheet to SheetPayload.V2Opaque.of(loadedBytes)))
            repos.projects.replaceProjectContents(project.uuid, bundle)
            store.files.putAll(SidecarCodec.encode(bundle))
            saver = SheetSaver(
                SheetTarget(slug, project.uuid, sheet, ContentHasher.sha256(loadedBytes)),
                SheetSaverPorts(
                    repos, store, SidecarSlugLock(), diagnostics,
                    log = SketchLog(SketchLogSink { logLines += it }),
                    samples = SaveSamples { n, ms, bytes -> samples += Triple(n, ms, bytes) },
                    encoder = { doc, _ -> encoder(doc) },
                    clockMillis = { clock },
                ),
                context = dispatcher,
                encodeContext = dispatcher,
            )
        }

        fun diskDoc(): SketchDocumentV2 = SheetCodecV2.decode(store.files.getValue(path)).getOrNull()!!
    }

    @Test
    fun editUndoEdit_should_PersistTheFinalSceneAfterOneSerialisedWrite() = runTest {
        val rig = Rig(this, latencyMs = 200)
        rig.start()
        val edited = docOf("e0", "e1")
        val final = docOf("e0", "e2")

        rig.saver.submit(edited)
        advanceTimeBy(30)
        rig.saver.submit(rig.loaded)
        advanceTimeBy(50)
        rig.saver.submit(final)
        advanceUntilIdle()

        assertEquals(1, rig.store.writes.size)
        assertEquals(1, rig.store.maxConcurrentWrites)
        assertContentEquals(bytesOf(final), rig.store.files.getValue(path))
        assertEquals(SaveState.Saved, rig.saver.state.value)
        rig.saver.close()
    }

    @Test
    fun editDuringAWrite_should_WaitForItAndNeverOverlap() = runTest {
        val rig = Rig(this, latencyMs = 200)
        rig.start()
        val first = docOf("e0", "e1")
        val second = docOf("e0", "e1", "e2")

        rig.saver.submit(first)
        advanceTimeBy(600)
        assertEquals(SaveState.Saving, rig.saver.state.value)
        rig.saver.submit(second)
        advanceUntilIdle()

        assertEquals(2, rig.store.writes.size)
        assertEquals(1, rig.store.maxConcurrentWrites)
        assertContentEquals(bytesOf(second), rig.store.files.getValue(path))
        rig.saver.close()
    }

    @Test
    fun unchangedScene_should_WriteNothing() = runTest {
        val rig = Rig(this)
        rig.start()

        rig.saver.submit(docOf("e0"))
        advanceUntilIdle()

        assertEquals(0, rig.store.writes.size)
        assertEquals(SaveState.Saved, rig.saver.state.value)
        rig.saver.close()
    }

    @Test
    fun save_should_UpdateTheModelMirrorAndTheDatabaseRowTogether() = runTest {
        val rig = Rig(this)
        rig.start()
        val doc = docOf("e0", "e1")

        rig.saver.submit(doc)
        advanceUntilIdle()

        val written = rig.store.writes.single()
        assertTrue(SidecarCodec.MODEL_PATH in written && path in written)
        assertEquals(ContentHasher.sha256(bytesOf(doc)), SidecarCodec.v2SheetShas(written.getValue(SidecarCodec.MODEL_PATH))!!.getValue(path))
        val project = rig.repos.projects.loadProject(ProjectUuid("p1")).getOrNull()!!.project
        assertEquals(SidecarCodec.bundleHash(rig.store.files), project.lastSyncedSidecarHash)
        rig.saver.close()
    }

    @Test
    fun heapExhaustedWhileEncoding_should_ShowSaveFailedWithRetryAndKeepTheWork() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.encoder = { throw FakeHeapExhausted() }
        val doc = docOf("e0", "e1")

        rig.saver.submit(doc)
        advanceUntilIdle()

        assertEquals(SaveState.Failed(SaveFailure.Unexpected), rig.saver.state.value)
        assertEquals(listOf(BannerRowId.SaveFailed), rig.diagnostics.diagnostics.value.entries.map { it.row })
        assertTrue(rig.saver.state.value.hasUnsavedWork)
        assertEquals(0, rig.store.writes.size)

        rig.encoder = { SheetCodecV2.encode(it) }
        rig.saver.retry()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, rig.saver.state.value)
        assertTrue(rig.diagnostics.diagnostics.value.entries.isEmpty())
        assertContentEquals(bytesOf(doc), rig.store.files.getValue(path))
        rig.saver.close()
    }

    @Test
    fun failureLogs_should_CarryOnlyAReasonToken() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.encoder = { throw FakeHeapExhausted() }

        rig.saver.submit(docOf("e0", "e1"))
        advanceUntilIdle()

        assertEquals(listOf("sketch.persist.failed reason=unexpected"), rig.logLines)
        rig.saver.close()
    }

    @Test
    fun scriptedWriteFailure_should_FailThenRetrySuccessfully() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.store.nextOutcome = dev.stapler.stelekit.projectbridge.SidecarWriteOutcome.Failed(
            dev.stapler.stelekit.error.DomainError.FileSystemError.WriteFailed(path, "disk full"),
        )

        rig.saver.submit(docOf("e0", "e1"))
        advanceUntilIdle()
        assertEquals(SaveState.Failed(SaveFailure.Write), rig.saver.state.value)

        rig.saver.retry()
        advanceUntilIdle()
        assertEquals(SaveState.Saved, rig.saver.state.value)
        rig.saver.close()
    }

    @Test
    fun gitSyncBusy_should_KeepPendingAndRetryWithoutFailing() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.store.nextOutcome = dev.stapler.stelekit.projectbridge.SidecarWriteOutcome.Deferred

        rig.saver.submit(docOf("e0", "e1"))
        advanceTimeBy(1_000)
        assertEquals(SaveState.Pending, rig.saver.state.value)
        advanceUntilIdle()

        assertEquals(SaveState.Saved, rig.saver.state.value)
        assertTrue(rig.diagnostics.diagnostics.value.entries.isEmpty())
        rig.saver.close()
    }

    @Test
    fun slowEncodes_should_BackOffToOneThenTwoSecondsAndResetOnAFastOne() = runTest {
        val rig = Rig(this)
        rig.start()
        var encodeMs = SLOW_ENCODE_MS
        rig.encoder = { doc -> rig.clock += encodeMs; SheetCodecV2.encode(doc) }
        val writeTimes = mutableListOf<Long>()
        val timed = ScriptedSidecarStore(onWrite = { writeTimes += testScheduler.currentTime })
        timed.files.putAll(rig.store.files)
        val saver = SheetSaver(
            SheetTarget(slug, ProjectUuid("p1"), sheet, ContentHasher.sha256(bytesOf(rig.loaded))),
            SheetSaverPorts(
                rig.repos, timed, SidecarSlugLock(), rig.diagnostics,
                encoder = { doc, _ -> rig.encoder(doc) }, clockMillis = { rig.clock },
            ),
            context = StandardTestDispatcher(testScheduler), encodeContext = StandardTestDispatcher(testScheduler),
        )
        rig.saver.close()

        suspend fun editAndSettle(id: String): Long {
            val before = testScheduler.currentTime
            saver.submit(docOf("e0", id))
            advanceUntilIdle()
            return writeTimes.last() - before
        }

        assertEquals(500, editAndSettle("a"))
        assertEquals(1_000, editAndSettle("b"))
        assertEquals(2_000, editAndSettle("c"))
        encodeMs = 0
        assertEquals(2_000, editAndSettle("d"))
        assertEquals(500, editAndSettle("e"))
        saver.close()
    }

    @Test
    fun closedSaver_should_IgnoreEditsAndLaunchNothing() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.saver.close()

        rig.saver.submit(docOf("e0", "e1"))
        advanceUntilIdle()
        runCurrent()

        assertEquals(0, rig.store.writes.size)
        assertEquals(SaveState.Saved, rig.saver.state.value)
    }

    @Test
    fun everyEncode_should_BeSampledWithItsElementCountAndBytes() = runTest {
        val rig = Rig(this)
        rig.start()
        val doc = docOf("e0", "e1", "e2")

        rig.saver.submit(doc)
        advanceUntilIdle()

        val sample = rig.samples.single()
        assertEquals(3, sample.first)
        assertEquals(bytesOf(doc).size, sample.third)
        rig.saver.close()
    }

    @Test
    fun unsavedStates_should_NeedALeavePrompt_And_SavedShouldNot() = runTest {
        assertEquals(false, SaveState.Saved.hasUnsavedWork)
        assertTrue(SaveState.Conflict(null).hasUnsavedWork && SaveState.Pending.hasUnsavedWork && SaveState.Failed(SaveFailure.Write).hasUnsavedWork)
        // The debounce config default is the plan's 500 ms (Story 1.4.4).
        assertEquals(500L, SheetSaverConfig().debounceMs)
    }

    private companion object {
        const val SLOW_ENCODE_MS = 400L
    }
}
