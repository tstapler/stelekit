// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

import dev.stapler.stelekit.capture.CaptureSocketClient
import dev.stapler.stelekit.capture.PendingCapturesDirectory
import dev.stapler.stelekit.capture.PendingCaptureWriter

/**
 * Parses `--capture-text <text>` from CLI args, returning `null` when the flag is absent or
 * has no following value — either case means "launch the app normally."
 */
fun parseCaptureArgs(args: Array<String>): String? {
    val i = args.indexOf("--capture-text")
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

/**
 * Headless CLI capture path (`stelekit --capture-text "..."`): tries the socket fast path first
 * (delivers immediately if SteleKit is already running), falling back to the pending-capture
 * queue with the same `captureId` so a lost ack can't double-record. Extracted from `main()` so
 * it's directly testable without launching Compose — [socketPath]/[pendingCaptureDirectory]
 * default to the same real paths `main()` uses, overridable only for tests.
 */
fun runHeadlessCapture(
    text: String,
    socketPath: String = "${System.getProperty("user.home")}/.stelekit/stelekit.sock",
    pendingCaptureDirectory: String = PendingCapturesDirectory.path(),
): CaptureSocketClient.CaptureSendResult {
    val result = CaptureSocketClient.trySend(text, socketPath)
    if (!result.delivered) {
        PendingCaptureWriter.write(text, result.captureId, pendingCaptureDirectory)
    }
    return result
}
