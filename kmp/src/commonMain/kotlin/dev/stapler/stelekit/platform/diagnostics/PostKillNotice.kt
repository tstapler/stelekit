// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.diagnostics

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A previous editor session ended abnormally; [recordId] identifies the exit record or crash file. */
data class PostKillNotice(val recordId: String)

/** Remembers which exit record the banner already announced. Implementations may throw; callers guard. */
interface SeenExitRecordStore {
    fun lastSeenId(): String?
    fun markSeen(id: String)
}

object PreviousSessionAssessor {
    // Reasons that are the user or the system tidying up, not an unexpected death.
    private val BENIGN_REASONS = setOf(1, 8, 10, 11, 15, 16)

    /** True when the last line the previous session wrote about the sampler is a sample, not the stop marker. */
    fun editorWasOpenAtExit(previousTail: List<String>): Boolean {
        val last = previousTail.lastOrNull {
            it.contains(DiagnosticsSample.SAMPLE_PREFIX)
        } ?: return false
        return !last.contains(DiagnosticsSample.STOPPED_LINE)
    }

    /**
     * @param records newest first, as `getHistoricalProcessExitReasons` returns them (empty below API 30)
     * @param previousTail the previous session's last log lines
     * @param crashFileId non-null when `last-crash.txt` existed; stands in for the record id without exit info
     */
    fun assess(records: List<ExitRecord>, previousTail: List<String>, crashFileId: String?): PostKillNotice? {
        if (!editorWasOpenAtExit(previousTail)) return null
        val latest = records.firstOrNull()
        return when {
            latest != null && latest.reason !in BENIGN_REASONS -> PostKillNotice(latest.id)
            latest == null && crashFileId != null -> PostKillNotice(crashFileId)
            else -> null
        }
    }
}

/** Holds the banner state for the launch after an unexpected editor-session death; shows each record once. */
class PostKillBannerController(private val store: SeenExitRecordStore) {
    private val logger = Logger("PostKillBanner")
    private val _notice = MutableStateFlow<PostKillNotice?>(null)
    val notice: StateFlow<PostKillNotice?> = _notice.asStateFlow()

    /** Shows [candidate] unless its record was seen before. A store read failure logs and shows nothing. */
    fun offer(candidate: PostKillNotice?) {
        if (candidate == null) return
        val seen = try {
            store.lastSeenId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Could not read last seen exit record: ${e.message}")
            return
        }
        if (seen == candidate.recordId) return
        _notice.value = candidate
        try {
            store.markSeen(candidate.recordId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Could not persist seen exit record: ${e.message}")
        }
    }

    fun dismiss() {
        _notice.value = null
    }

    companion object {
        const val MESSAGE = "The app closed unexpectedly while annotating."
    }
}
