// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.GraphContentAssetStack
import dev.stapler.stelekit.ui.rememberGraphContentAssetStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Story 4.1 / 4.2 wiring proof (Robolectric, in-memory backend, see [WiredAppFixture.android] for why):
 * the real `rememberGraphContentAssetStack` fills `asset_index` once the graph is fully loaded and the
 * Asset indexing switch is on, registers an attach, and stays inert while the switch is unset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetStackEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val graphPath = "/test/wired-graph"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val dataDir: File = createTempDirectory("asset_stack_entry_").toFile()
    private var fixture: WiredAppFixture? = null

    @After
    fun tearDown() {
        AssetBuildFlags.internalBuild = false
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private class FakeAttachments(private val fs: FakeFileSystem, private val bytes: ByteArray) : MediaAttachmentService {
        override suspend fun pickAndAttach(notesPath: EffectiveNotesPath, pageRelativePath: String): Either<DomainError, AttachmentResult> {
            fs.writeFileBytes(notesPath.child("assets/attached.png"), bytes)
            return AttachmentResult("../assets/attached.png", "attached.png").right()
        }
    }

    private fun mount(switchOn: Boolean, loaded: Boolean = true, internalBuild: Boolean = true): Triple<WiredAppFixture, MutableStateFlow<AppState>, () -> GraphContentAssetStack?> {
        AssetBuildFlags.internalBuild = internalBuild
        val fs = FakeFileSystem()
        repeat(3) { fs.writeFileBytes("$graphPath/assets/img$it.png", png) }
        val ports = FakePlatformPorts(fileSystem = fs)
        ports.settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, switchOn)
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir, ports, graphPath) }
        fixture = wired
        val appState = MutableStateFlow(AppState(currentGraphPath = graphPath, isFullyLoaded = loaded))
        val deps = wired.deps.copy(
            platformIntegrations = wired.deps.platformIntegrations.copy(attachmentService = FakeAttachments(fs, png + byteArrayOf(42))),
        )
        var stack: GraphContentAssetStack? = null
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, fs, null)
            val io = rememberGraphContentGraphIoStack(
                deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo, Logger("AssetStackEntryPointTest"),
            )
            stack = rememberGraphContentAssetStack(deps, vault.effectiveFileSystem, io, appState, Logger("AssetStackEntryPointTest"))
        }
        composeRule.waitForIdle()
        return Triple(wired, appState, { stack })
    }

    private fun rows(wired: WiredAppFixture) = runBlocking { wired.repos.assetRepository.countAssets().getOrNull() ?: -1L }

    private fun awaitRows(wired: WiredAppFixture, expected: Long) = runBlocking {
        withTimeout(15_000) { while (rows(wired) != expected) { composeRule.waitForIdle(); delay(20) } }
    }

    @Test
    fun switchOn_andGraphFullyLoaded_populatesAssetIndex_thenAttachIsRegisteredToo() {
        val (wired, _, stack) = mount(switchOn = true)

        ObservableEffect.assert("asset-index-rows") { awaitRows(wired, 3) }

        val assets = assertNotNull(stack()?.attachmentService, "the attach path is wrapped when a platform service exists")
        runBlocking { assets.pickAndAttach(EffectiveNotesPath(graphPath), "") }
        awaitRows(wired, 4)
        // The same bytes attached again are one row (idempotent on content hash).
        runBlocking { assets.pickAndAttach(EffectiveNotesPath(graphPath), "") }
        assertEquals(4L, rows(wired))
    }

    @Test
    fun switchUnset_leavesTheIndexEmpty_evenWhenTheGraphIsFullyLoaded() {
        val (wired, _, stack) = mount(switchOn = false)

        runBlocking { delay(300) }
        composeRule.waitForIdle()

        assertEquals(0L, rows(wired))
        val attachments = assertNotNull(stack()?.attachmentService)
        runBlocking { attachments.pickAndAttach(EffectiveNotesPath(graphPath), "") }
        assertEquals(0L, rows(wired), "attach is not indexed while the switch is off")
    }

    @Test
    fun graphStillLoading_doesNotStartTheWalk_untilFullyLoaded() {
        val (wired, appState, _) = mount(switchOn = true, loaded = false)

        runBlocking { delay(300) }
        composeRule.waitForIdle()
        assertEquals(0L, rows(wired), "loadGraph must never overlap the backfill")

        appState.value = appState.value.copy(isFullyLoaded = true)
        awaitRows(wired, 3)
    }

    @Test
    fun releaseVariant_neverStartsTheBackfill_evenIfTheKeyIsSet() {
        val (wired, _, stack) = mount(switchOn = true, internalBuild = false)

        runBlocking { delay(300) }
        composeRule.waitForIdle()

        assertEquals(0L, rows(wired), "a build that hides the switch must not index, whatever the stored key says")
        assertEquals(false, assertNotNull(stack()).indexing.toggleVisible)
        assertEquals(null, stack()?.moveAsset, "Move is not offered when the switch is hidden")
    }
}
