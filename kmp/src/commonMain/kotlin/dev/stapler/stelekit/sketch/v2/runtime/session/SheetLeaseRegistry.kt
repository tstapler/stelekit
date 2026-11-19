// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.session

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.platform.SketchWriterLease
import dev.stapler.stelekit.platform.SketchWriterLeaseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

private const val CLOSE_WAIT_MS = 10_000L

/** Which editor holds a sheet's edit lease. */
enum class LeaseHolder { Legacy, V2 }

/** Why an edit lease was refused. Nothing is changed for the editor that already holds the sheet. */
sealed interface LeaseRefusal {
    /** Another editor of this graph holds the sheet. */
    data class SheetBusy(val heldBy: LeaseHolder) : LeaseRefusal

    /** The single-writer lease of the sidecar is held by another tab (reason id `OtherTab`, UX-142). */
    data object OtherTab : LeaseRefusal
}

/** One granted edit lease; [release] is idempotent and safe from a dispose callback. */
class SheetLease internal constructor(
    val sheet: SheetUuid,
    val holder: LeaseHolder,
    private val onRelease: (SheetLease) -> Unit,
) {
    private val closingFlag = MutableStateFlow(false)

    /** True once the holder began leaving: it still owns the sheet while its last edits are written. */
    val closing: Boolean get() = closingFlag.value

    fun markClosing() {
        closingFlag.value = true
    }

    fun release() = onRelease(this)
}

/**
 * One edit lease per sheet, in a UI-free layer: one instance per graph, injected into the legacy and the v2 editor, the
 * preview path and the importer. A v2 lease first asks the graph's [SketchWriterLease] (a second browser tab is
 * [LeaseRefusal.OtherTab]); a legacy sheet lives in the database and needs no writer lease. Lock-free: the held map is
 * swapped with compare-and-set, so [SheetLease.release] can run from a non-suspending `onForgotten`.
 */
class SheetLeaseRegistry(private val writerLease: SketchWriterLease) {
    private val held = MutableStateFlow<Map<String, SheetLease>>(emptyMap())

    suspend fun acquire(sheet: SheetUuid, holder: LeaseHolder): Either<LeaseRefusal, SheetLease> {
        if (holder == LeaseHolder.V2 && writerLease.acquire() != SketchWriterLeaseState.Held) return LeaseRefusal.OtherTab.left()
        val lease = SheetLease(sheet, holder, ::release)
        var refusal: LeaseRefusal? = null
        held.update { current ->
            val existing = current[sheet.value]
            refusal = existing?.let { LeaseRefusal.SheetBusy(it.holder) }
            if (existing == null) current + (sheet.value to lease) else current
        }
        return refusal?.left() ?: lease.right()
    }

    /**
     * Suspends while the holder of [sheet] is leaving (its flush has not finished), so a reopen reads the file the leaving editor wrote
     * and finds the sheet free. Gives up after [CLOSE_WAIT_MS]: a flush stuck on a failing disk must not block the reopen forever.
     */
    suspend fun awaitClosing(sheet: SheetUuid) {
        val leaving = held.value[sheet.value]?.takeIf { it.closing } ?: return
        withTimeoutOrNull(CLOSE_WAIT_MS) { held.first { it[sheet.value] !== leaving } }
    }

    /** The holder of [sheet] now, or null. */
    fun holderOf(sheet: SheetUuid): LeaseHolder? = held.value[sheet.value]?.holder

    private fun release(lease: SheetLease) {
        held.update { current -> if (current[lease.sheet.value] === lease) current - lease.sheet.value else current }
    }
}
