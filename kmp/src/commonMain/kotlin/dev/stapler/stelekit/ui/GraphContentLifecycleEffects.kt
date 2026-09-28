// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.voice.VoiceCaptureViewModel

/**
 * Force-flushes pending writes on Android lifecycle pause/stop. Keyed on [voiceCaptureViewModel]
 * so the observer is re-registered whenever the VM is recreated (e.g. after voicePipeline
 * changes), preventing calls on a stale closed instance. Uses each object's own internal scope
 * rather than `rememberCoroutineScope` to avoid `ForgottenCoroutineScopeException` when ON_PAUSE
 * fires after composition teardown. [vaultManager] is a live-read lambda (not a plain value) so
 * the observer — created once per key — always observes the current vault manager, matching
 * [ObservePermissionRevocationOnResume]'s identical rationale.
 */
@Composable
internal fun ObserveGraphContentLifecycle(
    viewModel: StelekitViewModel,
    voiceCaptureViewModel: VoiceCaptureViewModel,
    graphIoStack: GraphContentGraphIoStack,
    vaultManager: () -> VaultManager?,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceCaptureViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            handleGraphContentLifecycleEvent(event, viewModel, voiceCaptureViewModel, graphIoStack, vaultManager)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun handleGraphContentLifecycleEvent(
    event: Lifecycle.Event,
    viewModel: StelekitViewModel,
    voiceCaptureViewModel: VoiceCaptureViewModel,
    graphIoStack: GraphContentGraphIoStack,
    vaultManager: () -> VaultManager?,
) {
    when (event) {
        Lifecycle.Event.ON_PAUSE -> {
            viewModel.savePendingChanges()  // launches flush on viewModel's own scope
            voiceCaptureViewModel.cancel()
        }
        Lifecycle.Event.ON_STOP -> {
            val vm = vaultManager() ?: return
            // Use viewModel's own scope — avoids ForgottenCoroutineScopeException
            // if ON_STOP fires after composition teardown.
            viewModel.flushAndLockVault(graphIoStack.graphLoader, graphIoStack.graphWriter, vm)
        }
        else -> Unit
    }
}
