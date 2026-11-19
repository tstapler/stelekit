// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.sketch.v2.bridge.DimensionExpectation

/**
 * The project's data a technical sheet edits beside its drawing (Story 4.2.5): the bundle that holds the named dimensions and
 * zones, and the dimensions its projection to v1 has to keep. Null on [HostServices] for a sheet that is not technical, which
 * then shows no data panel.
 */
interface TechnicalDataSource {
    /** The project's bundle as saved, or null when it cannot be read. */
    suspend fun load(): ProjectBundle?

    /** Saves [bundle] as one model write; false when it was refused or could not be written. */
    suspend fun save(bundle: ProjectBundle): Boolean

    /** The named dimensions the geometry has to keep, for the projection report; none when the sheet names none. */
    fun expectations(): List<DimensionExpectation> = emptyList()
}
