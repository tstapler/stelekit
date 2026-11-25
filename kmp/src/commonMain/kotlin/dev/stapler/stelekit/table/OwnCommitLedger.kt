package dev.stapler.stelekit.table

/** What the ledger concludes about an observed block content (Story 3.2, classification rules 1-5). */
enum class LedgerVerdict { Echo, Stale, Defer, Adopt, External, ExternalWithDraft, AdoptWithDraft }

/**
 * Remembers the table contents the sheet has shown, committed or loaded, so a feed emission can be told apart
 * from an external edit. Keyed by content hash plus length with its own monotonic `seq`; it never sees a DB
 * or UI version (R2-B1). Invariants (Story 3.2 rule 0): one entry per hash, "retired" means `Acked` (never
 * removed), echoing an entry acks every older `InFlight` one, `markDone` acks without an echo, eviction is LRU by `seq`.
 * Rule 4/5 (P5-5): an `Acked` state below `ownFloor` (newest own commit or undo/redo reservation; foreign
 * observations never raise it) is `Stale` unless `expectAdopt` reserved it; unknown content while
 * a commit is `InFlight` is `Defer`red and handed back by [takeDeferred].
 * Single-threaded: the host calls it from one coroutine.
 */
class OwnCommitLedger(private val capacity: Int = DEFAULT_CAPACITY) {
    private enum class State { InFlight, Acked }

    private data class Key(val hash: Long, val length: Int)
    private class Entry(var seq: Long, var state: State)
    private class Expectation(val key: Key, val seq: Long)

    private val entries = HashMap<Key, Entry>()
    private val expectations = ArrayList<Expectation>()
    private var nextSeq = 1L
    private var currentSeq = 0L
    private var ownFloor = 0L
    private var displayed: Key? = null
    private var deferred: String? = null

    val size: Int get() = entries.size

    /** The state the grid shows, as the ledger tracks it. */
    val displayedSeq: Long get() = currentSeq

    fun registerLoaded(content: String): Long = put(keyOf(content), State.Acked, makeCurrent = true)

    fun registerCommit(content: String): Long =
        put(keyOf(content), State.InFlight, makeCurrent = true).also { ownFloor = maxOf(ownFloor, it) }

    /** Declares the content the manager's undo/redo produced, reserving its `seq` now (R3-C1). */
    fun expectAdopt(content: String): Long {
        val seq = nextSeq++
        ownFloor = maxOf(ownFloor, seq)
        expectations.add(Expectation(keyOf(content), seq))
        if (expectations.size > MAX_EXPECTATIONS) expectations.removeAt(0)
        return seq
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
        return verdictFor(content, draftPending)
    }

    private fun verdictFor(content: String, draftPending: Boolean): LedgerVerdict {
        val key = keyOf(content)
        val entry = entries[key]
        // An Echo never consumes an expectation: the emission may be an older commit's, not the undo's (P5-5).
        if (key == displayed) {
            entry?.let { ack(it) }
            return LedgerVerdict.Echo
        }
        expectations.firstOrNull { it.key == key }?.let { expected ->
            expectations.remove(expected)
            put(key, State.Acked, makeCurrent = true, seq = expected.seq)
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
            return LedgerVerdict.Adopt
        }
        if (entry != null) {
            if (entry.seq < ownFloor) return LedgerVerdict.Stale
            if (entry.seq > ownFloor && entries.values.any { it.state == State.InFlight }) {
                deferred = content
                return LedgerVerdict.Defer
            }
            if (draftPending) return LedgerVerdict.AdoptWithDraft
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

    private fun ack(e: Entry) {
        val upTo = e.seq
        e.state = State.Acked
        for (other in entries.values) if (other.state == State.InFlight && other.seq < upTo) other.state = State.Acked
    }

    private fun put(key: Key, state: State, makeCurrent: Boolean, seq: Long = nextSeq++): Long {
        val existing = entries[key]
        if (existing != null) {
            existing.seq = seq
            existing.state = state
        } else {
            entries[key] = Entry(seq, state)
            if (entries.size > capacity) evictOldest()
        }
        if (makeCurrent) {
            currentSeq = seq
            displayed = key
        }
        return seq
    }

    private fun evictOldest() {
        val oldest = entries.entries.minByOrNull { it.value.seq } ?: return
        entries.remove(oldest.key)
    }

    private fun keyOf(content: String): Key {
        var h = FNV_OFFSET
        for (ch in content) {
            h = (h xor ch.code.toLong()) * FNV_PRIME
        }
        return Key(h, content.length)
    }

    companion object {
        const val DEFAULT_CAPACITY = 128
        private const val MAX_EXPECTATIONS = 16
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
    }
}
