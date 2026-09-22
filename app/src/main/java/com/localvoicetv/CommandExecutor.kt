package com.localvoicetv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Universal action dispatcher — executes any [CommandAction] purely
 * based on its JSON-declared fields. Targets using these supported protocols
 * can be configured without adding target-specific Kotlin branches.
 *
 * Supported action types:
 *   "activity" / "intent" → startActivity
 *   "broadcast"           → sendBroadcast
 *   "service"             → startService
 *   "uri"                 → ACTION_VIEW with Uri
 *   "builtin"             → hard-coded (volume, home)
 */
enum class CommandExecution { COMPLETED, PENDING }

class CommandExecutor(
    private val context: Context,
    private val builtinHandlers: Map<String, (Map<String, String>) -> CommandExecution> = emptyMap(),
) {

    companion object {
        private const val TAG = "CommandExecutor"

        /** Maps human-readable flag names from JSON to Intent.FLAG_* constants. */
        private val FLAG_MAP = mapOf(
            "FLAG_ACTIVITY_NEW_TASK" to Intent.FLAG_ACTIVITY_NEW_TASK,
            "FLAG_ACTIVITY_CLEAR_TOP" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
            "FLAG_ACTIVITY_SINGLE_TOP" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
            "FLAG_ACTIVITY_CLEAR_TASK" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
            "FLAG_ACTIVITY_NO_HISTORY" to Intent.FLAG_ACTIVITY_NO_HISTORY,
            "FLAG_ACTIVITY_NO_ANIMATION" to Intent.FLAG_ACTIVITY_NO_ANIMATION,
            "FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS" to Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            "FLAG_ACTIVITY_REORDER_TO_FRONT" to Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            "FLAG_INCLUDE_STOPPED_PACKAGES" to Intent.FLAG_INCLUDE_STOPPED_PACKAGES,
            "FLAG_RECEIVER_FOREGROUND" to Intent.FLAG_RECEIVER_FOREGROUND,
            "FLAG_GRANT_READ_URI_PERMISSION" to Intent.FLAG_GRANT_READ_URI_PERMISSION,
            "FLAG_GRANT_WRITE_URI_PERMISSION" to Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            "FLAG_GRANT_PERSISTABLE_URI_PERMISSION" to Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            "FLAG_GRANT_PREFIX_URI_PERMISSION" to Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }

    /**
     * Execute the given action with optional variable injection.
     */
    fun execute(action: CommandAction, variables: Map<String, String> = emptyMap()): CommandExecution {
        action.validate()
        val parameters = CommandParameters(variables)
        try {
            if (action.type == "builtin") {
                val name = parameters.text(requireNotNull(action.builtinAction))
                val params = action.builtinParams.mapValues { parameters.text(it.value) }
                builtinHandlers[name]?.let { return it(params) }
                executeBuiltin(name)
                return CommandExecution.COMPLETED
            }
            val intent = buildIntent(action, parameters)
            when (action.type) {
                "activity", "intent", "uri" -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Log.i(TAG, "startActivity: $intent")
                    context.startActivity(intent)
                }
                "broadcast" -> {
                    Log.i(TAG, "sendBroadcast: $intent")
                    context.sendBroadcast(intent)
                }
                "service" -> {
                    Log.i(TAG, "startService (${action.serviceMode}): $intent")
                    val component = if (action.serviceMode == "foreground" && Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    check(component != null) { "未找到目标 Service：${intent.component}" }
                }
            }
            return CommandExecution.COMPLETED
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("未找到可处理跳转的应用或页面，请检查应用安装状态和 JSON 目标", e)
        } catch (e: SecurityException) {
            throw SecurityException("目标应用拒绝此次调用，请检查组件是否开放及所需权限", e)
        }
    }

    // ────────────────── Intent builder ──────────────────

    /**
     * Build an [Intent] from the JSON-declared fields on [action].
     * Activity, URI, broadcast and service share this path, including typed extras.
     */
    private fun buildIntent(
        action: CommandAction,
        parameters: CommandParameters,
    ): Intent {
        val intent = Intent()
        fun inject(value: String): String = parameters.text(value)
        fun nonBlank(value: String, field: String): String = inject(value).also {
            require(it.isNotBlank()) { "$field 替换后不能为空" }
        }

        // action
        action.intentAction?.let { intent.action = nonBlank(it, "intentAction") }

        // data & type (must use setDataAndType when both are present)
        val dataTemplate = if (action.type == "uri") action.uri else action.intentData
        val data = dataTemplate?.let { Uri.parse(nonBlank(it, "uri/intentData")) }
        if (action.type == "uri") {
            require(!data?.scheme.isNullOrBlank()) { "uri 必须包含协议，例如 https:// 或 example://" }
        }
        if (intent.action == null && data != null && action.type in setOf("activity", "intent", "uri")) {
            intent.action = Intent.ACTION_VIEW
        }
        val type = action.intentType?.let { nonBlank(it, "intentType") }
        when {
            data != null && type != null -> intent.setDataAndType(data, type)
            data != null -> intent.data = data
            type != null -> intent.type = type
        }

        // explicit component
        val packageName = action.intentPackage?.let { nonBlank(it, "intentPackage") }
        if (action.intentClass != null) {
            val targetPackage = requireNotNull(packageName) { "intentClass 必须提供 intentPackage" }
            val className = nonBlank(action.intentClass, "intentClass")
            intent.setClassName(targetPackage, if (className.startsWith(".")) targetPackage + className else className)
        } else if (packageName != null) {
            intent.setPackage(packageName)
        }

        // categories
        action.intentCategories?.forEach { intent.addCategory(nonBlank(it, "intentCategories")) }

        // flags (from JSON string names)
        action.intentFlags?.forEach { flagName ->
            intent.addFlags(parseFlag(inject(flagName)))
        }

        // extras — type-aware put
        action.intentExtras?.forEach { (key, value) ->
            val finalVal = try {
                parameters.extra(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("intentExtras[$key]：${e.message}", e)
            }
            when (finalVal) {
                is String  -> intent.putExtra(key, finalVal)
                is Int     -> intent.putExtra(key, finalVal)
                is Boolean -> intent.putExtra(key, finalVal)
                is Double  -> intent.putExtra(key, finalVal)
                is Float   -> intent.putExtra(key, finalVal)
                is Long    -> intent.putExtra(key, finalVal)
                is IntArray -> intent.putExtra(key, finalVal)
                is LongArray -> intent.putExtra(key, finalVal)
                is BooleanArray -> intent.putExtra(key, finalVal)
                is Array<*> -> intent.putExtra(key, Array(finalVal.size) { finalVal[it] as String })
                else -> throw IllegalArgumentException("intentExtras[$key] 的类型不受支持")
            }
        }

        return intent
    }

    private fun parseFlag(value: String): Int {
        FLAG_MAP[value]?.let { return it }
        val numeric = try {
            if (value.startsWith("0x", ignoreCase = true)) value.substring(2).toLong(16) else value.toLong()
        } catch (_: NumberFormatException) {
            null
        }
        require(numeric != null && numeric in Int.MIN_VALUE.toLong()..0xffffffffL) {
            "不支持的 intentFlags：$value，请使用受支持名称或 32 位十进制/十六进制数值"
        }
        return numeric.toInt()
    }

    // ────────────────── Builtins ──────────────────

    private fun executeBuiltin(name: String) {
        when (name) {
            "volume_up"   -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "toggle_mute" -> adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            "go_home" -> {
                val homeIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(homeIntent)
            }
            else -> throw IllegalArgumentException("Unknown builtin action: $name")
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
    }
}
