// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.projectbridge.ProjectSlugs
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchProjectRepository
import dev.stapler.stelekit.sketch.export.SketchExporter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.random.Random

private const val STANDALONE_KEY = "standalone"

/** True for a sketch started without a project; the project list leaves these out and shows them as sketches. */
val SketchProject.isStandalone: Boolean get() = settings[STANDALONE_KEY]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true

/** The markdown a journal block holds for a sketch: an image of the exported `preview.svg`, which plain Logseq also renders. */
object SketchEmbed {
    private val reference = Regex("assets/stelekit-sketch/(proj-[0-9a-f]{6})/preview\\.svg")

    fun markdown(slug: String): String = "![sketch](../assets/stelekit-sketch/$slug/preview.svg)"

    /** The opaque project slug an image reference points at, or null when it is any other image. */
    fun slugOf(imageRef: String): String? = reference.find(imageRef)?.groupValues?.get(1)
}

/**
 * A sketch ready to edit. [embedMarkdown] is null when this platform cannot write the preview file (wasmJs, iOS), so
 * nothing is inserted into a page that would show a broken image.
 */
data class NewSketch(val projectUuid: String, val slug: String, val embedMarkdown: String?)

/**
 * Starts a sketch with no ceremony: a hidden one-sheet project (so the editor and sidecar need no new storage) plus a
 * blank `preview.svg` for a journal embed. The owner can group it into a project later. Writes go through [write].
 */
class StandaloneSketchService(
    private val projects: SketchProjectRepository,
    private val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
    private val exporter: SketchExporter?,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    @OptIn(DirectRepositoryWrite::class)
    suspend fun create(): Either<DomainError, NewSketch> = either {
        var created: SketchProject? = null
        var failure: DomainError? = null
        repeat(MAX_SLUG_ATTEMPTS) {
            if (created != null) return@repeat
            val project = ProjectFactory.create(
                DEFAULT_NAME, ProjectSlugs.generate(random), ProjectFactory.newUuid(), clock(),
            ).copy(settings = JsonObject(mapOf(STANDALONE_KEY to JsonPrimitive(true))))
            write { projects.saveProject(project) }.fold({ failure = it }, { created = project })
        }
        val project = created ?: raise(failure ?: DomainError.DatabaseError.WriteFailed("no free project slug"))
        val preview = exporter?.exportPreview(SketchDocument(), project.slug)
        NewSketch(project.uuid.value, project.slug, SketchEmbed.markdown(project.slug).takeIf { preview?.isRight() == true })
    }

    private companion object {
        const val DEFAULT_NAME = "Sketch"
        const val MAX_SLUG_ATTEMPTS = 3
    }
}

/** The project behind an embed's [slug]; scans in bounded pages and ends when a page comes back short. */
suspend fun SketchProjectRepository.findUuidBySlug(slug: String): ProjectUuid? {
    var offset = 0
    while (true) {
        val page = getProjects(SLUG_SCAN_PAGE, offset).first().getOrNull().orEmpty()
        page.firstOrNull { it.slug == slug }?.let { return it.uuid }
        if (page.size < SLUG_SCAN_PAGE) return null
        offset += page.size
    }
}

private const val SLUG_SCAN_PAGE = 200
