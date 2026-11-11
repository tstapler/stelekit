package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.LoadedSketchProject
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchSheetRepository
import dev.stapler.stelekit.units.Inches
import kotlinx.coroutines.flow.first
import dev.stapler.stelekit.model.SketchPrimitive as StoredPrimitive

/** The editor's repository access: bounded reads, and writes serialised through the actor when there is one. */
internal class SketchStore(private val repos: SketchRepositories, private val writeActor: DatabaseWriteActor?) {

    suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> =
        writeActor?.execute { op() } ?: op()

    suspend fun readAllPrimitives(sheetUuid: SheetUuid): Either<DomainError, List<StoredPrimitive>> = either {
        val all = mutableListOf<StoredPrimitive>()
        var offset = 0
        while (true) {
            val page = repos.sheets.getPrimitives(sheetUuid, SketchSheetRepository.MAX_PRIMITIVE_PAGE, offset).first().bind()
            all += page
            if (page.size < SketchSheetRepository.MAX_PRIMITIVE_PAGE) break
            offset += page.size
        }
        all
    }

    /** The first project, or a new blank one when the graph has none. */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun findOrCreateProject(newId: () -> String): Either<DomainError, ProjectUuid> = either {
        val existing = repos.projects.getProjects(1, 0).first().bind().firstOrNull()
        if (existing != null) return@either existing.uuid
        val id = newId()
        val created = SketchProject(ProjectUuid(id), "proj-${id.filter { it.isLetterOrDigit() }.takeLast(SLUG_SUFFIX)}", "Untitled sketch", 0)
        write { repos.projects.saveProject(created) }.bind()
        created.uuid
    }

    /** Loads the project and its first sheet, creating a blank plan sheet when it has none. */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun loadSheet(uuid: ProjectUuid, newId: () -> String): Either<DomainError, Pair<LoadedSketchProject, SketchSheet>> = either {
        val loaded = repos.projects.loadProject(uuid).bind() ?: raise(DomainError.DatabaseError.ReadFailed("project not found"))
        val first = repos.sheets.getSheets(uuid, 1).first().bind().items.firstOrNull() ?: blankSheet(uuid, loaded.token, newId).bind()
        loaded to first
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun blankSheet(uuid: ProjectUuid, token: RevisionToken, newId: () -> String): Either<DomainError, SketchSheet> = either {
        val blank = SketchSheet(
            uuid = SheetUuid(newId()), projectUuid = uuid, kind = "plan", viewDirection = "top", unit = "in",
            scaleSixteenthsPerUnit = Inches.SIXTEENTHS_PER_INCH,
        )
        write { repos.sheets.saveSheet(blank, token) }.bind()
        blank
    }

    private companion object {
        const val SLUG_SUFFIX = 6
    }
}
