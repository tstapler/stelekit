// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.git.NotificationDestination
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The standalone sync status sheet's open and close paths: chip, Settings, banner and notification destinations. */
class StelekitViewModelSyncStatusSheetTest {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun makeViewModel(): StelekitViewModel {
        val fileSystem = FakeFileSystem()
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(fileSystem),
                platformSettings = InMemorySettings(),
                scope = scope,
            ),
        )
    }

    @Test
    fun `the status sheet destination opens the sheet rather than Settings`() {
        val vm = makeViewModel()
        vm.openSyncDestination(NotificationDestination.SyncStatusSheet("g"))
        assertTrue(vm.uiState.value.syncStatusSheetVisible)
        assertFalse(vm.uiState.value.settingsVisible)
        assertNull(vm.uiState.value.syncStatusNotice)
    }

    @Test
    fun `a notification for a vanished graph opens the current graph's sheet with the notice`() {
        val vm = makeViewModel()
        vm.openSyncDestination(NotificationDestination.CurrentGraphSheet("Updates are waiting for Notes"))
        assertTrue(vm.uiState.value.syncStatusSheetVisible)
        assertEquals("Updates are waiting for Notes", vm.uiState.value.syncStatusNotice)
    }

    @Test
    fun `an up to date destination opens nothing`() {
        val vm = makeViewModel()
        vm.openSyncDestination(NotificationDestination.UpToDate("g"))
        assertFalse(vm.uiState.value.syncStatusSheetVisible)
    }

    @Test
    fun `opening from Settings closes Settings and dismissing clears the notice`() {
        val vm = makeViewModel()
        vm.setSettingsVisible(true)
        vm.openSyncStatusFromSettings()
        assertTrue(vm.uiState.value.syncStatusSheetVisible)
        assertFalse(vm.uiState.value.settingsVisible)

        vm.openSyncStatusSheet("note")
        vm.dismissSyncStatusSheet()
        assertFalse(vm.uiState.value.syncStatusSheetVisible)
        assertNull(vm.uiState.value.syncStatusNotice)
    }

    @Test
    fun `the sheet's settings button swaps the sheet for Settings`() {
        val vm = makeViewModel()
        vm.openSyncStatusSheet()
        vm.openSyncSettingsFromStatusSheet()
        assertFalse(vm.uiState.value.syncStatusSheetVisible)
        assertTrue(vm.uiState.value.settingsVisible)
    }
}
