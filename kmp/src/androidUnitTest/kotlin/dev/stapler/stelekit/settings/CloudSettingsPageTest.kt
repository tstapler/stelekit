package dev.stapler.stelekit.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.asset.pipeline.CLOUD_REVOKE_COPY
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudConsent
import dev.stapler.stelekit.asset.pipeline.CloudConsentGate
import dev.stapler.stelekit.asset.pipeline.CloudSettingsController
import dev.stapler.stelekit.asset.pipeline.EgressLedger
import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.ui.components.settings.CloudSettingsSection
import dev.stapler.stelekit.wiring.FakeSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cloud row on Settings > Plugins over the real gate, registry and ledger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CloudSettingsPageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class MemoryCredentials : CredentialAccess {
        val values = mutableMapOf<String, String>()
        override fun retrieve(key: String) = values[key]
        override fun store(key: String, value: String) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
    }

    private val settings = FakeSettings().also { it.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true) }
    private val creds = MemoryCredentials().also { it.values["llm.anthropic.api_key"] = "sk-test" }
    private val gate = CloudConsentGate(settings, LlmCredentialStore(creds))
    private var graph = "graph-a"

    private fun controller(): CloudSettingsController {
        val registry = PluginRegistry(settings, PlatformCaps.NONE, cloudConsentGranted = { gate.token(graph) != null })
        return CloudSettingsController(
            gate = gate, registry = registry, ledger = EgressLedger(settings), assets = InMemoryAssetRepository(),
            graphId = { graph }, graphName = { "Work notes" }, meteredAware = { false }, now = { 1_000L },
        )
    }

    private fun mount(controller: CloudSettingsController = controller()): CloudSettingsController {
        composeRule.setContent { CloudSettingsSection(controller) }
        return controller
    }

    @Test
    fun cloudSwitch_should_StayOffAndOpenDialog_When_Flipped() {
        val c = mount()

        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Send images to Anthropic?").assertExists()
        composeRule.onNode(isToggleable()).assertIsOff()
        assertEquals(CloudConsent.NotAsked, gate.consent(graph))
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNode(isToggleable()).assertIsOff()
        assertEquals(CloudConsent.NotAsked, c.state.value.consent)
    }

    @Test
    fun enableUploads_should_StoreGrantedForThisGraphOnly_And_MoveTheSwitch_When_Tapped() {
        mount()

        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Enable uploads").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(isToggleable()).assertIsOn()
        assertEquals(CloudConsent.Granted("anthropic", 1_000L, CloudBackfillScope.NewOnly), gate.consent("graph-a"))
        assertEquals(CloudConsent.NotAsked, gate.consent("graph-b"))
        assertTrue(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.CLOUD_CLAUDE), false))
    }

    @Test
    fun revoke_should_BeImmediateWithNoConfirmAndShowKeptCopy_When_SwitchTurnedOff() {
        gate.grant(graph, 1L, CloudBackfillScope.NewOnly)
        settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.CLOUD_CLAUDE), true)
        mount()
        composeRule.onNode(isToggleable()).assertIsOn()

        composeRule.onNode(isToggleable()).performClick()
        composeRule.waitForIdle()

        composeRule.onNode(isToggleable()).assertIsOff()
        assertEquals(CloudConsent.Revoked, gate.consent(graph))
        assertNull(gate.token(graph))
        composeRule.onNodeWithText(CLOUD_REVOKE_COPY).assertExists()
    }

    @Test
    fun forgetApiKey_should_ConfirmThenDeleteSharedKeyAndSayVoiceStops_When_Confirmed() {
        mount()

        composeRule.onNodeWithText("Forget API key").performClick()
        composeRule.onNodeWithText("This also stops Claude voice formatting", substring = true).assertExists()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals("sk-test", creds.values["llm.anthropic.api_key"], "cancel keeps the key")

        composeRule.onNodeWithText("Forget API key").performClick()
        composeRule.onNodeWithText("Forget").performClick()
        composeRule.waitForIdle()
        assertTrue(creds.values.isEmpty())
        composeRule.onNodeWithText("Add an Anthropic key in LLM settings", substring = true).assertExists()
    }

    @Test
    fun counters_should_BeLabelledAllGraphsAndBeIdentical_When_SecondGraphOpened() {
        mount()
        composeRule.onNodeWithText("all graphs", substring = true).assertExists()
        composeRule.onNodeWithText("Using your Anthropic key from LLM settings").assertExists()

        graph = "graph-b"
        val second = controller()
        assertEquals(controller().state.value.dailyCount, second.state.value.dailyCount)
        assertEquals(controller().state.value.launchCount, second.state.value.launchCount)
    }
}
