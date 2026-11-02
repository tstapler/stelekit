package dev.stapler.stelekit.wiring

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.pipeline.AssetBuildFlags
import dev.stapler.stelekit.asset.pipeline.CloudBackfillScope
import dev.stapler.stelekit.asset.pipeline.CloudConsentGate
import dev.stapler.stelekit.asset.pipeline.CloudNetwork
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.CloudStackSeams
import dev.stapler.stelekit.ui.GraphContentAssetStack
import dev.stapler.stelekit.ui.MemoryPressureListeners
import dev.stapler.stelekit.ui.rememberGraphContentAssetStack
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Cloud wiring proof: through the real `rememberGraphContentAssetStack`, with consent granted and a key present,
 * exactly one request goes through the real `defaultAssetPlugins` path and its text lands in `asset_index`;
 * with consent unset nothing is sent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CloudStackEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val graphPath = "/test/wired-graph"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
    private val dataDir: File = createTempDirectory("cloud_stack_entry_").toFile()
    private var fixture: WiredAppFixture? = null
    private val requests = AtomicInteger()

    @After
    fun tearDown() {
        AssetBuildFlags.internalBuild = false
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private class MemoryCredentials : CredentialAccess {
        val values = mutableMapOf("llm.anthropic.api_key" to "sk-test")
        override fun retrieve(key: String) = values[key]
        override fun store(key: String, value: String) { values[key] = value }
        override fun delete(key: String) { values.remove(key) }
    }

    private fun mount(granted: Boolean): Pair<WiredAppFixture, () -> GraphContentAssetStack?> {
        AssetBuildFlags.internalBuild = true
        val fs = FakeFileSystem()
        fs.writeFileBytes("$graphPath/assets/receipt.png", png)
        val ports = FakePlatformPorts(fileSystem = fs)
        ports.settings.putBoolean(AssetBuildFlags.PIPELINE_ENABLED_KEY, true)
        ports.settings.putBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.CLOUD_CLAUDE), true)
        val wired = runBlocking { WiredAppFixture.android(ApplicationProvider.getApplicationContext<Context>(), dataDir, ports, graphPath) }
        fixture = wired
        val credentials = LlmCredentialStore(MemoryCredentials())
        if (granted) {
            val graphId = wired.graphManager.graphRegistry.value.activeGraphId?.value.orEmpty()
            CloudConsentGate(ports.settings, credentials).grant(graphId, 1L, CloudBackfillScope.NewOnly)
        }
        val engine = MockEngine {
            requests.incrementAndGet()
            respond("""{"content":[{"type":"text","text":"A receipt from Acme"}]}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val appState = MutableStateFlow(AppState(currentGraphPath = graphPath, isFullyLoaded = true))
        var stack: GraphContentAssetStack? = null
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, fs, null)
            val io = rememberGraphContentGraphIoStack(
                wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo, Logger("CloudStackEntryPointTest"),
            )
            stack = rememberGraphContentAssetStack(
                wired.deps, vault.effectiveFileSystem, io, appState, MemoryPressureListeners(), Logger("CloudStackEntryPointTest"),
                cloudSeams = CloudStackSeams(credentials, HttpClient(engine), network = { CloudNetwork.Online }),
            )
        }
        composeRule.waitForIdle()
        return wired to { stack }
    }

    private fun awaitIndexed(stack: GraphContentAssetStack?) = runBlocking {
        withTimeout(15_000) { assertNotNull(stack).indexing.progress.first { it is AssetIndexProgress.Finished } }
    }

    private fun description(wired: WiredAppFixture): String? = runBlocking {
        val all = wired.repos.assetRepository.getAssets(10, 0).first().getOrNull().orEmpty()
        all.firstOrNull()?.cloudDescription
    }

    @Test
    fun consentGranted_keyPresent_sendsExactlyOneRequestThroughDefaultAssetPluginsAndPersistsIt() {
        val (wired, stack) = mount(granted = true)
        awaitIndexed(stack())

        composeRule.waitUntil(timeoutMillis = 15_000) { description(wired) != null }

        ObservableEffect.assert("cloud-description-persisted") {
            assertEquals(1, requests.get(), "one request through the real plugin path")
            assertEquals("A receipt from Acme", description(wired))
        }
    }

    @Test
    fun consentNotAsked_sendsNothing_evenWithTheSwitchAndKeyPresent() {
        val (wired, stack) = mount(granted = false)
        awaitIndexed(stack())
        composeRule.waitUntil(timeoutMillis = 15_000) { stack()?.plugins?.progress?.value is PipelineProgress.Finished }

        assertEquals(0, requests.get())
        assertEquals(null, description(wired))
    }
}
