package dev.stapler.stelekit.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.LibraryRepository

/**
 * Keeps one [LibraryViewModel] alive across navigation so filters, search and scroll position survive Back
 * from the editor or detail (LD-4). Owned by the router; closed when the router leaves composition.
 */
class LibrarySession(private val repository: LibraryRepository, private val settings: Settings?) {
    private var viewModel: LibraryViewModel? = null

    fun viewModel(): LibraryViewModel = viewModel ?: LibraryViewModel(repository, settings).also { viewModel = it }

    fun close() {
        viewModel?.close()
        viewModel = null
    }
}

/** Router entry for `Screen.Library`: applies the entry-point filter, refreshes on re-entry, wires navigation. */
@Composable
fun LibraryRoute(
    session: LibrarySession,
    screen: dev.stapler.stelekit.ui.Screen.Library,
    onboarding: LibraryOnboardingState,
    onOpenAnnotationEditor: (String) -> Unit,
    onOpenAsset: (String) -> Unit,
    onLibraryOpen: () -> Unit,
    onDismissTip: () -> Unit,
    onDismissNotice: () -> Unit,
    onAddPhoto: (() -> Unit)?,
) {
    val viewModel = remember(session) { session.viewModel() }
    val currentOnLibraryOpen by rememberUpdatedState(onLibraryOpen)
    LaunchedEffect(screen) {
        viewModel.applyEntryPoint(screen.initialFilter, screen.initialSearch)
        viewModel.reload()
        currentOnLibraryOpen()
    }
    LibraryScreen(
        viewModel = viewModel,
        onOpenAnnotationEditor = onOpenAnnotationEditor,
        onOpenAsset = onOpenAsset,
        onboarding = onboarding,
        onDismissTip = onDismissTip,
        onDismissNotice = onDismissNotice,
        onShowAnnotations = { viewModel.applyEntryPoint(LibraryFilter.Annotated, null) },
        onAddPhoto = onAddPhoto,
    )
}
