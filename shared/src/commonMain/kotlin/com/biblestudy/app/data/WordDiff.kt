package com.biblestudy.app.data

/**
 * Which words of one translation differ from another's wording of the same verse (SPLIT-5):
 * words are compared ignoring case and punctuation, and those not in the longest common run
 * of words are marked.
 */
object WordDiff {
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}’']+")

    private fun norm(w: String) = w.lowercase().replace('’', '\'').trim('\'')

    /** Character ranges in [text] whose words aren't in [other], with neighbouring ones joined. */
    fun changed(text: String, other: String): List<IntRange> {
        val a = WORD.findAll(text).toList()
        val b = WORD.findAll(other).map { norm(it.value) }.toList()
        if (a.isEmpty()) return emptyList()
        if (b.isEmpty()) return listOf(a.first().range.first..a.last().range.last)
        val an = a.map { norm(it.value) }
        // Longest common subsequence of words, then walk back to find the shared ones.
        val n = an.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (an[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val same = BooleanArray(n)
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                an[i] == b[j] -> { same[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        val out = ArrayList<IntRange>()
        for (k in 0 until n) {
            if (same[k]) continue
            val r = a[k].range
            val last = out.lastOrNull()
            if (last != null && k > 0 && !same[k - 1] && text.substring(last.last + 1, r.first).isBlank()) out[out.lastIndex] = last.first..r.last
            else out += r
        }
        return out
    }
}
