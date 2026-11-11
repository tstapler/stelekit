// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import dev.stapler.stelekit.ui.sketchimport.SketchImportHost

/** Shows the tagging screen for [session] and reports once the owner saved or cancelled. */
@Composable
fun DrawingImportSession(
    session: DrawingSession,
    onFinish: (ImportPhase, DimensionRegistry?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.viewModel.state.collectAsState()
    val finish by rememberUpdatedState(onFinish)
    LaunchedEffect(state.phase) {
        if (state.phase == ImportPhase.Done || state.phase == ImportPhase.Cancelled) finish(state.phase, state.registry)
    }
    SketchImportHost(session.viewModel, modifier)
}
