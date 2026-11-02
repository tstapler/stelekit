package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.llm.AnthropicMessagesClient
import dev.stapler.stelekit.llm.LlmCredentialStore
import dev.stapler.stelekit.platform.image.ImageDownscalerPort
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/** In-memory credential backend; the gate reads the key through the real [LlmCredentialStore] over it. */
class FakeCredentialAccess : CredentialAccess {
    val values = mutableMapOf<String, String>()
    override fun retrieve(key: String): String? = values[key]
    override fun store(key: String, value: String) { values[key] = value }
    override fun delete(key: String) { values.remove(key) }
}

/** In-memory [Settings] whose whole contents a leak audit can read. */
class InspectableSettings : Settings {
    val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

const val TEST_KEY = "sk-ant-test-secret-key"

/** A Ktor mock engine that records every request and answers with [status] and [body]. */
class RecordingEngine(
    private val status: HttpStatusCode = HttpStatusCode.OK,
    private val body: String = """{"content":[{"type":"text","text":"A receipt from Acme"}]}""",
) {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { request ->
        requests += request
        respond(body, status, headersOf("Content-Type", "application/json"))
    }

    fun bodyText(index: Int = 0): String = (requests[index].body as TextContent).text
}

/** Everything a cloud test needs, built from the real gate over fake inputs. */
class CloudRig(
    status: HttpStatusCode = HttpStatusCode.OK,
    body: String = """{"content":[{"type":"text","text":"A receipt from Acme"}]}""",
    sessionCap: Int = 20,
    dailyCap: Int = 100,
    val withKey: Boolean = true,
    scaled: ByteArray? = null,
    today: () -> String = { "2026-11-01" },
) {
    val settings = InspectableSettings()
    val credentialAccess = FakeCredentialAccess().also { if (withKey) it.values["llm.anthropic.api_key"] = TEST_KEY }
    val credentials = LlmCredentialStore(credentialAccess)
    val gate = CloudConsentGate(settings, credentials)
    val http = RecordingEngine(status, body)
    val fs = FakeFileSystem()
    val ledger = EgressLedger(settings, sessionCap, dailyCap, session = arrow.atomic.AtomicInt(0), today = today)
    val client = AnthropicMessagesClient(HttpClient(http.engine), apiKey = { credentials.getApiKey(CloudConsentGate.PROVIDER) })
    val requestedEdges = mutableListOf<Int>()
    val downscaler = object : ImageDownscalerPort {
        override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? {
            requestedEdges += maxEdges
            return scaled?.let { s -> maxEdges.map { s } }
        }
    }
    val plugin = CloudClaudePlugin(
        config = CloudEnrichmentConfig(CloudProvider.CLAUDE, enabled = true),
        client = client,
        gate = gate,
        graphId = GRAPH_A,
        ledger = ledger,
        fileSystem = fs,
        downscaler = downscaler,
    )

    fun putImage(uuid: String, bytes: ByteArray = SMALL_JPEG, importedAtMs: Long = 2_000L, sizeBytes: Long = bytes.size.toLong()): AssetEntry {
        val path = "/g/assets/$uuid.jpg"
        fs.writeFileBytes(path, bytes)
        return cloudAsset(uuid, path, importedAtMs, sizeBytes)
    }

    companion object {
        const val GRAPH_A = "graph-a"
        const val GRAPH_B = "graph-b"
        val SMALL_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDA.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    }
}

fun cloudAsset(uuid: String, path: String, importedAtMs: Long = 2_000L, sizeBytes: Long = 1_000L) = AssetEntry(
    uuid = AssetUuid(uuid), filePath = path, relativePath = "../assets/$uuid.jpg",
    mediaType = AssetMediaType.IMAGE, subfolder = "", tags = emptyList(), autoLabels = emptyList(),
    ocrText = null, cloudDescription = null, pageUuids = emptyList(), sizeBytes = sizeBytes,
    importedAtMs = importedAtMs, mlProcessed = false, mlAttemptedAt = null, mlFailed = false,
    contentHash = null, isOrphan = false, mlTagsSource = "NONE",
)
