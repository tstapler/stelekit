// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression coverage for [FilePathRootMigration.rerootFilePath] — the pure re-rooting logic
 * behind the fix for a confirmed silent-write-loss bug: [GraphManager.updateGraphPath] moves a
 * graph's registered path without rewriting already-stored `pages.file_path` values, causing
 * every path built fresh from the graph's current path (e.g. sidecar writes) to permanently
 * disagree with the stale DB-stored content paths.
 */
class FilePathRootMigrationTest {

    @Test
    fun `reroots a journal path onto the new graph root`() {
        val result = FilePathRootMigration.rerootFilePath("/stelekit/default/journals/2026_09_03.md", "/stelekit/notes")
        assertEquals("/stelekit/notes/journals/2026_09_03.md", result)
    }

    @Test
    fun `reroots a page path onto the new graph root`() {
        val result = FilePathRootMigration.rerootFilePath("/stelekit/default/pages/Sonar.md", "/stelekit/notes")
        assertEquals("/stelekit/notes/pages/Sonar.md", result)
    }

    @Test
    fun `reroots a nested namespace page path onto the new graph root`() {
        val result = FilePathRootMigration.rerootFilePath("/stelekit/default/pages/foo/Bar.md", "/stelekit/notes")
        assertEquals("/stelekit/notes/pages/foo/Bar.md", result)
    }

    @Test
    fun `is a no-op when the path already matches the current graph root`() {
        val result = FilePathRootMigration.rerootFilePath("/stelekit/notes/journals/2026_09_03.md", "/stelekit/notes")
        assertEquals("/stelekit/notes/journals/2026_09_03.md", result)
    }

    @Test
    fun `returns null for a path with neither a journals nor pages segment`() {
        val result = FilePathRootMigration.rerootFilePath("/stelekit/default/CLAUDE.md", "/stelekit/notes")
        assertNull(result)
    }
}
