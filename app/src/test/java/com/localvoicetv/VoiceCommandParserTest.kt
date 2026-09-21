package com.localvoicetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun parsesSettingsCommandsBeforeGenericSettings() {
        assertEquals(
            VoiceCommand.OPEN_NETWORK_SETTINGS,
            VoiceCommandParser.parse("请打开网络设置"),
        )
        assertEquals(
            VoiceCommand.OPEN_BLUETOOTH_SETTINGS,
            VoiceCommandParser.parse("打开蓝牙设置"),
        )
        assertEquals(
            VoiceCommand.OPEN_SETTINGS,
            VoiceCommandParser.parse("打开系统设置"),
        )
        assertEquals(
            VoiceCommand.OPEN_SETTINGS,
            VoiceCommandParser.parse("大开设置"),
        )
        assertEquals(
            VoiceCommand.OPEN_NETWORK_SETTINGS,
            VoiceCommandParser.parse("打开网路设置"),
        )
    }

    @Test
    fun ignoresPunctuationAndSpaces() {
        assertEquals(
            VoiceCommand.VOLUME_UP,
            VoiceCommandParser.parse("请把 音量调大 一点！"),
        )
        assertEquals(
            VoiceCommand.GO_HOME,
            VoiceCommandParser.parse("返回，主页。"),
        )
    }

    @Test
    fun returnsNullForUnsupportedCommand() {
        assertNull(VoiceCommandParser.parse("今天天气怎么样"))
        assertNull(VoiceCommandParser.parse("设置一个闹钟"))
        assertNull(VoiceCommandParser.parse("   "))
    }
}
