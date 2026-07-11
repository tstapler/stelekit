package dev.stapler.stelekit.transfer.qrcode

/**
 * LT peeling/reduction engine, ported from bc-ur's `fountain-decoder.cpp`
 * (`process_simple_part` / `process_mixed_part` / `reduce_part_by_part`, BCR-2020-012, ADR-001).
 * Pure in-memory XOR algebra: repeatedly reduces mixed parts by simple (single-fragment) parts
 * until every fragment index `0 until seqLen` has been recovered, tolerating any arrival order
 * and any number of redundant/duplicate parts.
 *
 * [ChunkBuffer] is the wire-level adapter (payload bound checks, `FountainChunk` parsing, the
 * whole-payload CRC32 proof gate); this class only knows about fragment-index sets and bytes.
 */
internal class FountainDecoder(private val seqLen: Int) {

    class Part(val indexes: Set<Int>, val data: ByteArray) {
        val isSimple: Boolean get() = indexes.size == 1
        val index: Int get() = indexes.first()
    }

    private val receivedIndexes = mutableSetOf<Int>()
    private val simpleParts = LinkedHashMap<Set<Int>, Part>()
    private val mixedParts = LinkedHashMap<Set<Int>, Part>()
    private val queue = ArrayDeque<Part>()

    /** Set once every fragment index has been recovered (structural completion — see [isComplete]). */
    var resultFragments: List<ByteArray>? = null
        private set

    val receivedCount: Int get() = receivedIndexes.size

    /** Structural completion only — does NOT mean the reassembled bytes are correct. */
    val isComplete: Boolean get() = resultFragments != null

    fun receive(part: Part) {
        if (isComplete) return
        enqueue(part)
        while (!isComplete && queue.isNotEmpty()) processQueueItem()
    }

    private fun enqueue(p: Part) {
        queue.addLast(p)
    }

    private fun processQueueItem() {
        val part = queue.removeFirst()
        if (part.isSimple) processSimplePart(part) else processMixedPart(part)
    }

    private fun processSimplePart(p: Part) {
        val idx = p.index
        if (idx in receivedIndexes) return // dedup by fragment identity, not frame count

        simpleParts[p.indexes] = p
        receivedIndexes.add(idx)

        if (receivedIndexes.size == seqLen) {
            resultFragments = (0 until seqLen).map { i ->
                simpleParts.values.first { it.index == i }.data
            }
        } else {
            reduceMixedBy(p)
        }
    }

    private fun reduceMixedBy(p: Part) {
        val reduced = mixedParts.values.map { reducePartByPart(it, p) }
        mixedParts.clear()
        for (r in reduced) {
            if (r.isSimple) enqueue(r) else mixedParts[r.indexes] = r
        }
    }

    private fun reducePartByPart(a: Part, b: Part): Part {
        // If b's fragments are a strict subset of a's, a can be reduced by b: a XOR b.
        if (b.indexes != a.indexes && a.indexes.containsAll(b.indexes)) {
            return Part(a.indexes - b.indexes, xorWith(a.data, b.data))
        }
        return a
    }

    private fun processMixedPart(p: Part) {
        if (mixedParts.containsKey(p.indexes)) return // dedup by fragment identity

        var reduced = p
        for (r in simpleParts.values) reduced = reducePartByPart(reduced, r)
        for (r in mixedParts.values) reduced = reducePartByPart(reduced, r)

        if (reduced.isSimple) {
            enqueue(reduced)
        } else {
            reduceMixedBy(reduced)
            mixedParts[reduced.indexes] = reduced
        }
    }
}
