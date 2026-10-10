// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.ui.onboarding.Onboarding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Renders [GraphContent]'s first-launch onboarding flow (graph selection or demo). */
@Composable
internal fun GraphContentOnboarding(
    fileSystem: FileSystem,
    graphManager: GraphManager,
    viewModel: StelekitViewModel,
    scope: CoroutineScope,
) {
    Onboarding(
        fileSystem = fileSystem,
        onComplete = { viewModel.setOnboardingCompleted(true) },
        onGraphSelect = { path ->
            scope.launch {
                val graphId = graphManager.addGraph(path)
                // Persist BEFORE switchGraph — switchGraph updates activeGraphId which
                // triggers key(activeGraphId) to destroy and recreate GraphContent
                // along with its scope. The new ViewModel reads these persisted values
                // in its init block and calls loadGraph automatically.
                viewModel.setGraphPath(path)
                viewModel.setOnboardingCompleted(true)
                graphManager.switchGraph(graphId)
            }
        },
        onDemoSelect = {
            scope.launch {
                try {
                    val graphId = graphManager.addDemoGraph()
                    graphManager.switchGraph(graphId)
                    viewModel.setOnboardingCompleted(true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    viewModel.sendSnackbar("Could not load demo content. Starting with an empty graph.")
                }
            }
        }
    )
}
