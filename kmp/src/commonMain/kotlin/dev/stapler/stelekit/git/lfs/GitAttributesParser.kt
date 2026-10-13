// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.lfs

/** Parser for the `.gitattributes` / `.git/info/attributes` file format. */
object GitAttributesParser {
    private const val MACRO_PREFIX = "[attr]"

    /**
     * @param allowMacros git only honours `[attr]` macro definitions in the root file, `info/attributes`
     *   and global files, never in subdirectory files.
     */
    fun parse(text: String, dirPrefix: String = "", allowMacros: Boolean = dirPrefix.isEmpty(), sourceName: String? = null): GitAttributesFile {
        val rules = ArrayList<AttrRule>()
        val macros = LinkedHashMap<String, List<Pair<String, AttrState>>>()
        text.removePrefix("﻿").lines().forEachIndexed { index, rawLine ->
            val line = rawLine.trimStart(' ', '\t').trimEnd('\r')
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.startsWith(MACRO_PREFIX)) {
                if (allowMacros) parseMacro(line.removePrefix(MACRO_PREFIX))?.let { macros[it.first] = it.second }
                return@forEachIndexed
            }
            parseRule(line, index + 1)?.let { rules += it }
        }
        return if (sourceName != null) GitAttributesFile(dirPrefix, rules, macros, sourceName)
        else GitAttributesFile(dirPrefix, rules, macros)
    }

    private fun parseMacro(rest: String): Pair<String, List<Pair<String, AttrState>>>? {
        val tokens = rest.trimStart().split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.first() to tokens.drop(1).map(::parseAttribute)
    }

    private fun parseRule(line: String, lineNumber: Int): AttrRule? {
        val (pattern, remainder) = splitPattern(line) ?: return null
        // Git rejects negative patterns in attribute files ("Negative patterns are ignored").
        if (pattern.startsWith("!") || pattern.isEmpty()) return null
        val attrs = remainder.split(' ', '\t').filter { it.isNotEmpty() }.map(::parseAttribute)
        return AttrRule(pattern, attrs, lineNumber)
    }

    private fun parseAttribute(token: String): Pair<String, AttrState> = when {
        token.startsWith("-") && token.length > 1 -> token.substring(1) to AttrState.Unset
        token.startsWith("!") && token.length > 1 -> token.substring(1) to AttrState.Unspecified
        token.contains('=') -> token.substringBefore('=') to AttrState.Value(token.substringAfter('='))
        else -> token to AttrState.Set
    }

    /** Splits a rule line into its (possibly quoted) pattern and the attribute remainder. */
    private fun splitPattern(line: String): Pair<String, String>? {
        if (!line.startsWith("\"")) {
            val end = line.indexOfFirst { it == ' ' || it == '\t' }
            return if (end < 0) line to "" else line.substring(0, end) to line.substring(end + 1)
        }
        val sb = StringBuilder()
        var i = 1
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> return sb.toString() to line.substring(i + 1)
                c == '\\' && i + 1 < line.length -> {
                    sb.append(unescape(line[i + 1]))
                    i++
                }
                else -> sb.append(c)
            }
            i++
        }
        return null // unterminated quote: git ignores the line
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'
        't' -> '\t'
        else -> c
    }
}
