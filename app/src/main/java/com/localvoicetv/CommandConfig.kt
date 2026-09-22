package com.localvoicetv

/**
 * Data model for voice command configuration.
 * Maps directly to the JSON schema in default_commands.json.
 */
data class CommandConfig(
    val version: Int = 1,
    val hotwordsScore: Float = 8.0f,
    val commands: List<CommandEntry> = emptyList(),
)

data class CommandEntry(
    val id: String,
    val displayName: String,
    val keywords: List<String> = emptyList(),
    val endsWith: String? = null,
    val regex: String? = null,
    val hotwords: List<String> = emptyList(),
    val priority: Int = 0,
    val excludeKeywords: List<String>? = null,
    val action: CommandAction,
)

/**
 * Universal action descriptor — fully driven by JSON.
 *
 * Supported types:
 *   "activity"  / "intent"  → context.startActivity(buildIntent())
 *   "broadcast"              → context.sendBroadcast(buildIntent())
 *   "service"                → context.startService(buildIntent())
 *   "uri"                    → ACTION_VIEW + Uri.parse(uri)
 *   "builtin"                → hard-coded actions (volume, home …)
 */
data class CommandAction(
    // ── dispatch type (required) ──
    val type: String,

    // ── Intent construction (shared by activity / broadcast / service) ──
    val intentAction: String? = null,
    val intentData: String? = null,
    val intentType: String? = null,
    val intentPackage: String? = null,
    val intentClass: String? = null,
    val intentCategories: List<String>? = null,
    val intentFlags: List<String>? = null,
    val intentExtras: Map<String, Any>? = null,

    // ── URI jump (type = "uri") ──
    val uri: String? = null,

    // ── builtin (type = "builtin") ──
    val builtinAction: String? = null,
)

data class CommandMatchResult(
    val entry: CommandEntry,
    val variables: Map<String, String> = emptyMap(),
)
