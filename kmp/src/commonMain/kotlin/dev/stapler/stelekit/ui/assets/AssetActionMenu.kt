package dev.stapler.stelekit.ui.assets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.stapler.stelekit.asset.AssetEntry

@Composable
fun AssetActionMenu(
    asset: AssetEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (AssetAction) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Open") },
            onClick = { onDismiss(); onAction(AssetAction.Open) },
            leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
        )
        DropdownMenuItem(
            text = { Text("Copy Link") },
            onClick = { onDismiss(); onAction(AssetAction.CopyLink) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = { onDismiss(); onAction(AssetAction.Delete) },
            leadingIcon = { Icon(Icons.Default.Delete, null) },
        )
    }
}
