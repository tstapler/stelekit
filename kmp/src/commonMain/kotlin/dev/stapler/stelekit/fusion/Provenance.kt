package dev.stapler.stelekit.fusion

/**
 * An immutable set of constraint ids stored as a bitset, so a derived row can name exactly which
 * constraints contributed to it (ADR-003). Ids are the caller's non-negative [Constraint.id]s.
 */
class Provenance private constructor(private val words: LongArray) {

    val size: Int get() = words.sumOf { it.countOneBits() }

    val isEmpty: Boolean get() = words.isEmpty()

    operator fun contains(id: Int): Boolean {
        val word = id / WORD_BITS
        return id >= 0 && word < words.size && (words[word] ushr (id % WORD_BITS)) and 1L == 1L
    }

    fun union(other: Provenance): Provenance {
        val out = LongArray(maxOf(words.size, other.words.size)) { i ->
            words.getOrElse(i) { 0L } or other.words.getOrElse(i) { 0L }
        }
        return trimmed(out)
    }

    fun with(id: Int): Provenance = union(of(listOf(id)))

    fun without(id: Int): Provenance {
        if (id !in this) return this
        val copy = words.copyOf()
        copy[id / WORD_BITS] = copy[id / WORD_BITS] and (1L shl (id % WORD_BITS)).inv()
        return trimmed(copy)
    }

    /** Ids in ascending order. */
    fun ids(): List<Int> = buildList {
        words.forEachIndexed { index, word ->
            for (bit in 0 until WORD_BITS) if ((word ushr bit) and 1L == 1L) add(index * WORD_BITS + bit)
        }
    }

    override fun equals(other: Any?): Boolean = other is Provenance && words.contentEquals(other.words)

    override fun hashCode(): Int = words.contentHashCode()

    override fun toString(): String = ids().joinToString(prefix = "{", postfix = "}")

    companion object {
        private const val WORD_BITS = 64

        val EMPTY = Provenance(LongArray(0))

        /** Every id must be non-negative. */
        fun of(ids: Iterable<Int>): Provenance {
            val list = ids.toList()
            if (list.isEmpty()) return EMPTY
            val out = LongArray(list.max() / WORD_BITS + 1)
            for (id in list) {
                require(id >= 0) { "constraint ids are non-negative" }
                out[id / WORD_BITS] = out[id / WORD_BITS] or (1L shl (id % WORD_BITS))
            }
            return Provenance(out)
        }

        // Trailing zero words are dropped so equal sets have equal arrays.
        private fun trimmed(words: LongArray): Provenance {
            var end = words.size
            while (end > 0 && words[end - 1] == 0L) end--
            return if (end == 0) EMPTY else Provenance(if (end == words.size) words else words.copyOf(end))
        }
    }
}
