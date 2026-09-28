package dev.stapler.stelekit.platform

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private fun base64ToUint8Array(base64: String): JsAny = js("""
    (function() {
        var binary = atob(base64);
        var len = binary.length;
        var bytes = new Uint8Array(len);
        for (var i = 0; i < len; i++) { bytes[i] = binary.charCodeAt(i); }
        return bytes;
    })()
""")

@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toJsUint8Array(): JsAny = base64ToUint8Array(Base64.encode(this))

private fun arrayBufferToBase64(buffer: JsAny): String = js("""
    (function() {
        var bytes = new Uint8Array(buffer);
        var binary = '';
        var chunk = 0x8000;
        for (var i = 0; i < bytes.length; i += chunk) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        return btoa(binary);
    })()
""")

@OptIn(ExperimentalEncodingApi::class)
internal fun jsArrayBufferToByteArray(buffer: JsAny): ByteArray =
    Base64.decode(arrayBufferToBase64(buffer))
