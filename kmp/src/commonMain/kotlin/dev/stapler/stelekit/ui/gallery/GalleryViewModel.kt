// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.gallery

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.repository.ImageAnnotationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sort order for the gallery image grid.
 */
enum class GallerySortOrder {
    BY_DATE_CAPTURED,
    BY_DATE_IMPORTED,
    BY_MEASUREMENT_COUNT,
}

/**
 * Immutable state snapshot for [GalleryViewModel].
 */
data class GalleryState(
    val images: List<ImageAnnotation> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val selectedTag: String? = null,
    val sortOrder: GallerySortOrder = GallerySortOrder.BY_DATE_IMPORTED,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

/**
 * ViewModel for the image gallery screen.
 *
 * CRITICAL: owns its [CoroutineScope] internally — never accepts an externally supplied scope.
 * Compose cancels [rememberCoroutineScope] on composition exit; any object holding that scope
 * will throw [ForgottenCoroutineScopeException] on its next [launch].
 *
 * Call [close] when the gallery screen is permanently dismissed to cancel in-flight queries.
 */
class GalleryViewModel(
    private val imageAnnotationRepository: ImageAnnotationRepository,
) {
    // CRITICAL: internal scope — never injected from outside composition.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = Logger("GalleryViewModel")

    private val _state = MutableStateFlow(GalleryState())
    val state: StateFlow<GalleryState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration: Int = 0

    init {
        loadImages()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadImages() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = scope.launch {
            val tag = _state.value.selectedTag
            val flow = if (tag != null) {
                imageAnnotationRepository.getImageAnnotationsByTag(tag)
            } else {
                imageAnnotationRepository.getAllImageAnnotations()
            }
            flow.collect { result ->
                // Discard emissions from a superseded load — a newer loadImages() call
                // has already replaced this job's tag/filter context.
                if (generation != loadGeneration) return@collect
                result.fold(
                    ifLeft = { err ->
                        logger.error("Failed to load gallery images: ${err.message}")
                        _state.update { it.copy(isLoading = false, errorMessage = err.message) }
                    },
                    ifRight = { images ->
                        val sorted = sortImages(images, _state.value.sortOrder)
                        val tags = images.flatMap { it.tags }.distinct().sorted()
                        _state.update { it.copy(images = sorted, availableTags = tags, isLoading = false, errorMessage = null) }
                    }
                )
            }
        }
    }

    // ── Filtering and sorting ─────────────────────────────────────────────────

    /**
     * Filter by [tag]. Pass null to show all images.
     */
    fun selectTag(tag: String?) {
        _state.update { it.copy(selectedTag = tag, isLoading = true) }
        loadImages()
    }

    /**
     * Change the sort order and re-sort the current image list.
     */
    fun setSortOrder(order: GallerySortOrder) {
        _state.update { state ->
            state.copy(
                sortOrder = order,
                images = sortImages(state.images, order),
            )
        }
    }

    private fun sortImages(images: List<ImageAnnotation>, order: GallerySortOrder): List<ImageAnnotation> =
        when (order) {
            GallerySortOrder.BY_DATE_CAPTURED ->
                images.sortedByDescending { it.capturedAtMs ?: 0L }
            GallerySortOrder.BY_DATE_IMPORTED ->
                images.sortedByDescending { it.importedAtMs }
            GallerySortOrder.BY_MEASUREMENT_COUNT ->
                images.sortedByDescending { it.measurementCountFromProperties() }
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Cancel the internal [CoroutineScope]. Call when the gallery is permanently dismissed.
     */
    fun close() {
        scope.cancel()
    }
}

// Placeholder measurement count until MeasurementPropertySyncer populates block properties (Epic 6).
@Suppress("FunctionOnlyReturningConstant")
private fun ImageAnnotation.measurementCountFromProperties(): Int = 0
