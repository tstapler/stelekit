// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.projectbridge.EngineChangeBus
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.SessionConfirmation
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.RigSettings
import dev.stapler.stelekit.sketch.v2.V2EditorRig
import dev.stapler.stelekit.sketch.v2.runtime.session.AckLevel
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.ui.sketch.v2.migration.MigrationTags
import dev.stapler.stelekit.ui.sketch.v2.migration.ModeSettingTags
import dev.stapler.stelekit.ui.sketch.v2.migration.SignalsReadoutTags
import dev.stapler.stelekit.ui.sketch.v2.migration.SketchSettingsHost
import dev.stapler.stelekit.ui.sketch.v2.migration.SketchSettingsPorts
import dev.stapler.stelekit.platform.UnsupportedBrowserPersistence
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Task 6.2.0c: the route shows the Upgrade chip over a classic sheet and converts it only through the first-write dialog; the
 * Settings section stores a widening mode only after its confirmation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(DirectRepositoryWrite::class)
class V2EntryRouteTest {
    @get:Rule
    val rule = createComposeRule()

    private val settings = RigSettings()

    @Test
    fun classicSheet_should_ShowTheChipAndOpenTheEditorAsV2_OnlyAfterTheDialogIsConfirmed() {
        val rig = V2EditorRig()
        val classic = rig.bundle().let { b ->
            ProjectBundle(b.project, b.sheets.map { it.copy(schemaVersion = 1) }, emptyList(), emptyList())
        }
        runBlocking {
            rig.repos.projects.replaceProjectContents(rig.project.uuid, classic)
            rig.store.files.putAll(SidecarCodec.encode(classic))
        }
        val deps = V2EditorDeps(
            repos = rig.repos,
            store = { rig.store },
            leases = null,
            settings = ProjectBridgeSettings(settings).also { it.setV2Mode(V2EditorRig.GRAPH, V2Mode.On) },
            graphId = { V2EditorRig.GRAPH },
            acks = ProjectAckStore(settings) { V2EditorRig.GRAPH },
            onboarding = OnboardingSequencer(OnboardingCounters(settings)),
        )
        rule.setContent { SketchEditorV2Route(deps, rig.project.uuid, onBack = {}) { Text("classic", Modifier.testTag(LEGACY)) } }

        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithTagCount(LEGACY) > 0 && rule.onAllNodesWithTagCount(BannerTags.PRIMARY) > 0 }
        assertEquals(0, rig.store.files.keys.count { it.endsWith(".v2.json") }, "opening writes nothing")
        rule.onNodeWithTag(BannerTags.PRIMARY).performClick()
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithTagCount(MigrationTags.FIRST_WRITE_CHECKBOX) > 0 }
        assertEquals(0, rig.store.files.keys.count { it.endsWith(".v2.json") }, "the dialog is up and nothing is written")
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_CHECKBOX).performClick()
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_PRIMARY).performClick()

        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithTagCount(HostTags.ROOT) > 0 }
        assertEquals(1, rig.store.files.keys.count { it.endsWith(".v2.json") }, "the v2 file exists after the confirmed conversion")
    }

    @Test
    fun settings_should_StoreAWideningModeOnlyAfterItsConfirmation() {
        val bridge = ProjectBridgeSettings(settings)
        val acks = ProjectAckStore(settings) { V2EditorRig.GRAPH }
        val ports = SketchSettingsPorts(
            settings = bridge,
            graphId = { V2EditorRig.GRAPH },
            capabilities = { V2Capabilities(conversionReady = true, conversionOffered = true, storage = V2Storage.Available) },
            acks = acks,
            quality = QualitySignals(settings),
            persistence = UnsupportedBrowserPersistence,
            session = SessionConfirmation(),
            engineChange = EngineChangeBus(),
        )
        rule.setContent { SketchSettingsHost(ports, editorOpen = false) }

        rule.onNodeWithTag(ModeSettingTags.option(V2Mode.On)).performClick()
        rule.onNodeWithTag(MigrationTags.ENABLE_CANCEL).performClick()
        assertEquals(V2Mode.Off, bridge.v2Mode(V2EditorRig.GRAPH), "cancel stores nothing")

        rule.onNodeWithTag(ModeSettingTags.option(V2Mode.On)).performClick()
        rule.onNodeWithTag(MigrationTags.ENABLE_CONFIRM).performClick()
        assertEquals(V2Mode.On, bridge.v2Mode(V2EditorRig.GRAPH))
        assertEquals(AckLevel.On, acks.level)

        rule.onNodeWithTag(ModeSettingTags.option(V2Mode.Off)).performClick()
        assertEquals(V2Mode.Off, bridge.v2Mode(V2EditorRig.GRAPH), "narrowing needs no confirmation")
        rule.onNodeWithTag(SignalsReadoutTags.ADOPTION).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val LEGACY = "test.classic-editor"
        const val WAIT_MS = 30_000L
    }
}
