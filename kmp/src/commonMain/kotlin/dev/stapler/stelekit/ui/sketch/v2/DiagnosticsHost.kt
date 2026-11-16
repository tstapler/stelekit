// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.diagnostics.SketchDiagnostics
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

object DiagnosticsTags {
    const val POLITE = "sketch.live.polite"
    const val ASSERTIVE = "sketch.live.assertive"
}

/**
 * Moves the port's retained entries into the [SketchNoticeQueue] (Task 3.6.4b): an onboarding row goes through the queue's
 * [OnboardingSequencer], every other row is a banner candidate of the queue, and a row that left the port is settled. Pure and
 * clock-free. Producers only call `DiagnosticsPort.raise`; nothing here renders, so the banner slot stays the only renderer.
 */
class DiagnosticsBridge(private val queue: SketchNoticeQueue) {
    private val seen = HashMap<BannerRowId, BannerCandidate>()

    /** Applies [diagnostics]: new or changed rows are offered, rows that are gone are resolved. Safe to call repeatedly. */
    fun sync(diagnostics: SketchDiagnostics) {
        val current = diagnostics.entries.associateBy { it.row }
        for ((row, candidate) in current) {
            if (seen[row] == candidate) continue
            seen[row] = candidate
            offer(candidate)
        }
        for (row in seen.keys.filter { it !in current }) {
            seen.remove(row)
            settle(row)
        }
    }

    private fun offer(candidate: BannerCandidate) {
        val item = onboardingItem(candidate.row)
        if (item != null) queue.sequencer?.trigger(item, candidate) else queue.submitBanner(candidate)
    }

    private fun settle(row: BannerRowId) {
        val item = onboardingItem(row)
        if (item != null) queue.sequencer?.dismiss(item, Dismissal.ActionButton) else queue.resolveBanner(row)
    }

    private fun onboardingItem(row: BannerRowId): OnboardingItem? =
        if (row.tier == BannerTier.Onboarding) OnboardingItem.entries.firstOrNull { it.row == row } else null
}

private val clockOrigin = TimeSource.Monotonic.markNow()

private fun monotonicMillis(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

/**
 * What the host composable shows and says: the two live-region texts and the bridge to the queue. [clock] is the host's
 * time source in milliseconds, so a test runs in virtual time. Not thread-safe: the editor drives it from the main thread.
 */
class DiagnosticsHostState(
    val port: DiagnosticsPort,
    val queue: SketchNoticeQueue,
    private val clock: () -> Long = ::monotonicMillis,
) {
    private val bridge = DiagnosticsBridge(queue)

    /** The last polite announcement; a new text replaces it (last wins). */
    var polite by mutableStateOf("")
        private set

    /** The last assertive announcement: only an error that blocks work (UX-48). */
    var assertive by mutableStateOf("")
        private set

    private var revision by mutableIntStateOf(0)

    /** Tells the host that something was posted to the queue directly (a toast, a status message), so it announces it. */
    fun queueChanged() {
        revision += 1
    }

    internal val queueRevision: Int get() = revision

    internal fun attach(diagnostics: SketchDiagnostics) = bridge.sync(diagnostics)

    /** Says what is due; true while more is waiting (a polite one held back by the one-per-second rate). */
    internal fun pump(): Boolean {
        val next = queue.nextAnnouncement(clock())
        if (next != null) {
            if (next.assertive) assertive = next.text else polite = next.text
        }
        return queue.hasPendingAnnouncement()
    }
}

/**
 * The host of the typed diagnostics (Story 3.6.4, UX-48): entries the port retained before any composable existed are shown
 * when it attaches, and each one is announced once through a polite live region, or an assertive one for a blocking error.
 * [content] is the editor.
 */
@Composable
fun DiagnosticsHost(state: DiagnosticsHostState, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val diagnostics by state.port.diagnostics.collectAsState()
    LaunchedEffect(diagnostics, state.queueRevision) {
        state.attach(diagnostics)
        while (state.pump()) delay(NoticeTiming.POLITE_INTERVAL_MS)
    }
    Box(modifier) {
        content()
        LiveRegionNode(state.polite, LiveRegionMode.Polite, DiagnosticsTags.POLITE)
        LiveRegionNode(state.assertive, LiveRegionMode.Assertive, DiagnosticsTags.ASSERTIVE)
    }
}

@Composable
private fun LiveRegionNode(text: String, mode: LiveRegionMode, tag: String) {
    Box(
        Modifier.size(1.dp).testTag(tag).semantics {
            liveRegion = mode
            contentDescription = text
        },
    )
}
