package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphInfoSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `old GraphInfo JSON without new fields deserializes with safe defaults`() {
        val oldJson = """{"id":"abc123","path":"/home/user/notes","displayName":"notes","addedAt":1234567890,"isParanoidMode":false}"""
        val info = json.decodeFromString<GraphInfo>(oldJson)
        assertNull(info.detectedRepoRoot)
        assertNull(info.detectedWikiSubdir)
        assertFalse(info.gitDetectionDismissed)
    }

    @Test
    fun `GraphInfo round-trips with new fields`() {
        val original = GraphInfo(
            id = GraphId("abc123"),
            path = "/home/user/notes",
            displayName = "notes",
            addedAt = 1234567890L,
            isParanoidMode = false,
            detectedRepoRoot = "/home/user",
            detectedWikiSubdir = "notes",
            gitDetectionDismissed = false,
        )
        val encoded = json.encodeToString(GraphInfo.serializer(), original)
        val decoded = json.decodeFromString<GraphInfo>(encoded)
        assertEquals(original, decoded)
    }

    // T-8
    @Test
    fun `GraphInfo without isDemo field deserializes with isDemo=false default`() {
        val oldJson = """{"id":"abc123","path":"/home/user/notes","displayName":"notes","addedAt":1234567890}"""
        val info = json.decodeFromString<GraphInfo>(oldJson)
        assertFalse(info.isDemo, "isDemo must default to false when the field is absent from JSON")
    }

    // T-9
    @Test
    fun `GraphInfo with isDemo=false omits field from JSON`() {
        val graphInfo = GraphInfo(
            id = GraphId("abc123"),
            path = "/home/user/notes",
            displayName = "notes",
            addedAt = 1234567890L,
            isDemo = false,
        )
        val encoded = json.encodeToString(GraphInfo.serializer(), graphInfo)
        assertFalse(
            encoded.contains("isDemo"),
            "isDemo=false must be omitted from JSON (encodeDefaults is not set): $encoded",
        )
    }

    // T-10
    @Test
    fun `GraphInfo with isDemo=true serializes and round-trips`() {
        val original = GraphInfo(
            id = GraphId("__demo__"),
            path = "/demo",
            displayName = "Demo Graph",
            addedAt = 1000L,
            isDemo = true,
        )
        val encoded = json.encodeToString(GraphInfo.serializer(), original)
        assertTrue(
            encoded.contains("\"isDemo\":true"),
            "isDemo=true must appear in JSON output: $encoded",
        )
        val decoded = json.decodeFromString<GraphInfo>(encoded)
        assertEquals(original, decoded, "Round-tripped GraphInfo with isDemo=true must equal original")
    }
}
