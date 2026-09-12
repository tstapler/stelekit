// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.desktop

/**
 * Parses `--capture-text <text>` from CLI args, returning `null` when the flag is absent or
 * has no following value — either case means "launch the app normally."
 */
fun parseCaptureArgs(args: Array<String>): String? {
    val i = args.indexOf("--capture-text")
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
