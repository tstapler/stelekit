package dev.stapler.stelekit.ui.nav

import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.StelekitViewModelTestHarness
import dev.stapler.stelekit.ui.library.LibraryFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Old Gallery / AssetBrowser values still resolve (plan D.11a): no alias ever reaches the back stack. */
class LibraryRedirectTest {
    @Test
    fun navigatingToGallery_landsOnLibraryAnnotated_andBackReturnsToThePreviousScreen() {
        val vm = StelekitViewModelTestHarness().viewModel
        vm.navigateTo(Screen.Flashcards)
        vm.navigateTo(Screen.Gallery)

        val state = vm.uiState.value
        assertEquals(Screen.Library(initialFilter = LibraryFilter.Annotated), state.currentScreen)
        assertTrue(state.navigationHistory.none { it is Screen.Gallery || it is Screen.AssetBrowser })

        vm.goBack()
        assertEquals(Screen.Flashcards, vm.uiState.value.currentScreen)
    }

    @Test
    fun navigatingToAssetBrowser_carriesTheSearch() {
        val vm = StelekitViewModelTestHarness().viewModel
        vm.navigateTo(Screen.AssetBrowser("kitchen"))
        assertEquals(Screen.Library(initialSearch = "kitchen"), vm.uiState.value.currentScreen)
    }

    @Test
    fun navigateToGalleryHelper_opensTheAnnotatedLibrary() {
        val vm = StelekitViewModelTestHarness().viewModel
        vm.navigateToGallery()
        assertEquals(Screen.Library(initialFilter = LibraryFilter.Annotated), vm.uiState.value.currentScreen)
    }

    @Test
    fun otherScreensAreNotRewritten() {
        val vm = StelekitViewModelTestHarness().viewModel
        vm.navigateTo(Screen.AllPages())
        assertEquals(Screen.AllPages(), vm.uiState.value.currentScreen)
    }

    @Test
    fun paletteExposesGoToLibraryAndShowAnnotated() {
        val vm = StelekitViewModelTestHarness().viewModel
        vm.navigateTo(Screen.Journals) // refreshes the command list
        val labels = vm.uiState.value.commands.map { it.label }
        // updateCommands runs asynchronously on the harness scope (Unconfined), so it has completed.
        assertTrue("Go to Library" in labels, "palette labels: $labels")
        assertTrue("Show annotated photos" in labels, "palette labels: $labels")
    }
}
