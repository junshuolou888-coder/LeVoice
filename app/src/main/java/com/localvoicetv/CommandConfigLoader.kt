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

            // keywords (optional)
            val keywordsArray = obj.optJSONArray("keywords")
            val keywords = if (keywordsArray != null) {
                (0 until keywordsArray.length()).map { j -> keywordsArray.getString(j) }
            } else {
                emptyList()
            }

            val endsWith = obj.optString("endsWith").ifEmpty { null }
            val regex = obj.optString("regex").ifEmpty { null }

            // hotwords (optional)
            val hotwordsArray = obj.optJSONArray("hotwords")
            val hotwords = if (hotwordsArray != null) {
                (0 until hotwordsArray.length()).map { j -> hotwordsArray.getString(j) }
            } else {
                emptyList()
            }

            val priority = obj.optInt("priority", 0)

            val excludeArray = obj.optJSONArray("excludeKeywords")
            val excludeKeywords = if (excludeArray != null) {
                (0 until excludeArray.length()).map { j -> excludeArray.getString(j) }
            } else null

            val actionObj = obj.getJSONObject("action")

            // intentExtras — preserve native types (Int, Boolean, String, …)
            val intentExtrasObj = if (actionObj.isNull("intentExtras")) null else actionObj.getJSONObject("intentExtras")
            val intentExtras = if (intentExtrasObj != null) {
                val map = mutableMapOf<String, Any>()
                val keys = intentExtrasObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = intentExtrasObj.get(key)
                }
                map
            } else null

            // intentCategories (optional string array)
            val categoriesArray = if (actionObj.isNull("intentCategories")) null else actionObj.getJSONArray("intentCategories")
            val intentCategories = if (categoriesArray != null) {
                (0 until categoriesArray.length()).map { j -> categoriesArray.getString(j) }
            } else null

            // intentFlags (optional string array)
            val flagsArray = if (actionObj.isNull("intentFlags")) null else actionObj.getJSONArray("intentFlags")
            val intentFlags = if (flagsArray != null) {
                (0 until flagsArray.length()).map { j -> flagsArray.getString(j) }
            } else null

            val action = CommandAction(
                type = requireNotNull(actionObj.optionalString("type")) { "action 必须提供 type" },
                intentAction = actionObj.optionalString("intentAction"),
                intentData = actionObj.optionalString("intentData"),
                intentType = actionObj.optionalString("intentType"),
                intentPackage = actionObj.optionalString("intentPackage"),
                intentClass = actionObj.optionalString("intentClass"),
                intentCategories = intentCategories,
                intentFlags = intentFlags,
                intentExtras = intentExtras,
                uri = actionObj.optionalString("uri"),
                builtinAction = actionObj.optionalString("builtinAction"),
                serviceMode = actionObj.optionalString("serviceMode") ?: "normal",
                builtinParams = if (actionObj.isNull("builtinParams")) emptyMap() else {
                    val params = actionObj.getJSONObject("builtinParams")
                    params.keys().asSequence().associateWith { key ->
                        val value = params.get(key)
                        require(value is String) { "builtinParams[$key] 必须是字符串" }
                        value
                    }
                },
            )

            CommandEntry(
                id = obj.getString("id"),
                displayName = obj.getString("displayName"),
                feedback = obj.optionalString("feedback"),
                keywords = keywords,
                endsWith = endsWith,
                regex = regex,
                hotwords = hotwords,
                priority = priority,
                excludeKeywords = excludeKeywords,
                action = action,
            )
        }

        return CommandConfig(
            version = version,
            hotwordsScore = hotwordsScore,
            commands = commands,
        ).also { it.validate() }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (isNull(key)) return null
        val value = get(key)
        require(value is String) { "action.$key 必须是字符串" }
        return value.takeIf { it.isNotBlank() }
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
                action = CommandAction(type = "activity", intentAction = "android.settings.SETTINGS"),
            ),
        ),
    )
}
