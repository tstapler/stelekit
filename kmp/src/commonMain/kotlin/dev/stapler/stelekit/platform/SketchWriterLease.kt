package dev.stapler.stelekit.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SketchWriterLeaseState { Held, HeldElsewhere, Acquiring }

/**
 * Single-writer lease for v2 sheet writes of one graph (D-123, D-130). Always held on desktop and Android; on
 * the web a second tab is [SketchWriterLeaseState.HeldElsewhere] and read-only.
 */
interface SketchWriterLease {
    val state: StateFlow<SketchWriterLeaseState>

    /**
     * Grows by one each time the lease becomes `Held` again after a loss (D-150). A write parked under one epoch
     * is never replayed under a later one.
     */
    val epoch: Long

    /** Takes the lease when the first v2 sheet of the graph opens for editing, never at startup. */
    suspend fun acquire(): SketchWriterLeaseState

    /** Releases the lease when the graph stops being the ready graph. */
    fun release()
}

/** The lease of desktop, Android and every platform with no second writer: always held, epoch constant. */
class AlwaysHeldSketchWriterLease : SketchWriterLease {
    override val state: StateFlow<SketchWriterLeaseState> = MutableStateFlow(SketchWriterLeaseState.Held)
    override val epoch: Long = 0
    override suspend fun acquire(): SketchWriterLeaseState = SketchWriterLeaseState.Held
    override fun release() = Unit
}
