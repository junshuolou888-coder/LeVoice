package com.stv.voice

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.localvoicetv.WeatherController

/** Explicit settings only, opened by the notification; never used by voice-key handling. */
class VoiceSettingsActivity : Activity() {
    private lateinit var weather: WeatherController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weather = WeatherController(this) { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        weather.showSettings { if (!isFinishing) finish() }
    }
    override fun onDestroy() {
        weather.close()
        super.onDestroy()
    }
}
