package dev.stapler.stelekit.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.components.NavSectionList
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.i18n.t

/**
 * Phone "More": the same grouped, described list as the desktop sidebar (ADR-007), in a modal bottom sheet.
 * [onOpenDrawer] is the deliberate way to the drawer, which still holds graphs, favorites and sync status
 * that the nav model does not cover; opening More itself never touches the drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onGitSetup: () -> Unit,
    onOpenDrawer: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showLibraryNewBadge: Boolean = false,
    homeSketchEnabled: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag("more-sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            NavSectionList(
                currentScreen = currentScreen,
                onNavigate = onNavigate,
                onGitSetup = onGitSetup,
                showLibraryNewBadge = showLibraryNewBadge,
                homeSketchEnabled = homeSketchEnabled,
                onActivate = onDismiss,
            )
            TextButton(
                onClick = { onDismiss(); onOpenDrawer() },
                modifier = Modifier.heightIn(min = 48.dp).testTag("more-sheet-drawer"),
            ) {
                Text(t(I18n.NAV_MORE_DRAWER))
            }
        }
    }
}
