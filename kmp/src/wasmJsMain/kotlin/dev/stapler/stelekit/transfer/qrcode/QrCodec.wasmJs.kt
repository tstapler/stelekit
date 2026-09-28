package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame

/**
 * Web/WASM [QrCodec] actual — QR transfer is entirely out of scope for v1 on this target
 * (ADR-005: not just receive, unlike iOS's encode-only carve-out). Both operations throw rather
 * than silently succeeding/returning `null`, matching iOS `QrCodec.decode`'s "fail loudly, don't
 * pretend to work" convention for a deferred platform — there is no reachable UI entry point on
 * web that calls either function (`QrTransferEntryPoints` is never wired into the web app), so
 * this exists purely to satisfy the `expect`/`actual` compile requirement for the wasm-js target.
 */
actual object QrCodec {
    actual fun encode(bytes: ByteArray): QrMatrix =
        throw NotImplementedError("QR transfer is out of scope for Web/WASM in v1 (ADR-005)")

    actual fun decode(frame: CameraFrame): ByteArray? =
        throw NotImplementedError("QR transfer is out of scope for Web/WASM in v1 (ADR-005)")
}
