package dev.stapler.stelekit.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun MainLayout(
    sidebarExpanded: Boolean = false,
    onSidebarDismiss: () -> Unit = {},
    topBar: @Composable () -> Unit,
    leftSidebar: @Composable () -> Unit,
    rightSidebar: @Composable () -> Unit,
    content: @Composable () -> Unit,
    statusBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val isMobile = LocalWindowSizeClass.current.isMobile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
        // statusBarsPadding removed — TopBar owns the status bar inset on mobile (edge-to-edge)
    ) {
        // Top Menu Bar
        topBar()

        // Main Content Area
        if (isMobile) {
            val sidebarFocusRequester = remember { FocusRequester() }

            LaunchedEffect(sidebarExpanded) {
                if (sidebarExpanded) {
                    try { sidebarFocusRequester.requestFocus() } catch (_: Exception) {}
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Content always fills full width on mobile
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("content-area")
                ) {
                    content()
                }

                // Scrim behind sidebar when open — animated alpha avoids ColumnScope overload
                val scrimAlpha = animateFloatAsState(
                    targetValue = if (sidebarExpanded) 0.4f else 0f,
                    label = "scrim"
                ).value
                if (scrimAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = scrimAlpha))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onSidebarDismiss
                            )
                    )
                }

                // Sidebar overlays content on mobile
                Box(
                    modifier = Modifier
                        .testTag("left-sidebar")
                        .focusRequester(sidebarFocusRequester)
                        .focusable()
                ) { leftSidebar() }
            }
        } else {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.testTag("left-sidebar")) { leftSidebar() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("content-area")
                ) {
                    content()
                }

                rightSidebar()
            }
        }

        // Status Bar
        statusBar()

        // Bottom navigation bar (Android phone only via PlatformBottomBar).
        // NavigationBar internally handles WindowInsets.navigationBars, so no manual
        // navigationBarsPadding() Spacer is needed here.
        bottomBar()
    }
}
