package dev.stapler.stelekit.table

enum class NoticeType { Info, Undo, Retry, Why, Action }

/** [id] is caller-chosen so hosts can match a shown notice back to its source. */
data class Notice(val id: Long, val text: String, val type: NoticeType, val actionLabel: String? = null) {
    val isActionBearing: Boolean get() = type != NoticeType.Info
}

enum class OfferOutcome { Shown, Replaced, Queued, LiveRegionOnly, RetiredOldestUndo }

data class NoticeHeightLimit(val maxLines: Int, val maxFractionAboveIme: Float)

/**
 * One visible notice at a time (ux.md 3.10, UX-87). Informational notices are replaceable; an
 * action-bearing one is never dropped or replaced by a different kind. Not thread-safe.
 */
class NoticeQueue {
    var visible: Notice? = null
        private set
    private val waiting = ArrayDeque<Notice>()
    private val retired = ArrayList<Notice>()
    private val live = ArrayList<String>()

    val pending: List<Notice> get() = waiting.toList()

    /** Count behind the visible one, shown as "N more". */
    val moreCount: Int get() = waiting.size

    /** Undo steps that left the queue; still reachable from the Undo control. */
    val retiredUndos: List<Notice> get() = retired.toList()

    /** Texts that go only to the live region and status line. */
    val liveRegionOnly: List<String> get() = live.toList()

    fun offer(n: Notice): OfferOutcome {
        val cur = visible
        if (!n.isActionBearing) {
            return when {
                cur == null -> show(n)
                !cur.isActionBearing -> {
                    visible = n
                    OfferOutcome.Replaced
                }
                else -> {
                    live.add(n.text)
                    OfferOutcome.LiveRegionOnly
                }
            }
        }
        return when {
            cur == null -> show(n)
            !cur.isActionBearing -> {
                visible = n
                OfferOutcome.Replaced
            }
            n.type == NoticeType.Retry -> {
                waiting.addFirst(cur)
                visible = n
                OfferOutcome.Shown
            }
            n.type == NoticeType.Why -> replaceWhyOrQueue(cur, n)
            else -> enqueue(n)
        }
    }

    /** The visible notice was activated or dismissed: show the next waiting one. */
    fun advance(): Notice? {
        visible = waiting.removeFirstOrNull()
        return visible
    }

    fun heightLimit(landscape: Boolean) =
        NoticeHeightLimit(maxLines = if (landscape) 1 else 2, maxFractionAboveIme = MAX_FRACTION_ABOVE_IME)

    private fun show(n: Notice): OfferOutcome {
        visible = n
        return OfferOutcome.Shown
    }

    private fun replaceWhyOrQueue(cur: Notice, n: Notice): OfferOutcome {
        if (cur.type == NoticeType.Why) {
            visible = n
            return OfferOutcome.Replaced
        }
        val i = waiting.indexOfFirst { it.type == NoticeType.Why }
        if (i >= 0) {
            waiting[i] = n
            return OfferOutcome.Replaced
        }
        return enqueue(n)
    }

    private fun enqueue(n: Notice): OfferOutcome {
        waiting.addLast(n)
        val undoCount = waiting.count { it.type == NoticeType.Undo } +
            if (visible?.type == NoticeType.Undo) 1 else 0
        if (undoCount > MAX_UNDO_NOTICES) {
            val i = waiting.indexOfFirst { it.type == NoticeType.Undo }
            if (i >= 0) {
                retired.add(waiting.removeAt(i))
                return OfferOutcome.RetiredOldestUndo
            }
        }
        return OfferOutcome.Queued
    }

    companion object {
        const val MAX_UNDO_NOTICES = 5
        const val MAX_FRACTION_ABOVE_IME = 0.25f
        const val AUTO_DISMISS_MS = 10_000L
    }
}
