package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.logging.Logger

/** Window after a drag-measure commit in which an undo counts as "undo-after-drag-measure". */
internal const val UNDO_AFTER_DRAG_MEASURE_WINDOW_MS: Long = 5_000L

/**
 * One log line per placement gesture, read by the 48 dp threshold tuning (Task 2.7a). The lines carry
 * no coordinates and no photo path.
 */
internal object PlacementCounters {
    private val logger = Logger("PlacementCounters")

    fun dragMeasure() = logger.info("placement gesture=drag-measure")

    fun aimPlace() = logger.info("placement gesture=aim-place")

    fun undoAfterDragMeasure() = logger.info("placement undo-after-drag-measure")
}
