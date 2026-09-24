package com.localvoicetv

/** Presentation only: a malformed template must never turn a dispatched action into an error. */
object CommandFeedback {
    fun success(match: CommandMatchResult): String = try {
        match.entry.feedback?.takeIf { it.isNotBlank() }?.let {
            CommandParameters(match.variables).text(it)
        } ?: "好的，这就为您处理"
    } catch (_: IllegalArgumentException) { "好的，这就为您处理" }
}
