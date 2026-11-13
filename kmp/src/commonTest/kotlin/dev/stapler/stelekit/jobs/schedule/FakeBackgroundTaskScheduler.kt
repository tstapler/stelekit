// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** A pending one-shot as the fake recorded it; [effective] is [requested] after capability downgrade. */
data class FakeOneShot(
    val requestId: String,
    val requested: TaskConstraints,
    val effective: TaskConstraints,
    val downgradeReason: String?,
)

/**
 * In-memory [BackgroundTaskScheduler] for tests and the reference behaviour of the contract suite
 * (no @Test methods). Shares [clamp] and [NetworkGate] with production. Pause/resume follow
 * ADR-003: `pause` snapshots live work into [ledger] then cancels; `resume` restores snapshot, else
 * last applied, else an enabled store entry, and never resurrects an explicitly disabled entry.
 */
class FakeBackgroundTaskScheduler(
    override val capabilities: SchedulerCapabilities = DEFAULT_CAPABILITIES,
    private val ledger: AppliedConfigLedger,
    private val store: TaskSettingsStore? = null,
    private val registry: TaskRegistry = TaskRegistry(),
    private val clock: () -> Long = { 0L },
) : BackgroundTaskScheduler {
    private val periodic = LinkedHashMap<TaskKey, TaskConfig>()
    private val oneShots = LinkedHashMap<TaskKey, FakeOneShot>()
    private val paused = LinkedHashSet<TaskKey>()
    private var nextRequestId = 0

    /** Ordered log of port calls, e.g. `apply:git-sync.2.g1`. */
    val calls = mutableListOf<String>()

    override suspend fun apply(key: TaskKey, config: TaskConfig) {
        calls += "apply:${TaskKeyEncoding.encode(key)}"
        if (!config.enabled) {
            ledger.clear(key)
            periodic.remove(key)
            paused.remove(key)
            return
        }
        ledger.recordApplied(key, config)
        if (key in paused) {
            ledger.snapshotPaused(key, config) // user change during a pause updates the snapshot, nothing enqueued
            return
        }
        periodic[key] = config
    }

    override suspend fun runOnce(key: TaskKey, constraints: TaskConstraints, onConflict: OneShotConflict) {
        calls += "runOnce:${TaskKeyEncoding.encode(key)}:$onConflict"
        if (oneShots.containsKey(key) && onConflict == OneShotConflict.Keep) return
        val downgraded = constraints.network == NetworkPolicy.Unmetered && !capabilities.supportsUnmetered
        oneShots[key] = FakeOneShot(
            requestId = "oneshot-${++nextRequestId}",
            requested = constraints,
            effective = if (downgraded) constraints.copy(network = NetworkPolicy.Any) else constraints,
            downgradeReason = if (downgraded) "unmetered not supported by this scheduler" else null,
        )
    }

    override suspend fun cancel(key: TaskKey) {
        calls += "cancel:${TaskKeyEncoding.encode(key)}"
        removeAll(key)
    }

    override suspend fun cancelAll(scope: TaskScope) {
        calls += "cancelAll:${scope.value}"
        (periodic.keys + oneShots.keys + paused).filter { it.scope == scope }.forEach { removeAll(it) }
    }

    override suspend fun pause(scope: TaskScope) {
        calls += "pause:${scope.value}"
        for (key in periodic.keys.filter { it.scope == scope }) {
            val live = periodic.getValue(key)
            ledger.snapshotPaused(key, live.copy(enabled = true)) // never throws; cancel below always runs
            periodic.remove(key)
            paused += key
        }
    }

    override suspend fun resume(scope: TaskScope) {
        calls += "resume:${scope.value}"
        for (key in paused.filter { it.scope == scope }) {
            paused.remove(key)
            if (store?.isExplicitlyDisabled(key) == true) {
                ledger.clear(key)
                continue
            }
            val config = ledger.pausedSnapshot(key)?.config
                ?: ledger.lastApplied(key)
                ?: store?.read(key)
            if (config != null && config.enabled) {
                periodic[key] = config
                ledger.recordApplied(key, config)
            }
            ledger.clearPausedSnapshot(key)
        }
    }

    override suspend fun describe(key: TaskKey): TaskScheduleInfo? {
        periodic[key]?.let { return periodicInfo(it) }
        // A downgraded one-shot is reported with a sentinel zero-interval info; other one-shots have no describe entry.
        val shot = oneShots[key] ?: return null
        val reason = shot.downgradeReason ?: return null
        return TaskScheduleInfo(0, false, null, 0, 0, appliesAfterNextRun = false, downgradeReason = reason)
    }

    /** Inserts live periodic work the way a pre-upgrade job exists: no ledger, store or legacy flag involved. */
    fun seedExternalPeriodic(key: TaskKey, config: TaskConfig) {
        periodic[key] = config
    }

    fun oneShot(key: TaskKey): FakeOneShot? = oneShots[key]

    /** Whether [key] is held by a `pause` (work cancelled, awaiting `resume`). */
    fun isPaused(key: TaskKey): Boolean = key in paused

    /**
     * Simulates the platform firing [key] under [state]: the [NetworkGate] runs first and a skip
     * never invokes the task body. A fired one-shot is consumed.
     */
    suspend fun fireNow(key: TaskKey, state: ConstraintState): TaskRunResult {
        val oneShot = oneShots[key]
        val policy = oneShot?.effective?.network
            ?: periodic[key]?.constraints?.network?.let { effectiveNetwork(it) }
            ?: return TaskRunResult.Skipped("not-scheduled")
        val gate = NetworkGate.evaluate(policy, state.online, state.isMetered)
        if (gate is NetworkGateResult.Skip) return gate.toTaskRunResult()
        val task = registry.taskFor(key.kind) ?: return TaskRunResult.Failed("no task registered for ${key.kind.id}")
        val ctx = TaskRunContext(scheduledAtMs = null, startedAtMs = clock(), appOpen = true, constraintState = state)
        val result = task.run(key, ctx)
        if (oneShot != null) oneShots.remove(key)
        return result
    }

    private fun removeAll(key: TaskKey) {
        periodic.remove(key)
        oneShots.remove(key)
        paused.remove(key)
        ledger.clear(key)
    }

    private fun effectiveNetwork(policy: NetworkPolicy): NetworkPolicy =
        if (policy == NetworkPolicy.Unmetered && !capabilities.supportsUnmetered) NetworkPolicy.Any else policy

    private fun periodicInfo(config: TaskConfig): TaskScheduleInfo {
        val effective = config.schedule.clamp(capabilities)
        val downgrade = when {
            config.constraints.network == NetworkPolicy.Unmetered && !capabilities.supportsUnmetered ->
                "unmetered not supported by this scheduler"
            config.constraints.requiresCharging && !capabilities.supportsCharging ->
                "charging constraint not supported by this scheduler"
            else -> null
        }
        return TaskScheduleInfo(
            effectiveIntervalMinutes = effective.intervalMinutes,
            clamped = effective.clamped,
            nextRunAtMs = clock() + effective.intervalMinutes * MS_PER_MINUTE,
            minGapMinutes = effective.intervalMinutes,
            maxGapMinutes = effective.intervalMinutes + effective.jitterMinutes,
            appliesAfterNextRun = false,
            downgradeReason = downgrade,
        )
    }

    companion object {
        private const val MS_PER_MINUTE = 60_000L

        /** WorkManager-like defaults: 15 min floor, unmetered and charging supported. */
        val DEFAULT_CAPABILITIES = SchedulerCapabilities(
            minIntervalMinutes = 15,
            minFlexMinutes = 5,
            supportsUnmetered = true,
            supportsCharging = true,
            supportsJitter = true,
            runsWhenClosed = true,
            schedulesWork = true,
        )
    }
}
