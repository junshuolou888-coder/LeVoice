package com.localvoicetv

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.Locale

// Deliberately not a data class: toString must not expose the credential.
class WeatherSettings(
    host: String = DEFAULT_HOST,
    val apiKey: String = "",
    val defaultCity: String = "",
) {
    val host = host.trim().removePrefix("https://").trimEnd('/').lowercase(Locale.ROOT)

    fun validate(requireCredential: Boolean = true) {
        require(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.qweatherapi\\.com").matches(host)) {
            "请填写控制台中的和风天气 API Host，不含路径"
        }
        require(apiKey.none { it.isWhitespace() || it.isISOControl() }) { "API KEY 不能包含空格或换行" }
        require(apiKey.length <= 512) { "API KEY 格式不正确" }
        require(defaultCity.length <= 60) { "默认城市名称过长" }
        if (requireCredential) {
            require(apiKey.isNotEmpty()) { "请先打开「天气设置」填写 API KEY" }
            require(!Regex("Q[A-Za-z0-9]{9}").matches(apiKey)) {
                "这里需要 API KEY，请勿填写开发者 ID 或凭据 ID"
            }
        }
    }

    companion object {
        const val DEFAULT_HOST = "jw6939gar5.re.qweatherapi.com"
    }
}

/** App-private, excluded from Android backups; never stored in the voice command JSON. */
class WeatherSettingsStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "weather.json"))

    fun load(): WeatherSettings {
        if (!file.baseFile.exists()) return WeatherSettings()
        return try {
            val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
            WeatherSettings(json.getString("host"), json.getString("apiKey"), json.optString("defaultCity", ""))
                .also { it.validate(requireCredential = false) }
        } catch (_: Exception) {
            throw IllegalStateException("天气设置文件损坏，请重新填写并保存")
        }
    }

    fun save(settings: WeatherSettings) {
        settings.validate()
        val data = JSONObject().put("host", settings.host).put("apiKey", settings.apiKey)
            .put("defaultCity", settings.defaultCity).toString().toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(data)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}
