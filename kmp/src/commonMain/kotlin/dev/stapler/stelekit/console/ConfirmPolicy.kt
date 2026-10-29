// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/** How much friction a write's confirm needs. */
sealed interface ConfirmTier {
    data object Standard : ConfirmTier

    /** The primary button stays disabled until the owner types [count] exactly. */
    data class TypedCount(val count: Long) : ConfirmTier
}

/** A table other than the target whose row count the dry run changed (trigger or cascade). */
data class TableDelta(val table: String, val delta: Long)

/** What the rolled-back dry run measured. */
data class DryRunMeasurement(
    val targetTable: String,
    /** Rows in [targetTable] before the statement; null when unknown. */
    val targetRows: Long?,
    val changes: Long,
    /** `total_changes()` delta: also counts trigger and cascade writes. */
    val totalChanges: Long,
    val otherTables: List<TableDelta>,
)

sealed interface ConfirmDecision {
    data class Refuse(val reason: String) : ConfirmDecision

    /**
     * [count] is what the modal announces. [triggered] is true when triggers or cascades reach other rows,
     * in which case [count] is the larger `total_changes()` figure and [tables] lists the other tables.
     */
    data class Confirm(
        val tier: ConfirmTier,
        val count: Long,
        val triggered: Boolean,
        val tables: List<TableDelta>,
    ) : ConfirmDecision
}

/**
 * The one pure rule behind the `sql!` confirm (plan Task 3.3b). Tables with an FTS index maintain it
 * through triggers that add to `total_changes()`; that overhead alone does not escalate.
 */
object ConfirmPolicy {
    const val ABSOLUTE_ROWS = 1_000L
    const val MIN_ROWS_FOR_SHARE_RULE = 10L
    const val CANT_COUNT = "Can't count the rows this would change, so it won't run. Nothing was changed."
    const val TIMED_OUT = "Couldn't count the rows this would change in time. Nothing was changed."

    /** Tables whose triggers maintain a full-text index; see `SteleDatabase.sq` (`blocks_ai`, `pages_ai`, ...). */
    private val INDEXED_TABLES = setOf("blocks", "pages")

    fun decide(measurement: DryRunMeasurement?, nondeterministic: Boolean): ConfirmDecision {
        if (measurement == null) return ConfirmDecision.Refuse(CANT_COUNT)
        if (reachesOtherRows(measurement.targetTable, measurement.changes, measurement.totalChanges, measurement.otherTables.isNotEmpty())) {
            val total = maxOf(measurement.totalChanges, measurement.changes)
            return ConfirmDecision.Confirm(ConfirmTier.TypedCount(total), total, true, measurement.otherTables)
        }
        val base = tierFor(measurement.changes, measurement.targetRows)
        val tier = if (nondeterministic && base is ConfirmTier.Standard) ConfirmTier.TypedCount(measurement.changes) else base
        return ConfirmDecision.Confirm(tier, measurement.changes, false, emptyList())
    }

    /** At least 1,000 rows, or half the table and at least 10 rows (a 2-row table never trains typing through). */
    fun tierFor(changes: Long, targetRows: Long?): ConfirmTier {
        val large = changes >= ABSOLUTE_ROWS ||
            (targetRows != null && changes >= MIN_ROWS_FOR_SHARE_RULE && changes * 2 >= targetRows)
        return if (large) ConfirmTier.TypedCount(changes) else ConfirmTier.Standard
    }

    /**
     * True when the real write must roll back because it no longer matches what the owner confirmed;
     * the figures are measured inside the write transaction, before commit.
     */
    fun isStale(confirmed: ConfirmDecision.Confirm, targetTable: String, targetRows: Long?, actualChanges: Long, actualTotal: Long): Boolean {
        val actualCount = if (confirmed.triggered) maxOf(actualTotal, actualChanges) else actualChanges
        return when (val tier = confirmed.tier) {
            is ConfirmTier.TypedCount -> actualCount != tier.count
            ConfirmTier.Standard ->
                reachesOtherRows(targetTable, actualChanges, actualTotal, otherTablesChanged = false) ||
                    tierFor(actualChanges, targetRows) is ConfirmTier.TypedCount
        }
    }

    /** The count the owner was shown when the real write turned out different. */
    fun actualCount(confirmed: ConfirmDecision.Confirm, actualChanges: Long, actualTotal: Long): Long =
        if (confirmed.triggered) maxOf(actualTotal, actualChanges) else actualChanges

    private fun reachesOtherRows(targetTable: String, changes: Long, totalChanges: Long, otherTablesChanged: Boolean): Boolean {
        val extra = totalChanges - changes
        val indexOnly = extra > 0 && !otherTablesChanged && targetTable in INDEXED_TABLES
        return otherTablesChanged || (extra > 0 && !indexOnly)
    }
}
