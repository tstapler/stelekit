// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Why a run was skipped before any network call. [label] is the stable run-log string. */
sealed interface NetworkSkipReason {
    val label: String

    data object Offline : NetworkSkipReason { override val label = "offline" }
    data object MeteredNetwork : NetworkSkipReason { override val label = "metered" }
    data object UnknownNetwork : NetworkSkipReason { override val label = "unknown-network" }

    companion object {
        val all: List<NetworkSkipReason> = listOf(Offline, MeteredNetwork, UnknownNetwork)
    }
}

sealed interface NetworkGateResult {
    data object Proceed : NetworkGateResult
    data class Skip(val reason: NetworkSkipReason) : NetworkGateResult
}

/** A skip is never a failure: periodic work retries next period. */
fun NetworkGateResult.Skip.toTaskRunResult(): TaskRunResult = TaskRunResult.Skipped(reason.label)

/** Pure three-valued check of a [NetworkPolicy] against live connectivity; unknown metering fails closed for Unmetered. */
object NetworkGate {
    fun evaluate(policy: NetworkPolicy, online: Boolean, isMetered: Boolean?): NetworkGateResult {
        if (!online) return NetworkGateResult.Skip(NetworkSkipReason.Offline)
        return when (policy) {
            NetworkPolicy.Any -> NetworkGateResult.Proceed
            NetworkPolicy.Unmetered -> when (isMetered) {
                false -> NetworkGateResult.Proceed
                true -> NetworkGateResult.Skip(NetworkSkipReason.MeteredNetwork)
                null -> NetworkGateResult.Skip(NetworkSkipReason.UnknownNetwork)
            }
        }
    }

    /** For callers with no `online` signal (LFS); defined as `evaluate(policy, online = true, isMetered)`. */
    fun evaluateMetered(policy: NetworkPolicy, isMetered: Boolean?): NetworkGateResult =
        evaluate(policy, online = true, isMetered = isMetered)
}
