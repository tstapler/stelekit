// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.service.AttachmentResult
import dev.stapler.stelekit.service.MediaAttachmentService
import dev.stapler.stelekit.service.toMarkdown
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Non-state collaborators [WireGraphContentBootstrapEffects] needs (bundled for parameter-count relief). */
internal class GraphContentBootstrapEnv(
    val blockStateManager: BlockStateManager,
    val scope: CoroutineScope,
    val graphContentLogger: Logger,
)

/**
 * Registers the memory-pressure handler so the host Activity can invoke it, and wires the
 * `/image` command in the command palette to the platform attachment service. The image-attach
 * callback reads the currently editing block from [GraphContentBootstrapEnv.blockStateManager] so
 * it works even when invoked from the command palette (no block UUID passed explicitly).
 */
@Composable
internal fun WireGraphContentBootstrapEffects(
    viewModel: StelekitViewModel,
    onMemoryPressure: (((() -> Unit) -> Unit))?,
    attachmentService: MediaAttachmentService?,
    env: GraphContentBootstrapEnv,
) {
    LaunchedEffect(viewModel) {
        onMemoryPressure?.invoke { viewModel.onMemoryPressure() }
    }

    LaunchedEffect(viewModel, attachmentService) {
        registerAttachImageCallback(viewModel, attachmentService, env)
    }
}

private fun registerAttachImageCallback(
    viewModel: StelekitViewModel,
    attachmentService: MediaAttachmentService?,
    env: GraphContentBootstrapEnv,
) {
    if (attachmentService == null) {
        viewModel.registerAttachImageCallback(null)
        return
    }
    viewModel.registerAttachImageCallback {
        env.scope.launch { attachImageFromCommandPalette(attachmentService, viewModel, env) }
    }
}

private suspend fun attachImageFromCommandPalette(
    attachmentService: MediaAttachmentService,
    viewModel: StelekitViewModel,
    env: GraphContentBootstrapEnv,
) {
    // Throwable (not just Exception) is caught below: an uncaught Throwable on this plain
    // rememberCoroutineScope() (no CoroutineExceptionHandler) would otherwise kill the Android
    // process — see GraphContentCameraCapture's saveCapturedImage for the same pattern/rationale.
    try {
        val editingBlockUuid = env.blockStateManager.editingBlockUuid.value
        val graphRoot = viewModel.uiState.value.currentGraphPath ?: return
        val result = attachmentService.pickAndAttach(graphRoot = graphRoot, pageRelativePath = "") ?: return
        applyAttachmentResult(result, editingBlockUuid, env)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        env.graphContentLogger.error("Image attachment from command palette crashed: ${e.message}", e)
    }
}

private fun applyAttachmentResult(
    result: Either<DomainError, AttachmentResult>,
    editingBlockUuid: BlockUuid?,
    env: GraphContentBootstrapEnv,
) {
    result.fold(
        ifLeft = { err -> env.graphContentLogger.warn("Image attachment failed: $err") },
        ifRight = { attachment ->
            if (editingBlockUuid != null) {
                env.blockStateManager.insertTextAtCursor(editingBlockUuid, attachment.toMarkdown())
            }
        }
    )
}
