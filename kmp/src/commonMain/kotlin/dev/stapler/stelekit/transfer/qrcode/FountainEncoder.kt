package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.Crc32
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.util.ContentHasher
import kotlin.math.ceil

/**
 * Luby Transform fountain encoder, ported faithfully from BlockchainCommons/bc-ur's
 * `fountain-encoder.cpp` / `xoshiro256.cpp` / `random-sampler.cpp` / `fountain-utils.cpp`
 * (BCR-2020-012, ADR-001) so [FountainCodecVectorTest] can replay the official BC-UR reference
 * vectors bit-for-bit.
 *
 * Deviation from bc-ur, called out per ADR-001: bc-ur's `seqNum` is 1-based; this codec's
 * [ChunkIndex] is 0-based (`chunkIndex = seqNum - 1`) to match `TransferTypes.kt`'s "zero-based"
 * contract. The mixed-part RNG seed is still exactly `(seqNum, checksum)` as upstream — NOT
 * `transferId` — so fragment bytes match the reference vectors; `transferId` is wire-level
 * session scoping only (ADR-001 header), not an input to the fountain math.
 */
class FountainEncoder private constructor(
    private val transferId: TransferId,
    private val messageLen: Int,
    private val checksum: Int,
    private val fragmentLen: Int,
    private val fragments: List<ByteArray>,
) {
    /** Number of "pure" (non-mixed) fragments the payload was partitioned into. */
    val seqLen: Int get() = fragments.size

    /**
     * Unbounded lazy sequence of fountain parts — a receiver can start mid-stream and still
     * reconstruct (ADR-001). Chunk indexes `0 until seqLen` are the pure fragments; indexes at or
     * beyond `seqLen` are LT-mixed redundant parts.
     */
    fun parts(): Sequence<FountainChunk> = sequence {
        var seqNum = 0
        while (true) {
            seqNum += 1
            val indexes = chooseFragments(seqNum, seqLen, checksum)
            val mixed = mix(fragments, indexes, fragmentLen)
            yield(
                FountainChunk(
                    transferId = transferId,
                    chunkIndex = ChunkIndex(seqNum - 1),
                    payloadLen = messageLen,
                    payloadCrc = PayloadChecksum(checksum),
                    fragment = mixed,
                ),
            )
        }
    }

    companion object {
        /** Matches bc-ur's own default `min_fragment_len` so vector-parity holds out of the box. */
        const val DEFAULT_MIN_FRAGMENT_BYTES = 10
        const val DEFAULT_MAX_PAYLOAD_BYTES = 65536

        operator fun invoke(
            transferId: TransferId,
            payloadBytes: ByteArray,
            maxFragmentBytes: Int,
            maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
            minFragmentBytes: Int = DEFAULT_MIN_FRAGMENT_BYTES,
        ): Either<DomainError.QrTransferError.PayloadTooLarge, FountainEncoder> {
            if (payloadBytes.size > maxPayloadBytes) {
                return DomainError.QrTransferError.PayloadTooLarge(payloadBytes.size, maxPayloadBytes).left()
            }
            val checksum = Crc32.of(payloadBytes)
            val fragmentLen = findNominalFragmentLength(payloadBytes.size, minFragmentBytes, maxFragmentBytes)
            val fragments = partitionMessage(payloadBytes, fragmentLen)
            return FountainEncoder(transferId, payloadBytes.size, checksum, fragmentLen, fragments).right()
        }
    }
}

// ---- BC-UR LT fountain math (BCR-2020-012), ported for bit-exact reference-vector parity ----
// Shared by FountainEncoder (this file) and ChunkBuffer/FountainDecoder (same package).

internal fun findNominalFragmentLength(messageLen: Int, minFragmentLen: Int, maxFragmentLen: Int): Int {
    require(messageLen > 0)
    require(minFragmentLen > 0)
    require(maxFragmentLen >= minFragmentLen)
    val maxFragmentCount = messageLen / minFragmentLen
    var fragmentLen = messageLen
    for (fragmentCount in 1..maxFragmentCount) {
        fragmentLen = ceil(messageLen.toDouble() / fragmentCount).toInt()
        if (fragmentLen <= maxFragmentLen) break
    }
    return fragmentLen
}

internal fun partitionMessage(message: ByteArray, fragmentLen: Int): List<ByteArray> {
    val fragments = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < message.size) {
        val end = minOf(offset + fragmentLen, message.size)
        val fragment = ByteArray(fragmentLen)
        message.copyInto(fragment, 0, offset, end)
        fragments.add(fragment)
        offset += fragmentLen
    }
    return fragments
}

internal fun mix(fragments: List<ByteArray>, indexes: Set<Int>, fragmentLen: Int): ByteArray {
    val result = ByteArray(fragmentLen)
    for (index in indexes) xorInto(result, fragments[index])
    return result
}

internal fun xorInto(target: ByteArray, source: ByteArray) {
    for (i in target.indices) target[i] = (target[i].toInt() xor source[i].toInt()).toByte()
}

internal fun xorWith(a: ByteArray, b: ByteArray): ByteArray {
    val result = a.copyOf()
    xorInto(result, b)
    return result
}

internal fun chooseDegree(seqLen: Int, rng: Xoshiro256): Int {
    val degreeProbabilities = (1..seqLen).map { 1.0 / it }
    val sampler = RandomSampler(degreeProbabilities)
    return sampler.next { rng.nextDouble() } + 1
}

/**
 * Selects the set of pure-fragment indexes mixed into part [seqNum] (1-based, matching bc-ur).
 * `seqNum <= seqLen` is always the pure fragment `seqNum - 1` — independent of the RNG — which is
 * what lets a receiver reconstruct from just the first `seqLen` parts with no redundancy needed.
 */
internal fun chooseFragments(seqNum: Int, seqLen: Int, checksum: Int): Set<Int> {
    if (seqNum <= seqLen) return setOf(seqNum - 1)
    val seed = intToBytesBE(seqNum) + intToBytesBE(checksum)
    val rng = Xoshiro256.fromBytes(seed)
    val degree = chooseDegree(seqLen, rng)
    val indexes = (0 until seqLen).toList()
    return shuffled(indexes, rng).take(degree).toSet()
}

/** Fisher-Yates shuffle matching bc-ur's `shuffled()` (pop a random remaining element each step). */
internal fun <T> shuffled(items: List<T>, rng: Xoshiro256): List<T> {
    val remaining = items.toMutableList()
    val result = mutableListOf<T>()
    while (remaining.isNotEmpty()) {
        val index = rng.nextInt(0, remaining.size - 1)
        result.add(remaining.removeAt(index))
    }
    return result
}

internal fun intToBytesBE(n: Int): ByteArray = byteArrayOf(
    ((n ushr 24) and 0xFF).toByte(),
    ((n ushr 16) and 0xFF).toByte(),
    ((n ushr 8) and 0xFF).toByte(),
    (n and 0xFF).toByte(),
)

private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = hex[i * 2].digitToInt(16)
        val lo = hex[i * 2 + 1].digitToInt(16)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * Deterministic xoshiro256** RNG (Blackman/Vigna, public domain), seeded via SHA-256 exactly as
 * bc-ur's `Xoshiro256::hash_then_set_s` — required for [FountainCodecVectorTest] bit-parity.
 * Reuses [ContentHasher]'s existing pure-Kotlin SHA-256 (no new hash implementation needed).
 */
internal class Xoshiro256 private constructor(s0: Long, s1: Long, s2: Long, s3: Long) {
    private val s = longArrayOf(s0, s1, s2, s3)

    private fun rotl(x: Long, k: Int): Long = (x shl k) or (x ushr (64 - k))

    fun next(): Long {
        val result = rotl(s[1] * 5, 7) * 9
        val t = s[1] shl 17

        s[2] = s[2] xor s[0]
        s[3] = s[3] xor s[1]
        s[1] = s[1] xor s[2]
        s[0] = s[0] xor s[3]

        s[2] = s[2] xor t
        s[3] = rotl(s[3], 45)

        return result
    }

    /** Unsigned 64-bit-to-double conversion — matches C++'s implicit `uint64_t -> double` cast. */
    fun nextDouble(): Double = next().toULong().toDouble() / TWO_POW_64

    fun nextInt(low: Int, high: Int): Int = (nextDouble() * (high - low + 1)).toInt() + low

    companion object {
        private const val TWO_POW_64 = 18446744073709551616.0

        fun fromBytes(bytes: ByteArray): Xoshiro256 = fromDigest(hexToBytes(ContentHasher.sha256(bytes)))
        fun fromString(s: String): Xoshiro256 = fromBytes(s.encodeToByteArray())
        fun fromChecksum(checksum: Int): Xoshiro256 = fromBytes(intToBytesBE(checksum))

        private fun fromDigest(digest: ByteArray): Xoshiro256 {
            require(digest.size == 32)
            val s = LongArray(4)
            for (i in 0 until 4) {
                var v = 0L
                for (n in 0 until 8) v = (v shl 8) or (digest[i * 8 + n].toLong() and 0xFF)
                s[i] = v
            }
            return Xoshiro256(s[0], s[1], s[2], s[3])
        }
    }
}

/**
 * Walker-Vose alias-method weighted sampler, ported from bc-ur's `random-sampler.cpp` (itself
 * translated from Keith Schwarz's reference C implementation). Drives the LT degree distribution.
 */
internal class RandomSampler(probs: List<Double>) {
    private val probsTable: DoubleArray
    private val aliases: IntArray

    init {
        require(probs.all { it >= 0.0 })
        val sum = probs.sum()
        require(sum > 0.0)
        val n = probs.size
        val p = DoubleArray(n) { probs[it] * n / sum }

        // Reverse iteration order (n-1 downTo 0) matches bc-ur exactly — it affects which index
        // lands in `small` vs. `large` on ties, which affects the resulting alias table.
        val small = ArrayDeque<Int>()
        val large = ArrayDeque<Int>()
        for (i in n - 1 downTo 0) {
            if (p[i] < 1.0) small.addLast(i) else large.addLast(i)
        }

        val pr = DoubleArray(n)
        val al = IntArray(n)
        while (small.isNotEmpty() && large.isNotEmpty()) {
            val a = small.removeLast()
            val g = large.removeLast()
            pr[a] = p[a]
            al[a] = g
            p[g] = p[g] + p[a] - 1.0
            if (p[g] < 1.0) small.addLast(g) else large.addLast(g)
        }
        while (large.isNotEmpty()) pr[large.removeLast()] = 1.0
        while (small.isNotEmpty()) pr[small.removeLast()] = 1.0

        probsTable = pr
        aliases = al
    }

    fun next(rng: () -> Double): Int {
        val r1 = rng()
        val r2 = rng()
        val i = (probsTable.size * r1).toInt()
        return if (r2 < probsTable[i]) i else aliases[i]
    }
}
