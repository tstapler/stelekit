// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.guide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.sketch.v2.PlaceRect
import dev.stapler.stelekit.ui.sketch.v2.TabStop

/** One stop of a tour: [anchor] is the region it points at (null centres the popover on the editor). */
data class TourStep(val id: String, val anchor: TabStop?, val title: String, val body: String)

/**
 * A tour of one feature or of the interface. It starts on its own at most once per device, when a feature named in
 * [triggers] is first used; the user can replay it from Help whenever they like.
 */
data class Tour(val id: String, val title: String, val steps: List<TourStep>, val triggers: Set<String> = emptySet()) {
    init {
        require(steps.isNotEmpty()) { "a tour needs at least one step" }
    }
}

class TourRegistry(val all: List<Tour>) {
    init {
        require(all.map { it.id }.toSet().size == all.size) { "tour ids must be unique" }
    }

    fun byId(id: String): Tour? = all.firstOrNull { it.id == id }

    fun forTrigger(featureId: String): List<Tour> = all.filter { featureId in it.triggers }
}

/**
 * What the user has already been shown, per device: one `Settings` key per tour, so the stored set is bounded by the registry.
 * "Don't show tips" stops every automatic start but leaves Replay working.
 */
class TourProgress(private val settings: Settings) {
    fun isSeen(tourId: String): Boolean = settings.getBoolean("$SEEN_PREFIX$tourId", false)

    fun markSeen(tourId: String) = settings.putBoolean("$SEEN_PREFIX$tourId", true)

    /** True when the user chose to see no automatic tours. */
    var autoToursOff: Boolean
        get() = settings.getBoolean(AUTO_OFF_KEY, false)
        set(value) = settings.putBoolean(AUTO_OFF_KEY, value)

    companion object {
        const val SEEN_PREFIX = "sketch.tour.seen."
        const val AUTO_OFF_KEY = "sketch.tour.autoOff"
    }
}

/**
 * The tour on screen. [onFeatureFirstUse] is the only automatic entry; it starts the first matching tour not yet seen, and a
 * tour counts as seen the moment it starts, so quitting half way never brings it back. Not thread-safe: the editor drives it
 * from the main thread.
 */
class TourController(private val registry: TourRegistry, private val progress: TourProgress) {
    var active: Tour? by mutableStateOf(null)
        private set

    var stepIndex: Int by mutableStateOf(0)
        private set

    /** Where each region sits in root pixels; reported by the shell's layout. */
    val anchors = mutableStateMapOf<TabStop, PlaceRect>()

    val step: TourStep? get() = active?.steps?.getOrNull(stepIndex)

    val isLast: Boolean get() = active?.let { stepIndex >= it.steps.lastIndex } ?: false

    /** The tours Help lists, in registry order. */
    val tours: List<Tour> get() = registry.all

    /** The user used [featureId]; starts its tour if it has one they have not seen. */
    fun onFeatureFirstUse(featureId: String): Boolean {
        if (active != null || progress.autoToursOff) return false
        val tour = registry.forTrigger(featureId).firstOrNull { !progress.isSeen(it.id) } ?: return false
        begin(tour)
        return true
    }

    /** Starts [tourId] whether or not it was seen (Help's Replay); false for an unknown id. */
    fun replay(tourId: String): Boolean {
        val tour = registry.byId(tourId) ?: return false
        begin(tour)
        return true
    }

    /** The next step; ends the tour after the last one. */
    fun next() {
        if (isLast) finish() else stepIndex += 1
    }

    fun back() {
        if (stepIndex > 0) stepIndex -= 1
    }

    /** Done, Skip or Esc: ends this tour; it stays seen. */
    fun finish() {
        active = null
        stepIndex = 0
    }

    /** "Don't show tips": ends this tour and stops every automatic one. */
    fun skipAll() {
        progress.autoToursOff = true
        finish()
    }

    /** Turns automatic tours back on (Help's switch). */
    fun setAutoTours(enabled: Boolean) {
        progress.autoToursOff = !enabled
    }

    val autoTours: Boolean get() = !progress.autoToursOff

    private fun begin(tour: Tour) {
        progress.markSeen(tour.id)
        stepIndex = 0
        active = tour
    }
}
