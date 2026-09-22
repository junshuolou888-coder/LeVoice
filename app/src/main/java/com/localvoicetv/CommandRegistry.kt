package com.localvoicetv

/**
 * Holds the active command configuration and provides:
 * - Hotwords string generation for sherpa-onnx
 * - Text-to-command matching with priority ordering
 * - Display name list for the UI
 */
class CommandRegistry(private var config: CommandConfig) {

    /** Generate the hotwords string to pass to sherpa-onnx createStream(). */
    fun buildHotwords(): String =
        config.commands
            .flatMap { it.hotwords }
            .joinToString("\n")

    /** Get the configured hotwords score. */
    fun hotwordsScore(): Float = config.hotwordsScore

    /**
     * Match recognized text against commands in priority order.
     * Returns the first matching [CommandEntry], or null if none match.
     */
    fun match(recognizedText: String): CommandMatchResult? {
        val text = normalize(recognizedText)
        
        // Pass 1: Exact match (highest priority)
        for (entry in config.commands) {
            if (entry.keywords.any { it == text }) {
                return CommandMatchResult(entry, emptyMap())
            }
        }

        // Pass 2: Regex match
        for (entry in config.commands) {
            if (entry.regex != null) {
                val regex = Regex(entry.regex)
                val match = regex.find(text)
                if (match != null) {
                    val vars = mutableMapOf<String, String>()
                    if (match.groupValues.size > 1) {
                        vars["param1"] = match.groupValues[1]
                    }
                    return CommandMatchResult(entry, vars)
                }
            }
        }

        // Pass 3: EndsWith match
        for (entry in config.commands) {
            if (entry.endsWith != null && text.endsWith(entry.endsWith)) {
                return CommandMatchResult(entry, emptyMap())
            }
        }

        // Pass 4: Contains match (lowest priority fallback)
        for (entry in config.commands) {
            if (entry.keywords.any { text.contains(it) }) {
                return CommandMatchResult(entry, emptyMap())
            }
        }
        
        return null
    }

    /** Return display names of all commands, for the "你可以这样说" panel. */
    fun allDisplayNames(): List<String> =
        config.commands.map { it.displayName }

    /** Replace the current config (for future hot-reload). */
    fun reload(newConfig: CommandConfig) {
        config = newConfig
    }

    companion object {
        /**
         * Normalize raw recognized text:
         * lowercase, strip whitespace and Chinese/English punctuation.
         */
        fun normalize(rawText: String): String =
            rawText
                .lowercase()
                .replace(Regex("[\\s，。！？、,.!?]"), "")
    }
}
