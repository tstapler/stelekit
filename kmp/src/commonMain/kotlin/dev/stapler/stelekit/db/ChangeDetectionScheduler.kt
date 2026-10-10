package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Why a rescan ran. [Timer]/[Signal]/[Resume] are external triggers; [FollowUp] is scheduler-internal. */
enum class RescanReason { Timer, Signal, Resume, FollowUp }

/** What a rescan found. Drives whether [ChangeDetectionScheduler] schedules a follow-up burst. */
data class RescanOutcome(val foundChange: Boolean)

/**
 * Owns the "when do we re-check for external file changes" decision for a change-detection
 * loop, decoupled from what a rescan actually *does* — that stays entirely platform/domain
 * specific (`FileRegistry.detectChanges` on JVM/Android, an OPFS/host-directory walk on
 * wasmJs). Composed into [GraphFileWatcher] and (wasmJs) `HostDirectorySync` so both
 * platforms share one triggering/backoff state machine instead of each maintaining its own
 * ad-hoc timer-plus-observer loop.
 *
 * Two trigger sources:
 *  - [hint]: an external signal (Android `ContentObserver`/`FileObserver`, web
 *    `FileSystemObserver`, app-foreground/visibility-regain) says "something MAY have
 *    changed" — never proof, just cause to look sooner than the steady-state interval.
 *  - A steady-state timer, whose interval widens ([observerHealthy]/[slow]) once the
 *    platform's native signal source is confirmed reliable — the timer becomes a safety
 *    net, not the primary trigger, exactly like `HostDirectorySync`'s existing
 *    `observerConfirmedActive` backoff already did for the web platform alone.
 *
 * **The actual defect this closes**: a rescan triggered by a hint can still race an
 * eventually-consistent OS-level index (Android SAF's `ContentResolver` query, or a real
 * host-directory File System Access handle) and come back seeing nothing, even though the
 * file genuinely landed on disk moments earlier. Today that single negative result is
 * trusted until the next unrelated signal or full timer interval — which is exactly the
 * "hasn't loaded new files from disk in some time" bug. This scheduler instead treats "a
 * [Signal] or [Resume] rescan found nothing" as inconclusive, not proof of absence, and runs
 * a short, bounded burst of fast follow-up rescans ([followUpDelaysMs]) before falling back
 * to the steady-state cadence — event-driven convergence instead of either "believe it
 * forever" or "poll tightly forever." An ordinary [Timer] tick that finds nothing is the
 * normal, expected case and does **not** trigger a follow-up burst — only a source that
 * specifically claimed something changed earns the extra looks.
 */
class ChangeDetectionScheduler(
    private val baseIntervalMs: Long,
    private val followUpDelaysMs: List<Long> = listOf(250L, 1_000L, 3_000L),
    private val onRescan: suspend (RescanReason) -> RescanOutcome,
) {
    private val logger = Logger("ChangeDetectionScheduler")
    private val hintTrigger = Channel<RescanReason>(Channel.CONFLATED)
    private var job: Job? = null

    /** True once [start] has been called and its job hasn't since completed/been [stop]ped. */
    val isRunning: Boolean get() = job?.isActive == true

    // Not @Volatile: that annotation is JVM-only and unavailable on Kotlin/Wasm. Both fields
    // are only ever read/written from this scheduler's own coroutines (start()'s two loops)
    // plus caller-thread setters that, on every current target (JVM/Android/wasmJs), run on
    // the same dispatcher these loops observe from — no cross-thread visibility gap in
    // practice for this class's actual usage.

    /** Set by the caller once its native signal source (ContentObserver/FileSystemObserver/etc.) is confirmed working. */
    private var observerHealthy = false

    /** Set by the caller for conditions that should widen the safety-net interval further (e.g. backgrounded tab). */
    private var slow = false

    fun setObserverHealthy(healthy: Boolean) {
        observerHealthy = healthy
    }

    fun setSlow(slow: Boolean) {
        this.slow = slow
    }

    /** Requests a rescan sooner than the steady-state interval. Coalesces — a burst of hints collapses to one rescan. */
    fun hint(reason: RescanReason = RescanReason.Signal) {
        hintTrigger.trySend(reason)
    }

    /**
     * Multiplier mirrors `HostDirectorySync`'s existing backoff constants: a healthy native
     * signal source or an explicitly-slow condition (backgrounded tab, etc.) both widen the
     * timer 6x; the two reasons never compound (matches that class's documented "maxOf, not
     * product" rule) since either one alone is sufficient justification to rely on the
     * fast path over the timer.
     */
    fun effectiveIntervalMs(): Long {
        val multiplier = if (slow || observerHealthy) BACKOFF_MULTIPLIER else 1L
        return baseIntervalMs * multiplier
    }

    /** Starts the timer + hint loops as children of [scope]. Cancels any previously-started run first. */
    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            launch { timerLoop() }
            launch { hintLoop() }
        }
    }

    /** Stops both loops. Does not cancel [scope] itself — that remains the caller's responsibility. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun timerLoop() {
        while (currentCoroutineContext().isActive) {
            delay(effectiveIntervalMs())
            runRescanWithFollowUps(RescanReason.Timer)
        }
    }

    private suspend fun hintLoop() {
        for (reason in hintTrigger) {
            runRescanWithFollowUps(reason)
        }
    }

    private suspend fun runRescanWithFollowUps(reason: RescanReason) {
        val first = safeRescan(reason)
        if (first.foundChange) return
        // An ordinary timer tick finding nothing is the expected steady state — no signal
        // claimed anything changed, so there is nothing inconclusive to chase.
        if (reason == RescanReason.Timer) return
        for (delayMs in followUpDelaysMs) {
            delay(delayMs)
            val outcome = safeRescan(RescanReason.FollowUp)
            if (outcome.foundChange) return
        }
    }

    private suspend fun safeRescan(reason: RescanReason): RescanOutcome = try {
        onRescan(reason)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn("rescan failed (reason=$reason): ${e.message}", e)
        RescanOutcome(foundChange = false)
    }

    private companion object {
        const val BACKOFF_MULTIPLIER = 6L
    }
}
