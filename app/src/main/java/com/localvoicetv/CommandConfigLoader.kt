package com.localvoicetv

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Loads [CommandConfig] with a priority chain:
 * 1. External storage: /sdcard/levoice/commands.json  (ADB push)
 * 2. App-private dir:  context.filesDir/commands.json (HTTP cache, future)
 * 3. APK assets:       assets/default_commands.json   (built-in default)
 */
object CommandConfigLoader {

    private const val TAG = "CmdConfigLoader"
    private const val EXTERNAL_DIR = "levoice"
    private const val CONFIG_FILE = "commands.json"
    private const val ASSET_FILE = "default_commands.json"

    fun load(context: Context): CommandConfig {
        // Priority 1: external storage
        try {
            val externalFile = File(
                Environment.getExternalStorageDirectory(),
                "$EXTERNAL_DIR/$CONFIG_FILE",
            )
            if (externalFile.isFile) {
                val config = parseJson(externalFile.readText())
                Log.i(TAG, "Loaded config from external: ${externalFile.absolutePath} (v${config.version}, ${config.commands.size} commands)")
                return config
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read external config, trying next source", e)
        }

        // Priority 2: app-private directory (for future HTTP download cache)
        try {
            val privateFile = File(context.filesDir, CONFIG_FILE)
            if (privateFile.isFile) {
                val config = parseJson(privateFile.readText())
                Log.i(TAG, "Loaded config from private dir (v${config.version}, ${config.commands.size} commands)")
                return config
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read private config, trying assets", e)
        }

        // Priority 3: APK assets
        try {
            val json = context.assets.open(ASSET_FILE)
                .bufferedReader()
                .use { it.readText() }
            val config = parseJson(json)
            Log.i(TAG, "Loaded config from assets (v${config.version}, ${config.commands.size} commands)")
            return config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read asset config, using hardcoded defaults", e)
        }

        return defaultConfig()
    }

    fun parseJson(json: String): CommandConfig {
        val root = JSONObject(json)

        val version = root.optInt("version", 1)
        val hotwordsScore = root.optDouble("hotwordsScore", 8.0).toFloat()

        val commandsArray = root.getJSONArray("commands")
        val commands = (0 until commandsArray.length()).map { i ->
            val obj = commandsArray.getJSONObject(i)

            val keywordsArray = obj.getJSONArray("keywords")
            val keywords = (0 until keywordsArray.length()).map { j ->
                keywordsArray.getString(j)
            }

            val hotwordsArray = obj.optJSONArray("hotwords")
            val hotwords = if (hotwordsArray != null) {
                (0 until hotwordsArray.length()).map { j -> hotwordsArray.getString(j) }
            } else {
                emptyList()
            }

            val actionObj = obj.getJSONObject("action")
            
            val intentExtrasObj = actionObj.optJSONObject("intentExtras")
            val intentExtras = if (intentExtrasObj != null) {
                val map = mutableMapOf<String, String>()
                val keys = intentExtrasObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = intentExtrasObj.getString(key)
                }
                map
            } else null

            val action = CommandAction(
                type = actionObj.getString("type"),
                intentAction = actionObj.optString("intentAction").ifEmpty { null },
                intentPackage = actionObj.optString("intentPackage").ifEmpty { null },
                intentClass = actionObj.optString("intentClass").ifEmpty { null },
                intentExtras = intentExtras,
                builtinAction = actionObj.optString("builtinAction").ifEmpty { null },
            )

            CommandEntry(
                id = obj.getString("id"),
                displayName = obj.getString("displayName"),
                keywords = keywords,
                endsWith = obj.optString("endsWith").ifEmpty { null },
                hotwords = hotwords,
                action = action,
            )
        }

        return CommandConfig(
            version = version,
            hotwordsScore = hotwordsScore,
            commands = commands,
        )
    }

    /** Hardcoded fallback in case all file sources fail. */
    private fun defaultConfig(): CommandConfig = CommandConfig(
        version = 0,
        hotwordsScore = 8.0f,
        commands = listOf(
            CommandEntry(
                id = "open_settings",
                displayName = "打开系统设置",
                keywords = listOf("打开设置", "系统设置", "进入设置"),
                endsWith = "设置",
                hotwords = listOf("打 开 设 置"),
                action = CommandAction(type = "intent", intentAction = "android.settings.SETTINGS"),
            ),
        ),
    )
}
