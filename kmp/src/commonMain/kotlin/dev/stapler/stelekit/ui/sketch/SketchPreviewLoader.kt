package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.findUuidBySlug
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionRead
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

sealed interface SketchPreview {
    /** No project with that slug on this device (the journal came from another device, or it was deleted): "Sketch not found". */
    data object Missing : SketchPreview
    data object Empty : SketchPreview

    /** The sheet was written by a newer app: "Update SteleKit to view". Nothing is read further and nothing crashes. */
    data object NewerSchema : SketchPreview

    /** A v2 sheet on a platform with no file storage (iOS, a browser without OPFS): a labelled placeholder, no thumbnail, no write (UX-135). */
    data object StorageUnavailable : SketchPreview

    /** A v2 sheet drawn from its own scene by the v2 draw list (Story 6.1.1); [scene] is the whole sheet, nothing is dropped. */
    class ModernDrawing(val scene: SketchSceneV2) : SketchPreview

    /** [notShownCount] is the elements of a v2 sheet this preview cannot draw ("N elements not shown in this preview"). */
    class Drawing(val document: SketchDocument, val notShownCount: Int = 0) : SketchPreview
}

/**
 * Read-only access to a sketch for journal previews; never creates a sheet or writes. The route is chosen per sheet from
 * the DB `schema_version` mirror (`SketchRenderRoute`): a v1 sheet reads its `sketch_primitives` rows; a v2 sheet reads its
 * sidecar file through [projection] and is drawn by the v2 draw list, and its empty `sketch_primitives` rows never make the
 * preview empty (Story 1.4.5); the file wins, so a newer schema inside it shows "Update SteleKit to view".
 */
class SketchPreviewLoader(
    private val repos: SketchRepositories,
    private val projection: SheetProjectionSource? = null,
    /** False on a platform with no file storage for v2 sketches (D-118); a v2 embed then shows its placeholder and reads nothing. */
    private val v2StorageAvailable: () -> Boolean = { true },
) {
    private val documents = SketchDocumentSource(repos, projection)
    private val savedSlugs = MutableSharedFlow<String>(extraBufferCapacity = SAVED_BUFFER)

    /** Slugs whose sketch was just saved, so a visible preview can reload. */
    val saved: SharedFlow<String> = savedSlugs.asSharedFlow()

    fun notifySaved(slug: String) {
        savedSlugs.tryEmit(slug)
    }

    suspend fun load(slug: String): SketchPreview {
        val uuid = with(repos.projects) { findUuidBySlug(slug) } ?: return SketchPreview.Missing
        val sheet = repos.sheets.getSheets(uuid, 1).first().getOrNull()?.items?.firstOrNull() ?: return SketchPreview.Empty
        return when (SketchRenderRoute.of(sheet.schemaVersion)) {
            SketchRenderRoute.Newer -> SketchPreview.NewerSchema
            SketchRenderRoute.Modern -> loadModern(slug, sheet)
            SketchRenderRoute.Classic -> loadClassic(slug, sheet)
        }
    }

    private suspend fun loadClassic(slug: String, sheet: SketchSheet): SketchPreview {
        val loaded = documents.load(slug, sheet).getOrNull() ?: return SketchPreview.Empty
        if (loaded.document.scene.size == 0 && loaded.droppedCount == 0) return SketchPreview.Empty
        return SketchPreview.Drawing(loaded.document, loaded.droppedCount)
    }

    /** A v2 sheet: the file decides (it is read, and a newer schema in it wins over the DB mirror); a read failure shows as empty, never a crash. */
    private suspend fun loadModern(slug: String, sheet: SketchSheet): SketchPreview {
        if (!v2StorageAvailable()) return SketchPreview.StorageUnavailable
        val source = projection ?: return SketchPreview.Empty
        return when (val read = source.read(slug, sheet.uuid)) {
            SheetProjectionRead.NewerSchema -> SketchPreview.NewerSchema
            is SheetProjectionRead.Failed -> SketchPreview.Empty
            is SheetProjectionRead.Loaded -> read.projection.scene.takeIf { it.size > 0 }?.let { SketchPreview.ModernDrawing(it) } ?: SketchPreview.Empty
        }
    }

    private companion object {
        const val SAVED_BUFFER = 8
    }
}
