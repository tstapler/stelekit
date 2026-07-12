package dev.stapler.stelekit.transfer.qrcode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FountainDecoderTest {

    @Test
    fun receive_should_BoundMixedPartsSize_When_SenderNeverEmitsAReducingSimplePart() {
        // Gate 2 finding M4: a malicious/stalled sender that never emits a genuine reducible
        // (simple, single-index) fragment can otherwise grow `mixedParts` forever — one retained
        // entry per distinct mixed-index-set it sends. This test feeds many more distinct degree-2
        // index-sets than the cap allows (mirrors FountainDecoder.MAX_MIXED_PARTS_PER_SEQ_LEN = 8)
        // and asserts retained memory stays bounded rather than growing unboundedly.
        val seqLen = 20
        val maxAllowed = seqLen * 8
        val decoder = FountainDecoder(seqLen)

        // All degree-2 (never degree-1/"simple"), so reduceMixedBy/reducePartByPart can never
        // shrink the map — no index-set here is ever a strict subset of another. C(20,2) = 190
        // distinct pairs, well beyond maxAllowed (160).
        var fed = 0
        for (i in 0 until seqLen) {
            for (j in (i + 1) until seqLen) {
                val data = byteArrayOf((i + j).toByte())
                decoder.receive(FountainDecoder.Part(setOf(i, j), data))
                fed++
            }
        }

        assertTrue(fed > maxAllowed, "test setup should feed more distinct index-sets than the cap")
        assertTrue(
            decoder.mixedPartsCountForTest <= maxAllowed,
            "mixedParts grew to ${decoder.mixedPartsCountForTest}, expected <= $maxAllowed",
        )
        assertFalse(decoder.isComplete, "no simple fragment was ever received, so decoding cannot resolve")
    }

    @Test
    fun receive_should_StillReduceNormally_When_MixedPartsStayWellUnderTheCap() {
        // Regression guard: a small, legitimate number of mixed parts (well under the M4 cap)
        // still reduces to completion once the matching simple parts arrive — the bound must not
        // interfere with honest decode traffic.
        val seqLen = 3
        val decoder = FountainDecoder(seqLen)
        val fragments = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))

        // One mixed part (degree 2, well under the cap of 24) that will reduce once fragment 0
        // arrives as a simple part.
        val mixed01 = xorWith(fragments[0], fragments[1])
        decoder.receive(FountainDecoder.Part(setOf(0, 1), mixed01))
        decoder.receive(FountainDecoder.Part(setOf(0), fragments[0]))
        decoder.receive(FountainDecoder.Part(setOf(2), fragments[2]))

        assertTrue(decoder.isComplete)
        val result = decoder.resultFragments
        assertTrue(result != null)
        assertTrue(result!![0].contentEquals(fragments[0]))
        assertTrue(result[1].contentEquals(fragments[1]))
        assertTrue(result[2].contentEquals(fragments[2]))
    }
}
