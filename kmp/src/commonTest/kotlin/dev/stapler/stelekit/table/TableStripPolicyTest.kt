package dev.stapler.stelekit.table

import kotlin.test.Test
import kotlin.test.assertEquals

class TableStripPolicyTest {
    private data class Row(
        val mode: GridMode,
        val profile: EntryProfile,
        val conflictHeld: Boolean,
        val visible: Boolean,
        val reduced: Boolean,
    )

    private val rows = listOf(
        Row(GridMode.Navigate, EntryProfile.Touch, false, visible = true, reduced = false),
        Row(GridMode.Navigate, EntryProfile.Hardware, false, visible = true, reduced = false),
        Row(GridMode.Edit, EntryProfile.Touch, false, visible = true, reduced = false),
        Row(GridMode.Edit, EntryProfile.Hardware, false, visible = false, reduced = false),
        Row(GridMode.Navigate, EntryProfile.Touch, true, visible = true, reduced = false),
        Row(GridMode.Navigate, EntryProfile.Hardware, true, visible = true, reduced = false),
        Row(GridMode.Edit, EntryProfile.Touch, true, visible = true, reduced = false),
        Row(GridMode.Edit, EntryProfile.Hardware, true, visible = true, reduced = true),
    )

    @Test
    fun visibility_should_FollowTable_When_ProfileModeAndConflictVary() {
        for (r in rows) {
            val p = TableStripPolicy(r.mode, r.profile, r.conflictHeld)
            assertEquals(r.visible, p.visible, "visible for $r")
            assertEquals(r.reduced, p.reduced, "reduced for $r")
        }
    }

    @Test
    fun profile_should_BeTouch_When_TouchOrPenPointer() {
        assertEquals(EntryProfile.Touch, TableStripPolicy.profileAtEntry(touchOrPenPointer = true, accessibilityServiceActive = false))
    }

    @Test
    fun profile_should_BeHardware_When_MouseOrKeyEntryAndNoAccessibilityService() {
        assertEquals(EntryProfile.Hardware, TableStripPolicy.profileAtEntry(touchOrPenPointer = false, accessibilityServiceActive = false))
    }

    @Test
    fun profile_should_BeTouch_When_AccessibilityServiceActiveEvenForMouseEntry() {
        assertEquals(EntryProfile.Touch, TableStripPolicy.profileAtEntry(touchOrPenPointer = false, accessibilityServiceActive = true))
    }

    @Test
    fun policy_should_NotChange_When_ProfileIsHeldAcrossMidEditKeyOrTap() {
        // The profile is an input fixed at edit entry; a mid-edit key or tap is not an input, so the answer is constant.
        val atEntry = TableStripPolicy.profileAtEntry(touchOrPenPointer = true, accessibilityServiceActive = false)
        val before = TableStripPolicy(GridMode.Edit, atEntry, false)
        val afterBluetoothKey = TableStripPolicy(GridMode.Edit, atEntry, false)
        assertEquals(before.visible, afterBluetoothKey.visible)
        val hw = TableStripPolicy.profileAtEntry(touchOrPenPointer = false, accessibilityServiceActive = false)
        assertEquals(false, TableStripPolicy(GridMode.Edit, hw, false).visible)
    }
}
