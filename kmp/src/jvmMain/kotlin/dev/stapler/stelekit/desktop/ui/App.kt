package dev.stapler.stelekit.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import dev.stapler.stelekit.domain.UrlFetcherJvm
import dev.stapler.stelekit.stats.FileLibraryStatsProvider
import dev.stapler.stelekit.ui.StelekitApp
import dev.stapler.stelekit.platform.PlatformFileSystem

@Composable
fun App(
    windowState: WindowState,
    fileSystem: PlatformFileSystem,
    graphPath: String,
    onExit: () -> Unit
) {
    Window(
        onCloseRequest = onExit,
        title = "Stelekit",
        state = windowState
    ) {
        StelekitApp(
            fileSystem = fileSystem,
            graphPath = graphPath,
            urlFetcher = UrlFetcherJvm(),
            libraryStatsProvider = FileLibraryStatsProvider(),
        )
    }
}
