// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

enum class ScalarKind { NULL, BOOL, INT, DECIMAL, STRING, QUOTED }

sealed interface YamlNode

/** [text] is the unquoted value; [comment] is the trailing `# comment` text on the same line. */
data class YamlScalar(val text: String, val kind: ScalarKind, val comment: String? = null) : YamlNode

data class YamlMap(val entries: List<Pair<String, YamlNode>>) : YamlNode

data class YamlList(val items: List<YamlNode>) : YamlNode

sealed interface MiniYamlResult {
    data class Parsed(val root: YamlNode) : MiniYamlResult

    /** Anchors, tags, multi-document, flow style or block scalars; [line] is 1-based. */
    data class Unsupported(val line: Int) : MiniYamlResult

    /** Not valid within the subset (tabs, bad indentation, unterminated quote); [line] is 1-based. */
    data class Malformed(val line: Int) : MiniYamlResult
}

/**
 * Hand-written parser for the YAML subset a dimension config uses: nested maps by indentation,
 * lists of scalars and maps, scalars with a trailing comment. Never throws.
 */
object MiniYaml {
    fun parse(text: String): MiniYamlResult = try {
        Parser(text).run()
    } catch (e: Fail) {
        if (e.unsupported) MiniYamlResult.Unsupported(e.line) else MiniYamlResult.Malformed(e.line)
    }

    private class Fail(val line: Int, val unsupported: Boolean) : RuntimeException(null, null, false, false)

    private class Line(val no: Int, val indent: Int, val body: String)

    private class Parser(text: String) {
        private val lines: MutableList<Line>
        private var pos = 0

        init {
            val out = ArrayList<Line>()
            text.split('\n').forEachIndexed { index, raw ->
                val no = index + 1
                val line = raw.trimEnd('\r')
                val trimmed = line.trimStart(' ')
                if (trimmed.startsWith("\t") || line.takeWhile { it == ' ' || it == '\t' }.contains('\t')) throw Fail(no, false)
                if (trimmed.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
                if (trimmed == "---" || trimmed.startsWith("--- ") || trimmed == "...") throw Fail(no, true)
                out.add(Line(no, line.length - trimmed.length, trimmed.trimEnd()))
            }
            lines = out
        }

        fun run(): MiniYamlResult {
            if (lines.isEmpty()) return MiniYamlResult.Parsed(YamlMap(emptyList()))
            val root = block(lines[0].indent)
            if (pos < lines.size) throw Fail(lines[pos].no, false)
            return MiniYamlResult.Parsed(root)
        }

        private fun isItem(body: String) = body == "-" || body.startsWith("- ")

        private fun block(indent: Int): YamlNode {
            val first = lines[pos]
            if (first.indent != indent) throw Fail(first.no, false)
            return if (isItem(first.body)) list(indent) else map(indent)
        }

        private fun list(indent: Int): YamlNode {
            val items = ArrayList<YamlNode>()
            while (pos < lines.size && lines[pos].indent == indent && isItem(lines[pos].body)) {
                val line = lines[pos]
                val rest = line.body.removePrefix("-")
                val inner = rest.trimStart(' ')
                if (inner.isEmpty()) {
                    pos++
                    items.add(if (pos < lines.size && lines[pos].indent > indent) block(lines[pos].indent) else nullScalar())
                } else if (isItem(inner) || splitKey(inner, line.no) != null) {
                    // The item's own content starts on the dash line, at the column after the dash.
                    val shifted = Line(line.no, indent + (rest.length - inner.length) + 1, inner)
                    lines[pos] = shifted
                    items.add(block(shifted.indent))
                } else {
                    pos++
                    items.add(scalar(inner, line.no))
                }
            }
            return YamlList(items)
        }

        private fun map(indent: Int): YamlNode {
            val entries = ArrayList<Pair<String, YamlNode>>()
            while (pos < lines.size && lines[pos].indent == indent && !isItem(lines[pos].body)) {
                val line = lines[pos]
                val (key, rest) = splitKey(line.body, line.no) ?: throw Fail(line.no, false)
                pos++
                val value: YamlNode = if (rest.isEmpty() || rest.startsWith("#")) {
                    val next = lines.getOrNull(pos)
                    when {
                        next == null -> nullScalar()
                        next.indent > indent -> block(next.indent)
                        next.indent == indent && isItem(next.body) -> list(indent)
                        else -> nullScalar()
                    }
                } else {
                    scalar(rest, line.no)
                }
                entries.add(key to value)
            }
            if (pos < lines.size && lines[pos].indent > indent) throw Fail(lines[pos].no, false)
            return YamlMap(entries)
        }

        private fun nullScalar() = YamlScalar("", ScalarKind.NULL)

        /** Splits `key: rest`; null when [body] is not a mapping entry. The key is unquoted. */
        private fun splitKey(body: String, no: Int): Pair<String, String>? {
            if (body.first() in UNSUPPORTED_STARTS || body.startsWith("? ")) throw Fail(no, true)
            if (body.first() in "[{") return null
            val key: String
            var i: Int
            if (body.startsWith("\"") || body.startsWith("'")) {
                val (text, end) = quoted(body, 0, no)
                key = text
                i = end
                while (i < body.length && body[i] == ' ') i++
                if (i >= body.length || body[i] != ':') return null
            } else {
                i = 0
                while (i < body.length) {
                    if (body[i] == ':' && (i + 1 == body.length || body[i + 1] == ' ')) break
                    if (body[i] == '#' && i > 0 && body[i - 1] == ' ') return null
                    i++
                }
                if (i >= body.length) return null
                key = body.substring(0, i).trim()
            }
            return key to body.substring(i + 1).trim()
        }

        /** Returns the unquoted text and the index after the closing quote. */
        private fun quoted(s: String, start: Int, no: Int): Pair<String, Int> {
            val q = s[start]
            val sb = StringBuilder()
            var i = start + 1
            while (i < s.length) {
                val c = s[i]
                when {
                    q == '"' && c == '\\' && i + 1 < s.length -> {
                        sb.append(
                            when (val n = s[i + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> n
                            },
                        )
                        i += 2
                        continue
                    }
                    q == '\'' && c == '\'' && i + 1 < s.length && s[i + 1] == '\'' -> {
                        sb.append('\'')
                        i += 2
                        continue
                    }
                    c == q -> return sb.toString() to i + 1
                    else -> sb.append(c)
                }
                i++
            }
            throw Fail(no, false)
        }

        private fun scalar(rest: String, no: Int): YamlNode {
            val first = rest.first()
            if (first == '[') return flowList(rest, no)
            if (first in UNSUPPORTED_STARTS || first in "{|>") {
                throw Fail(no, true)
            }
            if (first == '"' || first == '\'') {
                val (text, end) = quoted(rest, 0, no)
                val tail = rest.substring(end).trim()
                if (tail.isNotEmpty() && !tail.startsWith("#")) throw Fail(no, false)
                return YamlScalar(text, ScalarKind.QUOTED, tail.removePrefix("#").trim().ifEmpty { null })
            }
            val hash = rest.indices.firstOrNull { rest[it] == '#' && (it == 0 || rest[it - 1] == ' ') }
            val value = (if (hash == null) rest else rest.substring(0, hash)).trim()
            val comment = hash?.let { rest.substring(it + 1).trim() }?.ifEmpty { null }
            return YamlScalar(value, classify(value), comment)
        }

        /** One-line `[a, b, c]` of scalars (the subset's only flow form); nesting is unsupported. */
        private fun flowList(rest: String, no: Int): YamlNode {
            val hash = rest.indices.firstOrNull { rest[it] == '#' && rest[it - 1] == ' ' && rest.substring(0, it).count { c -> c == '"' } % 2 == 0 }
            val body = (if (hash == null) rest else rest.substring(0, hash)).trim()
            val comment = hash?.let { rest.substring(it + 1).trim() }?.ifEmpty { null }
            if (!body.endsWith("]")) throw Fail(no, true)
            val inner = body.substring(1, body.length - 1)
            if (inner.contains('[') || inner.contains(']') || inner.contains('{')) throw Fail(no, true)
            if (inner.isBlank()) return YamlList(emptyList())
            val items = inner.split(',').map { scalar(it.trim().ifEmpty { throw Fail(no, false) }, no) }
            return YamlList(items.map { if (it is YamlScalar) it.copy(comment = comment) else it })
        }

        private fun classify(v: String): ScalarKind = when {
            v.isEmpty() || v == "~" || v == "null" -> ScalarKind.NULL
            v == "true" || v == "false" -> ScalarKind.BOOL
            INT.matches(v) -> ScalarKind.INT
            DECIMAL.matches(v) -> ScalarKind.DECIMAL
            else -> ScalarKind.STRING
        }
    }

    private const val UNSUPPORTED_STARTS = "&*!"

    private val INT = Regex("[-+]?[0-9]+")
    private val DECIMAL = Regex("[-+]?([0-9]+\\.[0-9]*|\\.[0-9]+)")
}
