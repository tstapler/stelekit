// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.merge

/** [text] is the merged file body; [hasConflictMarkers] is true when it embeds `<<<<<<< LOCAL` blocks. */
data class UnionMergeOutput(val text: String, val hasConflictMarkers: Boolean)

/**
 * Faithful Kotlin port of the wiki's custom git merge driver (`merge=logseq-union`, the python
 * `stapler_logseq_tools.merge.MergeDriver`). JGit never runs custom drivers and the app cannot run
 * python on Android/web, so the driver's behavior is reimplemented here, quirks included.
 *
 * Strategy order, first match wins (python order, minus `TestCompatibilityStrategy`, a
 * test-environment hack that is inert outside python's own test suite):
 * 1. data-loss protection (either side shrank / dropped lines the other kept)
 * 2. simple addition (one side appended, other unchanged)
 * 3. same line count (positional per-line merge; both-changed lines become an embedded conflict)
 * 4. page-reference (any `[[`, `#` or `((`): difflib-opcode merge that dedups lines
 * 5. fallback: local lines then remote-only lines, deduplicated
 *
 * Quirks preserved on purpose: input is split with python `str.splitlines()` semantics, output is
 * `"\n".join(lines)` (no trailing newline), several strategies never propagate deletions, and
 * duplicate lines collapse.
 *
 * Deviation handled by callers: python exits 0 and git would commit `<<<<<<< LOCAL` markers
 * silently; [UnionMergeOutput.hasConflictMarkers] lets the sync flow surface them as a real
 * conflict instead.
 */
object LogseqUnionMergeDriver {
    /** The `.gitattributes` `merge=` value this port answers to. */
    const val DRIVER_NAME = "logseq-union"

    fun merge(baseText: String, localText: String, remoteText: String): UnionMergeOutput {
        val lines = mergeLines(PyStr.splitLines(baseText), PyStr.splitLines(localText), PyStr.splitLines(remoteText))
        val text = lines.joinToString("\n").replace("\r\n", "\n").replace('\r', '\n')
        return UnionMergeOutput(text, text.lineSequence().any { it.startsWith("<<<<<<<") })
    }

    fun mergeLines(base: List<String>, local: List<String>, remote: List<String>): List<String> = when {
        detectAndroidDataLoss(base, local, remote) || detectLocalDataLoss(base, local, remote) ->
            dataLossProtection(base, local, remote)
        isSimpleAddition(base, local, remote) -> if (local.size > base.size && remote == base) local else remote
        base.size == local.size && local.size == remote.size -> nonOverlapping(base, local, remote)
        (base + local + remote).any { "[[" in it || "#" in it || "((" in it } -> pageReference(base, local, remote)
        else -> fallback(local, remote)
    }

    private fun detectAndroidDataLoss(base: List<String>, local: List<String>, remote: List<String>) =
        remote.size < base.size * 0.7 && local.size >= base.size

    private fun detectLocalDataLoss(base: List<String>, local: List<String>, remote: List<String>): Boolean {
        val baseSet = stripped(base)
        if (baseSet.isEmpty()) return false
        val lost = (baseSet - stripped(local)).intersect(stripped(remote))
        return lost.size >= 3 && lost.size.toDouble() / baseSet.size > 0.2
    }

    private fun stripped(lines: List<String>): Set<String> =
        lines.map { PyStr.strip(it) }.filter { it.isNotEmpty() }.toSet()

    private fun dataLossProtection(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        if (detectLocalDataLoss(base, local, remote)) {
            val localOnly = linesAbsentFromBase(local, base)
            return if (localOnly.isNotEmpty()) remote + "" + localOnly else remote
        }
        val remoteOnly = linesAbsentFromBase(remote, base)
        return if (remoteOnly.isNotEmpty()) local + "" + remoteOnly else local
    }

    /** Non-blank [lines] whose stripped text matches no stripped line of [base]. */
    private fun linesAbsentFromBase(lines: List<String>, base: List<String>): List<String> {
        val baseStripped = base.map { PyStr.strip(it) }.toSet()
        return lines.filter {
            val s = PyStr.strip(it)
            s.isNotEmpty() && s !in baseStripped
        }
    }

    private fun isSimpleAddition(base: List<String>, local: List<String>, remote: List<String>) =
        (local.size > base.size && local.subList(0, base.size) == base && remote == base) ||
            (remote.size > base.size && remote.subList(0, base.size) == base && local == base)

    private fun nonOverlapping(base: List<String>, local: List<String>, remote: List<String>): List<String> =
        base.indices.map { i ->
            when {
                local[i] == remote[i] -> local[i]
                local[i] == base[i] -> remote[i]
                remote[i] == base[i] -> local[i]
                else -> "<<<<<<< LOCAL\n${local[i]}\n=======\n${remote[i]}\n>>>>>>> REMOTE"
            }
        }

    private enum class Fate { Equal, Replace, Delete }

    private class LineFate(val fate: Fate, val lines: List<String>)

    private class SideMap(val fates: Map<Int, LineFate>, val insertions: Map<Int, List<String>>)

    private fun sideMap(base: List<String>, side: List<String>): SideMap {
        val fates = HashMap<Int, LineFate>()
        val insertions = HashMap<Int, List<String>>()
        for (op in PySequenceMatcher(base, side).opcodes()) {
            when (op.tag) {
                PyOpcode.Tag.Equal -> for (i in op.i1 until op.i2) fates[i] = LineFate(Fate.Equal, listOf(base[i]))
                PyOpcode.Tag.Replace -> replaceFates(op, side, fates)
                PyOpcode.Tag.Delete -> for (i in op.i1 until op.i2) fates[i] = LineFate(Fate.Delete, emptyList())
                PyOpcode.Tag.Insert -> insertions[op.i1] = side.subList(op.j1, op.j2)
            }
        }
        return SideMap(fates, insertions)
    }

    /** Equal-size replace maps 1:1; otherwise the first base line takes every replacement line. */
    private fun replaceFates(op: PyOpcode, side: List<String>, fates: MutableMap<Int, LineFate>) {
        val replacement = side.subList(op.j1, op.j2)
        val oneToOne = op.i2 - op.i1 == op.j2 - op.j1
        for ((idx, i) in (op.i1 until op.i2).withIndex()) {
            val out = when {
                oneToOne -> listOf(replacement[idx])
                idx == 0 -> replacement
                else -> emptyList()
            }
            fates[i] = LineFate(Fate.Replace, out)
        }
    }

    private fun pageReference(base: List<String>, local: List<String>, remote: List<String>): List<String> {
        val l = sideMap(base, local)
        val r = sideMap(base, remote)
        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        fun emit(lines: List<String>) {
            for (line in lines) if (seen.add(line)) result.add(line)
        }

        for (idx in 0..base.size) {
            l.insertions[idx]?.let(::emit)
            r.insertions[idx]?.let(::emit)
            if (idx >= base.size) continue
            val lf = l.fates[idx] ?: LineFate(Fate.Equal, listOf(base[idx]))
            val rf = r.fates[idx] ?: LineFate(Fate.Equal, listOf(base[idx]))
            when {
                lf.fate == Fate.Replace && rf.fate == Fate.Replace -> {
                    // Local cleared the block ("-" or blank) while remote kept content: prefer remote.
                    if (isEmptyBullet(lf.lines.firstOrNull()) && !isEmptyBullet(rf.lines.firstOrNull())) {
                        emit(rf.lines)
                        emit(lf.lines.drop(1))
                    } else {
                        emit(lf.lines)
                    }
                }
                lf.fate == Fate.Replace -> emit(lf.lines)
                rf.fate == Fate.Replace -> emit(rf.lines)
                lf.fate == Fate.Delete || rf.fate == Fate.Delete -> Unit
                else -> emit(listOf(base[idx]))
            }
        }
        return result
    }

    private fun isEmptyBullet(line: String?): Boolean {
        val s = PyStr.strip(line ?: "")
        return s == "-" || s.isEmpty()
    }

    // Python's difflib fallback appends every remote line not already taken from local (both of
    // its branches together cover exactly that); its per-index backup merge only runs on an
    // exception that set-building cannot raise.
    private fun fallback(local: List<String>, remote: List<String>): List<String> {
        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        for (line in local + remote) if (seen.add(line)) result.add(line)
        return result
    }
}

/** The few python `str` behaviors the driver relies on. */
internal object PyStr {
    private val whitespace: Set<Char> = buildSet {
        addAll("\t\n\u000B\u000C\r\u001C\u001D\u001E\u001F \u0085      　".toList())
        for (c in ' '..' ') add(c)
    }

    private val lineBreaks: Set<Char> = "\n\r\u000B\u000C\u001C\u001D\u001E\u0085  ".toSet()

    fun strip(s: String): String = s.trim { it in whitespace }

    /** `str.splitlines()` (keepends=False): `\r\n` is one break, a trailing break adds no empty line. */
    fun splitLines(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] in lineBreaks) {
                out.add(text.substring(start, i))
                if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }
}
