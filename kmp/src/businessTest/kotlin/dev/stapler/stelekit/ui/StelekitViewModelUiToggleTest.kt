// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StelekitViewModelUiToggleTest {

    private val harness = StelekitViewModelTestHarness()
    private val vm = harness.viewModel
    private val state get() = vm.uiState.value

    @AfterTest
    fun tearDown() = harness.scope.cancel()

    @Test
    fun `toggleSidebar flips sidebarExpanded each call`() {
        val initial = state.sidebarExpanded
        vm.toggleSidebar()
        assertEquals(!initial, state.sidebarExpanded)
        vm.toggleSidebar()
        assertEquals(initial, state.sidebarExpanded)
    }

    @Test
    fun `toggleRightSidebar flips rightSidebarExpanded and leaves the left one alone`() {
        val left = state.sidebarExpanded
        vm.toggleRightSidebar()
        assertTrue(state.rightSidebarExpanded)
        assertEquals(left, state.sidebarExpanded)
        vm.toggleRightSidebar()
        assertFalse(state.rightSidebarExpanded)
    }

    @Test
    fun `setCommandPaletteVisible shows and hides the palette`() {
        assertFalse(state.commandPaletteVisible)
        vm.setCommandPaletteVisible(true)
        assertTrue(state.commandPaletteVisible)
        vm.setCommandPaletteVisible(false)
        assertFalse(state.commandPaletteVisible)
    }

    @Test
    fun `setSearchDialogVisible carries the initial query while visible`() {
        vm.setSearchDialogVisible(true, initialQuery = "kmp")
        assertTrue(state.searchDialogVisible)
        assertEquals("kmp", state.searchDialogInitialQuery)
    }

    @Test
    fun `hiding the search dialog clears the initial query`() {
        vm.setSearchDialogVisible(true, initialQuery = "kmp")
        vm.setSearchDialogVisible(false, initialQuery = "ignored")
        assertFalse(state.searchDialogVisible)
        assertEquals("", state.searchDialogInitialQuery)
    }

    @Test
    fun `setSettingsVisible toggles the settings flag`() {
        vm.setSettingsVisible(true)
        assertTrue(state.settingsVisible)
        vm.setSettingsVisible(false)
        assertFalse(state.settingsVisible)
    }
}
