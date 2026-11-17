// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.projectbridge.ProjectBridgeDeps
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.SketchDurability
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.ui.buildProjectBridgeDeps
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.HostTags
import dev.stapler.stelekit.ui.sketch.v2.KeymapEnv
import dev.stapler.stelekit.ui.sketch.v2.PainterResources
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorSession
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorV2Host
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import dev.stapler.stelekit.ui.sketch.v2.v2EditorDeps
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.ObservableEffect
import dev.stapler.stelekit.wiring.WiredAppFixture
import arrow.core.Either
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story 6.2.0b: the editor a root opens is built from the root's own project bridge: the sidecar store, the sheet lease registry,
 * the mode setting and the root's [SketchDurability]. A rectangle drawn in the mounted host reaches the file through that store
 * and the saver waits on the root's durability, not on a default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(DirectRepositoryWrite::class)
class WiringV2AndroidEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingDurability : SketchDurability {
        val waited = mutableListOf<Pair<String, String>>()
        override suspend fun awaitPersisted(slug: String, relPath: String): Either<DomainError, Unit> {
            waited += slug to relPath
            return Unit.right()
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("wiring_v2_android_").toFile()
    private val graphPath = "/test/wiring-v2-${System.nanoTime()}"
    private val fs = FakeFileSystem()
    private var fixture: WiredAppFixture? = null

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    @Test
    fun androidRoot_should_OpenTheV2EditorOverItsOwnStoreAndSaveThroughItsDurability() {
        val durability = RecordingDurability()
        val wired = runBlocking {
            WiredAppFixture.android(context, dataDir, ports = FakePlatformPorts(fileSystem = fs), graphPath = graphPath, sketchDurability = durability)
        }
        fixture = wired
        var bridge: ProjectBridgeDeps? = null
        var opened: SketchEditorSession? by mutableStateOf(null)
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, wired.ports.fileSystem, null)
            val io = rememberGraphContentGraphIoStack(
                wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                dev.stapler.stelekit.logging.Logger("WiringV2AndroidEntryPointTest"),
            )
            bridge = buildProjectBridgeDeps(wired.deps, vault.effectiveFileSystem, io)
            opened?.let { SketchEditorV2Host(it, ModernPreviewEnvironment(FixedAdvanceTextMetricsProvider(), PainterResources()), onBack = {}) }
        }
        composeRule.waitUntil(WAIT_MS) { bridge != null }
        val root = assertNotNull(bridge)
        val rig = V2EditorRig(repos = wired.repos.sketchRepositories)
        val store = assertNotNull(root.sidecarStore())
        runBlocking {
            wired.repos.sketchRepositories.projects.replaceProjectContents(rig.project.uuid, rig.bundle())
            store.write(rig.slug, SidecarCodec.encode(rig.bundle()))
        }
        root.settings.setV2Mode(V2EditorRig.GRAPH, V2Mode.On)
        val metrics = FixedAdvanceTextMetricsProvider()
        val deps = root.v2EditorDeps(wired.repos.sketchRepositories, wired.repos.writeActor, { V2EditorRig.GRAPH }, KeymapEnv(isMac = false, isWeb = false), touchPresent = false)
        val session = assertIs<EditorOpening.Editor>(runBlocking { V2EditorOpener(deps, Dispatchers.Default).open(rig.project.uuid, metrics) }).session
        composeRule.runOnUiThread {
            opened = session
            session.controller.chooseTool(ToolId.Rectangle)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HostTags.CANVAS_INPUT).performMouseInput {
            moveTo(Offset(30f, 30f))
            press()
            moveTo(Offset(80f, 60f))
            moveTo(Offset(130f, 90f))
            release()
        }
        composeRule.waitForIdle()
        runBlocking { session.controller.flush() }

        ObservableEffect.assert("sketch-durability-consulted-android") {
            assertTrue(durability.waited.any { it.first == rig.slug && it.second == rig.path }, "the saver waited on the root's durability: ${durability.waited}")
            val read = assertIs<SidecarRead.Present>(runBlocking { store.read(rig.slug) })
            val saved = SheetCodecV2.decode(read.files.getValue(rig.path)).getOrNull()
            assertEquals(1, saved?.scene?.elementsInOrder?.count { it is RectElement }, "the drawn rectangle is in the file the root's store holds")
        }
    }

    private companion object {
        const val WAIT_MS = 60_000L
    }
}
