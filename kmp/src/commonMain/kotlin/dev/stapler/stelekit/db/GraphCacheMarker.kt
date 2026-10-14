package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.GraphPath
import dev.stapler.stelekit.platform.Settings

/**
 * Records which graphs already have a populated DB so a launch can load incrementally.
 *
 * Keyed per graph: the former single `cached_graph_path` slot was overwritten whenever a second
 * graph (e.g. a clone's repo root vs. its wiki subdirectory) was opened, so switching back and
 * forth wiped and re-imported the whole DB each time.
 */
internal object GraphCacheMarker {
    private const val LEGACY_KEY = "cached_graph_path"

    private fun key(path: GraphPath) = "$LEGACY_KEY:${path.value}"

    fun isCached(settings: Settings, path: GraphPath): Boolean {
        if (settings.getString(key(path), "") == path.value) return true
        val legacy = settings.getString(LEGACY_KEY, "")
        return legacy.isNotEmpty() && GraphPath.of(legacy) == path
    }

    fun mark(settings: Settings, path: GraphPath) {
        settings.putString(key(path), path.value)
    }

    fun clear(settings: Settings, path: GraphPath) {
        settings.putString(key(path), "")
        if (GraphPath.of(settings.getString(LEGACY_KEY, "")) == path) settings.putString(LEGACY_KEY, "")
    }
}
