package dev.stapler.stelekit.git.merge

sealed class SequenceOp {
    data class Equal(val i1: Int, val i2: Int, val j1: Int, val j2: Int) : SequenceOp()
    data class Insert(val i1: Int, val j1: Int, val j2: Int) : SequenceOp()
    data class Delete(val i1: Int, val i2: Int, val j1: Int) : SequenceOp()
    data class Replace(val i1: Int, val i2: Int, val j1: Int, val j2: Int) : SequenceOp()
}

/**
 * Compute a diff between two lists of strings using LCS dynamic programming.
 * Returns a list of [SequenceOp] describing how to transform [a] into [b].
 * Indices follow the Python difflib opcodes convention:
 *   i1..i2 are indices into [a], j1..j2 are indices into [b] (exclusive upper bound).
 */
fun diff(a: List<String>, b: List<String>): List<SequenceOp> {
    val m = a.size
    val n = b.size

    // Build LCS dp table
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    // Traceback to produce raw edit sequence as (aIdx, bIdx) matched pairs
    // then collapse into opcodes
    val ops = mutableListOf<SequenceOp>()
    var i = 0
    var j = 0

    while (i < m || j < n) {
        when {
            i < m && j < n && a[i] == b[j] -> {
                // Equal — collect consecutive equals
                val i1 = i
                val j1 = j
                while (i < m && j < n && a[i] == b[j]) {
                    i++
                    j++
                }
                ops.add(SequenceOp.Equal(i1, i, j1, j))
            }
            j < n && (i >= m || dp[i][j + 1] >= dp[i + 1][j]) -> {
                // Insert from b
                val j1 = j
                while (j < n && shouldInsert(i, j, m, n, a, b, dp)) {
                    j++
                }
                ops.add(SequenceOp.Insert(i, j1, j))
            }
            else -> {
                // Delete from a
                val i1 = i
                while (i < m && shouldDelete(i, j, m, n, a, b, dp)) {
                    i++
                }
                ops.add(SequenceOp.Delete(i1, i, j))
            }
        }
    }

    // Merge adjacent Insert+Delete or Delete+Insert into Replace
    return mergeToReplace(ops)
}

private fun shouldInsert(i: Int, j: Int, m: Int, n: Int, a: List<String>, b: List<String>, dp: Array<IntArray>): Boolean {
    val exhaustedA = i >= m
    val preferInsert = exhaustedA || dp[i][j + 1] >= dp[i + 1][j]
    val notMatching = exhaustedA || a[i] != b[j]
    return j < n && preferInsert && notMatching
}

private fun shouldDelete(i: Int, j: Int, m: Int, n: Int, a: List<String>, b: List<String>, dp: Array<IntArray>): Boolean {
    val exhaustedB = j >= n
    val preferDelete = exhaustedB || dp[i + 1][j] > dp[i][j + 1]
    val notMatching = exhaustedB || a[i] != b[j]
    return i < m && preferDelete && notMatching
}

private fun mergeToReplace(ops: List<SequenceOp>): List<SequenceOp> {
    val result = mutableListOf<SequenceOp>()
    var idx = 0
    while (idx < ops.size) {
        val cur = ops[idx]
        val next = ops.getOrNull(idx + 1)
        when {
            cur is SequenceOp.Delete && next is SequenceOp.Insert && cur.j1 == next.i1 -> {
                result.add(SequenceOp.Replace(cur.i1, cur.i2, next.j1, next.j2))
                idx += 2
            }
            cur is SequenceOp.Insert && next is SequenceOp.Delete && cur.i1 == next.i1 -> {
                result.add(SequenceOp.Replace(next.i1, next.i2, cur.j1, cur.j2))
                idx += 2
            }
            else -> {
                result.add(cur)
                idx++
            }
        }
    }
    return result
}
