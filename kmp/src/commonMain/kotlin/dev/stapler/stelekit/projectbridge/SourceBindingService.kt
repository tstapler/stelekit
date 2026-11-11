// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.first

/** A search hit; [name] is the page title shown on this device only. */
data class PickerPage(val uuid: String, val name: String)

/** Display text for the picker, kept out of the composable so it is unit-tested. */
object SourcePickerText {
    const val MISSING_TITLE = "Missing page"

    fun kindLabel(kind: SourceBindingKind): String = when (kind) {
        SourceBindingKind.TABLE -> "table"
        SourceBindingKind.PROSE -> "notes"
    }

    const val REBIND_HINT = "Needs re-binding"

    fun boundLabel(row: BoundSourceRow): String =
        if (row.needsRebind) "$REBIND_HINT (${kindLabel(row.kind)}): pick the page again"
        else "${row.title ?: MISSING_TITLE} (${kindLabel(row.kind)})"
}

/** A bound source as listed to the owner; the title is resolved from the local database, never stored in the sidecar. */
data class BoundSourceRow(val kind: SourceBindingKind, val bindingId: String, val title: String?, val needsRebind: Boolean = false)

sealed interface BindOutcome {
    data object Bound : BindOutcome
    data object PageNotFound : BindOutcome
    data object NotReadable : BindOutcome
    data object LimitReached : BindOutcome
    data object ProjectNotFound : BindOutcome
    data class Failed(val message: String) : BindOutcome
}

/**
 * Chooses and records the wiki pages a project reads (Story 5.9b). Searches are bounded; a binding stores the page uuid and
 * a content hash only. Never writes a user page.
 */
class SourceBindingService(
    private val repositories: SketchRepositories,
    private val pages: PageRepository,
    private val readText: suspend (filePath: String) -> String?,
    private val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
    private val table: LocalBindingTable,
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    suspend fun search(query: String): List<PickerPage> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        return pages.searchPages(q, RESULT_LIMIT, 0).first().getOrNull().orEmpty().map { PickerPage(it.uuid.value, it.name) }
    }

    suspend fun listBound(projectUuid: String): List<BoundSourceRow> {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project ?: return emptyList()
        return resolutions(project.slug, project.settings).map { r ->
            when (r) {
                is BindingResolution.NeedsRebind -> BoundSourceRow(r.binding.kind, r.binding.bindingId, null, needsRebind = true)
                is BindingResolution.Resolved ->
                    BoundSourceRow(r.binding.kind, r.binding.bindingId, pages.getPageByUuid(PageUuid(r.pageUuid)).first().getOrNull()?.name)
            }
        }
    }

    /** Binds [pageUuid]; if a binding of the same kind is waiting to be re-bound on this device, it takes that one over. */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun bind(projectUuid: String, kind: SourceBindingKind, pageUuid: String): BindOutcome {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?: return BindOutcome.ProjectNotFound
        val page = pages.getPageByUuid(PageUuid(pageUuid)).first().getOrNull() ?: return BindOutcome.PageNotFound
        val text = page.filePath?.takeIf { page.isContentLoaded }?.let { readText(it) } ?: return BindOutcome.NotReadable
        val existing = resolutions(project.slug, project.settings).filter { it.binding.kind == kind }
        val reusable = existing.firstOrNull { it is BindingResolution.Resolved && it.pageUuid == pageUuid }
            ?: existing.firstOrNull { it is BindingResolution.NeedsRebind }
        val id = reusable?.binding?.bindingId ?: BindingIds.generate(random)
        val settings = SourceBindings.with(project.settings, SourceBinding(kind, id, ContentHasher.sha256(text)))
            ?: return BindOutcome.LimitReached
        table.put(project.slug, id, pageUuid)
        return write { repositories.projects.saveProject(project.copy(settings = settings)) }
            .fold({ BindOutcome.Failed("Could not save the binding") }, { BindOutcome.Bound })
    }

    @OptIn(DirectRepositoryWrite::class)
    suspend fun unbind(projectUuid: String, bindingId: String): BindOutcome {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?: return BindOutcome.ProjectNotFound
        table.remove(project.slug, bindingId)
        val settings = SourceBindings.without(project.settings, bindingId)
        return write { repositories.projects.saveProject(project.copy(settings = settings)) }
            .fold({ BindOutcome.Failed("Could not save the binding") }, { BindOutcome.Bound })
    }

    /**
     * Re-resolves unresolved bindings against [candidates] (at most [RESULT_LIMIT] search hits) by content hash, on this device
     * only. Returns how many were re-bound; the synced sidecar is not touched.
     */
    suspend fun autoRebind(projectUuid: String, candidates: List<PickerPage>): Int {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project ?: return 0
        val unresolved = resolutions(project.slug, project.settings).filterIsInstance<BindingResolution.NeedsRebind>().map { it.binding }
        if (unresolved.isEmpty() || candidates.isEmpty()) return 0
        val hashes = LinkedHashMap<String, String>()
        for (c in candidates.take(RESULT_LIMIT)) {
            val page = pages.getPageByUuid(PageUuid(c.uuid)).first().getOrNull() ?: continue
            val text = page.filePath?.takeIf { page.isContentLoaded }?.let { readText(it) } ?: continue
            hashes[c.uuid] = ContentHasher.sha256(text)
        }
        val matches = BindingResolver.matchByHash(unresolved, hashes)
        matches.forEach { (bindingId, pageUuid) -> table.put(project.slug, bindingId, pageUuid) }
        return matches.size
    }

    private fun resolutions(slug: String, settings: kotlinx.serialization.json.JsonObject) =
        BindingResolver.resolve(SourceBindings.of(settings)) { table.pageUuid(slug, it) }

    companion object {
        const val MIN_QUERY = 2
        const val RESULT_LIMIT = 20
    }
}

/** The first project's bound pages, read through [reader]; empty when there is no project or nothing is bound. */
suspend fun readBoundSources(repositories: SketchRepositories, reader: ProjectSourceReader, table: LocalBindingTable): SourceReadResult {
    val project = repositories.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull() ?: return SourceReadResult.EMPTY
    val bindings = SourceBindings.of(project.settings)
    return if (bindings.isEmpty()) SourceReadResult.EMPTY
    else reader.read(BindingResolver.resolve(bindings) { table.pageUuid(project.slug, it) })
}
