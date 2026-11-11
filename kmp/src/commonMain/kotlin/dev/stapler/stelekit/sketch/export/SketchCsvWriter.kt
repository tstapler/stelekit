// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.export

/** Writes the dimension table as RFC 4180 CSV (CRLF rows). */
object SketchCsvWriter {
    const val HEADER = "id,label,value,sixteenths,status,sources"

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t')

    fun write(dimensions: List<ExportDimension>): String = buildString {
        append(HEADER).append("\r\n")
        for (d in dimensions) {
            val row = listOf(
                textField(d.id), textField(d.label), textField(d.value), d.sixteenths?.toString().orEmpty(),
                textField(d.status), textField(d.sources),
            )
            append(row.joinToString(",")).append("\r\n")
        }
    }

    /** Quotes a field holding a comma, quote or line break, and defuses a leading spreadsheet formula character. */
    fun textField(raw: String): String {
        val safe = if (raw.firstOrNull() in FORMULA_STARTS) "'$raw" else raw
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
