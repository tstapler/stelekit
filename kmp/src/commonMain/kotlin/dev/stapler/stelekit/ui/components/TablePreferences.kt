package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.platform.Settings

/** Whether a table too wide for its column wraps cell text or keeps natural widths and scrolls. */
enum class TableOverflow { WRAP, SCROLL }

/** Cell padding: [COMPACT] for dense data, [COMFORTABLE] for readability. */
enum class TableDensity { COMPACT, COMFORTABLE }

/** String, default `wrap`: `wrap` or `scroll` (see [TableOverflow]). */
const val TABLE_OVERFLOW_KEY = "table_overflow"

/** Boolean, default on: right-align an unmarked column whose cells are all numbers. */
const val TABLE_NUMERIC_RIGHT_ALIGN_KEY = "table_numeric_right_align"

/** String, default `comfortable`: `compact` or `comfortable` (see [TableDensity]). */
const val TABLE_DENSITY_KEY = "table_density"

data class TableDisplayPrefs(
    val overflow: TableOverflow = TableOverflow.WRAP,
    val numericRightAlign: Boolean = true,
    val density: TableDensity = TableDensity.COMFORTABLE,
)

fun Settings?.tableDisplayPrefs(): TableDisplayPrefs {
    if (this == null) return TableDisplayPrefs()
    return TableDisplayPrefs(
        overflow = TableOverflow.entries.firstOrNull { it.name.lowercase() == getString(TABLE_OVERFLOW_KEY, "") }
            ?: TableOverflow.WRAP,
        numericRightAlign = getBoolean(TABLE_NUMERIC_RIGHT_ALIGN_KEY, true),
        density = TableDensity.entries.firstOrNull { it.name.lowercase() == getString(TABLE_DENSITY_KEY, "") }
            ?: TableDensity.COMFORTABLE,
    )
}

fun Settings.writeTableDisplayPrefs(prefs: TableDisplayPrefs) {
    putString(TABLE_OVERFLOW_KEY, prefs.overflow.name.lowercase())
    putBoolean(TABLE_NUMERIC_RIGHT_ALIGN_KEY, prefs.numericRightAlign)
    putString(TABLE_DENSITY_KEY, prefs.density.name.lowercase())
}

/**
 * Observable, persisted table preferences: the Settings dialog writes through [update] and every
 * rendered table reads [prefs], so a change repaints open pages without a restart.
 */
class TablePreferences(private val settings: Settings?) {
    var prefs: TableDisplayPrefs by mutableStateOf(settings.tableDisplayPrefs())
        private set

    fun update(transform: (TableDisplayPrefs) -> TableDisplayPrefs) {
        prefs = transform(prefs)
        settings?.writeTableDisplayPrefs(prefs)
    }
}

/** Defaults until the shell provides the persisted instance; tables outside the shell still render. */
@Suppress("CompositionLocalAllowlist") // set once by GraphContentActiveShell; threading it through BlockList/BlockItem would touch every page screen
val LocalTablePreferences = compositionLocalOf { TablePreferences(null) }
