// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.FileSystemSketchDurability
import dev.stapler.stelekit.projectbridge.PageLifecycle
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.SketchDurability
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task 1.4.7k: the saver reports Saved only after the durability port confirms, and handles quota, retry and a hidden page. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SheetSaverDurabilityTest {
    private val sheet = SheetUuid("s2")
    private val slug = "proj-0a1b2c"

    private val strings = object : BannerStringSource {
        override fun summary(row: BannerRowId) = "Couldn't save."
        override fun shortTitle(row: BannerRowId) = "Not saved"
        override fun primaryLabel(row: BannerRowId) = "Retry"
        override fun moreItems(row: BannerRowId) = emptyList<String>()
    }

    private class ScriptedDurability : SketchDurability {
        var gate: CompletableDeferred<Either<DomainError, Unit>>? = null
        var answer: Either<DomainError, Unit> = Unit.right()
        var calls = 0

        override suspend fun awaitPersisted(slug: String, relPath: String): Either<DomainError, Unit> {
            calls++
            return gate?.await() ?: answer
        }
    }

    private fun docOf(vararg ids: String): SketchDocumentV2 = SketchDocumentV2(
        scene = ids.foldIndexed(SketchSceneV2.empty()) { i, scene, id ->
            val rect = RectElement(
                ElementBase(ElementId(id), zKey = ZKey("a$id")),
                SceneUnit.ofInt(i * 20), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
            )
            scene.add(rect).getOrNull()!!
        },
        sheetUuid = sheet.value,
    )

    private inner class Rig(scope: TestScope, val durability: ScriptedDurability, val hidden: MutableSharedFlow<Unit> = MutableSharedFlow()) {
        val store = ScriptedSidecarStore()
        val diagnostics = InMemoryDiagnosticsPort(strings)
        lateinit var saver: SheetSaver
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)

        suspend fun start() {
            val repos = inMemorySketchRepositories()
            val project = SketchProject(ProjectUuid("p1"), slug, "Kitchen", 1L)
            val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16, schemaVersion = SheetPaths.SCHEMA_V2)
            val loadedBytes = SheetCodecV2.encode(docOf("e0")).getOrNull()!!
            val bundle = ProjectBundle(project, listOf(row), emptyList(), emptyList(), mapOf(sheet to SheetPayload.V2Opaque.of(loadedBytes)))
            repos.projects.replaceProjectContents(project.uuid, bundle)
            store.files.putAll(SidecarCodec.encode(bundle))
            val lifecycle = object : PageLifecycle {
                override val hidden = this@Rig.hidden
            }
            saver = SheetSaver(
                SheetTarget(slug, project.uuid, sheet, ContentHasher.sha256(loadedBytes)),
                SheetSaverPorts(repos, store, SidecarSlugLock(), diagnostics, durability = durability, lifecycle = lifecycle),
                context = dispatcher,
                encodeContext = dispatcher,
            )
        }
    }

    @Test
    fun saved_should_WaitForThePersistedConfirmation() = runTest {
        val durability = ScriptedDurability().apply { gate = CompletableDeferred() }
        val rig = Rig(this, durability)
        rig.start()

        rig.saver.submit(docOf("e0", "e1"))
        advanceTimeBy(600)
        assertEquals(1, rig.store.writes.size)
        assertEquals(SaveState.Saving, rig.saver.state.value)

        durability.gate!!.complete(Unit.right())
        advanceUntilIdle()
        assertEquals(SaveState.Saved, rig.saver.state.value)
        rig.saver.close()
    }

    @Test
    fun quotaExceeded_should_FailWithTheQuotaVariantAndKeepTheScene() = runTest {
        val durability = ScriptedDurability().apply { answer = DomainError.FileSystemError.StorageQuotaExceeded("p").left() }
        val rig = Rig(this, durability)
        rig.start()

        rig.saver.submit(docOf("e0", "e1"))
        advanceUntilIdle()

        assertEquals(SaveState.Failed(SaveFailure.Quota), rig.saver.state.value)
        assertEquals("quota", rig.diagnostics.diagnostics.value.entries.first { it.row == BannerRowId.SaveFailed }.details)
        rig.saver.close()
    }

    @Test
    fun otherFailure_should_FailAsWrite_AndRetryShouldRewriteTheUnchangedEncoding() = runTest {
        val durability = ScriptedDurability().apply { answer = DomainError.FileSystemError.WriteFailed("p", "move failed").left() }
        val rig = Rig(this, durability)
        rig.start()

        rig.saver.submit(docOf("e0", "e1"))
        advanceUntilIdle()
        assertEquals(SaveState.Failed(SaveFailure.Write), rig.saver.state.value)
        val writesBefore = rig.store.writes.size

        durability.answer = Unit.right()
        rig.saver.retry()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, rig.saver.state.value)
        assertTrue(rig.store.writes.size > writesBefore, "retry must re-enqueue the replace")
        rig.saver.close()
    }

    @Test
    fun hiddenPage_should_SkipTheDebounce() = runTest {
        val rig = Rig(this, ScriptedDurability())
        rig.start()

        rig.saver.submit(docOf("e0", "e1"))
        advanceTimeBy(100)
        assertEquals(0, rig.store.writes.size)
        rig.hidden.emit(Unit)
        runCurrent()

        assertEquals(1, rig.store.writes.size)
        advanceUntilIdle()
        rig.saver.close()
    }

    @Test
    fun fileSystemDurability_should_AwaitTheSheetFileThenTheModel() = runTest {
        val awaited = mutableListOf<String>()
        val fs = object : dev.stapler.stelekit.platform.FileSystem by dev.stapler.stelekit.platform.DemoFileSystem() {
            override suspend fun awaitDurable(path: String): Either<DomainError, Unit> {
                awaited += path
                return Unit.right()
            }
        }
        val durability = FileSystemSketchDurability(fs) { "/g/" }

        assertTrue(durability.awaitPersisted("p", "sheets/s.v2.json").isRight())
        assertEquals(
            listOf("/g/assets/stelekit-sketch/p/sheets/s.v2.json", "/g/assets/stelekit-sketch/p/project-model.json"),
            awaited,
        )
        assertTrue(FileSystemSketchDurability(fs) { null }.awaitPersisted("p", "x").isRight())
    }
}
