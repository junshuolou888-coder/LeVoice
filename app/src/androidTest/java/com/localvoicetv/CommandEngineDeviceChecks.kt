package com.localvoicetv

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import android.widget.PopupWindow
import android.widget.TextView
import org.json.JSONObject

/** Native Android regression checks; JVM/Robolectric uses a different regex implementation. */
class CommandEngineDeviceChecks : Instrumentation() {
    private var weatherUtterance: String? = null
    private var weatherUi = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        weatherUtterance = arguments?.getString("weatherUtterance")
        weatherUi = arguments?.getString("weatherUi") == "true"
        start()
    }

    override fun onStart() {
        val output = StringBuilder()
        var passed = 0
        fun verify(name: String, body: () -> Unit) {
            body()
            passed++
            output.append("PASS: ").append(name).append('\n')
        }

        try {
            val context = RecordingContext(targetContext)
            val executor = CommandExecutor(context)
            val config = targetContext.assets.open("default_commands.json").bufferedReader().use {
                CommandConfigLoader.parseJson(it.readText())
            }
            val registry = CommandRegistry(config)

            verify("all packaged command regexes compile on Android") {
                config.commands.mapNotNull { it.regex }.forEach { Regex(it) }
            }
            verify("a literal action initializes the parameter engine without crashing") {
                executor.execute(CommandAction("activity", intentAction = "android.settings.SETTINGS"))
                check(context.activity?.action == "android.settings.SETTINGS")
            }
            verify("plain, braced and raw placeholders preserve values in one pass") {
                executor.execute(CommandAction(
                    "activity", intentAction = "com.example.SEARCH",
                    intentExtras = mapOf(
                        "plain" to "\$param1", "braced" to "\${param1}",
                        "raw" to "\${param1:raw}", "ten" to "\$param10",
                        "boundary" to "\$param1suffix", "literal" to "{literal}",
                    ),
                ), mapOf("param1" to "\$param10", "param10" to "十"))
                val intent = checkNotNull(context.activity)
                for (name in listOf("plain", "braced", "raw")) {
                    check(intent.getStringExtra(name) == "\$param10")
                }
                check(intent.getStringExtra("ten") == "十")
                check(intent.getStringExtra("boundary") == "\$param1suffix")
                check(intent.getStringExtra("literal") == "{literal}")
            }
            verify("URI parameters keep query delimiters inside the value") {
                val value = "刘德华 &page=99/#? +"
                executor.execute(CommandAction(
                    "uri", uri = "example://search?q=\${param1:uri}&page=1",
                ), mapOf("param1" to value))
                val uri = checkNotNull(context.activity?.data)
                check(uri.getQueryParameter("q") == value)
                check(uri.getQueryParameter("page") == "1")
            }
            verify("JSON extras escape quotes and backslashes") {
                val value = "带\"引号\"和\\反斜杠\n的片名"
                executor.execute(CommandAction(
                    "activity", intentAction = "com.example.SEARCH",
                    intentExtras = mapOf("value" to "{\"keyword\":\"\$param1\"}"),
                ), mapOf("param1" to value))
                check(JSONObject(checkNotNull(context.activity!!.getStringExtra("value"))).getString("keyword") == value)
            }
            verify("repeated movie suffix reaches actor search with only the actor name") {
                val match = checkNotNull(registry.match("我想看刘德华的电影电影"))
                check(match.entry.id == "search_actor_movies")
                executor.execute(match.entry.action, match.variables)
                val value = JSONObject(checkNotNull(context.activity!!.getStringExtra("value")))
                check(value.getString("keyword") == "刘德华")
                check(value.getBoolean("isActorSearch"))
            }
            verify("weather matching passes extracted parameters to its builtin handler") {
                var received: Map<String, String>? = null
                val weather = CommandExecutor(context, mapOf("check_weather" to { parameters ->
                    received = parameters
                    CommandExecution.PENDING
                }))
                val match = checkNotNull(registry.match("上海明天天气"))
                check(weather.execute(match.entry.action, match.variables) == CommandExecution.PENDING)
                check(received?.get("city") == "上海")
                check(received?.get("period") == "明天")
            }
            verify("missing parameters fail before an activity is dispatched") {
                context.activity = null
                var rejected = false
                try {
                    executor.execute(CommandAction("uri", uri = "example://search?q=\${param1}"))
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                check(rejected && context.activity == null)
            }
            weatherUtterance?.let { utterance ->
                verify("active weather rule reaches live IP location and forecast") {
                    val active = CommandRegistry(CommandConfigLoader.load(targetContext))
                    val match = checkNotNull(active.match(utterance))
                    check(match.entry.action.builtinAction == "check_weather")
                    var report: WeatherReport? = null
                    val live = CommandExecutor(context, mapOf("check_weather" to { parameters ->
                        check(parameters["city"].isNullOrEmpty()) { "Expected an omitted city: $parameters" }
                        val settings = WeatherSettingsStore(targetContext).load()
                        check(settings.defaultCity.isBlank()) { "Clear the fixed city to verify IP location" }
                        val period = WeatherPeriod.parse(parameters["period"].orEmpty()
                            .ifEmpty { parameters["defaultPeriod"].orEmpty() })
                        report = QWeatherClient(settings).query("", period, NetworkCancellation())
                        CommandExecution.COMPLETED
                    }))
                    live.execute(match.entry.action, match.variables)
                    check(checkNotNull(report).title.contains("IP 定位"))
                    output.append(checkNotNull(report).displayText()).append('\n')
                }
            }
            if (weatherUi) {
                verify("weather UI keeps focus and automatically dismisses without a close button") {
                    val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                    waitForIdleSync()
                    val controllerField = MainActivity::class.java.getDeclaredField("weather").apply { isAccessible = true }
                    val popupField = WeatherController::class.java.getDeclaredField("popup").apply { isAccessible = true }
                    var controller: WeatherController? = null
                    var popup: PopupWindow? = null
                    runOnMainSync {
                        controller = controllerField.get(activity) as WeatherController
                        activity.onFinalResult("看一下今天的天气")
                    }
                    val deadline = SystemClock.uptimeMillis() + 60_000L
                    while (popup == null && SystemClock.uptimeMillis() < deadline) {
                        SystemClock.sleep(100)
                        runOnMainSync { popup = popupField.get(controller) as? PopupWindow }
                    }
                    runOnMainSync {
                        val window = checkNotNull(popup) { "No weather popup appeared" }
                        check(window.isShowing && !window.isFocusable && !window.isTouchable)
                        val message = (window.contentView as TextView).text.toString()
                        check(message.contains("IP 定位") && message.contains("最高") && message.contains("最低")) { message }
                        check(activity.hasWindowFocus())
                        output.append("WEATHER POPUP: ").append(message).append('\n')
                    }
                    SystemClock.sleep(12_500L)
                    runOnMainSync {
                        check(popupField.get(controller) == null)
                        check(!checkNotNull(popup).isShowing)
                        activity.finish()
                    }
                }
            }
            output.append("Device checks passed: ").append(passed).append('\n')
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", output.toString()) })
        } catch (failure: Throwable) {
            output.append(Log.getStackTraceString(failure))
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("stream", output.toString())
                putString("shortMsg", "Device check failed after $passed passes: ${failure.javaClass.simpleName}")
            })
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var activity: Intent? = null
        override fun startActivity(intent: Intent) { activity = intent }
    }
}
