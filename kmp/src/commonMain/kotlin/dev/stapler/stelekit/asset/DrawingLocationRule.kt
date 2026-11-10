package dev.stapler.stelekit.asset

import dev.stapler.stelekit.model.EffectiveNotesPath

enum class DrawingLocation { Skip, Drawing, Normal }

/**
 * Classifies a path by where it sits under `<notes>/assets/stelekit-sketch/<slug>/`.
 * Only `drawings/` holds asset rows (always [AssetMediaType.DRAWING]); the sidecar, sheets, sources,
 * exports and any `*.tmp` are sketch-managed files that never become rows.
 */
object DrawingLocationRule {
    const val SKETCH_DIR = "stelekit-sketch"
    private const val DRAWINGS_DIR = "drawings"

    fun classify(filePath: String, notesPath: EffectiveNotesPath): DrawingLocation {
        val prefix = notesPath.child("assets") + "/"
        if (!filePath.startsWith(prefix)) return DrawingLocation.Normal
        val segments = filePath.removePrefix(prefix).split('/')
        if (segments.first() != SKETCH_DIR) return DrawingLocation.Normal
        if (segments.last().endsWith(".tmp")) return DrawingLocation.Skip
        // stelekit-sketch / <slug> / drawings / <file...>; anything shallower or elsewhere is sketch-managed.
        return if (segments.size >= 4 && segments[2] == DRAWINGS_DIR) DrawingLocation.Drawing else DrawingLocation.Skip
    }
}
