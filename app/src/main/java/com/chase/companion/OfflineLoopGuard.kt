package com.chase.companion

import java.util.Locale

// Deliberately narrow English heuristic. It detects repetition, not truth.
internal object OfflineLoopGuard {
    private fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
    fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.size < 6 || y.size < 6) return false
        if (x == y) return true
        if (minOf(x.size, y.size).toDouble() / maxOf(x.size, y.size) < 0.75) return false
        val xs = x.windowed(3).map { it.joinToString(" ") }.toSet()
        val ys = y.windowed(3).map { it.joinToString(" ") }.toSet()
        return xs.intersect(ys).size.toDouble() / xs.union(ys).size.coerceAtLeast(1) >= 0.72
    }
    private fun intentionalRepeat(text: String): Boolean {
        val t = words(text).joinToString(" ")
        return Regex("^(please )?(repeat|restate|quote|say again)\\b").containsMatchIn(t) || Regex("\\b(verbatim|word for word)\\b").containsMatchIn(t)
    }
    private fun challenge(text: String): Boolean {
        val t = words(text).joinToString(" ")
        return Regex("\\b(wrong|incorrect|mistake|missing|omitted|loop|looping|repeating|correction|corrected)\\b|\\b(left out|leave out|leave them out|leave the|not included|should be included|includes even|includes them|counting includes|you said|i said)\\b").containsMatchIn(t)
    }
    fun shouldRecover(input: String, answer: String, turns: List<Pair<String, String>>): Boolean {
        if (intentionalRepeat(input) || !challenge(input)) return false
        return turns.filter { it.first == "assistant" }.takeLast(3).any { similar(answer, it.second) }
    }
}
