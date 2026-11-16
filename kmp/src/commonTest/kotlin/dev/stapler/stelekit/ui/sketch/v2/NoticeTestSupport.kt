// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.getOrElse
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.ChromeTable
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource

// Fixtures shared by the notice queue and onboarding tests (Story 3.6.7); no @Test methods.

/** An in-memory [Settings] whose raw keys a test can read back. */
internal class InMemorySettings : Settings {
    val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** The banner strings straight from the parsed chrome table, so a test never types a banner text. */
internal class ChromeBannerStrings(private val table: ChromeTable) : BannerStringSource {
    private fun row(id: BannerRowId) = table.banners.single { it.id == id.name }
    override fun summary(row: BannerRowId) = row(row).summary
    override fun shortTitle(row: BannerRowId) = row(row).shortTitle
    override fun primaryLabel(row: BannerRowId) = row(row).primaryLabel.orEmpty()
    override fun moreItems(row: BannerRowId) = row(row).moreItems

    fun candidate(row: BannerRowId): BannerCandidate = BannerCandidate.of(row, this).getOrElse { error("$row refused: $it") }
}
