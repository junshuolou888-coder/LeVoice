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
    val keywords: List<String>,
    val endsWith: String? = null,
    val hotwords: List<String> = emptyList(),
    val action: CommandAction,
)

data class CommandAction(
    val type: String,
    val intentAction: String? = null,
    val intentPackage: String? = null,
    val intentClass: String? = null,
    val intentExtras: Map<String, String>? = null,
    val builtinAction: String? = null,
)
