package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager

@Composable
fun LoadingOverlay(message: String, modifier: Modifier = Modifier) {
    val logs by LogManager.logs.collectAsState()
    val recentLogs = logs.take(10)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.testTag("loadingIndicator"))
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (recentLogs.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .fillMaxWidth()
                ) {
                    items(recentLogs) { entry ->
                        val color = when (entry.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                        Text(
                            text = "[${entry.level}] ${entry.tag}: ${entry.message}",
                            style = MaterialTheme.typography.labelSmall,
                            color = color,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
