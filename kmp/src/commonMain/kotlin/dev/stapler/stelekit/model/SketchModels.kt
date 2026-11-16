// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.model

import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

@JvmInline
value class ProjectUuid(val value: String)

@JvmInline
value class SheetUuid(val value: String)

/** Cache row of a project; the sidecar bundle is authoritative (ADR-005). [settings] never holds a path. */
data class SketchProject(
    val uuid: ProjectUuid,
    val slug: String,
    val name: String,
    val createdAt: Long,
    val settings: JsonObject = JsonObject(emptyMap()),
    val fusionResults: JsonObject = JsonObject(emptyMap()),
    val sourceHash: String = "",
    val lastDigestHash: String = "",
    val modelRevision: Long = 0,
    val baseRevision: Long = 0,
    val lastSyncedSidecarHash: String? = null,
)

data class SketchSheet(
    val uuid: SheetUuid,
    val projectUuid: ProjectUuid,
    val kind: String,
    val viewDirection: String,
    val unit: String,
    val scaleSixteenthsPerUnit: Long,
    val sourceAssetUuid: String? = null,
    val layers: List<String> = emptyList(),
    val zones: JsonArray = JsonArray(emptyList()),
    val position: Long = 0,
    /** DB mirror of the sheet file's schema (1 or 2); the file wins, see ADR-001. Copy it, never rebuild it. */
    val schemaVersion: Long = 1,
)

/** Coordinates are sixteenths of the sheet unit. */
data class SketchPrimitive(
    val uuid: String,
    val sheetUuid: SheetUuid,
    val kind: String,
    val x1: Long,
    val y1: Long,
    val x2: Long,
    val y2: Long,
    val name: String = "",
    val layer: String = "",
    val z: Long = 0,
)

/** [precision] is null for a count (ADR-002). Values are exact, so `37.9"` stays 379/10. */
data class SketchDimension(
    val uuid: String,
    val projectUuid: ProjectUuid,
    val key: String,
    val kind: String,
    val value: Rational,
    val precision: Rational?,
    val sourceKind: String,
    val sourceRef: String = "",
    val tolerance: Rational = Rational.ZERO,
    val locked: Boolean = false,
)

/** A bounded page of items; [hasMore] is true when the limit cut the result short. */
data class SketchPage<T>(val items: List<T>, val hasMore: Boolean)

/**
 * Returned by `loadProject`. Every sheet, primitive and dimension save carries it and fails with
 * `WriteFailed("stale-editor")` once the project was rebuilt since the load.
 */
data class RevisionToken(val projectUuid: ProjectUuid, val value: Long)

data class LoadedSketchProject(val project: SketchProject, val token: RevisionToken)

/**
 * What a sheet's file holds as far as the bundle pipeline is concerned. A v1 sheet is its [primitives]; a v2 sheet is
 * opaque bytes the pipeline carries unchanged (interpretation belongs to the v2 editor's codec, never to the bundle).
 */
sealed interface SheetPayload {
    data class V1(val primitives: List<SketchPrimitive>) : SheetPayload

    class V2Opaque(val bytes: ByteArray, val sha256: String) : SheetPayload {
        override fun equals(other: Any?) = other is V2Opaque && sha256 == other.sha256 && bytes.contentEquals(other.bytes)
        override fun hashCode() = sha256.hashCode()
        override fun toString() = "V2Opaque(sha256=$sha256, ${bytes.size} bytes)"

        companion object {
            fun of(bytes: ByteArray) = V2Opaque(bytes, ContentHasher.sha256(bytes))
        }
    }
}

/**
 * Everything `replaceProjectContents` rebuilds in one transaction. [primitives] holds v1 sheets' primitives only.
 * [sheetPayloads] holds the opaque bytes of every sheet whose `schemaVersion` is 2 (the database keeps no v2
 * content); a v1 sheet needs no entry, an explicit [SheetPayload.V1] entry overrides [primitives] for that sheet.
 */
data class ProjectBundle(
    val project: SketchProject,
    val sheets: List<SketchSheet>,
    val primitives: List<SketchPrimitive>,
    val dimensions: List<SketchDimension>,
    val sheetPayloads: Map<SheetUuid, SheetPayload> = emptyMap(),
)
