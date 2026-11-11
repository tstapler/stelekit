package dev.stapler.stelekit.sketch

/** What one undo step restores: the drawing, named zones and sheet settings. In-progress input is never part of it. */
data class SketchDocument(
    val scene: SketchScene = SketchScene.EMPTY,
    val zones: ZoneSet = ZoneSet.EMPTY,
    val sheet: SheetProps = SheetProps(),
) {
    /** What the canvas draws and hit-tests: primitives on hidden layers are left out. */
    val visibleScene: SketchScene by lazy(LazyThreadSafetyMode.NONE) {
        if (sheet.layers.all { it.visible }) scene else scene.filtered { sheet.isVisible(it.layer) }
    }

    /** [SketchScene] has identity equality, so compare its content. */
    fun sameContent(other: SketchDocument): Boolean =
        zones == other.zones && sheet == other.sheet && scene.primitives == other.scene.primitives
}

/**
 * Bounded snapshot history of committed [SketchDocument]s. One [commit] is one step, so a drag
 * that only moves a transient pointer commits once, on release. Immutable: every operation
 * returns a new history.
 */
class SketchHistory private constructor(
    private val past: List<SketchDocument>,
    val present: SketchDocument,
    private val future: List<SketchDocument>,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    val undoDepth: Int get() = past.size

    /** Records [next] as a new step and drops the redo branch; a no-change commit is ignored. */
    fun commit(next: SketchDocument): SketchHistory =
        if (next.sameContent(present)) this
        else SketchHistory((past + present).takeLast(MAX_STEPS), next, emptyList())

    fun undo(): SketchHistory =
        if (past.isEmpty()) this else SketchHistory(past.dropLast(1), past.last(), listOf(present) + future)

    fun redo(): SketchHistory =
        if (future.isEmpty()) this else SketchHistory(past + present, future.first(), future.drop(1))

    companion object {
        const val MAX_STEPS = 100

        fun of(initial: SketchDocument = SketchDocument()) = SketchHistory(emptyList(), initial, emptyList())
    }
}
