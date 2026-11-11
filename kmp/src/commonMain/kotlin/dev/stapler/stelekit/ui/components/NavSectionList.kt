package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.ui.i18n.I18n
import dev.stapler.stelekit.ui.nav.NavAction
import dev.stapler.stelekit.ui.nav.NavDestinationSpec
import dev.stapler.stelekit.ui.nav.NavIconKey
import dev.stapler.stelekit.ui.nav.NavModel
import dev.stapler.stelekit.ui.nav.NavSection

fun navIcon(key: NavIconKey): ImageVector = when (key) {
    NavIconKey.JOURNALS -> Icons.Default.DateRange
    NavIconKey.ALL_PAGES -> Icons.AutoMirrored.Filled.List
    NavIconKey.FLASHCARDS -> Icons.Default.Style
    NavIconKey.UNLINKED_REFS -> Icons.Default.Link
    NavIconKey.NOTIFICATIONS -> Icons.Default.Notifications
    NavIconKey.LIBRARY -> Icons.Default.PhotoLibrary
    NavIconKey.GRAPH_STATS -> Icons.Default.BarChart
    NavIconKey.GIT_SETUP -> Icons.Default.Sync
    NavIconKey.LOGS -> Icons.Default.Info
    NavIconKey.PERFORMANCE -> Icons.Default.Settings
}

/**
 * Grouped, described navigation shared by the desktop sidebar and the phone "More" sheet (ADR-007).
 * Section headers are headings; rows are >= 48 dp with a supporting line (two lines at large font scales);
 * selection is a semantic state, not only a colour; decorative icons are not announced.
 */
@Composable
fun NavSectionList(
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    onGitSetup: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shows the "New" badge on the Library row (existing installs, LD-20). */
    showLibraryNewBadge: Boolean = false,
    /** Extra content rendered right after the Journals row (the per-section journal shortcut). */
    afterJournals: (@Composable () -> Unit)? = null,
    sections: List<NavSection> = NavModel.sections(currentScreen),
    /** Called after any row is activated, e.g. to dismiss a sheet. */
    onActivate: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        sections.forEach { section ->
            Text(
                text = t(section.headerKey),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() }.testTag("nav-header-${section.id}"),
            )
            section.items.forEach { spec ->
                NavSectionRow(
                    spec = spec,
                    selected = spec.isSelected(currentScreen),
                    showNewBadge = showLibraryNewBadge && spec.id == NavModel.library.id,
                    onClick = {
                        when (val a = spec.action) {
                            is NavAction.Open -> onNavigate(a.screen)
                            NavAction.GitSetup -> onGitSetup()
                        }
                        onActivate()
                    },
                )
                if (spec.id == NavModel.journals.id) afterJournals?.invoke()
            }
        }
    }
}

@Composable
fun NavSectionRow(
    spec: NavDestinationSpec,
    selected: Boolean,
    showNewBadge: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = t(spec.labelKey)
    val newState = if (showNewBadge) t(I18n.NAV_NEW_BADGE_STATE) else null
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("nav-row-${spec.id}")
            .semantics(mergeDescendants = true) {
                this.selected = selected
                if (newState != null) stateDescription = newState
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = navIcon(spec.iconKey),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = t(spec.descriptionKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showNewBadge) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "● " + t(I18n.NAV_NEW_BADGE),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("nav-new-badge"),
                )
            }
        }
    }
}
