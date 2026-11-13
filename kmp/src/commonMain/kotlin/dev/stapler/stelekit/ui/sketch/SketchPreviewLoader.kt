package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchSheetRepository
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.sketch.findUuidBySlug
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

sealed interface SketchPreview {
    /** No project with that slug on this device (the journal came from another device). */
    data object Missing : SketchPreview
    data object Empty : SketchPreview
    class Drawing(val document: SketchDocument) : SketchPreview
}

/** Read-only access to a sketch for journal previews; never creates a sheet or writes. */
class SketchPreviewLoader(private val repos: SketchRepositories) {
    private val savedSlugs = MutableSharedFlow<String>(extraBufferCapacity = SAVED_BUFFER)

    /** Slugs whose sketch was just saved, so a visible preview can reload. */
    val saved: SharedFlow<String> = savedSlugs.asSharedFlow()

    fun notifySaved(slug: String) {
        savedSlugs.tryEmit(slug)
    }

    suspend fun load(slug: String): SketchPreview {
        val uuid = with(repos.projects) { findUuidBySlug(slug) } ?: return SketchPreview.Missing
        val sheet = repos.sheets.getSheets(uuid, 1).first().getOrNull()?.items?.firstOrNull() ?: return SketchPreview.Empty
        val stored = mutableListOf<dev.stapler.stelekit.model.SketchPrimitive>()
        var offset = 0
        while (true) {
            val page = repos.sheets.getPrimitives(sheet.uuid, SketchSheetRepository.MAX_PRIMITIVE_PAGE, offset).first().getOrNull().orEmpty()
            stored += page
            if (page.size < SketchSheetRepository.MAX_PRIMITIVE_PAGE) break
            offset += page.size
        }
        val scene = stored.mapNotNull { it.toScenePrimitive() }.fold(SketchScene.EMPTY) { s, p -> s.add(p).getOrNull() ?: s }
        if (scene.size == 0) return SketchPreview.Empty
        return SketchPreview.Drawing(SketchDocument(scene = scene, sheet = sheet.toSheetProps()))
    }

    private companion object {
        const val SAVED_BUFFER = 8
    }
}
