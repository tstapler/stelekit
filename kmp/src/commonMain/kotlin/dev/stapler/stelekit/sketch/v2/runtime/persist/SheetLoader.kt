// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.CorruptReason
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.util.ContentHasher

/** What reading a v2 sheet for the editor found. [Loaded.sha] is the sha of the bytes read: the saver's `loadedSha`. */
sealed interface SheetLoad {
    class Loaded(val doc: SketchDocumentV2, val sha: String) : SheetLoad

    /** The sheet, or the model that lists it, is from a newer app: the editor never opens it. */
    data object NewerSchema : SheetLoad

    /** There is no such file (a sheet never saved). */
    data object Missing : SheetLoad

    /** The platform cannot read the sidecar right now. */
    data object Unavailable : SheetLoad

    /** The file is not a readable sheet; [reason] is a fixed token, never file content. */
    class Unreadable(val reason: String) : SheetLoad
}

/** Reads one v2 sheet from the project sidecar through the one codec (Story 6.2.0a). Never writes. */
object SheetLoader {
    suspend fun load(store: ProjectSidecarStore, slug: String, sheet: SheetUuid, limits: SceneLimits = SceneLimits.Default): SheetLoad {
        val path = SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2)
        val bytes = when (val read = store.read(slug)) {
            is SidecarRead.Present -> read.files[path] ?: return SheetLoad.Missing
            SidecarRead.Absent -> return SheetLoad.Missing
            SidecarRead.Unavailable -> return SheetLoad.Unavailable
            is SidecarRead.Corrupt -> return if (read.reason == CorruptReason.NewerSchema) SheetLoad.NewerSchema else SheetLoad.Unreadable(read.reason.name)
        }
        return SheetCodecV2.decode(bytes, limits).fold(
            { error -> if (isNewer(error)) SheetLoad.NewerSchema else SheetLoad.Unreadable(error::class.simpleName.orEmpty()) },
            { doc -> SheetLoad.Loaded(doc, ContentHasher.sha256(bytes)) },
        )
    }

    private fun isNewer(error: SheetError): Boolean = (error as? SheetError.UnsupportedSchema)?.schema?.let { it > SheetPaths.SCHEMA_V2 } == true
}
