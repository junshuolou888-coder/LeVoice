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
    fun match(rawText: String): CommandEntry? {
        val text = normalize(rawText)
        if (text.isBlank()) return null

        for (entry in config.commands) {
            if (matchesEntry(text, entry)) return entry
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

    // -- internals --

    private fun matchesEntry(text: String, entry: CommandEntry): Boolean {
        // Check keywords (contains_any)
        if (entry.keywords.any { text.contains(it) }) {
            return true
        }
        // Check endsWith fallback
        val suffix = entry.endsWith
        if (suffix != null && text.endsWith(suffix)) {
            return true
        }
        return false
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
