// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.git.StalenessChipState
import dev.stapler.stelekit.git.SyncStaleness
import dev.stapler.stelekit.git.stalenessChipState
import kotlinx.coroutines.delay
import kotlin.time.Clock

private const val CHIP_REFRESH_MILLIS = 60_000L

/** The sync chip for [staleness], re-aged once a minute; null when there is no sync service to describe. */
@Composable
internal fun rememberStalenessChip(staleness: SyncStaleness?, intervalMinutes: Int): StalenessChipState? {
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CHIP_REFRESH_MILLIS)
            nowMs = Clock.System.now().toEpochMilliseconds()
        }
    }
    return remember(staleness, nowMs, intervalMinutes) { staleness?.let { stalenessChipState(it, nowMs, intervalMinutes) } }
}
