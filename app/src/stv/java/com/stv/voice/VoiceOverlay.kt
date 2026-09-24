package com.stv.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

internal interface VoiceFeedback {
    fun show(text: String, duration: Long = 0)
    fun hide()
}

/** A passive system overlay: never intercepts TV navigation or opens an Activity. */
internal class VoiceOverlay(private val context: Context) : VoiceFeedback {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private val dismiss = Runnable { hide() }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    override fun show(text: String, duration: Long) {
        main.removeCallbacks(dismiss)
        val label = view ?: TextView(context).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 26, 30, 39))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(50, 255, 255, 255))
            }
            elevation = dp(12).toFloat()
            maxLines = 12
        }
        label.text = text
        if (view == null) {
            val params = WindowManager.LayoutParams(
                minOf(dp(400), context.resources.displayMetrics.widthPixels - dp(48)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.RIGHT; x = dp(28); y = dp(28) }
            try { windows.addView(label, params); view = label }
            catch (error: RuntimeException) {
                android.util.Log.w("StvVoiceBridge", "Cannot show voice overlay", error)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
        if (duration > 0) main.postDelayed(dismiss, duration)
    }
    override fun hide() {
        main.removeCallbacks(dismiss)
        view?.let { try { windows.removeViewImmediate(it) } catch (_: IllegalArgumentException) {} }
        view = null
    }
}
