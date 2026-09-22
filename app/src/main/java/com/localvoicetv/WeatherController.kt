package com.localvoicetv

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** UI adapter; network work and parsing live outside Activity and outside Intent dispatch. */
class WeatherController(private val activity: Activity, private val status: (String) -> Unit) {
    private val store = WeatherSettingsStore(activity)
    private val main = Handler(Looper.getMainLooper())
    private val requests = LatestRequestRunner(deliver = { callback -> main.post { callback() } })
    private var client: QWeatherClient? = null
    private var dialog: AlertDialog? = null
    private var popup: PopupWindow? = null
    private val hidePopup = Runnable { dismissPopup() }
    private var pending = false

    fun query(params: Map<String, String>): CommandExecution {
        require(params.keys.all { it in setOf("city", "period", "defaultPeriod") }) { "天气参数只支持 city、period 和 defaultPeriod" }
        val city = params["city"].orEmpty()
        val period = WeatherPeriod.parse(params["period"].orEmpty().ifEmpty { params["defaultPeriod"].orEmpty() })
        val service = client ?: QWeatherClient(store.load().also { it.validate() }).also { client = it }
        dialog?.dismiss()
        dismissPopup()
        status(if (city.isBlank() || city in setOf("本地", "当地", "这里")) "正在定位并查询${period.label}天气…" else "正在查询${city}${period.label}天气…")
        pending = true
        requests.submit({ cancellation -> service.query(city, period, cancellation) }) { result ->
            pending = false
            result.fold(onSuccess = { report ->
                status("天气查询完成")
                showReport(report)
            }, onFailure = { error ->
                if (error is AmbiguousCityException) {
                    val examples = error.cities.map { city ->
                        listOf(city.adm1, city.adm2, city.name).filter { it.isNotBlank() }.distinct().joinToString("")
                    }.distinct().take(2).joinToString("、")
                    val message = "地区名称不明确，请补充省市" +
                        if (examples.isEmpty()) "后再查询" else "，例如：$examples"
                    status(message)
                    showPopup(message)
                } else {
                    val message = error.message ?: "天气查询失败，请稍后重试"
                    status(message)
                    showPopup(message, 8_000L)
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
        showPopup(report.displayText())
    }

    private fun showPopup(message: String, durationMillis: Long = 12_000L) {
        dismissPopup()
        val anchor = activity.window.decorView
        if (activity.isFinishing || activity.isDestroyed || !anchor.isAttachedToWindow) return
        val content = TextView(activity).apply {
            text = message
            textSize = 22f
            setTextColor(activity.getColor(R.color.text_primary))
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val width = minOf(dp(520), activity.resources.displayMetrics.widthPixels - dp(48))
        val window = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = false
            isOutsideTouchable = false
            elevation = dp(12).toFloat()
            setBackgroundDrawable(activity.getDrawable(R.drawable.panel_background))
            setOnDismissListener {
                if (popup === this) {
                    popup = null
                    main.removeCallbacks(hidePopup)
                }
            }
        }
        popup = window
        try {
            window.showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, dp(32))
            main.postDelayed(hidePopup, durationMillis)
        } catch (_: WindowManager.BadTokenException) {
            dismissPopup() // The activity's window may disappear while a request finishes.
        }
    }

    private fun dismissPopup() {
        main.removeCallbacks(hidePopup)
        popup?.dismiss()
        popup = null
    }

    fun cancel() {
        requests.cancel()
        if (pending) status("天气查询已取消")
        pending = false
        dismissPopup()
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
