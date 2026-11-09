// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window

/**
 * Collects per-frame total durations from `Window.addOnFrameMetricsAvailableListener`. Not
 * Choreographer: that was the throwaway spike's method and misses GPU time (plan B.1b).
 * Attach before the scenario, call [stop] after; frames dropped while detached are simply absent.
 */
class FrameSampler(private val window: Window) {
    private val durations = ArrayList<Long>(EXPECTED_FRAMES)
    private val thread = android.os.HandlerThread("probe-frame-sampler").apply { start() }
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        synchronized(durations) { durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
    }

    constructor(activity: Activity) : this(activity.window)

    fun start() {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    fun stop(): FrameStats {
        // Removal must run on a looper-bearing thread for some API levels; the main looper is safe.
        Handler(Looper.getMainLooper()).post { runCatching { window.removeOnFrameMetricsAvailableListener(listener) } }
        thread.quitSafely()
        return synchronized(durations) { FrameStats.fromDurationsNanos(durations.toList()) }
    }

    private companion object {
        const val EXPECTED_FRAMES = 256
    }
}
