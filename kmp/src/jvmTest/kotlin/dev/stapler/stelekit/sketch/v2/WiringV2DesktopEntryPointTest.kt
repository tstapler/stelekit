// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.ProjectBridgeDeps
import kotlin.test.assertFalse
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SketchDurability
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.buildProjectBridgeDeps
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import dev.stapler.stelekit.ui.sketch.v2.v2EditorDeps
import dev.stapler.stelekit.wiring.DesktopWiredAppFixture
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.HeadlessComposition
import dev.stapler.stelekit.wiring.ObservableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Story 6.2.0b: the editor the Desktop root opens is built from the root's own project bridge (real SQLDelight repositories, the
 * root's sidecar store, sheet leases, mode setting and durability), and a rectangle drawn through the pointer adapter reaches the
 * file in that store with the saver waiting on the root's [SketchDurability].
 */
@OptIn(DirectRepositoryWrite::class)
class WiringV2DesktopEntryPointTest {
    private class RecordingDurability : SketchDurability {
        val waited = mutableListOf<Pair<String, String>>()
        override suspend fun awaitPersisted(slug: String, relPath: String): Either<DomainError, Unit> {
            waited += slug to relPath
            return Unit.right()
        }
    }

    private val dataDir: File = createTempDirectory("wiring_v2_desktop_").toFile()
    private val graphPath = "/test/wiring-v2-${System.nanoTime()}"
    private val fs = FakeFileSystem()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun desktopRoot_should_OpenTheV2EditorOverItsOwnStoreAndSaveThroughItsDurability() {
        val durability = RecordingDurability()
        val wired = runBlocking { DesktopWiredAppFixture.desktop(dataDir, FakePlatformPorts(fileSystem = fs), graphPath = graphPath, sketchDurability = durability) }
        fixture = wired
        var bridge: ProjectBridgeDeps? = null
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                val vault = rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
                val io = rememberGraphContentGraphIoStack(
                    wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                    dev.stapler.stelekit.logging.Logger("WiringV2DesktopEntryPointTest"),
                )
                bridge = buildProjectBridgeDeps(wired.deps, vault.effectiveFileSystem, io)
            }
        }
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (bridge == null) {
            if (System.nanoTime() > deadline) fail("the project bridge never composed")
            headless!!.advance()
            Thread.yield()
        }
        val root = assertNotNull(bridge)
        val rig = V2EditorRig(repos = wired.repos.sketchRepositories)
        val store = assertNotNull(root.sidecarStore())
        runBlocking {
            wired.repos.sketchRepositories.projects.replaceProjectContents(rig.project.uuid, rig.bundle())
            store.write(rig.slug, SidecarCodec.encode(rig.bundle()))
        }
        root.settings.setV2Mode(V2EditorRig.GRAPH, V2Mode.On)
        val deps = root.v2EditorDeps(wired.repos.sketchRepositories, wired.repos.writeActor, { V2EditorRig.GRAPH }, KeymapEnv(isMac = false, isWeb = false), touchPresent = false)

        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, FixedAdvanceTextMetricsProvider()) }).session
        val controller = session.controller
        controller.tools.chooseTool(ToolId.Rectangle)
        var t = 0L
        for ((phase, at) in listOf(RawPhase.Down to ScreenPx(30.0, 30.0), RawPhase.Move to ScreenPx(80.0, 60.0), RawPhase.Move to ScreenPx(130.0, 90.0), RawPhase.Up to ScreenPx(130.0, 90.0))) {
            controller.tools.onPointer(RawPointer(1, PointerKind.Mouse, phase, at, timestamp = t++))
        }
        runBlocking { controller.flush() }

        ObservableEffect.assert("sketch-durability-consulted-desktop") {
            assertTrue(durability.waited.any { it.first == rig.slug && it.second == rig.path }, "the saver waited on the root's durability: ${durability.waited}")
            val read = assertIs<SidecarRead.Present>(runBlocking { store.read(rig.slug) })
            val saved = SheetCodecV2.decode(read.files.getValue(rig.path)).getOrNull()
            assertEquals(1, saved?.scene?.elementsInOrder?.count { it is RectElement }, "the drawn rectangle is in the file the root's store holds")
        }
        session.close()

        // Task 6.2.0c: the root built the consent records, and a graph removal clears them through GraphManager's one handler slot.
        val settings = wired.deps.platformSettings
        val gone = "removed-graph"
        ProjectAckStore(settings) { gone }.acknowledge("proj-x")
        val notices = assertNotNull(root.classicNotices)
        notices.record(gone, "proj-x", "ModeOff", null)
        assertNotNull(root.quality)
        wired.graphManager.onGraphRemoved(GraphId(gone))
        assertFalse(runBlocking { ProjectAckStore(settings) { gone }.isAcknowledged("proj-x") }, "the removed graph's acknowledgements are gone")
        assertEquals(0, notices.size(gone), "the removed graph's classic-notice ledger is gone")
    }

    private companion object {
        const val TIMEOUT_NANOS = 60_000_000_000L
    }
}
