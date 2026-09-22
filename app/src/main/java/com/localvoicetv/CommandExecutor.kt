package com.localvoicetv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log

/**
 * Universal action dispatcher — executes any [CommandAction] purely
 * based on its JSON-declared fields.  Adding a new jump target
 * should NEVER require touching this file; just edit the JSON.
 *
 * Supported action types:
 *   "activity" / "intent" → startActivity
 *   "broadcast"           → sendBroadcast
 *   "service"             → startService
 *   "uri"                 → ACTION_VIEW with Uri
 *   "builtin"             → hard-coded (volume, home)
 */
class CommandExecutor(private val context: Context) {

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
        )
    }

    /**
     * Execute the given action with optional variable injection.
     */
    fun execute(action: CommandAction, variables: Map<String, String> = emptyMap()) {
        fun String.injectVars(): String {
            var res = this
            variables.forEach { (k, v) ->
                res = res.replace("$$k", v)
            }
            return res
        }

        val inject: (String) -> String = { it.injectVars() }

        when (action.type) {
            "activity", "intent" -> {
                val intent = buildIntent(action, inject)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.i(TAG, "startActivity: $intent")
                context.startActivity(intent)
            }

            "broadcast" -> {
                val intent = buildIntent(action, inject)
                Log.i(TAG, "sendBroadcast: $intent")
                context.sendBroadcast(intent)
            }

            "service" -> {
                val intent = buildIntent(action, inject)
                Log.i(TAG, "startService: $intent")
                context.startService(intent)
            }

            "uri" -> {
                val rawUri = requireNotNull(action.uri) {
                    "type=uri requires 'uri' field"
                }.injectVars()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUri))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Log.i(TAG, "startActivity (uri): $intent")
                context.startActivity(intent)
            }

            "builtin" -> {
                val name = requireNotNull(action.builtinAction) {
                    "type=builtin requires 'builtinAction' field"
                }
                executeBuiltin(name)
            }

            else -> throw IllegalArgumentException("Unknown action type: ${action.type}")
        }
    }

    // ────────────────── Intent builder ──────────────────

    /**
     * Build an [Intent] from the JSON-declared fields on [action].
     * All String values are processed through [inject] for $param variable replacement.
     */
    private fun buildIntent(
        action: CommandAction,
        inject: (String) -> String,
    ): Intent {
        val intent = Intent()

        // action
        action.intentAction?.let { intent.action = inject(it) }

        // data & type (must use setDataAndType when both are present)
        val data = action.intentData?.let { Uri.parse(inject(it)) }
        val type = action.intentType?.let { inject(it) }
        when {
            data != null && type != null -> intent.setDataAndType(data, type)
            data != null -> intent.data = data
            type != null -> intent.type = type
        }

        // explicit component
        if (action.intentPackage != null && action.intentClass != null) {
            intent.setClassName(inject(action.intentPackage), inject(action.intentClass))
        }

        // categories
        action.intentCategories?.forEach { intent.addCategory(inject(it)) }

        // flags (from JSON string names)
        action.intentFlags?.forEach { flagName ->
            val flag = FLAG_MAP[flagName]
            if (flag != null) {
                intent.addFlags(flag)
            } else {
                Log.w(TAG, "Unknown intent flag: $flagName")
            }
        }

        // extras — type-aware put
        action.intentExtras?.forEach { (key, value) ->
            val finalVal = if (value is String) inject(value) else value
            when (finalVal) {
                is String  -> intent.putExtra(key, finalVal)
                is Int     -> intent.putExtra(key, finalVal)
                is Boolean -> intent.putExtra(key, finalVal)
                is Double  -> intent.putExtra(key, finalVal)
                is Float   -> intent.putExtra(key, finalVal)
                is Long    -> intent.putExtra(key, finalVal)
            }
        }

        return intent
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
