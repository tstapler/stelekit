// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import kotlin.math.roundToInt

object NameTagTags {
    const val LAYER = "sketch.nametags"
    fun tag(id: ElementId) = "sketch.nametag.${id.value}"
    fun badge(first: ElementId) = "sketch.nametag.badge.${first.value}"
}

/** Draws the chips of a [NameTagLayout] over the canvas (Story 3.5.7b): a name per tag, a count on a badge. Touch-transparent. */
@Composable
fun NameTags(layout: NameTagLayout, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag(NameTagTags.LAYER)) {
        for (tag in layout.tags) {
            Text(
                tag.text,
                Modifier.offset { IntOffset(tag.x.roundToInt(), tag.y.roundToInt()) }
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 4.dp)
                    .testTag(NameTagTags.tag(tag.id)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        for (badge in layout.badges) {
            Text(
                "${badge.count}",
                Modifier.offset { IntOffset(badge.x.roundToInt(), badge.y.roundToInt()) }
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 6.dp)
                    .testTag(NameTagTags.badge(badge.ids.first()))
                    .semantics { contentDescription = badge.description },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
