package dev.stapler.stelekit.table

/** What the ledger concludes about an observed block content (Story 3.2, classification rules 1-5). */
enum class LedgerVerdict { Echo, Stale, Defer, Adopt, External, ExternalWithDraft, AdoptWithDraft }

/**
 * Remembers the table contents the sheet has shown, committed or loaded, so a feed emission can be told apart
 * from an external edit. Keyed by the content itself (equality is exact, so distinct contents can never collide)
 * with its own monotonic `seq`; it never sees a DB or UI version (R2-B1). Invariants (Story 3.2 rule 0): one entry
 * per content, "retired" means `Acked` (never removed), echoing an entry acks every older `InFlight` one, `markDone`
 * acks without an echo, eviction is LRU by `seq` under both an entry and a total-size budget.
 * Rule 4/5 (P5-5, P5-6): an `Acked` state below `ownFloor` (newest own commit or undo/redo reservation; foreign
 * observations never raise it) is `Stale` unless `expectAdopt` reserved it, but only until the emission of the
 * state at `ownFloor` itself has been observed: the feed is ordered, so no older echo can follow it and an emission
 * equal to an older Acked state is then a genuine external edit (`External`/`ExternalWithDraft`). Known limit: if
 * the feed conflates that newest emission away, a later external revert to an older state reads as `Stale`.
 * Unknown content while a commit is `InFlight` is `Defer`red and handed back by [takeDeferred].
 * Single-threaded: the host calls it from one coroutine.
 */
class OwnCommitLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private enum class State { InFlight, Acked }

    private class Entry(var seq: Long, var state: State, var recommitted: Boolean = false)
    private class Expectation(val key: String, val seq: Long)

    private val entries = HashMap<String, Entry>()
    private var totalChars = 0L
    private var ownFloorObserved = false
    private var floorAmbiguous = false
    private var floorContent: String? = null
    private val expectations = ArrayList<Expectation>()
    private var nextSeq = 1L
    private var currentSeq = 0L
    private var ownFloor = 0L
    private var displayed: String? = null
    private var deferred: String? = null
    private var lastStale: String? = null

    val size: Int get() = entries.size

    /** The state the grid shows, as the ledger tracks it. */
    val displayedSeq: Long get() = currentSeq

    fun registerLoaded(content: String): Long = put(content, State.Acked, makeCurrent = true)

    fun registerCommit(content: String): Long {
        val ambiguous = recurs(content)
        return put(content, State.InFlight, makeCurrent = true, recommitted = content in entries)
            .also { raiseFloor(it, ambiguous, content) }
    }

    /**
     * Content already in the ledger while the previous floor's emission is still due: an emission of it cannot be told
     * from an old one (A, B, A), so it must not lift the floor. The floor then stays armed until the next commit.
     */
    private fun recurs(content: String) = content in entries && ownFloor > 0 && !ownFloorObserved

    /** Declares the content the manager's undo/redo produced, reserving its `seq` now (R3-C1). */
    fun expectAdopt(content: String): Long {
        val seq = nextSeq++
        // Undo or redo to what is already displayed changes nothing: no expectation, no floor. Only when the ledger is
        // settled (no expectation pending, nothing in flight, newest own emission seen): otherwise the display is about
        // to move and "displayed" is not where the UI will be.
        if (content == displayed && isSettled()) return seq
        raiseFloor(seq, recurs(content), content)
        expectations.add(Expectation(content, seq))
        if (expectations.size > MAX_EXPECTATIONS) expectations.removeAt(0)
        return seq
    }

    private fun isSettled() =
        expectations.isEmpty() && ownFloorObserved && entries.values.none { it.state == State.InFlight }

    private fun raiseFloor(seq: Long, ambiguous: Boolean, content: String) {
        if (seq > ownFloor) {
            floorContent = content
            ownFloor = seq
            ownFloorObserved = false
            floorAmbiguous = ambiguous
        }
    }

    /** The commit's `Job` completed: retire it without waiting for an echo (R3-C5). A re-seq'd entry no longer matches. */
    fun markDone(seq: Long) {
        entries.values.firstOrNull { it.seq == seq && it.state == State.InFlight }?.state = State.Acked
    }

    /** Foreign content held by [LedgerVerdict.Defer], once nothing is `InFlight` any more; null otherwise. Clears it. */
    fun takeDeferred(): String? {
        if (entries.values.any { it.state == State.InFlight }) return null
        return deferred.also { deferred = null }
    }

    /** Any new emission supersedes held foreign content: the feed is ordered, so the DB has moved on. */
    fun classify(content: String, draftPending: Boolean): LedgerVerdict {
        deferred = null
        lastStale = null
        return verdictFor(content, draftPending).also { if (it == LedgerVerdict.Stale) lastStale = content }
    }

    /**
     * The newest emission, if it was judged `Stale`, once nothing is `InFlight` any more: every write has completed, so
     * the DB holds that emission's content unless a newer one is still due (which then supersedes it). Content
     * recurring across commits and undos can make a genuine last emission look like an old echo; this settles it.
     * Clears it; null when nothing is pending or the display already shows it.
     */
    fun takeSettled(): String? {
        if (entries.values.any { it.state == State.InFlight }) return null
        // Only the newest registration's own content: an older echo that trails a newer commit is not the DB's state.
        return lastStale.also { lastStale = null }?.takeIf { it != displayed && it == floorContent }
    }

    /** Shows [content] released by [takeSettled]: an external edit as far as the host is concerned. */
    fun settle(content: String, draftPending: Boolean): LedgerVerdict {
        if (draftPending) {
            put(content, State.Acked, makeCurrent = false)
            return LedgerVerdict.ExternalWithDraft
        }
        put(content, State.Acked, makeCurrent = true)
        return LedgerVerdict.External
    }

    private fun verdictFor(content: String, draftPending: Boolean): LedgerVerdict {
        val key = content
        val entry = entries[key]
        // An Echo never consumes an expectation: the emission may be an older commit's, not the undo's (P5-5).
        if (key == displayed) {
            entry?.let {
                ack(it)
                observed(it.seq)
            }
            return LedgerVerdict.Echo
        }
        expectations.firstOrNull { it.key == key }?.let { expected ->
            expectations.remove(expected)
            if (entry != null && entry.state == State.InFlight) {
                // The same content is also a commit still in flight (A, B, A): keep that registration, only move the display.
                currentSeq = entry.seq
                displayed = key
                entry.recommitted = true
            } else {
                put(key, State.Acked, makeCurrent = true, seq = expected.seq)
            }
            observed(expected.seq)
            return LedgerVerdict.Adopt
        }
        if (entry != null && entry.state == State.InFlight) {
            val stale = entry.seq < maxOf(currentSeq, ownFloor)
            ack(entry)
            if (stale) return LedgerVerdict.Stale
            // Not the displayed content: an expected undo target moved the display below this newer commit, so the
            // host must rebuild to it (P5-5); a plain Echo would leave the older state shown.
            currentSeq = entry.seq
            displayed = key
            observed(entry.seq)
            return LedgerVerdict.Adopt
        }
        if (entry != null && !(entry.seq < ownFloor && ownFloorObserved)) {
            if (entry.seq < ownFloor) return LedgerVerdict.Stale
            if (entry.seq > ownFloor && entries.values.any { it.state == State.InFlight }) {
                deferred = content
                return LedgerVerdict.Defer
            }
            if (draftPending) return LedgerVerdict.AdoptWithDraft
            observed(entry.seq)
            put(key, State.Acked, makeCurrent = true)
            return LedgerVerdict.Adopt
        }
        if (entries.values.any { it.state == State.InFlight }) {
            deferred = content
            return LedgerVerdict.Defer
        }
        if (draftPending) {
            put(key, State.Acked, makeCurrent = false)
            return LedgerVerdict.ExternalWithDraft
        }
        put(key, State.Acked, makeCurrent = true)
        return LedgerVerdict.External
    }

    /** The emission of the state at `ownFloor` arrived: nothing older can follow it on an ordered feed. */
    private fun observed(seq: Long) {
        if (seq == ownFloor && !floorAmbiguous) ownFloorObserved = true
    }

    private fun ack(e: Entry) {
        // An emission of re-committed content may be the old state's: only the Job completing (`markDone`) retires it.
        if (e.state == State.InFlight && e.recommitted) return
        val upTo = e.seq
        e.state = State.Acked
        for (other in entries.values) if (other.state == State.InFlight && other.seq < upTo) other.state = State.Acked
    }

    private fun put(key: String, state: State, makeCurrent: Boolean, seq: Long = nextSeq++, recommitted: Boolean = false): Long {
        val existing = entries[key]
        if (existing != null) {
            existing.seq = seq
            existing.state = state
            existing.recommitted = recommitted
        } else {
            entries[key] = Entry(seq, state)
            totalChars += key.length
            while (entries.size > 1 && (entries.size > capacity || totalChars > MAX_TOTAL_CHARS)) evictOldest(keep = key)
        }
        if (makeCurrent) {
            currentSeq = seq
            displayed = key
        }
        return seq
    }

    private fun evictOldest(keep: String) {
        val oldest = entries.entries.filter { it.key != keep }.minByOrNull { it.value.seq }?.key ?: return
        entries.remove(oldest) // the map entry view throws once the map changed, so read the key first
        totalChars -= oldest.length
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
        private const val MAX_EXPECTATIONS = 16
        private const val MAX_TOTAL_CHARS = 4_000_000L
    }
}
