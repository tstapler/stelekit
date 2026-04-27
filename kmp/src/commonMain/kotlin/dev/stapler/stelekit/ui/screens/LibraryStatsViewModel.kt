// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.stats.GraphStatsReport
import dev.stapler.stelekit.stats.LibraryStatsProvider
import dev.stapler.stelekit.stats.NoOpLibraryStatsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LibraryStatsState {
    data object Idle : LibraryStatsState()
    data object Loading : LibraryStatsState()
    data class Loaded(val report: GraphStatsReport) : LibraryStatsState()
    data class Error(val message: String) : LibraryStatsState()
}

class LibraryStatsViewModel(
    private val provider: LibraryStatsProvider = NoOpLibraryStatsProvider,
    val graphPath: String = "",
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope = scope
    private val _state = MutableStateFlow<LibraryStatsState>(LibraryStatsState.Idle)
    val state: StateFlow<LibraryStatsState> = _state.asStateFlow()

    fun load() {
        if (_state.value == LibraryStatsState.Loading) return
        scope.launch {
            _state.value = LibraryStatsState.Loading
            val report = runCatching { provider.collect(graphPath) }.getOrElse { e ->
                _state.value = LibraryStatsState.Error(e.message ?: "Scan failed")
                return@launch
            }
            _state.value = if (report != null)
                LibraryStatsState.Loaded(report)
            else
                LibraryStatsState.Error("Graph path not found: $graphPath")
        }
    }

    fun close() { scope.cancel() }
}
