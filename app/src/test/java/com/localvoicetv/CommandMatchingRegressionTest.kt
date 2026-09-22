package com.localvoicetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

class CommandMatchingRegressionTest {
    private fun defaultConfig(): CommandConfig = CommandConfigLoader.parseJson(
        requireNotNull(javaClass.getResourceAsStream("/default_commands.json"))
            .bufferedReader().use { it.readText() },
    )

    private fun assertMatch(registry: CommandRegistry, text: String, id: String, param: String? = null) {
        val match = registry.match(text)
        assertNotNull(text, match)
        assertEquals(text, id, match!!.entry.id)
        if (param != null) assertEquals(text, param, match.variables["param1"])
    }

    @Test
    fun actorSuffixCanRepeatWithoutRewritingTheActor() {
        val registry = CommandRegistry(defaultConfig())
        for (suffix in listOf("电影", "影片", "电视剧", "综艺", "视频", "戏", "剧")) {
            for (count in 1..4) {
                assertMatch(registry, "我想看刘德华的" + suffix.repeat(count), "search_actor_movies", "刘德华")
            }
        }
        assertMatch(registry, "我想看 哈哈哈哈 的 电影电影。", "search_actor_movies", "哈哈哈哈")
    }

    @Test
    fun searchPreservesRepeatedTextAndTitlePrefixes() {
        val registry = CommandRegistry(defaultConfig())
        for (title in listOf("哈哈哈哈", "好好先生", "天天向上", "电影电影", "电影人生", "爸爸爸爸", "刘德华刘德华")) {
            assertMatch(registry, "搜索$title", "search_general", title)
        }
    }

    @Test
    fun literalSpecificityAndStructuredSearchSurviveCommandReordering() {
        val config = defaultConfig()
        val permutations = listOf(config.commands, config.commands.reversed()) +
            (0..9).map { config.commands.shuffled(Random(it)) }
        for (commands in permutations) {
            val registry = CommandRegistry(config.copy(commands = commands))
            assertMatch(registry, "我想看刘德华的电影电影", "search_actor_movies", "刘德华")
            assertMatch(registry, "打开刘德华的明星详情", "pano_star_detail")
            assertMatch(registry, "打开电影桌面", "open_desktop_movie")
            assertMatch(registry, "打开应用", "open_desktop_app")
            assertMatch(registry, "请打开网络设置", "open_network_settings")
            assertMatch(registry, "打开蓝牙设置", "open_bluetooth_settings")
            assertMatch(registry, "某某设置", "open_settings")
        }
    }

    @Test
    fun everyDeclaredKeywordStillTriggersItsOwnCommand() {
        val config = defaultConfig()
        val registry = CommandRegistry(config)
        for (entry in config.commands) {
            for (keyword in entry.keywords) assertMatch(registry, keyword, entry.id)
        }
    }
}
