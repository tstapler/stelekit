package dev.stapler.stelekit.table

import dev.stapler.stelekit.table.TableEdit.ClearRange
import dev.stapler.stelekit.table.TableEdit.DeleteCols
import dev.stapler.stelekit.table.TableEdit.DeleteRows
import dev.stapler.stelekit.table.TableEdit.InsertCols
import dev.stapler.stelekit.table.TableEdit.InsertRows
import dev.stapler.stelekit.table.TableEdit.MoveCols
import dev.stapler.stelekit.table.TableEdit.MoveRows
import dev.stapler.stelekit.table.TableEdit.PasteRange
import dev.stapler.stelekit.table.TableEdit.SetAlignment
import dev.stapler.stelekit.table.TableEdit.SetCell
import dev.stapler.stelekit.table.TableEdit.SortMove
import dev.stapler.stelekit.table.TableEdit.SortRows

/**
 * Pure facade: one [TableEdit] in, one [TableEditOutcome] out. [reference] rewrites every formula cell
 * after a structural edit or sort (null result = untouched); the default leaves formulas alone.
 * A result equal to the input doc is reported as `Applied(doc, NoChange)`, never as a new doc.
 */
class TableEditor(
    reference: FormulaReferenceTransform = FormulaReferenceTransform.None,
    sortKeyOf: SortKeyOf = SortKeyOf.Literal,
) {
    private val structural = StructuralOps(reference)
    private val sort = SortOp(reference, sortKeyOf)

    fun apply(doc: TableDoc, edit: TableEdit): TableEditOutcome {
        val outcome = when (edit) {
            is SetCell -> structural.setCell(doc, edit)
            is ClearRange -> structural.clearRange(doc, edit)
            is InsertRows -> structural.insertRows(doc, edit)
            is InsertCols -> structural.insertCols(doc, edit)
            is DeleteRows -> structural.deleteRows(doc, edit)
            is DeleteCols -> structural.deleteCols(doc, edit)
            is MoveRows -> structural.moveRows(doc, edit)
            is MoveCols -> structural.moveCols(doc, edit)
            is SetAlignment -> structural.setAlignment(doc, edit)
            is SortRows -> sort.apply(doc, edit)
            is PasteRange -> PasteOp.apply(doc, edit)
            is SortMove -> TableEditOutcome.Refused(RefusalReason.OutOfRange)
        }
        return if (outcome is TableEditOutcome.Applied && outcome.summary !is EditSummary.NoChange && outcome.doc == doc) {
            TableEditOutcome.Applied(doc, EditSummary.NoChange)
        } else {
            outcome
        }
    }
}
