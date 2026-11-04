package dev.stapler.stelekit.ui.assets

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.pipeline.CloudClaudePlugin

const val DESCRIBED_BY_ANTHROPIC = "Described by Anthropic"

/** The description text came from the cloud plugin; the marker stays after consent is revoked. */
fun AssetEntry.isDescribedByAnthropic(): Boolean = mlTagsSource == CloudClaudePlugin.CLOUD_TAGS_SOURCE

/** Icon plus text, so the marker never relies on colour alone. */
@Composable
fun DescribedByAnthropicMarker(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            DESCRIBED_BY_ANTHROPIC,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Grid cells are too small for text; the icon carries the same words as its description. */
@Composable
fun DescribedByAnthropicGridBadge(modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Cloud,
        contentDescription = DESCRIBED_BY_ANTHROPIC,
        modifier = modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}
