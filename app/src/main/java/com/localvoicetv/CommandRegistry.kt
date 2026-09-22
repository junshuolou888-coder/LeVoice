package com.localvoicetv

import android.util.Log
import java.util.Locale

/**
 * Compiles JSON rules once per configuration, then ranks all matching commands.
 * Repetition tolerance belongs in each command's grammar, never in normalization.
 */
class CommandRegistry(config: CommandConfig) {
    companion object {
        private const val TAG = "CommandRegistry"
        private val PUNCTUATION = Regex("[\\s，。！？、,.!?]")
        private val CANDIDATE_ORDER = compareByDescending<CommandMatchResult> { it.score }
            .thenByDescending { it.matchedText.length }
            .thenBy { it.matchType.ordinal }
            .thenBy { it.entry.id }

        /** Keep repetition intact: names and titles can legitimately contain it. */
        fun normalize(rawText: String): String =
            rawText.lowercase(Locale.ROOT).replace(PUNCTUATION, "")
    }

    private data class CompiledEntry(
        val entry: CommandEntry,
        val keywords: List<String>,
        val suffix: String?,
        val regex: Regex?,
        val exclusions: List<String>,
    )

    private data class State(val config: CommandConfig, val entries: List<CompiledEntry>)

    @Volatile
    private var state = compile(config)

    fun buildHotwords(): String =
        state.config.commands.flatMap { it.hotwords }.joinToString("\n")

    fun hotwordsScore(): Float = state.config.hotwordsScore

    fun allDisplayNames(): List<String> = state.config.commands.map { it.displayName }

    /** Compile first, so a failed reload leaves the active snapshot intact. */
    fun reload(newConfig: CommandConfig) {
        state = compile(newConfig)
    }

    fun match(recognizedText: String): CommandMatchResult? {
        val candidates = rankCandidates(recognizedText)
        val best = candidates.firstOrNull()
        if (best == null) {
            Log.i(TAG, "No match for text=\"${normalize(recognizedText)}\"")
        } else {
            Log.i(TAG, "Best match: [${best.entry.id}] score=${best.score} " +
                "type=${best.matchType} vars=${best.variables}; candidates=" +
                candidates.take(3).joinToString { "${it.entry.id}:${it.score}/${it.matchType}" })
            val tied = candidates.takeWhile {
                it.score == best.score && it.matchedText.length == best.matchedText.length &&
                    it.matchType == best.matchType
            }
            if (tied.size > 1) {
                Log.w(TAG, "Ambiguous rules: ${tied.map { it.entry.id }}; " +
                    "selected by id. Set JSON priority or narrow the rules.")
            }
        }
        return best
    }

    /** Best candidate per command, with evidence for debugging overlapping JSON rules. */
    fun rankCandidates(recognizedText: String): List<CommandMatchResult> {
        val text = normalize(recognizedText)
        if (text.isBlank()) return emptyList()
        return state.entries.mapNotNull { scoreEntry(text, it) }.sortedWith(CANDIDATE_ORDER)
    }

    private fun compile(config: CommandConfig): State {
        config.validate()
        return State(config, config.commands.map { entry ->
            val regex = entry.regex?.takeIf { it.isNotBlank() }?.let { pattern ->
                try {
                    Regex(pattern)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Invalid regex in [${entry.id}], regex rule disabled", e)
                    null
                }
            }
            CompiledEntry(
                entry = entry,
                keywords = entry.keywords.map(::normalize).filter { it.isNotEmpty() }.distinct(),
                suffix = entry.endsWith?.let(::normalize)?.takeIf { it.isNotEmpty() },
                regex = regex,
                exclusions = entry.excludeKeywords.orEmpty().map(::normalize)
                    .filter { it.isNotEmpty() }.distinct(),
            )
        })
    }

    private fun scoreEntry(text: String, rule: CompiledEntry): CommandMatchResult? {
        if (rule.exclusions.any { text.contains(it) }) return null
        var best: CommandMatchResult? = null

        fun consider(type: CommandMatchType, matchedText: String, variables: Map<String, String> = emptyMap()) {
            val coverage = when (type) {
                CommandMatchType.EXACT, CommandMatchType.REGEX_FULL -> 0L
                else -> matchedText.length.toLong() * 100 / text.length
            }
            val candidate = CommandMatchResult(
                entry = rule.entry,
                variables = variables,
                score = type.baseScore + coverage + rule.entry.priority.toLong() * 10,
                matchType = type,
                matchedText = matchedText,
            )
            val previous = best
            if (previous == null || CANDIDATE_ORDER.compare(candidate, previous) < 0) best = candidate
        }

        rule.keywords.forEach { keyword ->
            when {
                text == keyword -> consider(CommandMatchType.EXACT, keyword)
                text.contains(keyword) -> consider(CommandMatchType.CONTAINS, keyword)
            }
        }
        rule.suffix?.let { suffix ->
            if (text.endsWith(suffix)) consider(CommandMatchType.SUFFIX, suffix)
        }
        rule.regex?.let { regex ->
            val fullMatch = regex.matchEntire(text)
            val match = fullMatch ?: regex.find(text)
            // Empty matches carry no evidence and must not trigger an action.
            if (match != null && match.value.isNotEmpty()) {
                val variables = (1 until match.groupValues.size).associate { i ->
                    "param$i" to match.groupValues[i]
                }
                consider(
                    if (fullMatch != null) CommandMatchType.REGEX_FULL else CommandMatchType.REGEX_PARTIAL,
                    match.value,
                    variables,
                )
            }
        }
        return best
    }
}
