package dev.stapler.stelekit.table.formula


/**
 * @property armed a tap inserts a reference (drives "Tap a cell to insert its reference")
 * @property justInserted a point-mode reference was inserted and nothing typed since, so a tap replaces it
 * @property stoppedForThisEdit set by "Stop inserting"; cleared only when a new edit starts
 */
data class PointModeState(
    val armed: Boolean = false,
    val justInserted: Boolean = false,
    val stoppedForThisEdit: Boolean = false,
)

/** ux.md S7: when a tap on another cell inserts a reference instead of committing. */
object PointModeRule {
    private val triggers = setOf("+", "-", "*", "/", "^", "<", ">", "<=", ">=", "<>", "=", "(", ",")
    private val binaryOps = setOf("+", "-", "*", "/", "^", "<", ">", "<=", ">=", "<>", "=")

    fun allows(draft: String, caret: Int): Boolean {
        if (draft.isEmpty() || draft[0] != '=' || caret < 1 || caret > draft.length) return false
        val whole = Tokenizer.tokenize(draft, from = 1)
        if (whole.any { it !is Token.End && it.start < caret && caret < it.end }) return false
        val prefix = Tokenizer.tokenize(draft.substring(0, caret), from = 1).filter { it !is Token.End }
        return endsExpectingOperandAfterTrigger(prefix)
    }

    fun allows(draft: String, caret: Int, state: PointModeState): Boolean =
        !state.stoppedForThisEdit && allows(draft, caret)

    fun allowsReplace(state: PointModeState): Boolean = state.justInserted && !state.stoppedForThisEdit

    fun onEditStarted(): PointModeState = PointModeState()

    /** Any typed character or caret move: re-evaluates arming and ends the replace window. */
    fun onDraftChanged(state: PointModeState, draft: String, caret: Int): PointModeState =
        state.copy(armed = allows(draft, caret, state), justInserted = false)

    fun onReferenceInserted(state: PointModeState): PointModeState =
        if (state.stoppedForThisEdit) state else state.copy(armed = true, justInserted = true)

    fun stop(state: PointModeState): PointModeState =
        state.copy(armed = false, justInserted = false, stoppedForThisEdit = true)

    fun onDone(state: PointModeState): PointModeState = state.copy(armed = false, justInserted = false)

    /** Valid prefix of the closed grammar that ends where an operand may start right after a trigger. */
    private fun endsExpectingOperandAfterTrigger(tokens: List<Token>): Boolean {
        var expectOperand = true
        var depth = 0
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (expectOperand) {
                when {
                    t is Token.Number || t is Token.Text || t is Token.CellRef || t is Token.RefErr -> expectOperand = false
                    t is Token.Ident -> {
                        val open = tokens.getOrNull(i + 1)
                        if (open !is Token.Op || open.symbol != "(") return false
                        depth++
                        i++
                    }
                    t is Token.Op && t.symbol == "(" -> depth++
                    t is Token.Op && (t.symbol == "+" || t.symbol == "-") -> Unit
                    else -> return false
                }
            } else {
                if (t !is Token.Op) return false
                when {
                    t.symbol in binaryOps -> expectOperand = true
                    t.symbol == "%" -> Unit
                    t.symbol == ")" && depth > 0 -> depth--
                    t.symbol == "," && depth > 0 -> expectOperand = true
                    t.symbol == ":" -> expectOperand = true
                    else -> return false
                }
            }
            i++
        }
        val last = tokens.lastOrNull() as? Token.Op
        return expectOperand && (last == null || last.symbol in triggers)
    }
}
