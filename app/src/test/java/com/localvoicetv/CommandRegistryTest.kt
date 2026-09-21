package com.localvoicetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CommandRegistryTest {

    private lateinit var registry: CommandRegistry

    @Before
    fun setup() {
        val config = CommandConfig(
            version = 1,
            hotwordsScore = 8.0f,
            commands = listOf(
                CommandEntry(
                    id = "open_network_settings",
                    displayName = "打开网络设置",
                    keywords = listOf("网络设置", "网路设置", "wifi设置", "无线设置", "打开网络", "打开网路", "打开wifi", "打开无线"),
                    hotwords = listOf("打 开 网 络 设 置"),
                    action = CommandAction("intent", intentAction = "android.settings.WIFI_SETTINGS")
                ),
                CommandEntry(
                    id = "open_bluetooth_settings",
                    displayName = "打开蓝牙设置",
                    keywords = listOf("蓝牙"),
                    hotwords = listOf("打 开 蓝 牙 设 置"),
                    action = CommandAction("intent", intentAction = "android.settings.BLUETOOTH_SETTINGS")
                ),
                CommandEntry(
                    id = "toggle_mute",
                    displayName = "切换静音",
                    keywords = listOf("取消静音", "解除静音", "静音", "闭音"),
                    hotwords = listOf("静 音"),
                    action = CommandAction("builtin", builtinAction = "toggle_mute")
                ),
                CommandEntry(
                    id = "volume_up",
                    displayName = "增大音量",
                    keywords = listOf("增大音量", "调大音量", "音量调大", "音量大一点", "提高音量", "调大声音"),
                    hotwords = listOf("增 大 音 量"),
                    action = CommandAction("builtin", builtinAction = "volume_up")
                ),
                CommandEntry(
                    id = "volume_down",
                    displayName = "减小音量",
                    keywords = listOf("减小音量", "调小音量", "音量调小", "音量小一点", "降低音量", "调小声音"),
                    hotwords = listOf("减 小 音 量"),
                    action = CommandAction("builtin", builtinAction = "volume_down")
                ),
                CommandEntry(
                    id = "open_sound_settings",
                    displayName = "打开声音设置",
                    keywords = listOf("声音设置", "音频设置", "打开声音", "打开音频"),
                    hotwords = listOf("打 开 声 音 设 置"),
                    action = CommandAction("intent", intentAction = "android.settings.SOUND_SETTINGS")
                ),
                CommandEntry(
                    id = "open_display_settings",
                    displayName = "打开显示设置",
                    keywords = listOf("显示设置", "屏幕设置", "画面设置", "打开显示", "打开屏幕", "打开画面"),
                    hotwords = listOf("打 开 显 示 设 置"),
                    action = CommandAction("intent", intentAction = "android.settings.DISPLAY_SETTINGS")
                ),
                CommandEntry(
                    id = "open_app_settings",
                    displayName = "打开应用管理",
                    keywords = listOf("应用设置", "应用管理", "打开应用"),
                    hotwords = listOf("应 用 管 理"),
                    action = CommandAction("intent", intentAction = "android.settings.APPLICATION_SETTINGS")
                ),
                CommandEntry(
                    id = "go_home",
                    displayName = "返回主页",
                    keywords = listOf("返回主页", "回到主页", "返回桌面", "回到桌面"),
                    hotwords = listOf("返 回 主 页"),
                    action = CommandAction("builtin", builtinAction = "go_home")
                ),
                CommandEntry(
                    id = "open_settings",
                    displayName = "打开系统设置",
                    keywords = listOf("打开设置", "系统设置", "进入设置"),
                    endsWith = "设置",
                    hotwords = listOf("打 开 设 置"),
                    action = CommandAction("intent", intentAction = "android.settings.SETTINGS")
                )
            )
        )
        registry = CommandRegistry(config)
    }

    @Test
    fun parsesNetworkSettings() {
        assertCommand("open_network_settings", "请打开网络设置")
        assertCommand("open_network_settings", "打开网路设置")
        assertCommand("open_network_settings", "打开wifi设置")
    }

    @Test
    fun parsesBluetoothSettings() {
        assertCommand("open_bluetooth_settings", "打开蓝牙设置")
        assertCommand("open_bluetooth_settings", "蓝牙")
    }

    @Test
    fun parsesSpecificSettingsBeforeGenericSettings() {
        assertCommand("open_sound_settings", "打开声音设置")
        assertCommand("open_display_settings", "打开显示设置")
        assertCommand("open_app_settings", "打开应用管理")
    }

    @Test
    fun parsesGenericSettingsWithEndsWith() {
        assertCommand("open_settings", "打开系统设置")
        assertCommand("open_settings", "进入设置")
        // endsWith catch-all
        assertCommand("open_settings", "某某设置")
    }

    @Test
    fun parsesVolumeCommands() {
        assertCommand("volume_up", "增大音量")
        assertCommand("volume_up", "请把 音量调大 一点！")
        assertCommand("volume_down", "减小音量")
        assertCommand("volume_down", "音量小一点")
    }

    @Test
    fun parsesMuteCommand() {
        assertCommand("toggle_mute", "静音")
        assertCommand("toggle_mute", "取消静音")
    }

    @Test
    fun parsesGoHome() {
        assertCommand("go_home", "返回主页")
        assertCommand("go_home", "返回，主页。")
    }

    @Test
    fun returnsNullForUnsupportedCommand() {
        assertNull(registry.match("今天天气怎么样"))
        assertNull(registry.match("   "))
    }

    @Test
    fun hotwordsContainsAllEntries() {
        val hotwords = registry.buildHotwords()
        assert(hotwords.contains("打 开 设 置"))
        assert(hotwords.contains("打 开 网 络 设 置"))
        assert(hotwords.contains("静 音"))
    }

    @Test
    fun allDisplayNamesReturnsAllCommands() {
        val names = registry.allDisplayNames()
        assert(names.contains("打开网络设置"))
        assert(names.contains("切换静音"))
        assert(names.contains("返回主页"))
    }

    private fun assertCommand(expectedId: String, input: String) {
        val entry = registry.match(input)
        assertNotNull("Expected match for '$input' but got null", entry)
        assertEquals(
            "Input '$input' should match '$expectedId' but matched '${entry!!.id}'",
            expectedId,
            entry.id,
        )
    }
}
