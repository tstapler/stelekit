// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchSearchHit
import dev.stapler.stelekit.repository.SketchSearchRow
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.search.SketchTextExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Global search over sketch text (E8). The sheet saver keeps the index current for sheets edited in the app; [backfill]
 * catches the rest (sheets written by the CLI, pulled by git, or saved before the index existed).
 */
@OptIn(DirectRepositoryWrite::class)
class SketchTextSearch(
    private val repos: SketchRepositories,
    private val store: () -> ProjectSidecarStore?,
    private val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
    private val clock: () -> Long,
) {
    private val once = Mutex()
    private var backfilled = false

    /** At most [limit] sketches whose text contains every word of [query]; a failure reads as no results. */
    suspend fun search(query: String, limit: Int = RESULT_LIMIT): List<SketchSearchHit> =
        repos.searchText.search(query, limit).getOrNull().orEmpty()

    /**
     * Indexes every v2 sheet whose stored hash differs from its file's text, project by project in bounded pages
     * (never a whole-graph list), yielding between projects. Returns how many rows it wrote. Runs once per instance (once per graph open):
     * sheets saved in the app are indexed by the saver, and a sheet pulled in while the app runs is caught on the next open.
     */
    suspend fun backfill(): Int {
        val sidecar = store() ?: return 0
        return once.withLock {
            if (backfilled) 0 else withContext(PlatformDispatcher.IO) { backfillAll(sidecar) }.also { backfilled = true }
        }
    }

    private suspend fun backfillAll(sidecar: ProjectSidecarStore): Int {
        var written = 0
        var offset = 0
        while (true) {
            val page = repos.projects.getProjects(PROJECT_PAGE, offset).first().getOrNull().orEmpty()
            for (project in page) {
                written += backfillProject(project, sidecar)
                yield()
            }
            if (page.size < PROJECT_PAGE) return written
            offset += page.size
        }
    }

    private suspend fun backfillProject(project: SketchProject, sidecar: ProjectSidecarStore): Int {
        val sheets = repos.sheets.getSheets(project.uuid).first().getOrNull()?.items.orEmpty()
            .filter { it.schemaVersion == SheetPaths.SCHEMA_V2 }
        val stored = repos.searchText.hashesOf(project.uuid.value).getOrNull().orEmpty()
        val live = sheets.mapTo(HashSet()) { it.uuid.value }
        for (gone in stored.keys - live) write { repos.searchText.deleteSheet(gone) }
        var written = 0
        for (sheet in sheets) {
            val bytes = sidecar.readV2SheetFile(project.slug, sheet.uuid.value) ?: continue
            val doc = SheetCodecV2.decode(bytes).getOrNull() ?: continue
            val text = SketchTextExtractor.extract(doc)
            if (stored[sheet.uuid.value] == text.hash) continue
            val row = SketchSearchRow(project.uuid.value, sheet.uuid.value, text.body, text.hash, clock())
            if (write { repos.searchText.save(row) }.isRight()) written++
        }
        return written
    }

    companion object {
        const val RESULT_LIMIT = 5
        private const val PROJECT_PAGE = 50
    }
}
