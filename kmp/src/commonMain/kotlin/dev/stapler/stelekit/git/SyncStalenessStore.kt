// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings

enum class BackgroundOutcomeKind { Merged, Fetched, Error }

/** One background tick's result, as listed by `diag` and `git doctor`. [detail] is a short redaction-safe code such as an error class. */
data class BackgroundOutcome(val atMs: Long, val kind: BackgroundOutcomeKind, val behindCount: Int, val detail: String = "")

/**
 * What the sync chip and the updates-waiting surfaces read for one graph. Null timestamps and a null
 * [behindCount] mean "never happened" and "not yet known", which the chip words differently from zero.
 */
data class SyncStaleness(
    val lastMergedAtMs: Long? = null,
    val lastFetchedAtMs: Long? = null,
    val behindCount: Int? = null,
    /** Set while scheduled work for this graph can only fetch (SAF graph, or the app is closed). */
    val fetchOnlyReason: FetchOnlyReason? = null,
    /** When this graph was first observed, so a graph that never merged does not look stale on day one. */
    val firstSeenAtMs: Long? = null,
    /** Newest last; capped at [SyncStalenessStore.HISTORY_LIMIT]. */
    val history: List<BackgroundOutcome> = emptyList(),
)

/**
 * Settings-backed [SyncStaleness] per graph (`git_sync_staleness_<graphId>`), written by the live service and by
 * the closed-app fetch runner. Last writer wins; each write is a read-modify-write of one string.
 */
class SyncStalenessStore(private val settings: Settings) {

    fun read(graphId: String): SyncStaleness = decode(settings.getString(key(graphId), ""))

    fun markSeen(graphId: String, nowMs: Long) {
        val current = read(graphId)
        if (current.firstSeenAtMs == null) write(graphId, current.copy(firstSeenAtMs = nowMs))
    }

    /** A completed merge: nothing is behind any more. */
    fun recordMerged(graphId: String, nowMs: Long) = update(graphId, nowMs) {
        it.copy(lastMergedAtMs = nowMs, behindCount = 0).withOutcome(BackgroundOutcome(nowMs, BackgroundOutcomeKind.Merged, 0))
    }

    fun recordFetched(graphId: String, nowMs: Long, behindCount: Int) = update(graphId, nowMs) {
        it.copy(lastFetchedAtMs = nowMs, behindCount = behindCount)
            .withOutcome(BackgroundOutcome(nowMs, BackgroundOutcomeKind.Fetched, behindCount))
    }

    fun recordError(graphId: String, nowMs: Long, detail: String) = update(graphId, nowMs) {
        it.withOutcome(BackgroundOutcome(nowMs, BackgroundOutcomeKind.Error, it.behindCount ?: 0, sanitize(detail)))
    }

    fun setFetchOnlyReason(graphId: String, reason: FetchOnlyReason?) {
        val current = read(graphId)
        if (current.fetchOnlyReason != reason) write(graphId, current.copy(fetchOnlyReason = reason))
    }

    private fun update(graphId: String, nowMs: Long, change: (SyncStaleness) -> SyncStaleness) {
        val current = read(graphId)
        write(graphId, change(current.copy(firstSeenAtMs = current.firstSeenAtMs ?: nowMs)))
    }

    private fun SyncStaleness.withOutcome(outcome: BackgroundOutcome) =
        copy(history = (history + outcome).takeLast(HISTORY_LIMIT))

    private fun write(graphId: String, value: SyncStaleness) = settings.putString(key(graphId), encode(value))

    private fun key(graphId: String) = "git_sync_staleness_$graphId"

    companion object {
        const val HISTORY_LIMIT = 10
        private const val NONE = "-"

        fun encode(s: SyncStaleness): String = listOf(
            "m=${s.lastMergedAtMs ?: NONE}",
            "f=${s.lastFetchedAtMs ?: NONE}",
            "b=${s.behindCount ?: NONE}",
            "r=${s.fetchOnlyReason?.name ?: NONE}",
            "s=${s.firstSeenAtMs ?: NONE}",
            "h=" + s.history.joinToString("|") { "${it.atMs}:${it.kind.name}:${it.behindCount}:${it.detail}" },
        ).joinToString(";")

        /** Tolerant: any malformed field falls back to its default so a corrupt value never blocks sync. */
        fun decode(raw: String): SyncStaleness {
            if (raw.isEmpty()) return SyncStaleness()
            val fields = raw.split(';').mapNotNull { f -> f.indexOf('=').takeIf { it > 0 }?.let { f.substring(0, it) to f.substring(it + 1) } }.toMap()
            return SyncStaleness(
                lastMergedAtMs = fields["m"]?.toLongOrNull(),
                lastFetchedAtMs = fields["f"]?.toLongOrNull(),
                behindCount = fields["b"]?.toIntOrNull(),
                fetchOnlyReason = FetchOnlyReason.entries.firstOrNull { it.name == fields["r"] },
                firstSeenAtMs = fields["s"]?.toLongOrNull(),
                history = fields["h"].orEmpty().split('|').mapNotNull(::decodeOutcome).takeLast(HISTORY_LIMIT),
            )
        }

        private fun decodeOutcome(raw: String): BackgroundOutcome? {
            val parts = raw.split(':', limit = 4)
            if (parts.size < 3) return null
            return BackgroundOutcome(
                atMs = parts[0].toLongOrNull() ?: return null,
                kind = BackgroundOutcomeKind.entries.firstOrNull { it.name == parts[1] } ?: return null,
                behindCount = parts[2].toIntOrNull() ?: return null,
                detail = parts.getOrElse(3) { "" },
            )
        }

        private fun sanitize(detail: String) = detail.take(MAX_DETAIL).map { if (it.isLetterOrDigit() || it in " ._-") it else '_' }.joinToString("")

        private const val MAX_DETAIL = 60
    }
}
