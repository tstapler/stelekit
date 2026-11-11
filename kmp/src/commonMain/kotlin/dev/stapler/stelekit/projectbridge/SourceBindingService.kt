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

/** A bound source as listed to the owner; the title is resolved from the local database, never stored in the sidecar. */
data class BoundSourceRow(val kind: SourceBindingKind, val pageUuid: String, val title: String?)

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
) {
    suspend fun search(query: String): List<PickerPage> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        return pages.searchPages(q, RESULT_LIMIT, 0).first().getOrNull().orEmpty().map { PickerPage(it.uuid.value, it.name) }
    }

    suspend fun listBound(projectUuid: String): List<BoundSourceRow> {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project ?: return emptyList()
        return SourceBindings.of(project.settings).map { b ->
            BoundSourceRow(b.kind, b.pageUuid, pages.getPageByUuid(PageUuid(b.pageUuid)).first().getOrNull()?.name)
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    suspend fun bind(projectUuid: String, kind: SourceBindingKind, pageUuid: String): BindOutcome {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?: return BindOutcome.ProjectNotFound
        val page = pages.getPageByUuid(PageUuid(pageUuid)).first().getOrNull() ?: return BindOutcome.PageNotFound
        val text = page.filePath?.takeIf { page.isContentLoaded }?.let { readText(it) } ?: return BindOutcome.NotReadable
        val settings = SourceBindings.with(project.settings, SourceBinding(kind, pageUuid, ContentHasher.sha256(text)))
            ?: return BindOutcome.LimitReached
        return write { repositories.projects.saveProject(project.copy(settings = settings)) }
            .fold({ BindOutcome.Failed("Could not save the binding") }, { BindOutcome.Bound })
    }

    @OptIn(DirectRepositoryWrite::class)
    suspend fun unbind(projectUuid: String, kind: SourceBindingKind, pageUuid: String): BindOutcome {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?: return BindOutcome.ProjectNotFound
        val settings = SourceBindings.without(project.settings, kind, pageUuid)
        return write { repositories.projects.saveProject(project.copy(settings = settings)) }
            .fold({ BindOutcome.Failed("Could not save the binding") }, { BindOutcome.Bound })
    }

    companion object {
        const val MIN_QUERY = 2
        const val RESULT_LIMIT = 20
    }
}

/** The first project's bound pages, read through [reader]; empty when there is no project or nothing is bound. */
suspend fun readBoundSources(repositories: SketchRepositories, reader: ProjectSourceReader): SourceReadResult {
    val project = repositories.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull() ?: return SourceReadResult.EMPTY
    val bindings = SourceBindings.of(project.settings)
    return if (bindings.isEmpty()) SourceReadResult.EMPTY else reader.read(bindings)
}
