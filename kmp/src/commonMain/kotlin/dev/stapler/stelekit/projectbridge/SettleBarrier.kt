// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.coroutines.delay

/** Whether the database already reflects the file a bound page was pulled from. */
enum class PageSettleState {
    /** The loader has seen the file as it is on disk now. */
    Settled,

    /** Index-only stub (`isContentLoaded = false`): there is nothing stale to read. */
    IndexOnlyStub,

    /** The file on disk differs from what the loader last saw. */
    Stale,
}

fun interface PageSettleProbe {
    suspend fun stateOf(page: BoundPageRef): PageSettleState
}

/**
 * Production probe. The loader's own file hash is `String.hashCode()` of the raw content, kept per path in
 * `FileRegistry.contentHashes` (not a `Page` field: `Page` has no `contentHash`; only `Block` does).
 * That hash is stored when the watcher DETECTS a change, before the page is re-parsed and saved, so equality
 * is necessary but not sufficient proof the database caught up (INFERRED from `FileRegistry.detectChanges`).
 */
class LoaderPageSettleProbe(
    private val isIndexOnlyStub: suspend (BoundPageRef) -> Boolean,
    private val registryHash: suspend (filePath: String) -> Int?,
    private val readDisk: suspend (filePath: String) -> String?,
) : PageSettleProbe {
    override suspend fun stateOf(page: BoundPageRef): PageSettleState {
        if (isIndexOnlyStub(page)) return PageSettleState.IndexOnlyStub
        val disk = readDisk(page.filePath) ?: return PageSettleState.Stale
        return if (registryHash(page.filePath) == disk.hashCode()) PageSettleState.Settled else PageSettleState.Stale
    }
}

sealed interface SettleResult {
    /** Every bound page is current; [unloaded] counts index-only stubs among them. */
    data class Settled(val unloaded: Int) : SettleResult

    /** Gave up after the bounded polling; the run ends and retries on the next trigger. */
    data class Unsettled(val stalePages: Int, val gitSyncBusy: Boolean) : SettleResult {
        val note: String get() = if (gitSyncBusy) "unsettled=git sync busy" else "unsettled=$stalePages"
    }
}

/**
 * Guards against reading a stale database right after a pull: git sync publishes its state before the file
 * watcher has imported the pulled files, so a run must not trust a bound page until the loader caught up.
 * Waits while git sync is busy, then polls each page's [PageSettleProbe] with the injected [pause].
 */
class SettleBarrier(
    private val isGitSyncBusy: () -> Boolean,
    private val probe: PageSettleProbe,
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    suspend fun awaitSettled(boundPages: List<BoundPageRef>): SettleResult {
        var pending = boundPages
        var unloaded = 0
        var busy = false
        repeat(maxAttempts) { attempt ->
            busy = isGitSyncBusy()
            if (!busy) {
                val round = probeAll(pending)
                pending = round.stale
                unloaded += round.unloaded
                if (pending.isEmpty()) return SettleResult.Settled(unloaded)
            }
            if (attempt < maxAttempts - 1) pause(intervalMillis)
        }
        return SettleResult.Unsettled(stalePages = pending.size, gitSyncBusy = busy)
    }

    private class Round(val stale: List<BoundPageRef>, val unloaded: Int)

    private suspend fun probeAll(pages: List<BoundPageRef>): Round {
        val states = pages.map { it to probe.stateOf(it) }
        return Round(
            stale = states.filter { it.second == PageSettleState.Stale }.map { it.first },
            unloaded = states.count { it.second == PageSettleState.IndexOnlyStub },
        )
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 20
        const val DEFAULT_INTERVAL_MILLIS = 500L
    }
}
