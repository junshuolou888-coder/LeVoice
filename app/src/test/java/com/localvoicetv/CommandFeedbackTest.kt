package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test

class CommandFeedbackTest {
    @Test fun actorFeedbackUsesExtractedNameAndCategory() {
        val json = javaClass.classLoader!!.getResource("default_commands.json")!!.readText()
        val registry = CommandRegistry(CommandConfigLoader.parseJson(json))
        val match = registry.match("我想看刘德华的电影")!!
        assertEquals("好的，这就为您搜索刘德华的电影", CommandFeedback.success(match))
    }
    @Test fun oldOrBrokenTemplatesDoNotFailTheAction() {
        val entry = CommandEntry("test", "测试", action = CommandAction("builtin", builtinAction = "go_home"))
        assertEquals("好的，这就为您处理", CommandFeedback.success(CommandMatchResult(entry)))
        assertEquals("好的，这就为您处理", CommandFeedback.success(CommandMatchResult(entry.copy(feedback = "搜索${'$'}param1"))))
    }
}
