// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.platform.Settings

/**
 * The one reader/writer of the home-project-sketching settings: the global `homeSketch` flag
 * (default off) and the per-graph digest opt-ins. Everything the bridge does is inert unless
 * [isDigestActive] holds for the graph.
 */
class ProjectBridgeSettings(private val settings: Settings) {

    /** Master flag. Off means no hook, no digest write and no UI entry anywhere in the bridge. */
    var homeSketchEnabled: Boolean
        get() = settings.getBoolean(HOME_SKETCH_KEY, false)
        set(value) = settings.putBoolean(HOME_SKETCH_KEY, value)

    fun digestEnabled(graphId: String): Boolean = settings.getBoolean(digestEnabledKey(graphId), false)

    fun setDigestEnabled(graphId: String, enabled: Boolean) = settings.putBoolean(digestEnabledKey(graphId), enabled)

    /** Owner-chosen title, or `<displayName> Digest` until one is stored. */
    fun digestPageTitle(graphId: String, displayName: String): String {
        val stored = settings.getString(digestTitleKey(graphId), "")
        return stored.ifBlank { defaultDigestTitle(displayName) }
    }

    fun setDigestPageTitle(graphId: String, title: String) = settings.putString(digestTitleKey(graphId), title)

    fun analyseAfterSync(graphId: String): Boolean = settings.getBoolean(analyseKey(graphId), false)

    fun setAnalyseAfterSync(graphId: String, enabled: Boolean) = settings.putBoolean(analyseKey(graphId), enabled)

    /** True only when both the master flag and this graph's digest opt-in are on. */
    fun isDigestActive(graphId: String): Boolean = homeSketchEnabled && digestEnabled(graphId)

    companion object {
        const val HOME_SKETCH_KEY = "home_sketch_enabled"

        fun defaultDigestTitle(displayName: String): String = "$displayName Digest"

        private fun digestEnabledKey(graphId: String) = "home_sketch_digest_enabled_$graphId"
        private fun digestTitleKey(graphId: String) = "home_sketch_digest_title_$graphId"
        private fun analyseKey(graphId: String) = "home_sketch_analyse_after_sync_$graphId"
    }
}
