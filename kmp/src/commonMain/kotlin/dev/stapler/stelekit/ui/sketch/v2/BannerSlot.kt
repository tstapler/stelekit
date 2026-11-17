// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object BannerTags {
    const val ROW = "sketch.banner.row"
    const val TEXT = "sketch.banner.text"
    const val ICON = "sketch.banner.icon"
    const val PRIMARY = "sketch.banner.primary"
    const val MORE = "sketch.banner.more"
    const val CONSENT_SHEET = "sketch.banner.consent"
    fun moreItem(label: String) = "sketch.banner.more.${label.lowercase().replace(' ', '-')}"
}

/** What the slot does with a tap: the primary button, or an item of the More menu (by its label). */
class BannerActions(val onPrimary: (BannerCandidate) -> Unit = {}, val onMoreItem: (BannerCandidate, String) -> Unit = { _, _ -> })

/**
 * The banner slot's content (Story 3.1.4, UX-126): the head of the notice queue as ONE row, a severity icon, the summary (or the
 * short title when the summary does not word-wrap inside two lines at this width and font scale), one primary button and one
 * More button. A consent row (no short title) that does not fit is shown as its full sentence in a sheet instead, never as a
 * short title. It renders only the candidate it is given: it imports no producer.
 */
@Composable
fun BannerSlotContent(candidate: BannerCandidate, actions: BannerActions, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale.toDouble()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val form = ChromeText.bannerForm(candidate.summary, candidate.shortTitle, maxWidth.value.toInt(), fontScale)
        if (form == BannerForm.ConsentSheet) {
            ConsentSheet(candidate, actions)
        } else {
            BannerRow(candidate, if (form == BannerForm.ShortTitle) candidate.shortTitle.orEmpty() else candidate.summary, actions)
        }
    }
}

@Composable
private fun BannerRow(candidate: BannerCandidate, shown: String, actions: BannerActions) {
    val mode = if (candidate.announcesAssertively) LiveRegionMode.Assertive else LiveRegionMode.Polite
    Surface(Modifier.fillMaxWidth().testTag(BannerTags.ROW), color = tierColor(candidate.tier)) {
        Row(
            Modifier.padding(horizontal = ChromeBudget.GUTTER_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BannerTextBudget.GAP_DP.dp),
        ) {
            SeverityIcon(candidate.tier)
            // The row may show the short title; the live region and the description always carry the full summary.
            Text(
                shown,
                Modifier.weight(1f).testTag(BannerTags.TEXT).semantics { liveRegion = mode; contentDescription = candidate.summary },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = ChromeBudget.BANNER_LINES,
            )
            PrimaryButton(candidate, actions)
            MoreButton(candidate, actions)
        }
    }
}

@Composable
private fun SeverityIcon(tier: BannerTier) {
    Text(
        severityGlyph(tier),
        Modifier.size(BannerTextBudget.ICON_DP.dp).testTag(BannerTags.ICON).semantics { contentDescription = severityName(tier) },
    )
}

@Composable
private fun PrimaryButton(candidate: BannerCandidate, actions: BannerActions) {
    TextButton(
        onClick = { actions.onPrimary(candidate) },
        modifier = Modifier.width(BannerTextBudget.PRIMARY_DP.dp).defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp)
            .testTag(BannerTags.PRIMARY).semantics { contentDescription = candidate.primaryAccessibleName },
    ) { Text(candidate.primaryLabel, maxLines = BannerTextBudget.LABEL_WORDS) }
}

@Composable
private fun MoreButton(candidate: BannerCandidate, actions: BannerActions) {
    if (candidate.moreItems.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.size(BannerTextBudget.MORE_DP.dp).testTag(BannerTags.MORE)
                .semantics { contentDescription = SketchStrings.Chrome.moreFor(candidate.shortTitle ?: candidate.summary) },
        ) { Text(MORE_GLYPH) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in candidate.moreItems) {
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = { open = false; actions.onMoreItem(candidate, item) },
                    modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(BannerTags.moreItem(item)),
                )
            }
        }
    }
}

/** The one-time sheet of a consent row: the whole consequence sentence, which the one-row form cannot hold at this size. */
@Composable
private fun ConsentSheet(candidate: BannerCandidate, actions: BannerActions) {
    Surface(Modifier.fillMaxWidth().testTag(BannerTags.CONSENT_SHEET), color = tierColor(candidate.tier)) {
        Column(
            Modifier.padding(ChromeBudget.GUTTER_DP.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BannerTextBudget.GAP_DP.dp),
        ) {
            Text(
                candidate.summary,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = candidate.summary },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(BannerTextBudget.GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(candidate, actions)
                MoreButton(candidate, actions)
            }
        }
    }
}

@Composable
private fun tierColor(tier: BannerTier) = when (tier) {
    BannerTier.Blocking -> MaterialTheme.colorScheme.errorContainer
    BannerTier.State, BannerTier.Warning -> MaterialTheme.colorScheme.tertiaryContainer
    BannerTier.Info, BannerTier.Onboarding -> MaterialTheme.colorScheme.secondaryContainer
}

private fun severityGlyph(tier: BannerTier): String = when (tier) {
    BannerTier.Blocking -> "⛔"
    BannerTier.State -> "ℹ"
    BannerTier.Warning -> "⚠"
    BannerTier.Info -> "ⓘ"
    BannerTier.Onboarding -> "✦"
}

private fun severityName(tier: BannerTier): String = when (tier) {
    BannerTier.Blocking -> SketchStrings.Chrome.SEVERITY_BLOCKING
    BannerTier.State -> SketchStrings.Chrome.SEVERITY_STATE
    BannerTier.Warning -> SketchStrings.Chrome.SEVERITY_WARNING
    BannerTier.Info -> SketchStrings.Chrome.SEVERITY_INFO
    BannerTier.Onboarding -> SketchStrings.Chrome.SEVERITY_ONBOARDING
}

private const val MORE_GLYPH = "⋯"
