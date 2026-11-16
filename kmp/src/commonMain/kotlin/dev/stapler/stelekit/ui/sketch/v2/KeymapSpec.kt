package dev.stapler.stelekit.ui.sketch.v2

/**
 * The single data table the key dispatcher (Task 3.6.1a) and the shortcuts sheet (Task 3.6.1b) both read.
 * Pure: no Compose, no platform types. Decisions recorded here (plan Story 3.6.1, D-4, D-8, UX-85, UX-100,
 * UX-108, UX-111):
 *  - Tool letters match the PRODUCED character, so AZERTY and Dvorak users get the letter on the keycap.
 *    Only brackets fall back to the physical key, because "[" and "]" need a modifier on many layouts.
 *  - On macOS every Ctrl chord is bound to Cmd and a Ctrl press never fires it; Ctrl+Y redo is not on macOS.
 *  - K deviates from Excalidraw, which binds K to the laser pointer: here K opens the Constrain menu (D-8,
 *    laser is out of scope). The sheet never claims K parity; see [KeymapSpec.excalidrawDeviations].
 */
enum class SketchToolId(val id: String) {
    Select("select"), Hand("hand"), Rectangle("rectangle"), Diamond("diamond"), Ellipse("ellipse"),
    Arrow("arrow"), Line("line"), Freedraw("freedraw"), Text("text"), Image("image"), Frame("frame"),
    Eraser("eraser"), Point("point"), Arc("arc"),
}

enum class KeymapGroup { Tools, Edit, Arrange, View, Pan, Constrain }

enum class Modifier { Primary, Shift, Alt }

/** Keys matched by position when the produced character is not the binding's key (layout needs a modifier). */
enum class PhysicalKey { BracketLeft, BracketRight }

enum class Availability { Everywhere, NotMac, NotWeb, WebOnly }

/**
 * [key] is the produced character or a named key ("ArrowUp", "Delete", "Escape", "F6", "PageUp", "Home", "?").
 * [singleKey] marks an unmodified printable shortcut: active only while the canvas has focus and the
 * `SingleKeyShortcuts` setting is on (WCAG 2.1.4).
 */
data class KeyBinding(
    val key: String,
    val modifiers: Set<Modifier> = emptySet(),
    val physicalFallback: PhysicalKey? = null,
    val singleKey: Boolean = false,
    val availability: Availability = Availability.Everywhere,
)

data class KeymapEntry(val id: String, val group: KeymapGroup, val label: String, val bindings: List<KeyBinding>)

data class KeymapEnv(val isMac: Boolean, val isWeb: Boolean)

data class KeyPress(
    val produced: String?,
    val physical: PhysicalKey? = null,
    val ctrl: Boolean = false,
    val meta: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** What the user's layout offers, for the shortcuts sheet. */
interface KeyboardLayoutInfo {
    fun canTypeUnmodified(character: String): Boolean
    fun physicalKeyLabel(key: PhysicalKey): String
}

data class ExcalidrawDeviation(val key: String, val excalidraw: String, val here: String)

data class GateItem(val id: String, val what: String, val status: GateStatus)

enum class GateStatus { Recorded, Defaulted }

/** U8, U11 and U12 must each be recorded or defaulted before the map is frozen (D-4). */
data class KeymapGate(val items: List<GateItem>) {
    val frozen: Boolean get() = items.all { it.status == GateStatus.Recorded || it.status == GateStatus.Defaulted }

    /** Shown on the shortcuts sheet while any item rests on a default; null once all are recorded. */
    val footnote: String? get() =
        if (items.any { it.status == GateStatus.Defaulted }) FOOTNOTE_DEFAULTED else null

    companion object {
        const val FOOTNOTE_DEFAULTED = "not verified against the live Excalidraw dialog or your screen reader"
    }
}

object KeymapSpec {
    // Declared first: the entry lists below read these during object initialisation.
    private val ARROWS = listOf("ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight")
    private val SHIFTED_CHARACTERS = setOf("?", "+")

    private fun tool(id: SketchToolId, vararg keys: String) = KeymapEntry(
        id.id, KeymapGroup.Tools, id.name,
        keys.map { KeyBinding(it, singleKey = true) },
    )

    private val primary = setOf(Modifier.Primary)
    private val primaryShift = setOf(Modifier.Primary, Modifier.Shift)

    private fun chord(id: String, group: KeymapGroup, label: String, key: String, mods: Set<Modifier> = primary) =
        KeymapEntry(id, group, label, listOf(KeyBinding(key, mods)))

    private fun bracket(id: String, label: String, key: String, physical: PhysicalKey, mods: Set<Modifier>) =
        KeymapEntry(id, KeymapGroup.Arrange, label, listOf(KeyBinding(key, mods, physicalFallback = physical)))

    /** The 14 tools in the order of the chrome table: digits 1-9 and 0 are Excalidraw's tool digits. */
    val tools: List<KeymapEntry> = listOf(
        tool(SketchToolId.Select, "v", "1"),
        tool(SketchToolId.Hand, "h"),
        tool(SketchToolId.Rectangle, "r", "2"),
        tool(SketchToolId.Diamond, "d", "3"),
        tool(SketchToolId.Ellipse, "o", "4"),
        tool(SketchToolId.Arrow, "a", "5"),
        tool(SketchToolId.Line, "l", "6"),
        tool(SketchToolId.Freedraw, "p", "7"),
        tool(SketchToolId.Text, "t", "8"),
        tool(SketchToolId.Image, "9"),
        tool(SketchToolId.Frame, "f"),
        tool(SketchToolId.Eraser, "e", "0"),
        tool(SketchToolId.Point, "x"),
        tool(SketchToolId.Arc, "c"),
    )

    const val TOOL_LOCK_ID = "toolLock"
    const val CONSTRAIN_ID = "constrain"

    val others: List<KeymapEntry> = listOf(
        KeymapEntry(TOOL_LOCK_ID, KeymapGroup.Tools, "Tool lock", listOf(KeyBinding("q", singleKey = true))),
        KeymapEntry(
            "undo", KeymapGroup.Edit, "Undo", listOf(KeyBinding("z", primary)),
        ),
        KeymapEntry(
            "redo", KeymapGroup.Edit, "Redo",
            listOf(
                KeyBinding("z", primaryShift),
                KeyBinding("y", primary, availability = Availability.NotMac),
            ),
        ),
        chord("copy", KeymapGroup.Edit, "Copy", "c"),
        chord("paste", KeymapGroup.Edit, "Paste", "v"),
        chord("cut", KeymapGroup.Edit, "Cut", "x"),
        chord("duplicate", KeymapGroup.Edit, "Duplicate", "d"),
        chord("group", KeymapGroup.Arrange, "Group", "g"),
        chord("ungroup", KeymapGroup.Arrange, "Ungroup", "g", primaryShift),
        bracket("sendBackward", "Send backward", "[", PhysicalKey.BracketLeft, primary),
        bracket("bringForward", "Bring forward", "]", PhysicalKey.BracketRight, primary),
        bracket("sendToBack", "Send to back", "[", PhysicalKey.BracketLeft, primaryShift),
        bracket("bringToFront", "Bring to front", "]", PhysicalKey.BracketRight, primaryShift),
        KeymapEntry("delete", KeymapGroup.Edit, "Delete", listOf(KeyBinding("Delete"))),
        KeymapEntry("nudge", KeymapGroup.Edit, "Nudge selection (Shift: 10 units)", ARROWS.map { KeyBinding(it) }),
        KeymapEntry("nudgeFar", KeymapGroup.Edit, "Nudge selection by 10 units", ARROWS.map { KeyBinding(it, setOf(Modifier.Shift)) }),
        chord("resetZoom", KeymapGroup.View, "Reset zoom", "0"),
        chord("zoomIn", KeymapGroup.View, "Zoom in", "+"),
        chord("zoomOut", KeymapGroup.View, "Zoom out", "-"),
        KeymapEntry("zoomToFit", KeymapGroup.View, "Zoom to fit", listOf(KeyBinding("1", setOf(Modifier.Shift)))),
        chord("grid", KeymapGroup.View, "Grid", "'"),
        KeymapEntry("snap", KeymapGroup.View, "Snap", listOf(KeyBinding("s", setOf(Modifier.Alt)))),
        KeymapEntry("shortcutsSheet", KeymapGroup.View, "Shortcuts", listOf(KeyBinding("?"))),
        KeymapEntry("nextRegion", KeymapGroup.View, "Next region", listOf(KeyBinding("F6"))),
        KeymapEntry("previousRegion", KeymapGroup.View, "Previous region", listOf(KeyBinding("F6", setOf(Modifier.Shift)))),
        KeymapEntry("escape", KeymapGroup.View, "Close, cancel or return to the canvas", listOf(KeyBinding("Escape"))),
        KeymapEntry("pan", KeymapGroup.Pan, "Pan the view (nothing selected, or Hand tool)", ARROWS.map { KeyBinding(it) }),
        KeymapEntry("panFar", KeymapGroup.Pan, "Pan the view by 200 dp", ARROWS.map { KeyBinding(it, setOf(Modifier.Shift)) }),
        KeymapEntry("panAlways", KeymapGroup.Pan, "Pan the view with a selection", ARROWS.map { KeyBinding(it, primary) }),
        KeymapEntry("panPage", KeymapGroup.Pan, "Pan one screen", listOf(KeyBinding("PageUp"), KeyBinding("PageDown"))),
        KeymapEntry("panHome", KeymapGroup.Pan, "Fit to content", listOf(KeyBinding("Home"))),
        KeymapEntry(
            CONSTRAIN_ID, KeymapGroup.Constrain, "Constrain menu",
            listOf(
                KeyBinding("k", singleKey = true),
                KeyBinding("k", primaryShift, availability = Availability.NotWeb),
                KeyBinding("k", primary, availability = Availability.WebOnly),
            ),
        ),
    )

    val entries: List<KeymapEntry> = tools + others

    val excalidrawDeviations: List<ExcalidrawDeviation> = listOf(
        ExcalidrawDeviation(
            key = "K",
            excalidraw = "laser pointer (v0.18.1 HelpDialog.tsx)",
            here = "Constrain menu (D-8); the laser tool is out of scope, so no behaviour clashes",
        ),
    )

    /** Keys the shortcuts sheet may describe as "same as Excalidraw". */
    fun claimsExcalidrawParity(key: String): Boolean =
        excalidrawDeviations.none { it.key.equals(key, ignoreCase = true) }

    /**
     * U8 is checked against the HelpDialog.tsx SOURCE at v0.18.1 only (research/features.md section 14);
     * U11 needs the owner's screen reader; U12 was answered by Spike 0.1.4 (research/input-spike.md).
     */
    val gate: KeymapGate = KeymapGate(
        listOf(
            GateItem("U8", "shortcuts and tool lock checked against the live Excalidraw dialog", GateStatus.Defaulted),
            GateItem("U11", "NVDA and JAWS quick keys checked against the owner's reader", GateStatus.Defaulted),
            GateItem("U12", "stylus eraser-end and barrel availability", GateStatus.Recorded),
        ),
    )

    fun isAvailable(binding: KeyBinding, env: KeymapEnv): Boolean = when (binding.availability) {
        Availability.Everywhere -> true
        Availability.NotMac -> !env.isMac
        Availability.NotWeb -> !env.isWeb
        Availability.WebOnly -> env.isWeb
    }

    fun matches(binding: KeyBinding, press: KeyPress, env: KeymapEnv): Boolean {
        if (!isAvailable(binding, env)) return false
        val primaryDown = if (env.isMac) press.meta else press.ctrl
        val strayPrimary = if (env.isMac) press.ctrl else press.meta
        if (strayPrimary || primaryDown != (Modifier.Primary in binding.modifiers)) return false
        if (press.shift != (Modifier.Shift in binding.modifiers) && !isShiftedCharacter(binding, press)) return false
        if (press.alt != (Modifier.Alt in binding.modifiers)) return false
        val produced = press.produced
        if (produced != null && produced.equals(binding.key, ignoreCase = true)) return true
        return binding.physicalFallback != null && press.physical == binding.physicalFallback
    }

    /** "?" and "+" are produced with Shift on most layouts; the binding does not require it. */
    private fun isShiftedCharacter(binding: KeyBinding, press: KeyPress): Boolean =
        press.shift && Modifier.Shift !in binding.modifiers && binding.key in SHIFTED_CHARACTERS && press.produced == binding.key

    fun entriesFor(press: KeyPress, env: KeymapEnv): List<KeymapEntry> =
        entries.filter { entry -> entry.bindings.any { matches(it, press, env) } }

    /** The text the sheet shows: Cmd on macOS, and the key the layout needs when it cannot type the bracket. */
    fun displayLabel(binding: KeyBinding, env: KeymapEnv, layout: KeyboardLayoutInfo? = null): String {
        val primaryName = if (env.isMac) "Cmd" else "Ctrl"
        val parts = binding.modifiers.sortedBy { it.ordinal }.map {
            when (it) {
                Modifier.Primary -> primaryName
                Modifier.Shift -> "Shift"
                Modifier.Alt -> "Alt"
            }
        }
        val fallback = binding.physicalFallback
        val keyText = if (fallback != null && layout != null && !layout.canTypeUnmodified(binding.key)) {
            layout.physicalKeyLabel(fallback)
        } else {
            binding.key.uppercase()
        }
        return (parts + keyText).joinToString("+")
    }
}
