package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.PageName

/**
 * Carries the sender's page name alongside the markdown bytes, entirely inside the opaque
 * "payload" [FountainEncoder]/[ChunkFrameCodec]/[ChunkBuffer] already treat as a length+checksum
 * blob. [dev.stapler.stelekit.db.LogseqPageSerializer.serialize] never embeds a title — Logseq
 * derives a page's name from its filename, not its file content — and the QR receive path has no
 * file, so without this envelope the receiver has no way to know what the sender's page was
 * actually called (previously papered over with a synthesized "Received page &lt;epochMs&gt;"
 * placeholder in [dev.stapler.stelekit.ui.transfer.QrDecodeViewModel]).
 *
 * [wrap] runs BEFORE the markdown bytes reach [FountainEncoder]; [unwrap] runs AFTER
 * [ChunkBuffer.reassemble] already returned a [VerifiedTransferPayload] — this is a
 * payload-content-level change, not a wire-frame-level one. Layer 1 ([FountainEncoder],
 * [ChunkFrameCodec], [ChunkBuffer], [FountainChunk]) stays untouched and continues to just chunk
 * and checksum opaque bytes with no knowledge that a name is packed inside them.
 *
 * Format: `"<nameLenBytes decimal>\n"` followed by the name's UTF-8 bytes, followed by the
 * markdown's UTF-8 bytes. An ASCII-digit decimal length prefix — deliberately NOT a raw 2-byte
 * binary integer — because [ChunkBuffer.reassemble] hands the whole reassembled payload back as a
 * `String` (`bytes.decodeToString()`), so every byte of this envelope must itself be valid UTF-8
 * for that conversion (and the reverse `encodeToByteArray()` here in [unwrap]) to be lossless.
 * Raw bytes with the high bit set are not guaranteed valid standalone UTF-8 on their own, so a
 * binary length field risks silently corrupting itself for any page name whose byte length needed
 * it — an ASCII digit run avoids that risk entirely while staying just as simple.
 */
object TransferPayloadEnvelope {
    private const val DELIMITER: Byte = '\n'.code.toByte()

    /** Long enough for any realistic page-name byte length (a 7-digit prefix caps out at 9,999,999 bytes). */
    private const val MAX_HEADER_SEARCH_BYTES = 8

    fun wrap(pageName: PageName, markdown: String): ByteArray {
        val nameBytes = pageName.value.encodeToByteArray()
        val header = "${nameBytes.size}\n".encodeToByteArray()
        val markdownBytes = markdown.encodeToByteArray()

        val result = ByteArray(header.size + nameBytes.size + markdownBytes.size)
        header.copyInto(result)
        nameBytes.copyInto(result, header.size)
        markdownBytes.copyInto(result, header.size + nameBytes.size)
        return result
    }

    /**
     * [bytes] originates from a decoded QR transfer (attacker-adjacent input) — a missing
     * delimiter, an unparsable length, or a claimed name length exceeding the actual array must
     * return `Left`, never throw or index out of bounds.
     */
    fun unwrap(bytes: ByteArray): Either<DomainError.QrTransferError, Pair<PageName, String>> {
        val delimiterIndex = bytes.indexOfHeaderDelimiter()
            ?: return DomainError.QrTransferError.EnvelopeMalformed.left()

        val nameLen = bytes.copyOfRange(0, delimiterIndex).decodeToString().toIntOrNull()
            ?: return DomainError.QrTransferError.EnvelopeMalformed.left()
        if (nameLen < 0) return DomainError.QrTransferError.EnvelopeMalformed.left()

        val nameStart = delimiterIndex + 1
        val nameEnd = nameStart + nameLen
        // nameEnd < nameStart guards Int overflow from a maliciously huge claimed length; the
        // second check is the ordinary "ran past the actual buffer" bounds check.
        if (nameEnd < nameStart || nameEnd > bytes.size) {
            return DomainError.QrTransferError.EnvelopeMalformed.left()
        }

        val name = bytes.copyOfRange(nameStart, nameEnd).decodeToString()
        val markdown = bytes.copyOfRange(nameEnd, bytes.size).decodeToString()
        return (PageName(name) to markdown).right()
    }

    private fun ByteArray.indexOfHeaderDelimiter(): Int? {
        val limit = minOf(size, MAX_HEADER_SEARCH_BYTES)
        for (i in 0 until limit) {
            if (this[i] == DELIMITER) return i
        }
        return null
    }
}
