// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import android.content.ContentResolver
import android.provider.Settings
import dev.stapler.stelekit.ui.components.sheet.TableVisualPrefs

/**
 * Android's values of [TableVisualPrefs], read once when the activity composes: reduced motion is the system animator
 * scale set to 0 ("Remove animations"), high contrast is the "High contrast text" setting. The setting key is a
 * hidden constant (`high_text_contrast_enabled`) and is UNVERIFIED on devices; an absent key reads as off.
 */
fun androidTableVisualPrefs(resolver: ContentResolver): TableVisualPrefs = TableVisualPrefs(
    reducedMotion = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f,
    highContrast = Settings.Secure.getInt(resolver, "high_text_contrast_enabled", 0) == 1,
)
