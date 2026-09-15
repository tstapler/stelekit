package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.PageName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferPayloadEnvelopeTest {

    @Test
    fun envelope_should_RoundTripPageNameAndMarkdown_When_WrappedAndUnwrapped() {
        val pageName = PageName("Meeting Notes")
        val markdown = "- root block\n\t- child block\n"

        val wrapped = TransferPayloadEnvelope.wrap(pageName, markdown)
        val result = TransferPayloadEnvelope.unwrap(wrapped)

        assertTrue(result.isRight())
        val (decodedName, decodedMarkdown) = result.getOrNull()!!
        assertEquals(pageName.value, decodedName.value)
        assertEquals(markdown, decodedMarkdown)
    }

    @Test
    fun envelope_should_RoundTripMultiByteUtf8Name_When_WrappedAndUnwrapped() {
        // The name itself can be non-ASCII — the ASCII-digit length prefix is measured in BYTES,
        // not characters, so this also guards the byte-vs-character-count distinction.
        val pageName = PageName("café ☕ — notes")
        val markdown = "- some body\n"

        val wrapped = TransferPayloadEnvelope.wrap(pageName, markdown)
        val result = TransferPayloadEnvelope.unwrap(wrapped)

        assertTrue(result.isRight())
        val (decodedName, decodedMarkdown) = result.getOrNull()!!
        assertEquals(pageName.value, decodedName.value)
        assertEquals(markdown, decodedMarkdown)
    }

    @Test
    fun envelope_should_RoundTripEmptyMarkdown_When_NameIsPresent() {
        val pageName = PageName("Empty Page")
        val markdown = ""

        val wrapped = TransferPayloadEnvelope.wrap(pageName, markdown)
        val result = TransferPayloadEnvelope.unwrap(wrapped)

        assertTrue(result.isRight())
        val (decodedName, decodedMarkdown) = result.getOrNull()!!
        assertEquals(pageName.value, decodedName.value)
        assertEquals(markdown, decodedMarkdown)
    }

    @Test
    fun unwrap_should_ReturnLeftEnvelopeMalformed_When_NoDelimiterFound() {
        // No '\n' anywhere in the byte array — the header search must terminate and reject,
        // never scan unbounded or throw.
        val garbage = "not an envelope at all, no delimiter here".encodeToByteArray()

        val result = TransferPayloadEnvelope.unwrap(garbage)

        assertTrue(result.isLeft())
        assertIs<DomainError.QrTransferError.EnvelopeMalformed>(result.leftOrNull())
    }

    @Test
    fun unwrap_should_ReturnLeftEnvelopeMalformed_When_HeaderIsNotAValidNumber() {
        val malformed = "ab\nsome name and markdown".encodeToByteArray()

        val result = TransferPayloadEnvelope.unwrap(malformed)

        assertTrue(result.isLeft())
        assertIs<DomainError.QrTransferError.EnvelopeMalformed>(result.leftOrNull())
    }

    @Test
    fun unwrap_should_ReturnLeftEnvelopeMalformed_When_ClaimedNameLengthExceedsActualBytes() {
        // Attacker-adjacent input (a maliciously-crafted QR code): a claimed nameLen far beyond
        // the actual buffer must be bounds-checked and rejected, never index out of bounds.
        val malicious = "999999\nshort".encodeToByteArray()

        val result = TransferPayloadEnvelope.unwrap(malicious)

        assertTrue(result.isLeft())
        assertIs<DomainError.QrTransferError.EnvelopeMalformed>(result.leftOrNull())
    }

    @Test
    fun unwrap_should_ReturnLeftEnvelopeMalformed_When_ClaimedNameLengthIsNegative() {
        val malicious = "-1\nsome bytes".encodeToByteArray()

        val result = TransferPayloadEnvelope.unwrap(malicious)

        assertTrue(result.isLeft())
        assertIs<DomainError.QrTransferError.EnvelopeMalformed>(result.leftOrNull())
    }

    @Test
    fun unwrap_should_ReturnLeftEnvelopeMalformed_When_ByteArrayIsEmpty() {
        val result = TransferPayloadEnvelope.unwrap(ByteArray(0))

        assertTrue(result.isLeft())
        assertIs<DomainError.QrTransferError.EnvelopeMalformed>(result.leftOrNull())
    }
}
