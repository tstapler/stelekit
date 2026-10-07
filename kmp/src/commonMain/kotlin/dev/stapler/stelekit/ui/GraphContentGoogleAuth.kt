// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Google Account auth state — threaded into SettingsDialog via GraphDialogLayer (Parameter Object pattern). */
internal class GraphContentGoogleAuthState(
    val isAuthenticated: Boolean,
    val connectedEmail: String?,
    val isConnecting: Boolean,
    val authError: String?,
    val onConnect: (() -> Unit)?,
    val onDisconnect: (() -> Unit)?,
)

/** The mutable state backing [GraphContentGoogleAuthState] (bundled for parameter-count relief). */
private class GoogleAuthMutableState(
    val isAuthenticated: MutableState<Boolean> = mutableStateOf(false),
    val connectedEmail: MutableState<String?> = mutableStateOf(null),
    val isConnecting: MutableState<Boolean> = mutableStateOf(false),
    val authError: MutableState<String?> = mutableStateOf(null),
)

/**
 * Tracks Google Account connection state and wires connect/disconnect against [googleAuthManager].
 * Both callbacks are null (feature hidden) when no manager is supplied for this platform.
 */
@Composable
internal fun rememberGraphContentGoogleAuthState(
    googleAuthManager: GoogleAuthManager?,
    scope: CoroutineScope,
    graphContentLogger: Logger,
): GraphContentGoogleAuthState {
    val state = remember { GoogleAuthMutableState() }

    LaunchedEffect(googleAuthManager) {
        if (googleAuthManager != null) {
            state.isAuthenticated.value = googleAuthManager.isAuthenticated()
            state.connectedEmail.value = googleAuthManager.getConnectedEmail()
        }
    }

    val onConnectGoogle: (() -> Unit)? = if (googleAuthManager != null) {
        { scope.launch { connectGoogle(googleAuthManager, state, graphContentLogger) } }
    } else null

    val onDisconnectGoogle: (() -> Unit)? = if (googleAuthManager != null) {
        { scope.launch { disconnectGoogle(googleAuthManager, state, graphContentLogger) } }
    } else null

    return GraphContentGoogleAuthState(
        isAuthenticated = state.isAuthenticated.value,
        connectedEmail = state.connectedEmail.value,
        isConnecting = state.isConnecting.value,
        authError = state.authError.value,
        onConnect = onConnectGoogle,
        onDisconnect = onDisconnectGoogle,
    )
}

private suspend fun connectGoogle(googleAuthManager: GoogleAuthManager, state: GoogleAuthMutableState, graphContentLogger: Logger) {
    state.isConnecting.value = true
    state.authError.value = null
    // Throwable (not just Exception) is caught below: an uncaught Throwable on this plain
    // rememberCoroutineScope() (no CoroutineExceptionHandler) would otherwise kill the Android
    // process — see GraphContentCameraCapture's saveCapturedImage for the same pattern/rationale.
    try {
        when (val result = googleAuthManager.authenticate()) {
            is arrow.core.Either.Right -> {
                state.isAuthenticated.value = true
                state.connectedEmail.value = result.value
            }
            is arrow.core.Either.Left -> {
                val error = result.value
                // Browser launched — auth completes via deep-link callback; nothing to show.
                val isBrowserLaunched = error is dev.stapler.stelekit.error.DomainError.NetworkError.HttpError &&
                    error.statusCode == 202
                if (!isBrowserLaunched) state.authError.value = error.message
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        graphContentLogger.error("Google auth connect crashed: ${e.message}", e)
        state.authError.value = "Connection failed — try again"
    } finally {
        state.isConnecting.value = false
    }
}

private suspend fun disconnectGoogle(googleAuthManager: GoogleAuthManager, state: GoogleAuthMutableState, graphContentLogger: Logger) {
    // Throwable (not just Exception) is caught below — same rationale as connectGoogle above.
    try {
        googleAuthManager.signOut()
        state.isAuthenticated.value = false
        state.connectedEmail.value = null
        state.authError.value = null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        graphContentLogger.error("Google auth disconnect crashed: ${e.message}", e)
        state.authError.value = "Disconnect failed — try again"
    }
}
