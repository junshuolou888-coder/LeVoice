package com.localvoicetv

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CommandRankingTest {
    private fun entry(
        id: String,
        keywords: List<String> = emptyList(),
        regex: String? = null,
        suffix: String? = null,
        priority: Int = 0,
        exclusions: List<String> = emptyList(),
    ) = CommandEntry(
        id = id, displayName = id, keywords = keywords, regex = regex,
        endsWith = suffix, priority = priority, excludeKeywords = exclusions,
        action = CommandAction("builtin", builtinAction = "go_home"),
    )

    private fun registry(vararg entries: CommandEntry) = CommandRegistry(CommandConfig(commands = entries.toList()))

    @Test
    fun candidatesExposeScoresAndCapturedParameters() {
        val registry = registry(
            entry("literal", listOf("电影")),
            entry("partial", regex = "(刘德华)"),
            entry("full", regex = "^我想看(.+)的电影$"),
            entry("exact", listOf("我想看刘德华的电影")),
        )
        val candidates = registry.rankCandidates("我想看刘德华的电影")
        assertEquals(listOf("exact", "full", "partial", "literal"), candidates.map { it.entry.id })
        assertEquals(listOf(1000L, 800L, 633L, 422L), candidates.map { it.score })
        assertEquals("刘德华", candidates[1].variables["param1"])
        assertEquals(CommandMatchType.REGEX_PARTIAL, candidates[2].matchType)
        assertEquals("刘德华", candidates[2].matchedText)
    }

    @Test
    fun longerLiteralWinsEvenIfAnotherRuleMatchesTheSuffix() {
        val registry = registry(
            entry("settings", suffix = "设置"),
            entry("network", listOf("网络设置")),
            entry("bluetooth", listOf("蓝牙")),
        )
        assertEquals("network", registry.match("请打开网络设置")!!.entry.id)
        assertEquals("bluetooth", registry.match("打开蓝牙设置")!!.entry.id)
        assertEquals("settings", registry.match("某某设置")!!.entry.id)
    }

    @Test
    fun evaluatesAllMethodsWithinOneCommand() {
        val registry = registry(entry("network", listOf("网", "网络设置"), suffix = "设置"))
        val best = registry.match("请打开网络设置")!!
        assertEquals("网络设置", best.matchedText)
        assertEquals(CommandMatchType.CONTAINS, best.matchType)
    }

    @Test
    fun fullRegexMatchWinsWhenAnEarlierAlternativeOnlyMatchesPart() {
        val result = registry(entry("alternative", regex = "网|网络设置")).match("网络设置")!!
        assertEquals(CommandMatchType.REGEX_FULL, result.matchType)
        assertEquals("网络设置", result.matchedText)
    }

    @Test
    fun exactTiesUseStableIdsAndPriorityCanResolveThem() {
        val a = entry("a", regex = "^搜索(.+)$")
        val b = entry("b", regex = "^搜索(.+)$")
        assertEquals("a", registry(a, b).match("搜索战狼")!!.entry.id)
        assertEquals("a", registry(b, a).match("搜索战狼")!!.entry.id)
        assertEquals("b", registry(a, b.copy(priority = 1)).match("搜索战狼")!!.entry.id)
    }

    @Test
    fun keywordsAndExclusionsUseTheSameNormalizationAsInput() {
        val registry = registry(entry("wifi", listOf(" WiFi 设置！", "  "), exclusions = listOf("不要，", "  ")))
        assertEquals("wifi", registry.match("WIFI 设置")!!.entry.id)
        assertNull(registry.match("不要打开wifi设置"))
        assertNull(registry.match("不相关"))
    }

    @Test
    fun normalizationPreservesRepetitionAndDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("wifi哈哈哈哈电影电影", CommandRegistry.normalize(" WIFI 哈哈哈哈 电影电影！"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun invalidRegexDoesNotDisableOtherCommandsOrValidKeywords() {
        val registry = registry(
            entry("broken", keywords = listOf("设置"), regex = "["),
            entry("valid", regex = "^搜索(.+)$"),
        )
        assertEquals("broken", registry.match("设置")!!.entry.id)
        assertEquals("valid", registry.match("搜索战狼")!!.entry.id)
    }

    @Test
    fun blankRulesAndZeroWidthRegexesCannotTriggerAnAction() {
        val registry = registry(entry("blank", listOf(" ", "。"), regex = "(?=电影)", suffix = " "))
        assertNull(registry.match("电影"))
        assertNull(registry.match("   ！"))
    }

    @Test
    fun negativeScoresRemainValidMatchesAndPriorityDoesNotOverflow() {
        val low = entry("low", listOf("设置"), priority = Int.MIN_VALUE)
        val high = entry("high", listOf("设置"), priority = Int.MAX_VALUE)
        assertEquals(1000L + Int.MIN_VALUE.toLong() * 10, registry(low).match("设置")!!.score)
        assertEquals("high", registry(low, high).match("设置")!!.entry.id)
        assertNull(registry(high).match("无关文本"))
    }

    @Test
    fun reloadReplacesCompiledRulesAndUiMetadata() {
        val registry = registry(entry("old", regex = "^旧(.+)$"))
        registry.reload(CommandConfig(
            commands = listOf(entry("new", regex = "^新(.+)$").copy(hotwords = listOf("新 词"))),
            hotwordsScore = 4.0f,
        ))
        assertNull(registry.match("旧规则"))
        assertEquals("规则", registry.match("新规则")!!.variables["param1"])
        assertEquals(listOf("new"), registry.allDisplayNames())
        assertEquals("新 词", registry.buildHotwords())
        assertEquals(4.0f, registry.hotwordsScore(), 0.0f)
    }

    @Test
    fun failedReloadPreservesTheActiveConfiguration() {
        val original = entry("old", listOf("设置"))
        val registry = registry(original)
        assertThrows(IllegalArgumentException::class.java) {
            registry.reload(CommandConfig(commands = listOf(original, original)))
        }
        assertEquals("old", registry.match("设置")!!.entry.id)
    }

    @Test
    fun parserRejectsDuplicateOrBlankIdsBeforeConfigActivation() {
        val command = """{"id":"same","displayName":"设置","action":{"type":"activity"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            CommandConfigLoader.parseJson("""{"commands":[$command,$command]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandConfigLoader.parseJson("""{"commands":[${command.replace("same", " ")}]}""")
        }
    }
}
