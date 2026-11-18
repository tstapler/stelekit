// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.PageLifecycle
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review findings B5 and A5: the saver's conflict path survives a throw, and "Keep mine" with no local edits writes and settles. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SheetSaverResilienceTest {
    private class FakeHeapExhausted : Error("heap")

    private val strings = object : BannerStringSource {
        override fun summary(row: BannerRowId) = "x"
        override fun shortTitle(row: BannerRowId) = "x"
        override fun primaryLabel(row: BannerRowId) = "x"
        override fun moreItems(row: BannerRowId) = emptyList<String>()
    }
    private val sheet = SheetUuid("s2")
    private val slug = "proj-0a1b2c"
    private val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)

    private fun docOf(vararg ids: String) = SketchDocumentV2(
        scene = ids.foldIndexed(SketchSceneV2.empty()) { i, scene, id ->
            scene.add(RectElement(ElementBase(ElementId(id), zKey = ZKey("a$id")), SceneUnit.ofInt(i * 20), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10))).getOrNull()!!
        },
        sheetUuid = sheet.value,
    )

    private fun bytesOf(doc: SketchDocumentV2) = SheetCodecV2.encode(doc).getOrNull()!!

    private inner class Rig(scope: TestScope) {
        val store = ScriptedSidecarStore()
        val external = MutableSharedFlow<Unit>()
        val hidden = MutableSharedFlow<Unit>()
        val loaded = docOf("e0")
        var encoderThrows = false
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val diagnostics = InMemoryDiagnosticsPort(strings)
        lateinit var saver: SheetSaver

        suspend fun start() {
            val repos = inMemorySketchRepositories()
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
                    externalChanges = external,
                    encoder = { doc, limits -> if (encoderThrows) throw FakeHeapExhausted() else SheetCodecV2.encode(doc, limits) as Either<SheetError, ByteArray> },
                    lifecycle = object : PageLifecycle { override val hidden = this@Rig.hidden },
                ),
                context = dispatcher,
                encodeContext = dispatcher,
            )
        }

        /** The other device's version lands on disk and the post-sync coordinator says so: no unsaved edits, so a Conflict with no copy. */
        suspend fun pullTheirs(TestScope: TestScope, theirs: SketchDocumentV2) {
            store.files[path] = bytesOf(theirs)
            TestScope.runCurrent()
            external.emit(Unit)
            TestScope.runCurrent()
        }
    }

    @Test
    fun conflictCopyThrow_should_KeepTheLoopAlive_When_AnEditArrivesDuringAConflict() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.pullTheirs(this, docOf("e0", "theirs"))
        assertIs<SaveState.Conflict>(rig.saver.state.value)

        rig.encoderThrows = true
        rig.saver.submit(docOf("e0", "m1"))
        advanceUntilIdle()
        assertIs<SaveState.Conflict>(rig.saver.state.value, "a failed conflict copy leaves the conflict prompt up, not a Failed banner")

        rig.encoderThrows = false
        val second = docOf("e0", "m2")
        rig.saver.submit(second)
        advanceUntilIdle()

        assertTrue(rig.store.conflictCopies.any { it.contentEquals(bytesOf(second)) }, "the loop still saved a later edit as a conflict copy")
        rig.saver.close()
    }

    @Test
    fun conflictCopyThrow_should_KeepTheConflictAndTheHiddenCollector_When_ThePageIsHiddenDuringAConflict() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.pullTheirs(this, docOf("e0", "theirs"))
        rig.encoderThrows = true
        rig.saver.submit(docOf("e0", "m1"))
        runCurrent()
        rig.hidden.emit(Unit)
        runCurrent()
        assertIs<SaveState.Conflict>(rig.saver.state.value, "the hide-time flush failing leaves the conflict prompt up")

        rig.encoderThrows = false
        val second = docOf("e0", "m2")
        rig.saver.submit(second)
        runCurrent()
        rig.hidden.emit(Unit)
        runCurrent()

        assertTrue(rig.store.conflictCopies.any { it.contentEquals(bytesOf(second)) }, "a later hide still flushed")
        rig.saver.close()
    }

    @Test
    fun keepMine_should_WriteTheEditorsSceneAndSettle_When_ThereWereNoLocalEdits() = runTest {
        val rig = Rig(this)
        rig.start()
        val theirs = docOf("e0", "theirs")
        rig.pullTheirs(this, theirs)
        assertEquals(SaveState.Conflict(null), rig.saver.state.value)

        rig.saver.keepMine(rig.loaded)
        advanceUntilIdle()

        assertEquals(SaveState.Saved, rig.saver.state.value, "nothing is left pending after the choice")
        assertContentEquals(bytesOf(rig.loaded), rig.store.files.getValue(path), "mine replaced theirs, as the button says")
        rig.saver.close()
    }
}
