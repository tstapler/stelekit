package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetGrayFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.Foundation.NSData

/**
 * iOS [QrCodec] actual — encode-only (Epic 4.2; decode/receive deferred to Epic 4.4 per
 * ADR-003/ADR-005). Uses CoreImage's `CIQRCodeGenerator` filter rather than a third-party QR
 * library, since it ships in the OS and needs no dependency wiring for a send-only path.
 *
 * `CIQRCodeGenerator`'s output image is unscaled — its extent is exactly `moduleCount x
 * moduleCount` points, one point per module — so it is rasterized 1:1 into a single-channel gray
 * bitmap context and thresholded directly, with no need to detect a QR finder pattern the way a
 * decode path would.
 *
 * NOT independently compiled/tested in this environment: Kotlin/Native's iOS targets require
 * Xcode tooling unavailable on Linux. This CoreImage cinterop code is written from API knowledge
 * and matched against this repo's existing iosMain cinterop conventions
 * ([dev.stapler.stelekit.platform.security.CredentialStore],
 * [dev.stapler.stelekit.platform.sensor.IOSLidarDepthProvider]) but has not been exercised by a
 * real Kotlin/Native compile — see plan.md's Epic 4.2 preamble (`ci-ios.yml` cannot catch a
 * broken iOS actual either).
 */
@OptIn(ExperimentalForeignApi::class)
actual object QrCodec {

    // CIContext is explicitly designed by Apple to be reused across calls (it owns an expensive
    // Metal/GPU rendering pipeline internally) — encode() runs continuously while the sender
    // screen cycles frames (~every 400ms), so constructing a new one per call was a real,
    // avoidable per-frame cost. Cached lazily instead of at object-init time so a device with no
    // usable CIContext (unlikely, but not provably impossible) fails on first encode(), not at
    // class-load time.
    private val ciContext: CIContext by lazy { CIContext.contextWithOptions(null) }

    actual fun encode(bytes: ByteArray): QrMatrix {
        val filter = CIFilter.filterWithName("CIQRCodeGenerator")
            ?: error("CIQRCodeGenerator filter unavailable on this device")
        filter.setValue(bytes.toNSData(), forKey = "inputMessage")
        // "M" matches the JVM/Android actuals' ErrorCorrectionLevel.M (see their KDoc).
        filter.setValue("M", forKey = "inputCorrectionLevel")

        val outputImage = filter.outputImage
            ?: error("CIQRCodeGenerator produced no output image")

        val moduleCount = outputImage.extent.useContents { size.width }.toInt()
        require(moduleCount > 0) { "CIQRCodeGenerator produced an empty output image" }

        val cgImage = ciContext.createCGImage(outputImage, fromRect = outputImage.extent)
            ?: error("Failed to rasterize QR CIImage to CGImage")

        val colorSpace = CGColorSpaceCreateDeviceGray()
        val bitmapContext = CGBitmapContextCreate(
            data = null,
            width = moduleCount.toULong(),
            height = moduleCount.toULong(),
            bitsPerComponent = 8u,
            bytesPerRow = moduleCount.toULong(),
            space = colorSpace,
            bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNone.value,
        ) ?: error("Failed to create bitmap context for QR rasterization")

        // CIQRCodeGenerator's output has a transparent background outside the code (a documented
        // CoreImage quirk) — fill white first so "no ink" reads as a light module rather than an
        // unset (black, since CGBitmapContextCreate zero-fills) buffer.
        CGContextSetGrayFillColor(bitmapContext, 1.0, 1.0)
        val fullRect = CGRectMake(0.0, 0.0, moduleCount.toDouble(), moduleCount.toDouble())
        CGContextFillRect(bitmapContext, fullRect)
        CGContextDrawImage(bitmapContext, fullRect, cgImage)

        val pixels = CGBitmapContextGetData(bitmapContext)
            ?: error("Bitmap context has no backing pixel buffer")
        val pixelPtr = pixels.reinterpret<kotlinx.cinterop.UByteVar>()
        val bits = BooleanArray(moduleCount * moduleCount) { i -> pixelPtr[i].toInt() < 128 }

        CGContextRelease(bitmapContext)
        CGColorSpaceRelease(colorSpace)
        CGImageRelease(cgImage)

        return QrMatrix(bits, moduleCount)
    }

    /**
     * iOS QR receive is deferred to Epic 4.4 (AVFoundation `CameraFrameSource` + Vision/
     * `AVCaptureMetadataOutput` decode actual). Throws rather than returning `null` so callers
     * can't mistake "not implemented" for "no QR found in this frame" (validation.md:
     * `decode_should_ThrowNotImplementedError_When_DecodeCalled_BecauseReceiveIsDeferred`).
     */
    actual fun decode(frame: CameraFrame): ByteArray? =
        throw NotImplementedError(
            "iOS QrCodec.decode is deferred to Epic 4.4 — Epic 4.2 ships iOS send (encode) only"
        )

    private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
    }
}
