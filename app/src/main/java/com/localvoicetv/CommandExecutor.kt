package com.localvoicetv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager

/**
 * Executes a [CommandAction] by dispatching to the appropriate handler:
 * - "intent": launches an Android Intent with the specified action string
 * - "builtin": runs a pre-registered built-in action (volume, home, etc.)
 */
class CommandExecutor(private val context: Context) {

    /**
     * Execute the given action.
     * @throws ActivityNotFoundException if an intent target is not found
     * @throws SecurityException if the intent requires a missing permission
     * @throws IllegalArgumentException if the action type or builtin name is unknown
     */
    fun execute(action: CommandAction) {
        when (action.type) {
            "intent" -> {
                val intentAction = requireNotNull(action.intentAction) {
                    "intent action requires 'intentAction' field"
                }
                context.startActivity(
                    Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

            "builtin" -> {
                val name = requireNotNull(action.builtinAction) {
                    "builtin action requires 'builtinAction' field"
                }
                executeBuiltin(name)
            }

            else -> throw IllegalArgumentException("Unknown action type: ${action.type}")
        }
    }

    private fun executeBuiltin(name: String) {
        when (name) {
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
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
