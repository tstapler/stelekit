// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.util.ContentHasher

object MigrationChecksumComputer {

    /**
     * Normalizes [input] and returns a `sha256-v1:<hex>` checksum.
     *
     * Normalization steps (in order):
     * 1. Strip UTF-8 BOM (`\uFEFF`) from the start of the string.
     * 2. Replace all `\r\n` and standalone `\r` with `\n`.
     * 3. Strip trailing whitespace from each line.
     * 4. Trim leading/trailing blank lines from the whole string.
     */
    fun compute(input: String): String {
        // 1. Strip UTF-8 BOM
        val withoutBom = if (input.startsWith("\uFEFF")) input.substring(1) else input

        // 2. Normalize line endings: CRLF -> LF, then lone CR -> LF
        val normalizedEndings = withoutBom.replace("\r\n", "\n").replace("\r", "\n")

        // 3. Strip trailing whitespace from each line
        val lines = normalizedEndings.split("\n").map { it.trimEnd() }

        // 4. Trim leading/trailing blank lines
        val trimmedLines = lines
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }

        val normalized = trimmedLines.joinToString("\n")

        val hex = ContentHasher.sha256(normalized)
        return "sha256-v1:$hex"
    }
}
