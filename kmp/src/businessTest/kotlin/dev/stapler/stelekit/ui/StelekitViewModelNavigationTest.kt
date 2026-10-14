// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StelekitViewModelNavigationTest {

    private val harness = StelekitViewModelTestHarness()
    private val vm = harness.viewModel
    private val state get() = vm.uiState.value

    @AfterTest
    fun tearDown() = harness.scope.cancel()

    @Test
    fun `goBack at index 0 is a no-op`() {
        assertEquals(0, state.historyIndex)
        assertFalse(vm.goBack())
        assertEquals(0, state.historyIndex)
        assertEquals(Screen.Journals, state.currentScreen)
    }

    @Test
    fun `goForward at the last history item is a no-op`() {
        vm.navigateTo(Screen.Flashcards)
        val index = state.historyIndex
        assertFalse(state.canGoForward)
        assertFalse(vm.goForward())
        assertEquals(index, state.historyIndex)
        assertEquals(Screen.Flashcards, state.currentScreen)
    }

    @Test
    fun `navigateTo appends to history and moves the index to the end`() {
        vm.navigateTo(Screen.Flashcards)
        vm.navigateTo(Screen.Notifications)
        assertEquals(listOf(Screen.Journals, Screen.Flashcards, Screen.Notifications), state.navigationHistory)
        assertEquals(2, state.historyIndex)
        assertEquals(Screen.Notifications, state.currentScreen)
    }

    @Test
    fun `goBack and goForward walk the history and stop at both ends`() {
        vm.navigateTo(Screen.Flashcards)
        vm.navigateTo(Screen.Notifications)

        assertTrue(vm.goBack())
        assertEquals(Screen.Flashcards, state.currentScreen)
        assertTrue(vm.goBack())
        assertEquals(Screen.Journals, state.currentScreen)
        assertFalse(vm.goBack())

        assertTrue(vm.goForward())
        assertTrue(vm.goForward())
        assertEquals(Screen.Notifications, state.currentScreen)
        assertFalse(vm.goForward())
    }

    @Test
    fun `navigating after goBack discards the forward history`() {
        vm.navigateTo(Screen.Flashcards)
        vm.navigateTo(Screen.Notifications)
        vm.goBack()

        vm.navigateTo(Screen.Logs)

        assertEquals(listOf(Screen.Journals, Screen.Flashcards, Screen.Logs), state.navigationHistory)
        assertEquals(2, state.historyIndex)
        assertFalse(state.canGoForward)
    }

    @Test
    fun `navigateTo with addToHistory false changes screen but not history`() {
        vm.navigateTo(Screen.Flashcards, addToHistory = false)
        assertEquals(Screen.Flashcards, state.currentScreen)
        assertEquals(listOf(Screen.Journals), state.navigationHistory)
        assertEquals(0, state.historyIndex)
    }
}
