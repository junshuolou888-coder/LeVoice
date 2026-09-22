package com.localvoicetv

import android.util.Log

/**
 * Holds the active command configuration and provides:
 * - Hotwords string generation for sherpa-onnx
 * - Score-based text-to-command matching
 * - Display name list for the UI
 *
 * ## Matching Algorithm (Scored Best-Match)
 *
 * Instead of a simple first-match waterfall, every command is scored
 * and the **highest scoring** candidate wins.
 *
 * Score = matchTypeBase + coverageBonus + priorityBonus
 *
 *   matchTypeBase:
 *     exact   = 1000
 *     regex   =  800
 *     endsWith=  600
 *     contains=  400
 *
 *   coverageBonus (contains only):
 *     keyword.length / text.length * 100
 *     → longer keyword relative to input = higher score
 *
 *   priorityBonus:
 *     entry.priority * 10
 *     → JSON-declared explicit priority adjustment
 */
class CommandRegistry(private var config: CommandConfig) {

    companion object {
        private const val TAG = "CommandRegistry"

        // Match type base scores
        private const val SCORE_EXACT    = 1000
        private const val SCORE_REGEX    =  800
        private const val SCORE_ENDSWITH =  600
        private const val SCORE_CONTAINS =  400

        /**
         * Normalize raw recognized text:
         * lowercase, strip whitespace and Chinese/English punctuation.
         */
        fun normalize(rawText: String): String =
            rawText
                .lowercase()
                .replace(Regex("[\\s，。！？、,.!?]"), "")
    }

    /** Generate the hotwords string to pass to sherpa-onnx createStream(). */
    fun buildHotwords(): String =
        config.commands
            .flatMap { it.hotwords }
            .joinToString("\n")

    /** Get the configured hotwords score. */
    fun hotwordsScore(): Float = config.hotwordsScore

    /**
     * Score-based matching: evaluate ALL commands, pick the highest scorer.
     */
    fun match(recognizedText: String): CommandMatchResult? {
        val text = normalize(recognizedText)
        if (text.isBlank()) return null

        var bestResult: CommandMatchResult? = null
        var bestScore = -1

        for (entry in config.commands) {
            // Exclusion check: skip if text contains any exclude keyword
            if (entry.excludeKeywords?.any { text.contains(it) } == true) {
                continue
            }

            val (score, vars) = scoreEntry(text, entry)
            if (score > bestScore) {
                bestScore = score
                bestResult = CommandMatchResult(entry, vars)
            }
        }

        if (bestResult != null) {
            Log.i(TAG, "Best match: [${bestResult.entry.id}] score=$bestScore vars=${bestResult.variables} for text=\"$text\"")
        } else {
            Log.i(TAG, "No match for text=\"$text\"")
        }

        return bestResult
    }

    /** Return display names of all commands, for the "你可以这样说" panel. */
    fun allDisplayNames(): List<String> =
        config.commands.map { it.displayName }

    /** Replace the current config (for future hot-reload). */
    fun reload(newConfig: CommandConfig) {
        config = newConfig
    }

    // ────────────────── Scoring Engine ──────────────────

    /**
     * Compute the match score for [entry] against [text].
     * Returns (score, variables).  score = -1 means no match.
     */
    private fun scoreEntry(text: String, entry: CommandEntry): Pair<Int, Map<String, String>> {
        val priorityBonus = entry.priority * 10

        // 1. Exact match
        for (kw in entry.keywords) {
            if (kw == text) {
                return (SCORE_EXACT + priorityBonus) to emptyMap()
            }
        }

        // 2. Regex match
        if (entry.regex != null) {
            val regex = Regex(entry.regex)
            val match = regex.find(text)
            if (match != null) {
                val vars = mutableMapOf<String, String>()
                for (i in 1 until match.groupValues.size) {
                    vars["param$i"] = match.groupValues[i]
                }
                return (SCORE_REGEX + priorityBonus) to vars
            }
        }

        // 3. EndsWith match
        if (entry.endsWith != null && text.endsWith(entry.endsWith)) {
            val coverage = (entry.endsWith.length.toFloat() / text.length * 100).toInt()
            return (SCORE_ENDSWITH + coverage + priorityBonus) to emptyMap()
        }

        // 4. Contains match — pick the longest matching keyword for coverage bonus
        var bestContainsScore = -1
        for (kw in entry.keywords) {
            if (text.contains(kw)) {
                val coverage = (kw.length.toFloat() / text.length * 100).toInt()
                val score = SCORE_CONTAINS + coverage + priorityBonus
                if (score > bestContainsScore) {
                    bestContainsScore = score
                }
            }
        }
        if (bestContainsScore > 0) {
            return bestContainsScore to emptyMap()
        }

        return -1 to emptyMap()
    }
}
