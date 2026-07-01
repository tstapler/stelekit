package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sections.SectionManifest

/**
 * Sidebar indicator showing the active default section ("All sections" when defaultSection is empty).
 * Tapping opens the SectionQuickTogglePanel.
 */
@Composable
fun SectionContextIndicator(
    defaultSection: String,
    manifest: SectionManifest,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val section = remember(defaultSection, manifest) {
        if (defaultSection.isNotEmpty()) manifest.sections.find { it.id == defaultSection } else null
    }

    val dotColor = remember(section?.color) {
        section?.color?.let {
            try { Color(it.trimStart('#').toLong(16) or 0xFF000000L) }
            catch (_: NumberFormatException) { null }
        }
    } ?: if (section != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    val label = remember(section) { section?.displayName ?: "All sections" }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
