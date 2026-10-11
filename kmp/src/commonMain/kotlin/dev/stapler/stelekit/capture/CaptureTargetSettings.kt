// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.Settings

/** Where a capture or share lands. [NamedGraph] is resolved without activating that graph. */
sealed interface CaptureTarget {
    data object ActiveGraph : CaptureTarget
    data class NamedGraph(val graphId: GraphId) : CaptureTarget
}

/**
 * Persisted capture-destination preferences, stored as strings only (the `Settings` backends
 * differ on boolean support). A blank string means unset.
 *
 * The default graph is used by sharing and quick capture on Android and Desktop. A per-capture
 * override goes through [recordLastUsed] and never touches [defaultGraphId].
 */
class CaptureTargetSettings(private val platformSettings: Settings) {

    var defaultGraphId: GraphId?
        get() = read(KEY_DEFAULT_GRAPH_ID)
        set(value) = platformSettings.putString(KEY_DEFAULT_GRAPH_ID, value?.value.orEmpty())

    val lastGraphId: GraphId?
        get() = read(KEY_LAST_GRAPH_ID)

    var rememberLast: Boolean
        get() = platformSettings.getString(KEY_REMEMBER_LAST, "false") == "true"
        set(value) = platformSettings.putString(KEY_REMEMBER_LAST, value.toString())

    /** Records an explicit per-capture choice; the default is deliberately left alone. */
    fun recordLastUsed(graphId: GraphId) {
        platformSettings.putString(KEY_LAST_GRAPH_ID, graphId.value)
    }

    private fun read(key: String): GraphId? =
        platformSettings.getString(key, "").takeIf { it.isNotBlank() }?.let(::GraphId)

    companion object {
        const val KEY_DEFAULT_GRAPH_ID = "capture_default_graph_id"
        const val KEY_LAST_GRAPH_ID = "capture_last_graph_id"
        const val KEY_REMEMBER_LAST = "capture_remember_last"
    }
}
