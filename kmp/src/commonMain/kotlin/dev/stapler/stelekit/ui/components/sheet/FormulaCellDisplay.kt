// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import dev.stapler.stelekit.table.CellAddress
import dev.stapler.stelekit.table.TableDoc
import dev.stapler.stelekit.table.formula.CellError
import dev.stapler.stelekit.table.formula.CellValue
import dev.stapler.stelekit.table.formula.FormulaParser
import dev.stapler.stelekit.table.formula.TableCalc
import dev.stapler.stelekit.ui.components.TableMessages

/** What a non-editing cell shows (Task 4.5b). Edit always shows the raw text, never this. */
internal sealed interface CellDisplay {
    val text: String

    data class Plain(override val text: String) : CellDisplay

    /** A formula that evaluated: [text] is the value, [formula] the source for `stateDescription`. */
    data class Formula(override val text: String, val formula: String) : CellDisplay

    /** A formula that evaluated to an error token; [detail] is the spoken reason. */
    data class Failed(override val text: String, val formula: String, val detail: String) : CellDisplay

    /** Formula-shaped text that does not parse keeps the user's own words (pre-mortem P2-3). */
    data class InvalidFormula(override val text: String, val detail: String) : CellDisplay
}

/** [calc] is null above the formula cell cap, where every cell is plain text. */
internal fun cellDisplay(doc: TableDoc, calc: TableCalc?, address: CellAddress): CellDisplay {
    val raw = doc.cell(address)?.value ?: ""
    if (calc == null || !FormulaParser.isFormulaShaped(raw)) return CellDisplay.Plain(raw)
    val value = calc.valueAt(address)
    if (value is CellValue.Err) {
        return if (value.error == CellError.Parse) {
            CellDisplay.InvalidFormula(raw, TableMessages.errorDetail(CellError.Parse))
        } else {
            CellDisplay.Failed(value.error.display, raw, TableMessages.errorDetail(value.error))
        }
    }
    return CellDisplay.Formula(calc.displayAt(address), raw)
}

/** Builds one [TableCalc] per doc instance, so per-cell hooks (copy, Why?) do not each rebuild the dependency graph. */
internal class CalcMemo(private val build: (TableDoc) -> TableCalc? = { TableCalc.orNull(it) }) {
    private var doc: TableDoc? = null
    private var calc: TableCalc? = null

    fun of(d: TableDoc): TableCalc? {
        if (d !== doc) {
            calc = build(d)
            doc = d
        }
        return calc
    }
}

/** Points the strip's per-cell hooks at [memo], so a select-all copy evaluates the table once. */
internal fun StripHooks.bindCellDisplay(memo: CalcMemo) {
    cellText = { d, address -> cellDisplay(d, memo.of(d), address).text }
    errorDetail = { d, address ->
        when (val shown = cellDisplay(d, memo.of(d), address)) {
            is CellDisplay.Failed -> shown.detail
            is CellDisplay.InvalidFormula -> shown.detail
            else -> null
        }
    }
}

private val SAFE_CELL_URL_SCHEMES = listOf("http://", "https://", "mailto:")

/** Table-cell links open only web and mail URLs; `file:`, `intent:`, `javascript:` and the rest are refused. */
internal fun isSafeCellUrl(url: String): Boolean {
    val trimmed = url.trim()
    return SAFE_CELL_URL_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }
}
