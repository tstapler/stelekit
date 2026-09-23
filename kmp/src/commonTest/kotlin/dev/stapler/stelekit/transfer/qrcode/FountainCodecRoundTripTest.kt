package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.TransferId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 1.2.4's property-based coverage: encoder and decoder composed end to end over a
 * simulated lossy, reordering channel — this codec's own integration test, since Layer 1 has no
 * external I/O to integrate against (validation.md).
 */
class FountainCodecRoundTripTest {

    /**
     * The wire payload is always markdown text ([VerifiedTransferPayload.markdown] is a `String`,
     * per `LogseqPageSerializer.serialize`'s output), so the random payload here is random
     * printable-ASCII text rather than arbitrary binary — arbitrary binary is not guaranteed to
     * round-trip through `ByteArray.decodeToString()`, which is a real constraint of this
     * codec's markdown-shaped payload, not a test artifact.
     */
    private fun randomMarkdownLikePayload(random: Random, byteCount: Int): String {
        val printableAscii = (0x20..0x7E).map { it.toChar() }
        return buildString(byteCount) {
            repeat(byteCount) { append(printableAscii[random.nextInt(printableAscii.size)]) }
        }
    }

    @Test
    fun reassemble_should_ReconstructExactPayload_When_SimulatedChannelDrops30PercentFramesRandomOrder() {
        val random = Random(20260711)
        val payload = randomMarkdownLikePayload(random, 2048)
        val payloadBytes = payload.encodeToByteArray()

        val encoder = FountainEncoder(TransferId(42), payloadBytes, maxFragmentBytes = 100).getOrNull()!!
        val seqLen = encoder.seqLen

        // Generate enough parts (including redundant mixed ones) to survive a 30% drop.
        val generated = encoder.parts().take(seqLen * 3).toList()
        val delivered = generated
            .filter { random.nextDouble() > 0.30 }
            .shuffled(random)

        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        for (chunk in delivered) {
            buffer.accept(chunk)
            if (buffer.isComplete()) break
        }

        assertTrue(buffer.isComplete(), "decoder should reach completion before the delivered parts run out")
        assertEquals(payload, buffer.reassemble().getOrNull()?.markdown)
    }

    @Test
    fun fountainCodec_should_EncodeAndDecodeConsistently_When_EncoderAndDecoderComposedEndToEnd() {
        val random = Random(90210)
        val markdown = buildString {
            append("- root bullet\n")
            repeat(50) { append("\t- child bullet number $it with some extra filler text\n") }
        }
        val payloadBytes = markdown.encodeToByteArray()

        val encoder = FountainCodec.encoder(TransferId(1), payloadBytes, maxFragmentBytes = 60).getOrNull()!!
        val seqLen = encoder.seqLen

        val generated = encoder.parts().take(seqLen * 3).toList()
        val delivered = generated.filter { random.nextDouble() > 0.30 }.shuffled(random)

        val decoder = FountainCodec.decoder()
        for (chunk in delivered) {
            decoder.accept(chunk)
            if (decoder.isComplete()) break
        }

        assertTrue(decoder.isComplete())
        assertEquals(markdown, decoder.reassemble().getOrNull()?.markdown)
    }
}
