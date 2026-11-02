package dev.stapler.stelekit.asset.pipeline

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Request building, EXIF removal, key placement and error mapping of the cloud plugin, over a mock engine. */
class CloudClaudePluginTest {
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun geotaggedJpeg(): ByteArray {
        val jfif = segment(0xE0, "JFIF".encodeToByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
        val exif = segment(0xE1, "Exif".encodeToByteArray() + byteArrayOf(0, 0) + "GPSLatitude 48.8584 N".encodeToByteArray())
        val scan = segment(0xDA, byteArrayOf(0)) + byteArrayOf(9, 8, 7, 6, 5) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + jfif + exif + scan
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean =
        haystack.decodeToString().contains(needle)

    @OptIn(ExperimentalEncodingApi::class)
    private fun uploadedImage(body: String): ByteArray {
        val block = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray[0]
            .jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("image", block["type"]!!.jsonPrimitive.content)
        return Base64.decode(block["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    private fun granted(rig: CloudRig) = rig.gate.grant(CloudRig.GRAPH_A, atMs = 1_000L, scope = CloudBackfillScope.NewOnly)

    @Test
    fun cloudClaudePlugin_should_SendDownscaledImageWithNoExifAndKeyInHeaderOnly_When_12MpGeotaggedJpeg() = runTest {
        val rig = CloudRig(scaled = geotaggedJpeg())
        granted(rig)
        val asset = rig.putImage("a1", bytes = ByteArray(5_000_000) { 3 })

        val result = rig.plugin.processAsset(asset)

        assertIs<AssetPipelineResult.CloudDescription>(result.getOrNull())
        assertEquals(listOf(CloudEnrichmentConfig.CLOUD_MAX_EDGE_PX), rig.requestedEdges)
        assertEquals(1, rig.http.requests.size)
        val request = rig.http.requests.single()
        assertEquals(TEST_KEY, request.headers["x-api-key"])
        assertFalse(request.url.toString().contains(TEST_KEY), "key must not be in the URL")
        val body = rig.http.bodyText()
        assertFalse(body.contains(TEST_KEY), "key must not be in the body")

        val sent = uploadedImage(body)
        assertFalse(containsAscii(sent, "Exif"), "Exif APP1 payload survived")
        assertFalse(containsAscii(sent, "GPSLatitude"), "GPS data survived")
        assertTrue(sent.indices.none { it + 1 < sent.size && sent[it] == 0xFF.toByte() && sent[it + 1] == 0xE1.toByte() }, "APP1 marker survived")
        assertTrue(containsAscii(sent, "JFIF"), "the JFIF header is not metadata and stays")
    }

    @Test
    fun cloudClaudePlugin_should_MarkAssetFailedWithTypedErrorAndNotRetry_When_429Or5xx() = runTest {
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            val rig = CloudRig(status = status)
            granted(rig)
            val asset = rig.putImage("a1")

            val error = rig.plugin.processAsset(asset).leftOrNull()
            assertIs<DomainError.NetworkError.HttpError>(error)
            assertEquals(status.value, error.statusCode)
            assertEquals(1, rig.http.requests.size, "no automatic retry for $status")

            val repo = InMemoryAssetRepository()
            repo.saveAsset(asset)
            val registry = PluginRegistry().also { it.register(rig.plugin) }
            val service = AssetPipelineService(registry, repo, DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository()))
            assertEquals(AssetPipelineService.Outcome.Failed, service.processAsset(asset))
            assertEquals(AssetPipelineService.Outcome.Stayed, service.processAsset(asset), "a failed asset is not retried in the same run")
            assertEquals(2, rig.http.requests.size, "one request per attempt, none from the second call")
        }
    }

    @Test
    fun cloudClaudePlugin_should_ParseDescriptionIntoCloudDescription_When_MockResponseReceipt() = runTest {
        val rig = CloudRig()
        granted(rig)

        val result = rig.plugin.processAsset(rig.putImage("a1"))

        assertEquals(AssetPipelineResult.CloudDescription("A receipt from Acme", emptyList()), result.getOrNull())
    }
}
