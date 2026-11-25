package dev.stapler.stelekit.table

enum class EntryProfile { Touch, Hardware }

/**
 * Whether the inline action strip shows (ux.md design rules 10 and 15). [entryProfile] is fixed when
 * `Edit` starts and passed unchanged for the whole edit, so a mid-edit key or tap never changes the answer.
 */
data class TableStripPolicy(val mode: GridMode, val entryProfile: EntryProfile, val conflictHeld: Boolean = false) {
    val visible: Boolean
        get() = mode == GridMode.Navigate || entryProfile == EntryProfile.Touch || conflictHeld

    /** A held conflict in a hardware-entry edit shows only Resolve, Cancel edit and Markdown. */
    val reduced: Boolean
        get() = mode == GridMode.Edit && entryProfile == EntryProfile.Hardware && conflictHeld

    companion object {
        /** TalkBack or Switch Access (a plain flag from the platform) counts as touch. */
        fun profileAtEntry(touchOrPenPointer: Boolean, accessibilityServiceActive: Boolean): EntryProfile =
            if (touchOrPenPointer || accessibilityServiceActive) EntryProfile.Touch else EntryProfile.Hardware
    }
}
