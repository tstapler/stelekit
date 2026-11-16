// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.platform.sensor.CameraLens
import dev.stapler.stelekit.platform.sensor.LensKind

/**
 * Lens and zoom-stop chips (Story 4.4). Stateless: the caller owns selection. Chips wrap onto a second row rather than
 * shrink, every target is at least 48 dp, and the secondary focal line is folded into the content description so it
 * is never lost at large font scales.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraLensChips(
    lenses: List<CameraLens>,
    selectedId: String?,
    disabledReasons: Map<String, String>,
    bindingId: String?,
    enabled: Boolean,
    onSelect: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lenses.forEach { lens ->
            val reason = disabledReasons[lens.id]
            val isSelected = lens.id == selectedId
            LensChip(lens, isSelected, reason, bindingId == lens.id, enabled && reason == null) { onSelect(lens) }
        }
    }
}

@Composable
private fun LensChip(lens: CameraLens, selected: Boolean, disabledReason: String?, binding: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = chipDescription(lens, selected, disabledReason)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (selected) Color.White else Color.Black.copy(alpha = 0.55f),
        contentColor = if (selected) Color.Black else Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (disabledReason == null) 0.8f else 0.3f)),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics {
                contentDescription = description
                role = Role.RadioButton
                this.selected = selected
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(PaddingValues(horizontal = 14.dp, vertical = 6.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (binding) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Gray)
            } else {
                Text(lens.label, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            (lens.secondaryLabel ?: lens.hint)?.let { Text(it, fontSize = 10.sp, textAlign = TextAlign.Center) }
        }
    }
}

internal fun chipDescription(lens: CameraLens, selected: Boolean, disabledReason: String?): String = buildString {
    append(lens.label)
    append(
        when (lens.kind) {
            LensKind.ULTRA_WIDE -> ", ultra wide"
            LensKind.WIDE -> ", main camera"
            LensKind.TELE -> ", telephoto"
            LensKind.ZOOM_STOP -> if (lens.optical) ", optical zoom" else ", digital zoom"
        },
    )
    lens.secondaryLabel?.let { append(", $it") }
    if (selected) append(", selected")
    disabledReason?.let { append(", unavailable: $it") }
}
