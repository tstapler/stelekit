// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui.components.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.llm.LlmProvider
import dev.stapler.stelekit.llm.LlmProviderAvailability
import dev.stapler.stelekit.llm.LlmProviderKind
import dev.stapler.stelekit.llm.LlmProviderRegistry

/**
 * Epic 6 Story 6.2: flat list of configured [LlmProvider] instances with live
 * connection/eligibility status.
 *
 * Renders [registry].all() (static, no suspend) immediately, then each row asynchronously
 * resolves its own [LlmProvider.checkAvailability] and updates in place — the row **never**
 * shows an optimistic "Available" default while that check is pending (features research §2 /
 * Android ML Kit docs' explicit rule): it always starts at "Checking availability…".
 *
 * Built-in providers (Anthropic/OpenAI/Gemini/on-device) are edited in place, not
 * added/removed as instances. Custom OpenAI-compatible provider ids are namespaced
 * `"custom:<uuid>"` (see [LlmProvider.id] kdoc) — [onAddProvider] creates a new one.
 */
@Composable
fun LlmProviderListScreen(
    registry: LlmProviderRegistry,
    onAddProvider: () -> Unit,
    onEditProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    val providers = registry.all()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("AI Providers", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Configure the LLM providers used for tag suggestions, voice formatting, " +
                        "and other AI features.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAddProvider) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add provider")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (providers.isEmpty()) {
            Text(
                "No providers configured yet. Add a custom provider, or set an API key on a " +
                    "built-in provider above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }

        providers.forEachIndexed { index, provider ->
            LlmProviderRow(
                provider = provider,
                onClick = if (provider.kind != LlmProviderKind.ON_DEVICE) {
                    { onEditProvider(provider.id) }
                } else null,
            )
            if (index != providers.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

/** A single provider row — resolves [LlmProvider.checkAvailability] asynchronously per-row. */
@Composable
private fun LlmProviderRow(provider: LlmProvider, onClick: (() -> Unit)?) {
    // produceState re-runs the suspend block whenever `provider` changes identity; starts at
    // `null` ("Checking availability…") so we never render an optimistic default.
    val availability by produceState<LlmProviderAvailability?>(initialValue = null, provider) {
        value = provider.checkAvailability()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (provider.kind == LlmProviderKind.ON_DEVICE) Icons.Default.PhoneAndroid else Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(provider.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (provider.kind == LlmProviderKind.ON_DEVICE) "On-device" else "Remote",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderStatusIndicator(availability)
            if (onClick != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Status dot + label. `null` (still resolving) renders a spinner + "Checking availability…" —
 * this is the state that must be visible on first frame, never skipped straight to green.
 */
@Composable
internal fun ProviderStatusIndicator(availability: LlmProviderAvailability?, onRetry: (() -> Unit)? = null) {
    when (availability) {
        null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "Checking availability…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is LlmProviderAvailability.Available -> StatusDotLabel(
            color = Color(0xFF4CAF50),
            label = "Connected",
        )
        is LlmProviderAvailability.Preparing -> StatusDotLabel(
            color = Color(0xFFFFA000),
            label = availability.detail ?: "Preparing…",
        )
        is LlmProviderAvailability.Unavailable -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDotLabel(color = Color(0xFFE53935), label = availability.reason)
            if (availability.retryable && onRetry != null) {
                IconButton(onClick = onRetry, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusDotLabel(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(8.dp),
        ) {}
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
