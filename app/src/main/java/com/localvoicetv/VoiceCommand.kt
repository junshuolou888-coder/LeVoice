package com.localvoicetv

enum class VoiceCommand {
    OPEN_NETWORK_SETTINGS,
    OPEN_BLUETOOTH_SETTINGS,
    OPEN_SOUND_SETTINGS,
    OPEN_DISPLAY_SETTINGS,
    OPEN_APP_SETTINGS,
    OPEN_SETTINGS,
    GO_HOME,
    VOLUME_UP,
    VOLUME_DOWN,
    TOGGLE_MUTE,
}

object VoiceCommandParser {
    fun parse(rawText: String): VoiceCommand? {
        val text = rawText
            .lowercase()
            .replace(Regex("[\\s，。！？、,.!?]"), "")

        if (text.isBlank()) return null

        return when {
            text.containsAny("网络", "网路", "wifi", "无线") &&
                text.containsAny("打开", "设置") ->
                VoiceCommand.OPEN_NETWORK_SETTINGS

            text.contains("蓝牙") ->
                VoiceCommand.OPEN_BLUETOOTH_SETTINGS

            text.containsAny("取消静音", "解除静音", "静音", "闭音") ->
                VoiceCommand.TOGGLE_MUTE

            text.containsAny(
                "增大音量",
                "调大音量",
                "音量调大",
                "音量大一点",
                "提高音量",
                "调大声音",
            ) ->
                VoiceCommand.VOLUME_UP

            text.containsAny(
                "减小音量",
                "调小音量",
                "音量调小",
                "音量小一点",
                "降低音量",
                "调小声音",
            ) ->
                VoiceCommand.VOLUME_DOWN

            text.containsAny("声音设置", "音频设置") ||
                (text.endsWith("设置") && text.containsAny("声音", "音频")) ->
                VoiceCommand.OPEN_SOUND_SETTINGS

            text.containsAny("显示设置", "屏幕设置", "画面设置") ||
                (text.endsWith("设置") && text.containsAny("显示", "屏幕", "画面")) ->
                VoiceCommand.OPEN_DISPLAY_SETTINGS

            text.containsAny("应用设置", "应用管理") ||
                (text.endsWith("设置") && text.contains("应用")) ->
                VoiceCommand.OPEN_APP_SETTINGS

            text.containsAny("返回主页", "回到主页", "返回桌面", "回到桌面") ->
                VoiceCommand.GO_HOME

            text.containsAny("打开设置", "系统设置", "进入设置") ||
                text.endsWith("设置") ->
                VoiceCommand.OPEN_SETTINGS

            else -> null
        }
    }

    private fun String.containsAny(vararg candidates: String): Boolean =
        candidates.any(::contains)
}
