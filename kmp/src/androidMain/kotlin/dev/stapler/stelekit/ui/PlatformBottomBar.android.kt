package dev.stapler.stelekit.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.isMobile

private enum class BottomNavItem(
    val icon: ImageVector,
    val label: String
) {
    JOURNALS(Icons.Default.AutoStories, "Journals"),
    ALL_PAGES(Icons.AutoMirrored.Filled.List, "Pages"),
    SEARCH(Icons.Default.Search, "Search"),
    NOTIFICATIONS(Icons.Default.Notifications, "Notifications");

    fun matchesScreen(screen: Screen): Boolean = when (this) {
        JOURNALS      -> screen is Screen.Journals
        ALL_PAGES     -> screen is Screen.AllPages || screen is Screen.PageView
        // Search is a dialog, not a navigation destination — never appears "selected"
        SEARCH        -> false
        NOTIFICATIONS -> screen is Screen.Notifications
    }
}

@Composable
actual fun PlatformBottomBar(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onSearch: () -> Unit,
    isLeftHanded: Boolean
) {
    if (!LocalWindowSizeClass.current.isMobile) return
    // Hide the nav bar when the keyboard is open — the editing toolbar takes its place,
    // and keeping the nav bar visible creates a gap between toolbar and keyboard.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (imeVisible) return
    val items = if (isLeftHanded) BottomNavItem.entries.reversed() else BottomNavItem.entries
    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.matchesScreen(currentScreen),
                onClick = {
                    when (item) {
                        BottomNavItem.SEARCH        -> onSearch()
                        BottomNavItem.JOURNALS      -> onNavigate(Screen.Journals)
                        BottomNavItem.ALL_PAGES     -> onNavigate(Screen.AllPages)
                        BottomNavItem.NOTIFICATIONS -> onNavigate(Screen.Notifications)
                    }
                },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) }
            )
        }
    }
}
