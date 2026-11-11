// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketchimport.DrawingPaths
import dev.stapler.stelekit.sketchimport.PngTagSources
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SheetKind(val wire: String) {
    SVG("svg"),
    PNG("png");

    companion object {
        fun fromWire(s: String?): SheetKind? = entries.firstOrNull { it.wire == s }

        /** The drawing kind a picked file name denotes, or null for a type the importer does not take. */
        fun fromFileName(name: String): SheetKind? = fromWire(name.substringAfterLast('.', "").lowercase())
    }
}

/** An attached drawing as stored in the synced `sources[]`: its kind and content hash only, never a name or path. */
data class SheetSource(val kind: SheetKind, val contentHash: String) {
    val hash8: String get() = contentHash.take(HASH8)

    private companion object {
        const val HASH8 = 8
    }
}

/** Reads and rewrites the `svg` / `png` entries of a project's `settings["sources"]`, leaving page and yaml entries alone. */
object SheetSources {
    const val MAX_SHEETS = 50
    private val hashShape = Regex("[0-9a-f]{64}")

    fun isValidHash(hash: String) = hashShape.matches(hash)

    fun of(settings: JsonObject): List<SheetSource> = entries(settings).mapNotNull(::decode)

    /** Adds the source unless it is already attached; null once [MAX_SHEETS] would be exceeded. */
    fun with(settings: JsonObject, source: SheetSource): JsonObject? {
        val current = of(settings)
        if (current.any { it.contentHash == source.contentHash }) return settings
        if (current.size + 1 > MAX_SHEETS) return null
        return replace(settings, current + source)
    }

    fun without(settings: JsonObject, contentHash: String): JsonObject =
        replace(settings, of(settings).filterNot { it.contentHash == contentHash })

    fun encode(s: SheetSource): JsonObject =
        JsonObject(mapOf("kind" to JsonPrimitive(s.kind.wire), "contentHash" to JsonPrimitive(s.contentHash)))

    private fun replace(settings: JsonObject, sheets: List<SheetSource>): JsonObject {
        val others = entries(settings).filter { decode(it) == null }
        val all = others + sheets.sortedWith(compareBy({ it.kind.wire }, { it.contentHash })).map(::encode)
        val rest = settings - SourceBindings.KEY
        return if (all.isEmpty()) JsonObject(rest) else JsonObject(rest + (SourceBindings.KEY to JsonArray(all)))
    }

    private fun entries(settings: JsonObject): List<JsonElement> = (settings[SourceBindings.KEY] as? JsonArray)?.toList().orEmpty()

    private fun decode(e: JsonElement): SheetSource? {
        val o = e as? JsonObject ?: return null
        val kind = SheetKind.fromWire(o["kind"]?.jsonPrimitive?.contentOrNull) ?: return null
        val hash = o["contentHash"]?.jsonPrimitive?.contentOrNull?.takeIf(::isValidHash) ?: return null
        return SheetSource(kind, hash)
    }
}

/** The service over this graph's drawing folder; the sheet files are found again by slug and hash. */
fun attachedSheetService(
    repositories: SketchRepositories,
    fileSystem: FileSystem,
    notesPath: EffectiveNotesPath,
    write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
): AttachedSheetService = AttachedSheetService(
    repositories,
    write,
    { slug, hash, kind -> DrawingPaths.file(notesPath.child("assets"), slug, hash, kind.wire) },
    fileSystem::readFileBytes,
)

/** The attached SVG sheets for the post-sync collector; empty when the graph has no notes folder (demo graph). */
suspend fun readAttachedSvgSheets(
    repositories: SketchRepositories,
    fileSystem: FileSystem,
    notesPath: EffectiveNotesPath?,
    write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
): List<SheetSvg> = notesPath?.let { attachedSheetService(repositories, fileSystem, it, write).readSvgSheets() }.orEmpty()

/**
 * Every attached sheet: the `svg` / `png` entries plus the PNG sheets known only by their `png-calibration` record
 * (Story 4.5b), so a calibrated PNG is one sheet, not two. A calibration record wins over a bare `png` entry.
 */
fun attachedSources(settings: JsonObject): List<SheetSource> {
    val calibrated = PngTagSources.of(settings).map { SheetSource(SheetKind.PNG, it.contentHash) }
    val calibratedHashes = calibrated.mapTo(HashSet()) { it.contentHash }
    return SheetSources.of(settings).filterNot { it.contentHash in calibratedHashes } + calibrated
}

/** Whether an attached drawing can be read on this device. */
enum class SheetStatus(val label: String) {
    READY("ready"),
    RASTER("picture, not read for labels"),
    MISSING("file not on this device"),
    CHANGED("file differs from the attached copy"),
}

data class AttachedSheetRow(val kind: SheetKind, val contentHash: String, val status: SheetStatus) {
    val hash8: String get() = contentHash.take(HASH8)
    val summary: String get() = "${kind.wire.uppercase()} $hash8 - ${status.label}"

    private companion object {
        const val HASH8 = 8
    }
}

sealed interface AttachOutcome {
    data object Attached : AttachOutcome
    data object AlreadyAttached : AttachOutcome
    data object LimitReached : AttachOutcome
    data object ProjectNotFound : AttachOutcome
    data object Failed : AttachOutcome
}

/**
 * The project's attached drawings (Story 5.9d). The synced `sources[]` holds kind and content hash only; the drawing file
 * is found again on each device through [locate] (a path derived from the slug and hash) and checked against the hash.
 */
class AttachedSheetService(
    private val repositories: SketchRepositories,
    private val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
    private val locate: (slug: String, contentHash: String, kind: SheetKind) -> String,
    private val readBytes: (path: String) -> ByteArray?,
) {
    suspend fun list(projectUuid: String): List<AttachedSheetRow> {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project ?: return emptyList()
        return attachedSources(project.settings).map { AttachedSheetRow(it.kind, it.contentHash, statusOf(project.slug, it)) }
    }

    suspend fun isAttached(projectUuid: String, contentHash: String): Boolean =
        repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?.let { project -> attachedSources(project.settings).any { it.contentHash == contentHash } } == true

    /** The project's registry, or an empty one while it has none. */
    suspend fun registryOf(projectUuid: String): DimensionRegistry =
        repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?.let { ProjectFusionSettings.setupOf(it.slug, it.settings)?.registry }
            ?: DimensionRegistry(dimensions = emptyList())

    /** Records the source and, when tagging created keys, the grown [registry] in one save. */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun attach(projectUuid: String, source: SheetSource, registry: DimensionRegistry?): AttachOutcome {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?: return AttachOutcome.ProjectNotFound
        if (SheetSources.of(project.settings).any { it.contentHash == source.contentHash }) return AttachOutcome.AlreadyAttached
        val calibratedPng = source.kind == SheetKind.PNG && PngTagSources.of(project.settings).any { it.contentHash == source.contentHash }
        val withSource = if (calibratedPng) project.settings else SheetSources.with(project.settings, source) ?: return AttachOutcome.LimitReached
        val settings = registry?.let { withRegistry(project.slug, withSource, it) } ?: withSource
        val updated = project.copy(settings = settings, modelRevision = project.modelRevision + 1)
        return write { repositories.projects.saveProject(updated) }.fold({ AttachOutcome.Failed }, { AttachOutcome.Attached })
    }

    /** Removes the entry only; the drawing file and its sheet row stay. */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun detach(projectUuid: String, contentHash: String): Boolean {
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project ?: return false
        if (attachedSources(project.settings).none { it.contentHash == contentHash }) return false
        val settings = PngTagSources.without(SheetSources.without(project.settings, contentHash), contentHash)
        val updated = project.copy(settings = settings, modelRevision = project.modelRevision + 1)
        return write { repositories.projects.saveProject(updated) }.isRight()
    }

    /** SVG text of every attached, readable, unchanged SVG sheet of the graph's first project, for the post-sync collector. */
    suspend fun readSvgSheets(): List<SheetSvg> {
        val project = repositories.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull() ?: return emptyList()
        return attachedSources(project.settings).filter { it.kind == SheetKind.SVG }.mapNotNull { source ->
            val bytes = bytesOf(project.slug, source)?.takeIf { ContentHasher.sha256(it) == source.contentHash } ?: return@mapNotNull null
            SheetSvg(sheet = ELEVATION, locator = "drawing:${source.contentHash}", svg = bytes.decodeToString())
        }
    }

    private fun statusOf(slug: String, source: SheetSource): SheetStatus {
        val bytes = bytesOf(slug, source) ?: return SheetStatus.MISSING
        return when {
            ContentHasher.sha256(bytes) != source.contentHash -> SheetStatus.CHANGED
            source.kind == SheetKind.PNG -> SheetStatus.RASTER
            else -> SheetStatus.READY
        }
    }

    private fun bytesOf(slug: String, source: SheetSource): ByteArray? =
        try {
            readBytes(locate(slug, source.contentHash, source.kind))
        } catch (_: UnsupportedOperationException) {
            null
        }

    private fun withRegistry(slug: String, settings: JsonObject, registry: DimensionRegistry): JsonObject {
        val spec = ProjectFusionSettings.setupOf(slug, settings)?.spec ?: FusionSpec()
        return ProjectFusionSettings.with(settings, registry, spec)
    }

    private companion object {
        const val ELEVATION = "elevation"
    }
}
