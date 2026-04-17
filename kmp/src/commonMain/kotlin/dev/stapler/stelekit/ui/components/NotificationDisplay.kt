package dev.stapler.stelekit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.Notification
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.ui.NotificationManager

@Composable
fun NotificationOverlay(
    notificationManager: NotificationManager,
    modifier: Modifier = Modifier
) {
    val notifications by notificationManager.activeNotifications.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            notifications.forEach { notification ->
                NotificationToast(
                    notification = notification,
                    onDismiss = { notificationManager.clear(notification.id) }
                )
            }
        }
    }
}

@Composable
fun NotificationToast(
    notification: Notification,
    onDismiss: () -> Unit
) {
    val backgroundColor = when (notification.type) {
        NotificationType.INFO -> MaterialTheme.colorScheme.primaryContainer
        NotificationType.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        NotificationType.ERROR -> MaterialTheme.colorScheme.errorContainer
        NotificationType.SUCCESS -> MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = when (notification.type) {
        NotificationType.INFO -> MaterialTheme.colorScheme.onPrimaryContainer
        NotificationType.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        NotificationType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        NotificationType.SUCCESS -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val icon = when (notification.type) {
        NotificationType.INFO -> Icons.Default.Info
        NotificationType.WARNING -> Icons.Default.Warning
        NotificationType.ERROR -> Icons.Default.Error
        NotificationType.SUCCESS -> Icons.Default.CheckCircle
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 400.dp)
            .clickable { onDismiss() },
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = notification.content,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun NotificationHistory(
    notificationManager: NotificationManager
) {
    val history by notificationManager.history.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Notifications",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )
        
        if (history.isEmpty()) {
            Text(
                text = "No notifications yet.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(history) { notification ->
                    ListItem(
                        headlineContent = { Text(notification.content) },
                        supportingContent = { Text(notification.timestamp.toString()) },
                        leadingContent = {
                            val icon = when (notification.type) {
                                NotificationType.INFO -> Icons.Default.Info
                                NotificationType.WARNING -> Icons.Default.Warning
                                NotificationType.ERROR -> Icons.Default.Error
                                NotificationType.SUCCESS -> Icons.Default.CheckCircle
                            }
                            Icon(imageVector = icon, contentDescription = null)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
