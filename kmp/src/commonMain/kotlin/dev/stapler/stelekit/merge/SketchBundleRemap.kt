// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.merge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.util.ContentHasher

/**
 * Gives a copied sketch project its own identity under a new slug. The project, sheet, primitive and dimension uuids are
 * database keys, so a "Rename and copy" that kept them would make two slugs collide on one project row when both are
 * loaded. Each id becomes a hash of (new slug, old id), the same idea as [UuidRemap]; sheet file names, the model's
 * `sheetRef` entries and the `sheetUuid` inside each v2 sheet follow, because they are all rebuilt from the remapped bundle.
 */
internal object SketchBundleRemap {
    fun remap(files: Map<String, ByteArray>, newSlug: String): Either<DomainError, Map<String, ByteArray>> {
        val bundle = SidecarCodec.decode(files).getOrNull()
            ?: return DomainError.FileSystemError.ReadFailed(SidecarCodec.MODEL_PATH, "the sketch cannot be read as a project").left()
        fun id(old: String): String = uuidOf("sketch-copy:$newSlug:$old")
        val project = ProjectUuid(id(bundle.project.uuid.value))
        fun sheet(old: SheetUuid) = SheetUuid(id(old.value))
        fun primitive(p: SketchPrimitive) = p.copy(uuid = id(p.uuid), sheetUuid = sheet(p.sheetUuid))

        val payloads = LinkedHashMap<SheetUuid, SheetPayload>()
        for ((old, payload) in bundle.sheetPayloads) {
            payloads[sheet(old)] = when (payload) {
                is SheetPayload.V1 -> SheetPayload.V1(payload.primitives.map(::primitive))
                is SheetPayload.V2Opaque -> {
                    val doc = SheetCodecV2.decode(payload.bytes).getOrNull()
                        ?: return DomainError.FileSystemError.ReadFailed(old.value, "a sheet cannot be read").left()
                    val bytes = SheetCodecV2.encode(doc.copy(sheetUuid = sheet(old).value)).getOrNull()
                        ?: return DomainError.FileSystemError.WriteFailed(old.value, "a sheet cannot be re-encoded").left()
                    SheetPayload.V2Opaque.of(bytes)
                }
            }
        }
        val remapped = ProjectBundle(
            project = bundle.project.copy(uuid = project, slug = newSlug),
            sheets = bundle.sheets.map { it.copy(uuid = sheet(it.uuid), projectUuid = project) },
            primitives = bundle.primitives.map(::primitive),
            dimensions = bundle.dimensions.map { it.copy(uuid = id(it.uuid), projectUuid = project) },
            sheetPayloads = payloads,
        )
        return SidecarCodec.encode(remapped).right()
    }

    private fun uuidOf(seed: String): String {
        val h = ContentHasher.sha256(seed).substring(0, UUID_HEX)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    private const val UUID_HEX = 32
}

/** The same bundle as a separate project under another slug: its project, sheet and element uuids and sheet file names are remapped too. */
fun SketchBundle.renamed(newSlug: String): Either<DomainError, SketchBundle> =
    SketchBundleRemap.remap(files, newSlug).map { SketchBundle(newSlug, it, assets, missingAssets) }
