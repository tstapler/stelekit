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
class LibrarySession(
    private val repository: LibraryRepository,
    private val settings: Settings?,
    private val fileExists: ((String) -> Boolean)? = null,
    private val deleteAsset: (suspend (String) -> arrow.core.Either<dev.stapler.stelekit.error.DomainError, Unit>)? = null,
) {
    private var viewModel: LibraryViewModel? = null

    fun viewModel(): LibraryViewModel =
        viewModel ?: LibraryViewModel(repository, settings, fileExists = fileExists, deleteAsset = deleteAsset).also { viewModel = it }

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
    onCopyLink: (String) -> Unit = {},
    onAnnotate: (suspend (LibraryItem) -> arrow.core.Either<dev.stapler.stelekit.ui.annotate.OpenForAnnotationError, String>)? = null,
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
        onCopyLink = onCopyLink,
        onAnnotate = onAnnotate,
    )
}

/** The Library's delete action: one asset row removed through the write actor (or directly where none exists). */
@OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)
fun libraryAssetDeleter(
    assets: dev.stapler.stelekit.repository.AssetRepository,
    actor: dev.stapler.stelekit.db.DatabaseWriteActor?,
): suspend (String) -> arrow.core.Either<dev.stapler.stelekit.error.DomainError, Unit> = { id ->
    val uuid = dev.stapler.stelekit.asset.AssetUuid(id)
    if (actor != null) actor.execute { assets.deleteAsset(uuid) } else assets.deleteAsset(uuid)
}
