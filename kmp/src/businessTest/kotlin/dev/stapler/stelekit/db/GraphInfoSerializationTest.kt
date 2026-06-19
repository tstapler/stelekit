package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse

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
            id = "abc123",
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
}
