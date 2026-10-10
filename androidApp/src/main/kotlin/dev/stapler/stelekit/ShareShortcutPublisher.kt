// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.stapler.stelekit.app.R
import dev.stapler.stelekit.model.GraphInfo

/** Direct Share entries (UX S14): one dynamic shortcut per registered graph. */
object ShareShortcutPublisher {
    const val EXTRA_TARGET_GRAPH_ID = "target_graph_id"
    const val SHORTCUT_ID_PREFIX = "graph:"
    const val SHARE_CATEGORY = "dev.stapler.stelekit.category.SHARE_TARGET"

    /** Graph id carried by a share: the explicit extra, else the chooser's shortcut id. */
    fun targetGraphIdFrom(intent: Intent): String? =
        intent.getStringExtra(EXTRA_TARGET_GRAPH_ID)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                ?.takeIf { it.startsWith(SHORTCUT_ID_PREFIX) }
                ?.removePrefix(SHORTCUT_ID_PREFIX)
                ?.takeIf { it.isNotBlank() }

    /** Replaces the whole dynamic set, so shortcuts of removed graphs disappear. */
    fun publish(context: Context, graphs: List<GraphInfo>) {
        val limit = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
        val shortcuts = graphs.filterNot { it.isDemo }.take(limit).map { graph ->
            val label = GraphChoice(graph.id, graph.displayName).label
            ShortcutInfoCompat.Builder(context, SHORTCUT_ID_PREFIX + graph.id.value)
                .setShortLabel(label)
                .setLongLabel("Share to $label")
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setCategories(setOf(SHARE_CATEGORY))
                .setLongLived(true)
                .setIntent(
                    Intent(context, CaptureActivity::class.java)
                        .setAction(Intent.ACTION_SEND)
                        .putExtra(EXTRA_TARGET_GRAPH_ID, graph.id.value),
                )
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
}
