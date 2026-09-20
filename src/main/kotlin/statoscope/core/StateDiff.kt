package statoscope.core

/**
 * Line-level LCS diff between [before] and [after] — the Kotlin stand-in for Swift's
 * `logStateDiff` (`Array.difference(from:)`, itself `CollectionDifference`) and
 * `statoscope-zustand`'s hand-rolled `stateDiff()`. Returns only the changed lines, each
 * prefixed `"- "` (removed) or `"+ "` (added); unchanged lines are omitted entirely, so a
 * one-field state change reads as a one-line diff regardless of how large the rest of the
 * state is.
 */
fun diffLines(before: List<String>, after: List<String>): List<String> {
    val m = before.size
    val n = after.size
    val lcs = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            lcs[i][j] = if (before[i] == after[j]) {
                lcs[i + 1][j + 1] + 1
            } else {
                maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
    }

    val result = mutableListOf<String>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
        when {
            before[i] == after[j] -> { i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { result += "- ${before[i]}"; i++ }
            else -> { result += "+ ${after[j]}"; j++ }
        }
    }
    while (i < m) { result += "- ${before[i]}"; i++ }
    while (j < n) { result += "+ ${after[j]}"; j++ }
    return result
}
