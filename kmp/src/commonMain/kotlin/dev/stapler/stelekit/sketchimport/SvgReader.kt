// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser

private const val MAX_DEPTH = 64

/**
 * Reads the SVG subset the sketch import needs (lines, rects, M L H V Z paths, texts, transforms,
 * metadata). Uses Ksoup XML mode (ADR-004 amendment, Story 4.1a): attribute case survives and text
 * is read with wholeText() so whitespace is kept. Never throws; anything unsupported is listed.
 */
object SvgReader {
    fun read(source: String): SvgDoc {
        val acc = Accumulator()
        try {
            val root = Ksoup.parse(source, parser = Parser.xmlParser()).children().firstOrNull()
            if (root == null) acc.unsupported += Unsupported("empty-document") else acc.visit(root, emptyList(), 0)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            acc.unsupported += Unsupported("parse-error:${e::class.simpleName}")
        }
        return acc.build()
    }

    private class Accumulator {
        var width: Double? = null
        var height: Double? = null
        var viewBox: String? = null
        val lines = mutableListOf<SvgLineElement>()
        val rects = mutableListOf<SvgRect>()
        val paths = mutableListOf<SvgPath>()
        val texts = mutableListOf<SvgText>()
        val transforms = linkedSetOf<String>()
        var metadata: String? = null
        val unsupported = mutableListOf<Unsupported>()
        var visited = 0

        fun visit(el: Element, parent: List<String>, depth: Int) {
            visited++
            if (depth > MAX_DEPTH) {
                unsupported += Unsupported("depth")
                return
            }
            val own = el.attr("transform")
            val chain = if (own.isBlank()) parent else parent + own
            if (own.isNotBlank()) transforms += own
            when (val tag = el.tagName()) {
                "svg" -> {
                    if (depth == 0) {
                        width = el.attr("width").toLengthDouble()
                        height = el.attr("height").toLengthDouble()
                        viewBox = el.attr("viewBox").ifBlank { null }
                    }
                    visitChildren(el, chain, depth)
                }
                "g" -> visitChildren(el, chain, depth)
                "path" -> paths += SvgPath(parsePath(el.attr("d")), chain)
                "line" -> lines += SvgLineElement(
                    SvgLine(el.num("x1"), el.num("y1"), el.num("x2"), el.num("y2")), chain,
                )
                "rect" -> rects += SvgRect(el.num("x"), el.num("y"), el.num("width"), el.num("height"), chain)
                "text" -> texts += SvgText(
                    el.num("x"), el.num("y"), el.attr("text-anchor").ifBlank { "start" }, el.wholeText(), chain,
                )
                "metadata" -> metadata = el.wholeText().trim()
                "defs", "title", "desc" -> Unit
                else -> unsupported += Unsupported(tag)
            }
        }

        private fun visitChildren(el: Element, chain: List<String>, depth: Int) {
            for (child in el.children()) visit(child, chain, depth + 1)
        }

        private fun parsePath(d: String): List<SvgLine> {
            val out = mutableListOf<SvgLine>()
            var cx = 0.0
            var cy = 0.0
            var startX = 0.0
            var startY = 0.0
            var cmd = ' '
            var args = mutableListOf<Double>()

            fun flush() {
                when (cmd) {
                    'M', 'L' -> args.chunked(2).filter { it.size == 2 }.forEachIndexed { i, (x, y) ->
                        if (cmd == 'L' || i > 0) out += SvgLine(cx, cy, x, y) else {
                            startX = x
                            startY = y
                        }
                        cx = x
                        cy = y
                    }
                    'H' -> args.forEach { x -> out += SvgLine(cx, cy, x, cy); cx = x }
                    'V' -> args.forEach { y -> out += SvgLine(cx, cy, cx, y); cy = y }
                    'Z' -> if (cx != startX || cy != startY) {
                        out += SvgLine(cx, cy, startX, startY)
                        cx = startX
                        cy = startY
                    }
                }
                args = mutableListOf()
            }

            for (token in PATH_TOKEN.findAll(d).map { it.value }) {
                val c = token[0]
                if (c.isLetter()) {
                    flush()
                    cmd = c.uppercaseChar().takeIf { c.isUpperCase() && it in "MLHVZ" } ?: ' '
                    if (cmd == ' ') unsupported += Unsupported("path-command:$c")
                    if (cmd == 'Z') flush()
                } else if (cmd != ' ') {
                    token.toDoubleOrNull()?.let { args += it }
                }
            }
            flush()
            return out
        }

        fun build() = SvgDoc(
            width, height, viewBox, lines, rects, paths, texts, transforms.toList(), metadata, unsupported, visited,
        )
    }
}

private val PATH_TOKEN = Regex("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

private fun Element.num(name: String): Double = attr(name).toLengthDouble() ?: 0.0

private fun String.toLengthDouble(): Double? = trim().removeSuffix("px").toDoubleOrNull()

fun readSvg(source: String): SvgDoc = SvgReader.read(source)
