package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test

class WeatherCommandTest {
    private val config = CommandConfigLoader.parseJson(requireNotNull(javaClass.getResourceAsStream("/default_commands.json"))
        .bufferedReader().use { it.readText() })

    @Test fun cityAndDateAreExtractedWithoutRewritingTheUtterance() {
        val cases = listOf(
            Triple("北京天气", "北京", ""), Triple("查一下北京的天气怎么样", "北京", ""),
            Triple("请帮我查询一下上海明天天气如何", "上海", "明天"), Triple("明天的北京天气", "北京", "明天"),
            Triple("今天天气怎么样", "", "今天"), Triple("天气", "", ""),
            Triple("后天天气天气", "", "后天"), Triple("北京的今天天气", "北京", "今天"),
            Triple("本地天气", "本地", ""), Triple("搜索深圳天气", "深圳", ""),
            Triple("当前北京天气", "北京", "当前"), Triple("北京天气预报", "北京", "today"),
            Triple("查询天气预报", "", "today"), Triple("后天北京天气预报", "北京", "后天"),
            Triple("看一下 今天的天气", "", "今天"), Triple("看一下今天的天气。", "", "今天"),
            Triple("看下天气", "", ""), Triple("请帮我看一下明天的天气", "", "明天"),
            Triple("查看一下后天天气", "", "后天"), Triple("查一查今天天气", "", "今天"),
            Triple("看一下上海今天的天气", "上海", "今天"), Triple("看一下今天北京的天气", "北京", "今天"),
            Triple("看一下今天的天气预报", "", "今天"), Triple("看下北京天气预报", "北京", "today"),
        )
        for (entries in listOf(config.commands, config.commands.reversed())) {
            val registry = CommandRegistry(config.copy(commands = entries))
            for ((text, city, period) in cases) {
                val match = requireNotNull(registry.match(text)) { text }
                assertEquals(text, "check_weather", match.entry.action.builtinAction)
                val params = CommandParameters(match.variables)
                val values = match.entry.action.builtinParams.mapValues { params.text(it.value) }
                assertEquals(text, city, values["city"])
                assertEquals(text, period, values["period"].orEmpty().ifEmpty { values["defaultPeriod"].orEmpty() })
            }
        }
    }

    @Test fun movieTitlesAndCommandsStillUseTheirExistingActions() {
        val registry = CommandRegistry(config)
        for ((text, id) in listOf("搜索天气之子" to "search_general", "我想看北京天气" to "search_general",
            "我想看刘德华的电影电影" to "search_actor_movies", "打开网络设置" to "open_network_settings")) {
            assertEquals(text, id, registry.match(text)!!.entry.id)
        }
        for (text in listOf("不要查北京天气", "打开天气", "关闭天气", "播放天气", "看一下天气之子", "查看一下天气之子")) {
            assertNotEquals(text, "check_weather", registry.match(text)?.entry?.action?.builtinAction)
        }
    }

    @Test fun unsupportedDateIsRejectedByTheWeatherBusinessLayer() {
        assertThrows(WeatherException::class.java) { WeatherPeriod.parse("下周") }
    }
}
