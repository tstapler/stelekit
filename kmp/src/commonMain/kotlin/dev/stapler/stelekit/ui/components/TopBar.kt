package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.isDynamicColorSupported
import dev.stapler.stelekit.ui.AppState
import dev.stapler.stelekit.ui.LocalWindowSizeClass
import dev.stapler.stelekit.ui.isMobile
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.ui.theme.StelekitThemeMode

@Composable
fun TopBar(
    appState: AppState,
    platformSettings: PlatformSettings,
    onSettingsClick: () -> Unit,
    onNewPageClick: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onThemeChange: (StelekitThemeMode) -> Unit,
    onLanguageChange: (Language) -> Unit,
    onResetOnboarding: () -> Unit,
    onToggleDebug: () -> Unit,
    onGoBack: () -> Unit = {},
    onGoForward: () -> Unit = {},
    onMenuToggle: () -> Unit = {},
    onExportPage: ((formatId: String) -> Unit)? = null,
    onShowDebugMenu: (() -> Unit)? = null,
) {
    val isMobile = LocalWindowSizeClass.current.isMobile
    var viewMenuExpanded by remember { mutableStateOf(false) }
    var fileMenuExpanded by remember { mutableStateOf(false) }
    var overflowMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .then(
                if (isMobile)
                    // On mobile (edge-to-edge), TopBar owns the status bar inset.
                    // Do NOT set a fixed height before consuming the inset — the status bar
                    // padding would eat into the fixed height leaving no room for buttons.
                    // Let the Row grow naturally: statusBar height + button content height.
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                else
                    Modifier.height(40.dp)
            )
            .padding(horizontal = 8.dp)
            .testTag("top-bar"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMobile) {
            // Mobile: hamburger + nav buttons + title spacer + actions + overflow
            IconButton(onClick = onMenuToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Toggle Menu",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }

            IconButton(
                onClick = onGoBack,
                enabled = appState.canGoBack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Go Back",
                    modifier = Modifier.size(20.dp),
                    tint = if (appState.canGoBack)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }

            IconButton(
                onClick = onGoForward,
                enabled = appState.canGoForward,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Go Forward",
                    modifier = Modifier.size(20.dp),
                    tint = if (appState.canGoForward)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onNewPageClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New Page", modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            IconButton(onClick = onSettingsClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Settings, contentDescription = t("common.settings"), modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box {
                IconButton(
                    onClick = { overflowMenuExpanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = overflowMenuExpanded,
                    onDismissRequest = { overflowMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(t("menu.switch_graph")) },
                        onClick = {
                            overflowMenuExpanded = false
                            onResetOnboarding()
                        }
                    )
                    HorizontalDivider()
                    Text(
                        t("settings.language"),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Language.entries.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label) },
                            leadingIcon = {
                                if (appState.language == lang) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            },
                            onClick = {
                                onLanguageChange(lang)
                                overflowMenuExpanded = false
                            }
                        )
                    }
                    HorizontalDivider()
                    StelekitThemeMode.entries
                        .filter { it != StelekitThemeMode.DYNAMIC || isDynamicColorSupported() }
                        .forEach { mode ->
                            DropdownMenuItem(
                                text = { Text("${themeModeLabel(mode)} ${t("common.theme")}") },
                                leadingIcon = {
                                    if (appState.themeMode == mode) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    onThemeChange(mode)
                                    overflowMenuExpanded = false
                                }
                            )
                        }
                    HorizontalDivider()
                    Text(
                        "Developer",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    DropdownMenuItem(
                        text = { Text("Logs") },
                        onClick = {
                            overflowMenuExpanded = false
                            onNavigate(Screen.Logs)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Performance") },
                        onClick = {
                            overflowMenuExpanded = false
                            onNavigate(Screen.Performance)
                        }
                    )
                }
            }
        } else {
            // Desktop: text menus + nav buttons + spacer + action buttons
            IconButton(onClick = onMenuToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Toggle Menu",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                TextButton(
                    onClick = { fileMenuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(t("menu.file"), style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(
                    expanded = fileMenuExpanded,
                    onDismissRequest = { fileMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(t("menu.switch_graph")) },
                        onClick = {
                            fileMenuExpanded = false
                            onResetOnboarding()
                        }
                    )
                    HorizontalDivider()
                    val exportFormats = listOf(
                        "markdown" to "Export as Markdown",
                        "plain-text" to "Export as Plain Text",
                        "html" to "Export as HTML",
                        "json" to "Export as JSON"
                    )
                    exportFormats.forEach { (formatId, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            enabled = appState.currentPage != null,
                            onClick = {
                                fileMenuExpanded = false
                                onExportPage?.invoke(formatId)
                            }
                        )
                    }
                }
            }
            TextButton(
                onClick = {},
                enabled = false,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    t("menu.edit"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                )
            }

            Box {
                TextButton(
                    onClick = { viewMenuExpanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(t("menu.view"), style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(
                    expanded = viewMenuExpanded,
                    onDismissRequest = { viewMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Performance Dashboard") },
                        onClick = {
                            onNavigate(Screen.Performance)
                            viewMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Logs") },
                        onClick = {
                            onNavigate(Screen.Logs)
                            viewMenuExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (appState.isDebugMode) "Hide Debug Info" else "Show Debug Info") },
                        onClick = {
                            onToggleDebug()
                            viewMenuExpanded = false
                        }
                    )
                    if (onShowDebugMenu != null) {
                        DropdownMenuItem(
                            text = { Text("Debug Menu  ⌘⇧D") },
                            onClick = {
                                viewMenuExpanded = false
                                onShowDebugMenu()
                            }
                        )
                    }
                    HorizontalDivider()
                    Text(
                        t("settings.language"),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Language.entries.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label) },
                            leadingIcon = {
                                if (appState.language == lang) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            },
                            onClick = {
                                onLanguageChange(lang)
                                viewMenuExpanded = false
                            }
                        )
                    }
                    HorizontalDivider()
                    StelekitThemeMode.entries
                        .filter { it != StelekitThemeMode.DYNAMIC || isDynamicColorSupported() }
                        .forEach { mode ->
                            DropdownMenuItem(
                                text = { Text("${themeModeLabel(mode)} ${t("common.theme")}") },
                                leadingIcon = {
                                    if (appState.themeMode == mode) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    onThemeChange(mode)
                                    viewMenuExpanded = false
                                }
                            )
                        }
                }
            }

            TextButton(
                onClick = {},
                enabled = false,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    t("menu.help"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            IconButton(
                onClick = onGoBack,
                enabled = appState.canGoBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Go Back",
                    modifier = Modifier.size(18.dp),
                    tint = if (appState.canGoBack)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
            IconButton(
                onClick = onGoForward,
                enabled = appState.canGoForward,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Go Forward",
                    modifier = Modifier.size(18.dp),
                    tint = if (appState.canGoForward)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(onClick = onNewPageClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = "New Page", modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            IconButton(onClick = onSettingsClick, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Settings, contentDescription = t("common.settings"), modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
