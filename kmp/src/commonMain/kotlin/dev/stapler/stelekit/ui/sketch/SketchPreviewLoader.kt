package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.findUuidBySlug
import dev.stapler.stelekit.sketch.v2.runtime.projection.SheetProjectionSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

sealed interface SketchPreview {
    /** No project with that slug on this device (the journal came from another device). */
    data object Missing : SketchPreview
    data object Empty : SketchPreview

    /** [notShownCount] is the elements of a v2 sheet this preview cannot draw ("N elements not shown in this preview"). */
    class Drawing(val document: SketchDocument, val notShownCount: Int = 0) : SketchPreview
}

/**
 * Read-only access to a sketch for journal previews; never creates a sheet or writes. A converted (v2) sheet is drawn from
 * the in-memory projection of its sidecar file when a [projection] is given; its empty `sketch_primitives` rows never make
 * the preview empty (Story 1.4.5).
 */
class SketchPreviewLoader(private val repos: SketchRepositories, projection: SheetProjectionSource? = null) {
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
        val loaded = documents.load(slug, sheet).getOrNull() ?: return SketchPreview.Empty
        if (loaded.document.scene.size == 0 && loaded.droppedCount == 0) return SketchPreview.Empty
        return SketchPreview.Drawing(loaded.document, loaded.droppedCount)
    }

    private companion object {
        const val SAVED_BUFFER = 8
    }
}
