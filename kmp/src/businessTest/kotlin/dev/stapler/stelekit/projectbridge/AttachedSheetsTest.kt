// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class, ExperimentalCoroutinesApi::class)

package dev.stapler.stelekit.projectbridge

import app.cash.sqldelight.db.SqlDriver
import arrow.core.left
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketchimport.ImportFlow
import dev.stapler.stelekit.sketchimport.pngHeader
import dev.stapler.stelekit.ui.sketch.DrawingAttachAction
import dev.stapler.stelekit.ui.sketch.DrawingStart
import dev.stapler.stelekit.ui.sketch.DrawingText
import dev.stapler.stelekit.ui.sketch.PickedDrawing
import dev.stapler.stelekit.ui.sketch.ProjectListViewModel
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import dev.stapler.stelekit.sketchimport.TagChoice
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachedSheetsTest {
    private val notes = EffectiveNotesPath("/graph")
    private val slug = "proj-0a1b2c"
    private val svg = ClosetFixtureText.load("closet-elevation.svg").encodeToByteArray()
    private val untaggable = """<svg xmlns="http://www.w3.org/2000/svg"><text x="10" y="10">50"</text></svg>""".encodeToByteArray()
    private val png = pngHeader(1000, 500)
    private val svgHash = ContentHasher.sha256(svg)

    private lateinit var driver: SqlDriver
    private lateinit var repos: SketchRepositories
    private lateinit var fs: FakeFileSystem
    private lateinit var assets: AssetIndexService
    private lateinit var service: AttachedSheetService
    private var pickNext: PickedDrawing? = null
    private lateinit var action: DrawingAttachAction

    @BeforeTest
    fun setup() = runBlocking<Unit> {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        assets = AssetIndexService(SqlDelightAssetRepository(SteleDatabase(driver)))
        repos = inMemorySketchRepositories()
        fs = FakeFileSystem()
        val seeded = SketchTestSupport.project(uuid = "p1", slug = slug)
        repos.projects.saveProject(seeded.copy(settings = ProjectFusionSettings.with(seeded.settings, ClosetFusion.registry, FusionSpec())))
        service = attachedSheetService(repos, fs, notes) { it() }
        action = DrawingAttachAction(service, ImportFlow(assets, repos, fs, notes, writeActor = null)) { pickNext }
    }

    @AfterTest
    fun teardown() {
        assets.close()
        driver.close()
    }

    private suspend fun settings() = repos.projects.loadProject(ProjectUuid("p1")).getOrNull()!!.project.settings

    private suspend fun attach(file: String, bytes: ByteArray, tag: Boolean = false): String? {
        pickNext = PickedDrawing(file, bytes)
        val session = assertIs<DrawingStart.Started>(action.start("p1")).session
        if (tag) session.viewModel.state.value.items.first().let { session.viewModel.choose(it.ref, TagChoice.New("closet.extra", "closet.extra")) }
        session.viewModel.save()
        val done = withTimeout(30_000) { session.viewModel.state.first { it.phase == ImportPhase.Done || it.phase == ImportPhase.Failed } }
        assertEquals(ImportPhase.Done, done.phase)
        return action.finish(session, done.registry)
    }

    @Test
    fun anSvgDrawingIsImportedRecordedByKindAndHashAndReadBackForTheCollector() = runBlocking<Unit> {
        assertNull(attach("closet-elevation.svg", svg))

        val row = service.list("p1").single()
        assertEquals(SheetKind.SVG, row.kind)
        assertEquals(svgHash, row.contentHash)
        assertEquals(SheetStatus.READY, row.status)
        assertEquals("SVG ${svgHash.take(8)} - ready", row.summary)
        val sheets = service.readSvgSheets()
        assertEquals(1, sheets.size)
        val collected = ProjectSourceCollector(ClosetFusion.registry, reader = { ProjectSourceTexts(sheets = sheets) }).collect()
        assertTrue(collected.svgObservations.isNotEmpty(), "attached SVG labels must reach the collector")
    }

    @Test
    fun theSyncedSettingsHoldNoFileNameOrPath() = runBlocking<Unit> {
        attach("secret-closet-elevation.svg", svg)

        val text = settings().toString()
        assertTrue(text.contains(svgHash))
        listOf("secret", "closet-elevation", "/graph", "content://", "file://", ".svg").forEach { assertFalse(text.contains(it), "found <$it>") }
    }

    @Test
    fun aPngIsAttachedButContributesNothingToTheCollector() = runBlocking<Unit> {
        assertNull(attach("scan.png", png))

        val row = service.list("p1").single()
        assertEquals(SheetKind.PNG, row.kind)
        assertEquals(SheetStatus.RASTER, row.status)
        assertTrue(service.readSvgSheets().isEmpty())
    }

    @Test
    fun attachingTheSameDrawingTwiceIsRefusedBeforeAnythingIsImported() = runBlocking<Unit> {
        attach("a.svg", svg)

        pickNext = PickedDrawing("again.svg", svg)
        assertEquals(DrawingStart.Refused("That drawing is already attached"), action.start("p1"))
        assertEquals(1, service.list("p1").size)
    }

    @Test
    fun cancellingThePickerStartsNothing() = runBlocking<Unit> {
        pickNext = null
        assertEquals(DrawingStart.Cancelled, action.start("p1"))
    }

    @Test
    fun anUnsupportedFileOpensNoSourceAndIsNotRecorded() = runBlocking<Unit> {
        pickNext = PickedDrawing("notes.pdf", byteArrayOf(1, 2, 3))
        val session = assertIs<DrawingStart.Started>(action.start("p1")).session

        assertNull(session.source)
        assertEquals("That file type cannot be attached", action.finish(session, null))
        assertTrue(service.list("p1").isEmpty())
    }

    @Test
    fun keysCreatedWhileTaggingAreSavedWithTheSource() = runBlocking<Unit> {
        assertNull(attach("odd.svg", untaggable, tag = true))

        assertNotNull(service.registryOf("p1").entry("closet.extra"))
        assertEquals(1, service.list("p1").size)
    }

    @Test
    fun detachRemovesTheEntryButLeavesTheDrawingFile() = runBlocking<Unit> {
        attach("a.svg", svg)
        val path = "/graph/assets/stelekit-sketch/$slug/drawings/${svgHash.take(8)}.svg"

        assertTrue(service.detach("p1", svgHash))

        assertTrue(service.list("p1").isEmpty())
        assertNotNull(fs.readFileBytes(path))
        assertFalse(service.detach("p1", svgHash))
    }

    @Test
    fun aMissingOrChangedFileIsReportedNotDropped() = runBlocking<Unit> {
        attach("a.svg", svg)
        val path = "/graph/assets/stelekit-sketch/$slug/drawings/${svgHash.take(8)}.svg"

        fs.writeFileBytes(path, "<svg/>".encodeToByteArray())
        assertEquals(SheetStatus.CHANGED, service.list("p1").single().status)
        assertTrue(service.readSvgSheets().isEmpty())

        fs.deleteFile(path)
        assertEquals(SheetStatus.MISSING, service.list("p1").single().status)
    }

    @Test
    fun aPlatformWithoutByteReadsReportsMissing() = runBlocking<Unit> {
        attach("a.svg", svg)
        val blind = AttachedSheetService(repos, { it() }, { _, _, _ -> "x" }, { throw UnsupportedOperationException() })

        assertEquals(SheetStatus.MISSING, blind.list("p1").single().status)
    }

    @Test
    fun theProjectIsNotFoundForAnUnknownUuid() = runBlocking<Unit> {
        val source = SheetSource(SheetKind.SVG, svgHash)
        assertEquals(AttachOutcome.ProjectNotFound, service.attach("nope", source, null))
        assertFalse(service.detach("nope", svgHash))
        assertTrue(service.list("nope").isEmpty())
        assertFalse(service.isAttached("nope", svgHash))
        assertTrue(service.registryOf("nope").dimensions.isEmpty())
    }

    @Test
    fun aFailedSaveIsReported() = runBlocking<Unit> {
        val failing = AttachedSheetService(repos, { dev.stapler.stelekit.error.DomainError.DatabaseError.WriteFailed("x").left() }, { _, _, _ -> "x" }, { null })

        assertEquals(AttachOutcome.Failed, failing.attach("p1", SheetSource(SheetKind.SVG, svgHash), null))
        assertFalse(failing.detach("p1", svgHash))
    }

    @Test
    fun theMessagesCoverEveryOutcome() {
        assertNull(DrawingText.message(AttachOutcome.Attached))
        listOf(AttachOutcome.AlreadyAttached, AttachOutcome.LimitReached, AttachOutcome.ProjectNotFound, AttachOutcome.Failed)
            .forEach { assertTrue(DrawingText.message(it)!!.isNotEmpty()) }
    }

    // --- pure source-entry rules ---

    private fun hash(n: Int) = n.toString(16).padStart(64, '0')

    @Test
    fun sheetEntriesLeaveOtherSourcesAlone() {
        val yaml = YamlSourceEntry.with(JsonObject(emptyMap()), "0a1b2c3d")
        val page = checkNotNull(SourceBindings.with(yaml, SourceBinding(SourceBindingKind.TABLE, "0a0a0a0a", "h")))
        val both = checkNotNull(SheetSources.with(page, SheetSource(SheetKind.PNG, hash(2))))
        val all = checkNotNull(SheetSources.with(both, SheetSource(SheetKind.SVG, hash(1))))

        assertEquals(listOf(SheetSource(SheetKind.PNG, hash(2)), SheetSource(SheetKind.SVG, hash(1))), SheetSources.of(all))
        assertEquals("0a1b2c3d", YamlSourceEntry.snapshotOf(all))
        assertEquals(1, SourceBindings.of(all).size)
        val removed = SheetSources.without(all, hash(2))
        assertEquals(listOf(SheetSource(SheetKind.SVG, hash(1))), SheetSources.of(removed))
        assertEquals(1, SourceBindings.of(removed).size)
        assertTrue(SheetSources.without(SheetSources.without(removed, hash(1)), "x").let { SheetSources.of(it).isEmpty() })
    }

    @Test
    fun theSameHashIsNotAddedTwiceAndTheListIsBounded() {
        var s = JsonObject(emptyMap())
        repeat(SheetSources.MAX_SHEETS) { s = checkNotNull(SheetSources.with(s, SheetSource(SheetKind.SVG, hash(it)))) }

        assertEquals(s, SheetSources.with(s, SheetSource(SheetKind.SVG, hash(3))))
        assertNull(SheetSources.with(s, SheetSource(SheetKind.SVG, hash(999))))
    }

    @Test
    fun malformedEntriesAreIgnored() {
        val bad = JsonObject(
            mapOf(
                SourceBindings.KEY to JsonArray(
                    listOf(
                        JsonObject(mapOf("kind" to JsonPrimitive("svg"), "contentHash" to JsonPrimitive("short"))),
                        JsonObject(mapOf("kind" to JsonPrimitive("svg"))),
                        JsonObject(mapOf("kind" to JsonPrimitive("gif"), "contentHash" to JsonPrimitive(hash(1)))),
                        JsonPrimitive("x"),
                    ),
                ),
            ),
        )
        assertTrue(SheetSources.of(bad).isEmpty())
        assertNull(SheetKind.fromFileName("a.pdf"))
        assertEquals(SheetKind.PNG, SheetKind.fromFileName("A.PNG"))
    }

    // --- project list wiring ---

    @Test
    fun theProjectListAddsAndRemovesDrawingsAndIgnoresACancelledImport() = runBlocking<Unit> {
        val raw = BridgeMapSettings()
        val vm = ProjectListViewModel(
            repos.projects, "g1", ProjectBridgeSettings(raw), LlmSettings(raw), { it() }, { 1L }, Random(1),
            drawings = action, sheets = service, dispatcher = UnconfinedTestDispatcher(),
        )
        try {
            assertTrue(vm.drawingsAvailable)
            withTimeout(30_000) { vm.state.first { it.projects.isNotEmpty() } }

            pickNext = PickedDrawing("a.svg", svg)
            vm.addDrawing()
            val session = checkNotNull(vm.importSession.value)
            vm.addDrawing()
            assertTrue(vm.importSession.value === session)
            vm.finishDrawing(ImportPhase.Cancelled, null)
            assertNull(vm.importSession.value)
            assertTrue(vm.state.value.attachedSheets.isEmpty())

            pickNext = PickedDrawing("a.svg", svg)
            vm.addDrawing()
            val second = checkNotNull(vm.importSession.value)
            second.viewModel.save()
            val done = withTimeout(30_000) { second.viewModel.state.first { it.phase == ImportPhase.Done } }
            vm.finishDrawing(ImportPhase.Done, done.registry)
            withTimeout(30_000) { vm.state.first { it.attachedSheets.size == 1 } }

            vm.removeDrawing(svgHash)
            withTimeout(30_000) { vm.state.first { it.attachedSheets.isEmpty() } }
        } finally {
            vm.close()
        }
    }

    @Test
    fun theProjectListRefusesADuplicateWithAMessage() = runBlocking<Unit> {
        attach("a.svg", svg)
        val raw = BridgeMapSettings()
        val vm = ProjectListViewModel(
            repos.projects, "g1", ProjectBridgeSettings(raw), LlmSettings(raw), { it() }, { 1L }, Random(1),
            drawings = action, sheets = service, dispatcher = UnconfinedTestDispatcher(),
        )
        try {
            withTimeout(30_000) { vm.state.first { it.projects.isNotEmpty() } }
            pickNext = PickedDrawing("a.svg", svg)
            vm.addDrawing()
            assertEquals("That drawing is already attached", vm.state.value.message)
            assertNull(vm.importSession.value)
        } finally {
            vm.close()
        }
    }

    @Test
    fun withoutDrawingSupportTheProjectListOffersNone() {
        val raw = BridgeMapSettings()
        val vm = ProjectListViewModel(repos.projects, "g1", ProjectBridgeSettings(raw), LlmSettings(raw), { it() }, { 1L }, Random(1))
        try {
            assertFalse(vm.drawingsAvailable)
            vm.addDrawing()
            vm.removeDrawing("x")
            vm.finishDrawing(ImportPhase.Done, null)
            assertNull(vm.importSession.value)
        } finally {
            vm.close()
        }
    }
}
