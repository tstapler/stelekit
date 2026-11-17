// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the startup OPFS probe once per page load (Task 1.4.7m, D-137) and hands its result to [record]
 * (`PlatformFileSystem.recordProbe`). A second [startOnce] is a no-op, so two boot paths can never probe twice.
 */
class PageLoadProbe(
    private val probe: suspend () -> OpfsProbeResult,
    private val record: (OpfsProbeResult) -> Unit,
) {
    private var started = false

    /** Starts the probe in [scope] and returns its job; null when it already started. */
    fun startOnce(scope: CoroutineScope): Job? {
        if (started) return null
        started = true
        return scope.launch { record(probe()) }
    }
}
