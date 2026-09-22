package com.localvoicetv

/**
 * Data model for voice command configuration.
 * Maps directly to the JSON schema in default_commands.json.
 */
data class CommandConfig(
    val version: Int = 1,
    val hotwordsScore: Float = 8.0f,
    val commands: List<CommandEntry> = emptyList(),
) {
    internal fun validate() {
        require(commands.all { it.id.isNotBlank() }) { "Command id must not be blank" }
        require(commands.map { it.id }.distinct().size == commands.size) { "Command ids must be unique" }
        commands.forEach { entry ->
            try {
                entry.action.validate()
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("指令 ${entry.id} 的 action 配置错误：${e.message}", e)
            }
        }
    }
}

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

    // ── Intent construction (shared by activity / uri / broadcast / service) ──
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

    // "normal" uses startService; "foreground" uses startForegroundService on API 26+.
    val serviceMode: String = "normal",
    // Named business parameters, expanded using the same placeholders as Intent fields.
    val builtinParams: Map<String, String> = emptyMap(),
) {
    internal fun validate() {
        require(type in setOf("activity", "intent", "broadcast", "service", "uri", "builtin")) {
            "不支持的 action.type：$type"
        }
        require(intentClass == null || !intentPackage.isNullOrBlank()) { "intentClass 必须同时提供 intentPackage" }
        require(serviceMode in setOf("normal", "foreground")) { "serviceMode 必须是 normal 或 foreground" }
        require(serviceMode == "normal" || type == "service") { "serviceMode 只能用于 service" }
        require(builtinParams.isEmpty() || type == "builtin") { "builtinParams 只能用于 builtin" }
        if (type == "builtin") {
            require(!builtinAction.isNullOrBlank()) { "builtin 必须提供 builtinAction" }
            return
        }
        if (type == "uri") {
            require(!uri.isNullOrBlank()) { "uri 跳转必须提供 uri 字段" }
            require(intentData == null || intentData == uri) { "uri 和 intentData 不能指向不同地址" }
        } else {
            require(uri == null) { "uri 字段只用于 type=uri，其他类型请使用 intentData" }
            require(!intentAction.isNullOrBlank() || !intentClass.isNullOrBlank() || !intentData.isNullOrBlank()) {
                "跳转必须提供 intentAction、intentClass 或 intentData；仅有包名不足以指定页面"
            }
        }
        if (type == "service") {
            require(!intentPackage.isNullOrBlank() && !intentClass.isNullOrBlank()) {
                "service 必须通过 intentPackage 和 intentClass 指定目标组件"
            }
        }
    }
}

data class CommandMatchResult(
    val entry: CommandEntry,
    val variables: Map<String, String> = emptyMap(),
    val score: Long = 0,
    val matchType: CommandMatchType = CommandMatchType.EXACT,
    val matchedText: String = "",
)

/** Declaration order breaks ties between equally specific matching methods. */
enum class CommandMatchType(val baseScore: Int) {
    EXACT(1000),
    REGEX_FULL(800),
    REGEX_PARTIAL(600),
    CONTAINS(400),
    SUFFIX(400),
}
