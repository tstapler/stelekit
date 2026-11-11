// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The single `{kind: yaml, snapshot: <hash8>}` entry of a project's `sources[]`; it holds no path, SAF URI or file name. */
object YamlSourceEntry {
    const val KIND = "yaml"
    private const val SNAPSHOT = "snapshot"
    private val shape = Regex("[0-9a-f]{8}")

    fun isValidHash(hash8: String) = shape.matches(hash8)

    /** The snapshot hash named by [settings], or null when none is recorded or the entry is malformed. */
    fun snapshotOf(settings: JsonObject): String? =
        entries(settings).firstNotNullOfOrNull { e ->
            val o = e as? JsonObject ?: return@firstNotNullOfOrNull null
            if (o["kind"]?.jsonPrimitive?.contentOrNull != KIND) null
            else o[SNAPSHOT]?.jsonPrimitive?.contentOrNull?.takeIf(::isValidHash)
        }

    /** Replaces any earlier yaml entry with [hash8]; page bindings are left as they were. */
    fun with(settings: JsonObject, hash8: String): JsonObject {
        val entry = JsonObject(mapOf("kind" to JsonPrimitive(KIND), SNAPSHOT to JsonPrimitive(hash8)))
        val others = entries(settings).filterNot { (it as? JsonObject)?.get("kind")?.jsonPrimitive?.contentOrNull == KIND }
        return JsonObject(settings + (SourceBindings.KEY to JsonArray(listOf(entry) + others)))
    }

    private fun entries(settings: JsonObject): List<JsonElement> = (settings[SourceBindings.KEY] as? JsonArray)?.toList().orEmpty()
}

sealed interface YamlImportOutcome {
    data class Imported(val hash8: String) : YamlImportOutcome
    data object Unchanged : YamlImportOutcome
    data object Empty : YamlImportOutcome
    data object TooLarge : YamlImportOutcome
    data object NotText : YamlImportOutcome
    data object ProjectNotFound : YamlImportOutcome
    data object StoreUnavailable : YamlImportOutcome

    /** A git sync is running; nothing was recorded, the owner picks again afterwards. */
    data object Deferred : YamlImportOutcome
    data class Failed(val message: String) : YamlImportOutcome
}

/**
 * Snapshots a picked YAML file's CONTENT into the project's sidecar (Story 5.9c, OD-H2). Only bytes come in: no path, SAF URI
 * or file name ever reaches this class, so none can be stored. The snapshot file is written before `sources[]` names it.
 */
class YamlSnapshotImporter(
    private val repositories: SketchRepositories,
    private val store: () -> ProjectSidecarStore?,
    private val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
) {
    @OptIn(DirectRepositoryWrite::class)
    suspend fun import(projectUuid: String, bytes: ByteArray): YamlImportOutcome {
        val rejected = YamlSnapshotRules.check(bytes)
        if (rejected != null) return rejected
        val project = repositories.projects.loadProject(ProjectUuid(projectUuid)).getOrNull()?.project
            ?: return YamlImportOutcome.ProjectNotFound
        val hash8 = YamlSnapshotRules.hash8(bytes)
        if (YamlSourceEntry.snapshotOf(project.settings) == hash8) return YamlImportOutcome.Unchanged
        val sidecar = store() ?: return YamlImportOutcome.StoreUnavailable
        when (val written = sidecar.writeSourceSnapshot(project.slug, hash8, bytes)) {
            SidecarWriteOutcome.Written -> Unit
            SidecarWriteOutcome.Unavailable -> return YamlImportOutcome.StoreUnavailable
            SidecarWriteOutcome.Deferred -> return YamlImportOutcome.Deferred
            is SidecarWriteOutcome.Failed -> return YamlImportOutcome.Failed(written.error.message)
        }
        val updated = project.copy(settings = YamlSourceEntry.with(project.settings, hash8), modelRevision = project.modelRevision + 1)
        return write { repositories.projects.saveProject(updated) }
            .fold({ YamlImportOutcome.Failed("Could not save the YAML source") }, { YamlImportOutcome.Imported(hash8) })
    }
}

/** Size and text checks plus the snapshot's content-derived name; pure so the importer's rules are unit-tested. */
object YamlSnapshotRules {
    const val MAX_BYTES = 512 * 1024
    private const val HASH_LENGTH = 8

    fun check(bytes: ByteArray): YamlImportOutcome? = when {
        bytes.isEmpty() -> YamlImportOutcome.Empty
        bytes.size > MAX_BYTES -> YamlImportOutcome.TooLarge
        bytes.any { it == 0.toByte() } -> YamlImportOutcome.NotText
        else -> null
    }

    fun hash8(bytes: ByteArray): String = ContentHasher.sha256(bytes).take(HASH_LENGTH)

    fun message(outcome: YamlImportOutcome): String? = when (outcome) {
        is YamlImportOutcome.Imported -> null
        YamlImportOutcome.Unchanged -> "That YAML file is already the project's source"
        YamlImportOutcome.Empty -> "That file is empty"
        YamlImportOutcome.TooLarge -> "That file is larger than ${MAX_BYTES / 1024} KiB"
        YamlImportOutcome.NotText -> "That file is not a text file"
        YamlImportOutcome.ProjectNotFound -> "Create a project first"
        YamlImportOutcome.StoreUnavailable -> "This device cannot keep a YAML source for this graph"
        YamlImportOutcome.Deferred -> "A sync is running; pick the file again when it finishes"
        is YamlImportOutcome.Failed -> outcome.message
    }
}

/** The project's snapshot text, or null when none is recorded or its file is absent. */
suspend fun readYamlSnapshot(repositories: SketchRepositories, store: ProjectSidecarStore): String? {
    val project = repositories.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull() ?: return null
    val hash8 = YamlSourceEntry.snapshotOf(project.settings) ?: return null
    return store.readSourceSnapshot(project.slug, hash8)?.decodeToString()
}
