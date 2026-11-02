package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.llm.AnthropicMessagesClient
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** No consent token, no HTTP call: every case here drives the real [CloudConsentGate] over fake inputs. */
class CloudConsentEnforcementTest {
    private val repo = InMemoryAssetRepository()

    private fun service(rig: CloudRig): AssetPipelineService {
        val registry = PluginRegistry().also { it.register(rig.plugin) }
        return AssetPipelineService(registry, repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()))
    }

    private suspend fun seed(rig: CloudRig, uuid: String, importedAtMs: Long = 2_000L): AssetEntry =
        rig.putImage(uuid, importedAtMs = importedAtMs).also { repo.saveAsset(it) }

    @Test
    fun cloudPlugin_should_MakeZeroHttpRequestsAndSkipAsset_When_ConsentNotAskedRevokedOrKeyMissing() = runTest {
        val notAsked = CloudRig()
        val revoked = CloudRig().also { it.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly); it.gate.revoke(CloudRig.GRAPH_A) }
        val noKey = CloudRig(withKey = false).also { it.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly) }

        for (rig in listOf(notAsked, revoked, noKey)) {
            val asset = seed(rig, "a1")
            assertEquals(AssetPipelineService.Outcome.Stayed, service(rig).processAsset(asset))
            assertEquals(0, rig.http.requests.size)
            val stored = repo.getAssetByUuid(asset.uuid).first().getOrNull()!!
            assertFalse(stored.mlFailed, "skipping is not failing")
            assertFalse(stored.mlProcessed)
        }
    }

    @Test
    fun cloudPlugin_should_MakeZeroCallsForAssets4To10_When_ConsentRevokedAfterAsset3() = runTest {
        val rig = CloudRig()
        rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
        val service = service(rig)
        val outcomes = (1..10).map { n ->
            val outcome = service.processAsset(seed(rig, "a$n"))
            if (n == 3) rig.gate.revoke(CloudRig.GRAPH_A)
            outcome
        }

        assertEquals(3, rig.http.requests.size)
        assertEquals(List(3) { AssetPipelineService.Outcome.Processed } + List(7) { AssetPipelineService.Outcome.Stayed }, outcomes)
    }

    @Test
    fun cloudPlugin_should_CancelInFlightRequest_When_ConsentRevokedDuringCall() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            awaitCancellation()
            @Suppress("UNREACHABLE_CODE")
            respond("", HttpStatusCode.OK)
        }
        val rig = CloudRig()
        val plugin = CloudClaudePlugin(
            CloudEnrichmentConfig(CloudProvider.CLAUDE, enabled = true),
            AnthropicMessagesClient(HttpClient(engine), apiKey = { TEST_KEY }),
            rig.gate, CloudRig.GRAPH_A, rig.ledger, rig.fs, rig.downscaler,
        )
        rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
        val asset = rig.putImage("a1")

        val pending = CoroutineScope(Dispatchers.Default).async { plugin.processAsset(asset) }
        withTimeout(10_000) { started.await() }
        rig.gate.revoke(CloudRig.GRAPH_A)

        val result = withTimeout(10_000) { pending.await() }
        assertIs<AssetPipelineResult.Skipped>(result.getOrNull())
    }

    @Test
    fun cloudConsent_should_NotAuthorizeGraphB_When_GraphAGrantedAndGraphBNotAsked() = runTest {
        val rig = CloudRig()
        rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
        val pluginForB = CloudClaudePlugin(
            CloudEnrichmentConfig(CloudProvider.CLAUDE, enabled = true), rig.client, rig.gate,
            CloudRig.GRAPH_B, rig.ledger, rig.fs, rig.downscaler,
        )
        val asset = rig.putImage("b1")

        assertFalse(pluginForB.canProcess(asset))
        assertIs<AssetPipelineResult.Skipped>(pluginForB.processAsset(asset).getOrNull())
        assertEquals(0, rig.http.requests.size)
        assertNull(rig.gate.token(CloudRig.GRAPH_B))
        assertNotNull(rig.gate.token(CloudRig.GRAPH_A))
    }

    @Test
    fun consentGate_should_ReturnNull_When_NotGrantedForGraphOrKeyMissing_And_ReadsStateEveryCall() {
        val rig = CloudRig()
        assertNull(rig.gate.token(CloudRig.GRAPH_A), "NotAsked")
        rig.gate.grant(CloudRig.GRAPH_A, 5L, CloudBackfillScope.NewOnly)
        assertNotNull(rig.gate.token(CloudRig.GRAPH_A))
        rig.credentialAccess.values.clear()
        assertNull(rig.gate.token(CloudRig.GRAPH_A), "key removed after grant")
        rig.credentialAccess.values["llm.anthropic.api_key"] = TEST_KEY
        assertNotNull(rig.gate.token(CloudRig.GRAPH_A), "key back")
        rig.gate.revoke(CloudRig.GRAPH_A)
        assertNull(rig.gate.token(CloudRig.GRAPH_A), "revoked")
        assertEquals(CloudConsent.Revoked, rig.gate.consent(CloudRig.GRAPH_A))
        rig.credentialAccess.values["llm.anthropic.api_key"] = "   "
        rig.gate.grant(CloudRig.GRAPH_A, 6L, CloudBackfillScope.NewOnly)
        assertNull(rig.gate.token(CloudRig.GRAPH_A), "blank key")
    }

    @Test
    fun gateTokens_should_BeObtainedByRealGateOverFakeSettingsAndFakeCredentialStore_When_TestsNeedGrantedConsent() {
        val rig = CloudRig()
        rig.gate.grant(CloudRig.GRAPH_A, 42L, CloudBackfillScope.IncludeExisting)

        val token = rig.gate.token(CloudRig.GRAPH_A)!!

        assertEquals(CloudRig.GRAPH_A, token.graphId)
        assertEquals("anthropic", token.providerId)
        assertEquals(42L, token.grantedAtMs)
        assertEquals(CloudBackfillScope.IncludeExisting, token.scope)
        assertEquals(CloudConsent.Granted("anthropic", 42L, CloudBackfillScope.IncludeExisting), rig.gate.consent(CloudRig.GRAPH_A))
    }

    @Test
    fun forgetApiKey_should_DeleteSharedAnthropicKeyAndStateVoiceFormattingAlsoStops_When_Confirmed() {
        val rig = CloudRig()
        rig.gate.grant(CloudRig.GRAPH_A, 1L, CloudBackfillScope.NewOnly)
        assertEquals(TEST_KEY, rig.credentials.getApiKey("anthropic"))

        rig.gate.forgetApiKey()

        // The voice formatter reads the same `anthropic` entry, so one deletion removes the key for both.
        assertNull(rig.credentials.getApiKey("anthropic"))
        assertTrue(rig.credentialAccess.values.isEmpty())
        assertNull(rig.gate.token(CloudRig.GRAPH_A))
    }

    @Test
    fun consent_should_StoreScopeNewOnlyByDefault_And_MakeZeroRequestsForAssetsImportedBeforeConsent_When_Drained() = runTest {
        val newOnly = CloudRig()
        newOnly.gate.grant(CloudRig.GRAPH_A, atMs = 5_000L, scope = CloudBackfillScope.NewOnly)
        val old = seed(newOnly, "old", importedAtMs = 1_000L)
        val fresh = seed(newOnly, "fresh", importedAtMs = 9_000L)
        val svc = service(newOnly)

        assertEquals(AssetPipelineService.Outcome.Stayed, svc.processAsset(old))
        assertEquals(0, newOnly.http.requests.size)
        assertEquals(AssetPipelineService.Outcome.Processed, svc.processAsset(fresh))
        assertEquals(1, newOnly.http.requests.size)

        val existing = CloudRig()
        existing.gate.grant(CloudRig.GRAPH_A, atMs = 5_000L, scope = CloudBackfillScope.IncludeExisting)
        val older = seed(existing, "older", importedAtMs = 1_000L)
        assertEquals(AssetPipelineService.Outcome.Processed, service(existing).processAsset(older))
        assertEquals(1, existing.http.requests.size)
    }

    @Test
    fun consentDialogModel_should_ComputeBacklogCountAndDayEstimateFromLiveUnprocessedCountAndDailyCap_When_Opened() {
        assertEquals(CloudBacklog(1_760, 18), estimateCloudBacklog(1_760, dailyCap = 100))
        assertEquals(CloudBacklog(0, 0), estimateCloudBacklog(0, dailyCap = 100))
        assertEquals(CloudBacklog(100, 1), estimateCloudBacklog(100, dailyCap = 100))
        assertEquals(CloudBacklog(101, 2), estimateCloudBacklog(101, dailyCap = 100))
    }
}
