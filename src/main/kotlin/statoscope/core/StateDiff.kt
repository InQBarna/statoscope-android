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
    // Whatever both ends share can't be part of the change: a state where one field changed shrinks to a few lines here
    var start = 0
    while (start < before.size && start < after.size && before[start] == after[start]) start++
    var endBefore = before.size
    var endAfter = after.size
    while (endBefore > start && endAfter > start && before[endBefore - 1] == after[endAfter - 1]) {
        endBefore--
        endAfter--
    }
    val changedBefore = before.subList(start, endBefore)
    val changedAfter = after.subList(start, endAfter)
    // The LCS table is (m+1)x(n+1) ints: a big state (a plan with its whole library, copied several times) turned a log line into
    // hundreds of MB and the process died of it. Past this many cells the diff stops being exact about order.
    return if (changedBefore.size.toLong() * changedAfter.size <= MAX_LCS_CELLS) {
        lcsDiff(changedBefore, changedAfter)
    } else {
        multisetDiff(changedBefore, changedAfter)
    }
}

private const val MAX_LCS_CELLS = 2_000_000L

/** Linear memory: the lines each side has more of than the other, in their own order. Not minimal about moved lines. */
private fun multisetDiff(before: List<String>, after: List<String>): List<String> {
    val available = HashMap<String, Int>()
    after.forEach { available.merge(it, 1, Int::plus) }
    val result = mutableListOf<String>()
    val matched = HashMap<String, Int>()
    for (line in before) {
        val left = available[line] ?: 0
        if (left > 0) {
            available[line] = left - 1
            matched.merge(line, 1, Int::plus)
        } else {
            result += "- $line"
        }
    }
    for (line in after) {
        val usedUp = matched[line] ?: 0
        if (usedUp > 0) matched[line] = usedUp - 1 else result += "+ $line"
    }
    return result
}

private fun lcsDiff(before: List<String>, after: List<String>): List<String> {
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
