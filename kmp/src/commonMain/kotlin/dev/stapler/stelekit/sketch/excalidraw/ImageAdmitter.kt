// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Outcome of admitting one `files` entry: the image, or the reason it becomes a labelled placeholder. */
internal sealed interface Admission {
    class Admitted(val file: ExcalidrawFile) : Admission
    class Refused(val reason: ImportReason, val detail: String = "") : Admission
}

/**
 * Admits embedded images under explicit decoded-size limits (Story 5.1.1, engineering gap 13). The decoded pixel
 * size is derived from the file HEADER as soon as the first base64 slice is decoded, so a few KB that declare
 * 60,000 x 60,000 pixels are refused before the rest of the payload is decoded and no pixel is ever decoded here.
 * The payload is decoded in 64 KB slices with a budget tick between slices, so a 15 MB image is many short steps.
 * [onSlice] receives each slice's base64 length (the structural proof that no slice exceeds 64 KB).
 */
@OptIn(ExperimentalEncodingApi::class)
internal class ImageAdmitter(
    private val limits: SceneLimits,
    private val budget: ParseBudget,
    private val onSlice: (Int) -> Unit = {},
) {
    private var decodedBytesUsed = 0L

    suspend fun admit(fileId: String, declaredMime: String?, dataUrl: String?, created: Long?): Admission {
        if (dataUrl == null) return Admission.Refused(ImportReason.ImageInvalid, "no dataURL")
        val comma = dataUrl.indexOf(',')
        if (!dataUrl.startsWith(DATA_PREFIX) || comma < 0 || comma > HEADER_SCAN_CHARS) return Admission.Refused(ImportReason.ImageInvalid, "not a data URL")
        val header = dataUrl.substring(DATA_PREFIX.length, comma).lowercase()
        if (!header.endsWith(BASE64_SUFFIX)) return Admission.Refused(ImportReason.ImageInvalid, "not base64")
        val mime = normalise(header.removeSuffix(BASE64_SUFFIX))
        if (mime !in ALLOWED_MIMES) return Admission.Refused(ImportReason.ImageMimeNotAllowed, mime)
        if (declaredMime != null && normalise(declaredMime.lowercase()) != mime) return Admission.Refused(ImportReason.ImageMimeMismatch, "$declaredMime vs $mime")

        val payloadStart = comma + 1
        val payloadChars = dataUrl.length - payloadStart
        val estimatedBytes = payloadChars.toLong() / BASE64_GROUP_CHARS * BASE64_GROUP_BYTES
        if (estimatedBytes > limits.maxImageBytes) return Admission.Refused(ImportReason.ImageTooLarge, "file is larger than ${limits.maxImageBytes} bytes", )

        val out = ByteArray(estimatedBytes.toInt())
        var written = 0
        var pos = payloadStart
        var checked = false
        while (pos < dataUrl.length) {
            val sliceEnd = minOf(pos + SLICE_CHARS, dataUrl.length)
            val chunk = try {
                Base64.Default.decode(dataUrl, pos, sliceEnd)
            } catch (e: IllegalArgumentException) {
                return Admission.Refused(ImportReason.ImageInvalid, "invalid base64")
            }
            onSlice(sliceEnd - pos)
            if (written + chunk.size > out.size) return Admission.Refused(ImportReason.ImageInvalid, "payload longer than estimated")
            chunk.copyInto(out, written)
            written += chunk.size
            pos = sliceEnd
            budget.tick(chunk.size.toLong())
            if (!checked) {
                // Only the leading window is probed (and copied), so a header that never completes costs one window per slice, not the whole payload.
                if (written > HEADER_WINDOW_BYTES) return Admission.Refused(ImportReason.ImageInvalid, "no image header in the first $HEADER_WINDOW_BYTES bytes")
                when (val probe = ImageHeader.probe(out.copyOf(written))) {
                    is HeaderProbe.Found -> {
                        checked = true
                        refusalFor(probe, mime)?.let { return it }
                    }
                    is HeaderProbe.NeedMore -> Unit
                    HeaderProbe.Unknown -> return Admission.Refused(ImportReason.ImageMimeMismatch, "bytes are not a $mime image")
                }
            }
        }
        if (!checked) return Admission.Refused(ImportReason.ImageInvalid, "no image header found")
        val probe = ImageHeader.probe(out.copyOf(written)) as HeaderProbe.Found
        return Admission.Admitted(ExcalidrawFile(fileId, mime, out.copyOf(written), probe.widthPx, probe.heightPx, created))
    }

    private fun refusalFor(probe: HeaderProbe.Found, mime: String): Admission.Refused? {
        if (probe.kind.mime != mime) return Admission.Refused(ImportReason.ImageMimeMismatch, "bytes are ${probe.kind.mime}, declared $mime")
        if (probe.widthPx <= 0 || probe.heightPx <= 0) return Admission.Refused(ImportReason.ImageInvalid, "empty image")
        val pixels = probe.widthPx.toLong() * probe.heightPx
        if (probe.widthPx > limits.maxImageSidePx || probe.heightPx > limits.maxImageSidePx || pixels > limits.maxImagePixels) {
            return Admission.Refused(ImportReason.ImageTooLarge, "${probe.widthPx} x ${probe.heightPx} px")
        }
        val decoded = pixels * RGBA_BYTES
        if (decodedBytesUsed + decoded > limits.maxDecodedImageBytesPerSheet) return Admission.Refused(ImportReason.ImageBudgetExceeded, "sheet decoded-image budget used up")
        decodedBytesUsed += decoded
        return null
    }

    private fun normalise(mime: String) = if (mime == "image/jpg") "image/jpeg" else mime

    companion object {
        private const val SLICE_CHARS = ParseBudget.BASE64_SLICE_CHARS
        private const val DATA_PREFIX = "data:"
        private const val BASE64_SUFFIX = ";base64"
        private const val HEADER_SCAN_CHARS = 256
        private const val HEADER_WINDOW_BYTES = 4 * 1024 * 1024
        private const val BASE64_GROUP_CHARS = 4
        private const val BASE64_GROUP_BYTES = 3
        private const val RGBA_BYTES = 4L
        private val ALLOWED_MIMES = ImageKind.entries.map { it.mime }.toSet()
    }
}
