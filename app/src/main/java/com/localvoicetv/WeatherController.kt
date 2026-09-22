package com.localvoicetv

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** UI adapter; network work and parsing live outside Activity and outside Intent dispatch. */
class WeatherController(private val activity: Activity, private val status: (String) -> Unit) {
    private val store = WeatherSettingsStore(activity)
    private val main = Handler(Looper.getMainLooper())
    private val requests = LatestRequestRunner(deliver = { callback -> main.post { callback() } })
    private var client: QWeatherClient? = null
    private var dialog: AlertDialog? = null
    private var pending = false

    fun query(params: Map<String, String>): CommandExecution {
        require(params.keys.all { it in setOf("city", "period", "defaultPeriod") }) { "天气参数只支持 city、period 和 defaultPeriod" }
        val city = params["city"].orEmpty()
        val period = WeatherPeriod.parse(params["period"].orEmpty().ifEmpty { params["defaultPeriod"].orEmpty() })
        val service = client ?: QWeatherClient(store.load().also { it.validate() }).also { client = it }
        dialog?.dismiss()
        status(if (city.isBlank() || city in setOf("本地", "当地", "这里")) "正在定位并查询${period.label}天气…" else "正在查询${city}${period.label}天气…")
        pending = true
        requests.submit({ cancellation -> service.query(city, period, cancellation) }) { result ->
            pending = false
            result.fold(onSuccess = { report ->
                status("天气查询完成")
                showReport(report)
            }, onFailure = { error ->
                if (error is AmbiguousCityException) {
                    status("找到多个城市，请选择具体地区")
                    dialog = AlertDialog.Builder(activity).setTitle("选择城市")
                        .setItems(error.cities.map { it.label }.toTypedArray()) { _, index ->
                            query(mapOf("city" to error.cities[index].id, "period" to period.name.lowercase()))
                        }.setNegativeButton("取消", null).show()
                } else {
                    status(error.message ?: "天气查询失败，请稍后重试")
                }
            })
        }
        return CommandExecution.PENDING
    }

    fun showSettings() {
        cancel()
        val existing = try { store.load() } catch (e: IllegalStateException) {
            status(e.message.orEmpty())
            WeatherSettings()
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun field(label: String, value: String, password: Boolean = false): EditText {
            form.addView(TextView(activity).apply { text = label; textSize = 16f })
            return EditText(activity).apply {
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(value)
                textSize = 18f
                form.addView(this)
            }
        }
        val host = field("API Host", existing.host)
        val key = field("API KEY（控制台 → 项目管理 → 凭据）", existing.apiKey, password = true)
        val city = field("固定城市（留空时自动通过公网 IP 定位）", existing.defaultCity)
        val errorText = TextView(activity).apply { textSize = 16f }
        form.addView(errorText)
        form.addView(TextView(activity).apply {
            text = "仅查询天气时联网。IP 定位可能指向宽带出口或代理城市，可直接说城市名称覆盖。"
            textSize = 14f
        })
        val scroll = ScrollView(activity).apply { addView(form) }
        val settingsDialog = AlertDialog.Builder(activity).setTitle("天气设置").setView(scroll)
            .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog = settingsDialog
        settingsDialog.setOnShowListener {
            settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.save(WeatherSettings(host.text.toString().trim(), key.text.toString().trim(), city.text.toString().trim()))
                    client = null
                    status("天气设置已保存，可以说“今天天气怎么样”")
                    settingsDialog.dismiss()
                } catch (e: Exception) {
                    errorText.text = if (e is IllegalArgumentException) e.message else "天气设置保存失败，请重试"
                }
            }
        }
        settingsDialog.show()
    }

    private fun showReport(report: WeatherReport) {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
            addView(TextView(activity).apply {
                text = report.displayText()
                textSize = 22f
                setLineSpacing(dp(4).toFloat(), 1f)
            })
            addView(TextView(activity).apply {
                val label = "天气服务由和风天气驱动"
                text = SpannableString(label).apply {
                    setSpan(URLSpan("https://www.qweather.com"), 5, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textSize = 16f
                setPadding(0, dp(16), 0, dp(8))
                movementMethod = LinkMovementMethod.getInstance()
            })
            if (report.attributions.isNotEmpty()) addView(TextView(activity).apply {
                text = report.attributions.joinToString("\n")
                textSize = 14f
                Linkify.addLinks(this, Linkify.WEB_URLS)
                movementMethod = LinkMovementMethod.getInstance()
            })
        }
        dialog = AlertDialog.Builder(activity).setTitle("天气查询")
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton("关闭", null).show()
    }

    fun cancel() {
        requests.cancel()
        if (pending) status("天气查询已取消")
        pending = false
        dialog?.dismiss()
        dialog = null
    }

    fun close() {
        cancel()
        requests.close()
        main.removeCallbacksAndMessages(null)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
