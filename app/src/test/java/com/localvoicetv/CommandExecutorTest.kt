package com.localvoicetv

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class CommandExecutorTest {
    private class RecordingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var activity: Intent? = null
        var broadcast: Intent? = null
        var service: Intent? = null
        var foregroundService: Intent? = null
        var serviceResult: ComponentName? = ComponentName("com.example", "com.example.SyncService")
        var activityFailure: RuntimeException? = null

        override fun startActivity(intent: Intent) {
            activityFailure?.let { throw it }
            activity = intent
        }
        override fun sendBroadcast(intent: Intent) { broadcast = intent }
        override fun startService(intent: Intent): ComponentName? {
            service = intent
            return serviceResult
        }
        override fun startForegroundService(intent: Intent): ComponentName? {
            foregroundService = intent
            return serviceResult
        }
    }

    private lateinit var context: RecordingContext
    private lateinit var executor: CommandExecutor

    @Before
    fun setup() {
        context = RecordingContext()
        executor = CommandExecutor(context)
    }

    private fun parseAction(action: String): CommandAction = CommandConfigLoader.parseJson(
        """{"commands":[{"id":"test","displayName":"test","keywords":["test"],"action":$action}]}""",
    ).commands.single().action

    @Test
    fun uriRetainsAllSharedIntentFields() {
        executor.execute(CommandAction(
            type = "uri", uri = "content://com.example/video/7", intentPackage = "com.example.player",
            intentType = "video/*", intentCategories = listOf(Intent.CATEGORY_DEFAULT),
            intentFlags = listOf("FLAG_GRANT_READ_URI_PERMISSION"), intentExtras = mapOf("source" to "voice"),
        ))
        val intent = context.activity!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content://com.example/video/7", intent.dataString)
        assertEquals("video/*", intent.type)
        assertEquals("com.example.player", intent.`package`)
        assertEquals("voice", intent.getStringExtra("source"))
        assertTrue(intent.hasCategory(Intent.CATEGORY_DEFAULT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun packageOnlyRestrictsBroadcastTarget() {
        executor.execute(CommandAction("broadcast", intentAction = "com.example.REFRESH", intentPackage = "com.example"))
        assertEquals("com.example", context.broadcast!!.`package`)
        assertEquals(0, context.broadcast!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun placeholderNamesDoNotOverlapOrRecursivelyExpandValues() {
        executor.execute(CommandAction(
            "activity", intentAction = "com.example.SEARCH",
            intentExtras = mapOf("one" to "\$param1", "ten" to "\$param10"),
        ), linkedMapOf("param1" to "\$param10", "param10" to "十"))
        assertEquals("\$param10", context.activity!!.getStringExtra("one"))
        assertEquals("十", context.activity!!.getStringExtra("ten"))
    }

    @Test
    fun legacyJsonStringExtrasEscapeUserText() {
        val search = "带\"引号\"和\\反斜杠\n的片名"
        executor.execute(CommandAction(
            "activity", intentAction = "com.example.SEARCH",
            intentExtras = mapOf("value" to """{"keyword":"${'$'}param1","isActorSearch":true}"""),
        ), mapOf("param1" to search))
        val value = JSONObject(context.activity!!.getStringExtra("value")!!)
        assertEquals(search, value.getString("keyword"))
        assertTrue(value.getBoolean("isActorSearch"))
    }

    @Test
    fun missingParameterFailsBeforeAnyDispatch() {
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute(CommandAction("uri", uri = "example://search?q=\$param1"))
        }
        assertNull(context.activity)
    }

    @Test
    fun typedExtrasHaveTheTypesRequestedByJson() {
        executor.execute(parseAction("""{
            "type":"activity","intentAction":"com.example.SEARCH",
            "intentExtras": {
              "page":{"type":"int","value":"${'$'}param1"},
              "id":{"type":"long","value":"7"},
              "enabled":{"type":"boolean","value":"true"},
              "names":{"type":"stringArray","value":["${'$'}param2","next"]},
              "value":{"type":"json","value":{"keyword":"${'$'}param2","exact":true}}
            }
        }"""), mapOf("param1" to "3", "param2" to "带\"引号"))
        val intent = context.activity!!
        assertEquals(3, intent.getIntExtra("page", -1))
        assertEquals(7L, intent.getLongExtra("id", -1))
        assertTrue(intent.getBooleanExtra("enabled", false))
        assertArrayEquals(arrayOf("带\"引号", "next"), intent.getStringArrayExtra("names"))
        assertEquals("带\"引号", JSONObject(intent.getStringExtra("value")!!).getString("keyword"))
    }

    @Test
    fun unsupportedExtrasFailInsteadOfDisappearing() {
        assertThrows(IllegalArgumentException::class.java) {
            executor.execute(parseAction("""{
                "type":"activity","intentAction":"com.example.SEARCH",
                "intentExtras":{"bad":{"type":"typo","value":"x"}}
            }"""))
        }
        assertNull(context.activity)
    }

    @Test
    fun missingServiceDoesNotReportSuccess() {
        context.serviceResult = null
        assertThrows(IllegalStateException::class.java) {
            executor.execute(CommandAction("service", intentPackage = "com.example", intentClass = "com.example.SyncService"))
        }
    }

    @Test
    fun uriParameterEncodingKeepsQuerySeparatorsInsideTheValue() {
        val keyword = "刘德华 &page=99/#? +"
        executor.execute(CommandAction("uri", uri = "example://search?q=${'$'}{param1:uri}&page=1"), mapOf("param1" to keyword))
        assertEquals(keyword, context.activity!!.data!!.getQueryParameter("q"))
        assertEquals("1", context.activity!!.data!!.getQueryParameter("page"))
    }

    @Test
    fun activityAliasPreservesDataMimeAndResolvesRelativeClassNames() {
        executor.execute(CommandAction(
            "intent", intentData = "content://com.example/video/7", intentType = "video/*",
            intentPackage = "com.example", intentClass = ".PlayerActivity",
            intentFlags = listOf("0x04000000", "536870912"),
        ))
        val intent = context.activity!!
        assertEquals(ComponentName("com.example", "com.example.PlayerActivity"), intent.component)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content://com.example/video/7", intent.dataString)
        assertEquals("video/*", intent.type)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun unknownFlagsAndOverflowingNumbersFailBeforeDispatch() {
        for (flag in listOf("FLAG_TYPO", "0x100000000", "-2147483649")) {
            assertThrows(flag, IllegalArgumentException::class.java) {
                executor.execute(CommandAction("uri", uri = "example://open", intentFlags = listOf(flag)))
            }
        }
        assertNull(context.activity)
    }

    @Test
    fun invalidTypedValuesFailInsteadOfBeingTruncatedOrCoerced() {
        for (descriptor in listOf(
            """{"type":"int","value":"2147483648"}""",
            """{"type":"int","value":3.5}""",
            """{"type":"boolean","value":"yes"}""",
            """{"type":"float","value":"NaN"}""",
            """{"type":"double","value":"Infinity"}""",
            """{"type":"longArray","value":["not a number"]}""",
            """{"type":"json","value":"not an object"}""",
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                executor.execute(parseAction("""{"type":"uri","uri":"example://open","intentExtras":{"bad":$descriptor}}"""))
            }
            assertTrue(error.message!!.contains("bad"))
        }
        assertNull(context.activity)
    }

    @Test
    fun typedNumericAndArrayExtrasRoundTrip() {
        executor.execute(parseAction("""{
            "type":"uri","uri":"example://open",
            "intentExtras":{
              "ratio":{"type":"float","value":"0.5"},
              "precise":{"type":"double","value":1.25},
              "ids":{"type":"longArray","value":["4294967296","7"]},
              "pages":{"type":"intArray","value":[1,"${'$'}{param1}"]},
              "switches":{"type":"booleanArray","value":[true,"false"]},
              "literal":{"type":"string","value":"{\"text\":\"keep spacing\"}"}
            }
        }"""), mapOf("param1" to "3"))
        val intent = context.activity!!
        assertEquals(0.5f, intent.getFloatExtra("ratio", 0f), 0f)
        assertEquals(1.25, intent.getDoubleExtra("precise", 0.0), 0.0)
        assertArrayEquals(longArrayOf(4294967296L, 7L), intent.getLongArrayExtra("ids"))
        assertArrayEquals(intArrayOf(1, 3), intent.getIntArrayExtra("pages"))
        assertArrayEquals(booleanArrayOf(true, false), intent.getBooleanArrayExtra("switches"))
        assertEquals("""{"text":"keep spacing"}""", intent.getStringExtra("literal"))
    }

    @Test
    fun nestedJsonRetainsArraysBooleansNullAndLiteralDollarText() {
        executor.execute(parseAction("""{
            "type":"uri","uri":"example://open",
            "intentExtras":{"value":{"type":"json","value":{"items":[{"name":"${'$'}param1"},null,true,2]}}}
        }"""), mapOf("param1" to "\$param2"))
        val items = JSONObject(context.activity!!.getStringExtra("value")!!).getJSONArray("items")
        assertEquals("\$param2", items.getJSONObject(0).getString("name"))
        assertTrue(items.isNull(1))
        assertTrue(items.getBoolean(2))
        assertEquals(2, items.getInt(3))
    }

    @Test
    fun androidSixUsesNormalServiceForForegroundMode() {
        executor.execute(parseAction("""{
            "type":"service","serviceMode":"foreground","intentPackage":"com.example","intentClass":".SyncService"
        }"""))
        assertNotNull(context.service)
        assertNull(context.foregroundService)
        assertEquals(0, context.service!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    @Config(sdk = [28])
    fun modernAndroidUsesForegroundServiceWhenRequested() {
        executor.execute(CommandAction(
            "service", intentPackage = "com.example", intentClass = ".SyncService", serviceMode = "foreground",
        ))
        assertNotNull(context.foregroundService)
        assertNull(context.service)
    }

    @Test
    fun malformedActionConfigurationsAreRejectedDuringLoading() {
        for (action in listOf(
            """{"type":"uri"}""",
            """{"type":"activity","intentPackage":"com.example"}""",
            """{"type":"activity","intentClass":".MainActivity"}""",
            """{"type":"service","intentAction":"com.example.START"}""",
            """{"type":"uri","uri":"example://one","intentData":"example://two"}""",
            """{"type":"uri","uri":"example://open","serviceMode":"foreground"}""",
            """{"type":"uri","uri":123}""",
            """{"type":"unknown"}""",
        )) assertThrows(action, IllegalArgumentException::class.java) { parseAction(action) }
    }

    @Test
    fun explicitJsonNullDoesNotBecomeTheStringNull() {
        val action = parseAction("""{
            "type":"uri","uri":"example://open","intentPackage":null,"intentClass":null,"intentExtras":null
        }""")
        executor.execute(action)
        assertNull(context.activity!!.`package`)
        assertNull(context.activity!!.component)
        assertNull(context.activity!!.extras)
    }

    @Test
    fun targetFailuresRemainFailuresWithActionableMessages() {
        val missing = ActivityNotFoundException("missing")
        context.activityFailure = missing
        val notFound = assertThrows(IllegalStateException::class.java) {
            executor.execute(CommandAction("uri", uri = "example://open"))
        }
        assertSame(missing, notFound.cause)
        assertTrue(notFound.message!!.contains("未找到"))
        val denied = SecurityException("denied")
        context.activityFailure = denied
        val permission = assertThrows(SecurityException::class.java) {
            executor.execute(CommandAction("uri", uri = "example://open"))
        }
        assertSame(denied, permission.cause)
        assertTrue(permission.message!!.contains("权限"))
    }

    @Test
    fun existingSearchAndDesktopConfigurationsKeepTheirTargetsAndExtraTypes() {
        val config = CommandConfigLoader.parseJson(
            requireNotNull(javaClass.getResourceAsStream("/default_commands.json")).bufferedReader().use { it.readText() },
        )
        val result = CommandRegistry(config).match("我想看刘德华的电影电影")!!
        executor.execute(result.entry.action, result.variables)
        val intent = context.activity!!
        assertEquals("com.letv.leso.desktop.receiver", intent.action)
        assertEquals(1, intent.getIntExtra("type", -1))
        val value = JSONObject(intent.getStringExtra("value")!!)
        assertEquals("刘德华", value.getString("keyword"))
        assertEquals("刘德华", value.getString("fulltext"))
        assertTrue(value.getBoolean("isActorSearch"))

        val desktop = CommandRegistry(config).match("电影")!!
        executor.execute(desktop.entry.action, desktop.variables)
        assertEquals("com.stv.launcher.action.start", context.activity!!.action)
        assertEquals("1", context.activity!!.getStringExtra("type"))
        assertEquals("com.stv.template.movie", JSONObject(context.activity!!.getStringExtra("value")!!).getString("pluginid"))
    }
    @Test
    fun registeredBuiltinReceivesExpandedNamedParametersAndReturnsPending() {
        var received: Map<String, String>? = null
        val custom = CommandExecutor(context, mapOf("check_weather" to { params ->
            received = params
            CommandExecution.PENDING
        }))
        val action = parseAction("""{"type":"builtin","builtinAction":"check_weather","builtinParams":{"city":"$""" + """param1","period":"today"}}""")
        assertEquals(CommandExecution.PENDING, custom.execute(action, mapOf("param1" to "北京")))
        assertEquals(mapOf("city" to "北京", "period" to "today"), received)
        assertNull(context.activity)
    }

    @Test
    fun builtinParameterTypesAreValidatedAtConfigurationLoad() {
        assertThrows(IllegalArgumentException::class.java) {
            parseAction("""{"type":"builtin","builtinAction":"check_weather","builtinParams":{"city":42}}""")
        }
    }

}
