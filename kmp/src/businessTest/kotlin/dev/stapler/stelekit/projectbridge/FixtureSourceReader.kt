// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFixtureText

/**
 * Serves the frozen closet fixtures to the collector in place of the production reader (Stories 5.9b-d).
 * Each text can be overridden so seeded mutations apply to the loaded text, never to the files on disk.
 */
internal class FixtureSourceReader(
    private val tables: String = ClosetFixtureText.load("closet-tables.md"),
    private val yaml: String = ClosetFixtureText.load("closet.yaml"),
    private val elevation: String = ClosetFixtureText.load("closet-elevation.svg"),
    private val section: String = ClosetFixtureText.load("closet-section.svg"),
) : ProjectSourceReader {
    override suspend fun read() = ProjectSourceTexts(
        pages = listOf(TablePageText("closet-tables", tables)),
        yamlSnapshot = yaml,
        sheets = listOf(SheetSvg("elevation", "closet-elevation", elevation), SheetSvg("section", "closet-section", section)),
        boundPages = listOf(BoundPageRef("closet-tables", "pages/closet-tables.md")),
    )
}
