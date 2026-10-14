package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphPath
import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCacheMarkerTest {
    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
    }

    private val repoRoot = GraphPath.of("/data/graphs/abc")
    private val wikiRoot = GraphPath.wikiRoot("/data/graphs/abc", "logseq")

    @Test fun `marking one graph does not invalidate another`() {
        val settings = MapSettings()
        GraphCacheMarker.mark(settings, wikiRoot)
        GraphCacheMarker.mark(settings, repoRoot)
        // The regression: opening the repo root used to overwrite the wiki root's marker.
        assertTrue(GraphCacheMarker.isCached(settings, wikiRoot))
        assertTrue(GraphCacheMarker.isCached(settings, repoRoot))
    }

    @Test fun `unmarked graph is not cached`() {
        assertFalse(GraphCacheMarker.isCached(MapSettings(), wikiRoot))
    }

    @Test fun `legacy single-slot marker is honoured for its own graph only`() {
        val settings = MapSettings().apply { putString("cached_graph_path", "/data/graphs/abc/logseq/") }
        assertTrue(GraphCacheMarker.isCached(settings, wikiRoot))
        assertFalse(GraphCacheMarker.isCached(settings, repoRoot))
    }

    @Test fun `clear drops the marker and the matching legacy slot`() {
        val settings = MapSettings().apply { putString("cached_graph_path", wikiRoot.value) }
        GraphCacheMarker.mark(settings, wikiRoot)
        GraphCacheMarker.clear(settings, wikiRoot)
        assertFalse(GraphCacheMarker.isCached(settings, wikiRoot))
    }
}
