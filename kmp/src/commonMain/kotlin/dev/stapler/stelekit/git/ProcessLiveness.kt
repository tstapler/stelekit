// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** [Unknown] fails closed: callers must never delete anything on it. */
enum class Liveness { Alive, Dead, Unknown }

/**
 * Port: is the process described by a [LockOwnerRecord] still running? Implemented per platform
 * (raw `/proc` ticks on Linux/Android, `ProcessHandle` start instant on macOS/Windows) on top of [LivenessPolicy].
 */
fun interface ProcessLiveness {
    fun probe(pid: Long, startTicks: Long?, pidNs: String?, host: String): Liveness
}
