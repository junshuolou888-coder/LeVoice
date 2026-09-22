package com.localvoicetv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity(), SherpaSpeechRecognizer.Listener {
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var commandsText: TextView
    private lateinit var listenButton: Button
    private lateinit var settingsButton: Button
    private lateinit var speechRecognizer: SherpaSpeechRecognizer

    private lateinit var registry: CommandRegistry
    private lateinit var executor: CommandExecutor
    private lateinit var weather: WeatherController

    @Volatile
    private var modelReady = false
    private var screenActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        commandsText = findViewById(R.id.commandsText)
        listenButton = findViewById(R.id.listenButton)
        settingsButton = findViewById(R.id.settingsButton)

        // Load command config and create registry + executor
        val config = CommandConfigLoader.load(applicationContext)
        registry = CommandRegistry(config)
        weather = WeatherController(this) { statusText.text = it }
        executor = CommandExecutor(this, mapOf("check_weather" to weather::query))
        findViewById<Button>(R.id.weatherSettingsButton).setOnClickListener { weather.showSettings() }

        // Update the "你可以这样说" panel from config
        commandsText.text = registry.allDisplayNames().joinToString("　·　")

        listenButton.isEnabled = false
        listenButton.setOnClickListener {
            if (speechRecognizer.isListening()) {
                statusText.setText(R.string.status_stopping)
                speechRecognizer.stopListening()
            } else {
                ensureMicrophonePermissionAndStart()
            }
        }
        settingsButton.setOnClickListener {
            weather.cancel()
            val settingsAction = CommandAction(
                type = "intent",
                intentAction = "android.settings.SETTINGS",
            )
            try {
                executor.execute(settingsAction)
            } catch (e: Exception) {
                statusText.text = getString(R.string.command_failed, e.message)
            }
        }

        speechRecognizer = SherpaSpeechRecognizer(applicationContext, this)
        speechRecognizer.initialize(
            hotwords = registry.buildHotwords(),
            hotwordsScore = registry.hotwordsScore(),
        )
    }

    override fun onStart() {
        super.onStart()
        screenActive = true
    }

    override fun onStop() {
        screenActive = false
        weather.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        weather.close()
        speechRecognizer.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE_PERMISSION_REQUEST) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            statusText.setText(R.string.permission_denied)
        }
    }

    override fun onModelReady() {
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            modelReady = true
            listenButton.isEnabled = true
            if (statusText.text == getString(R.string.status_loading)) statusText.setText(R.string.status_ready)
            listenButton.requestFocus()
        }
    }

    override fun onListeningChanged(isListening: Boolean) {
        runOnUiThread {
            listenButton.setText(if (isListening) R.string.stop else R.string.listen)
            if (isListening) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusText.setText(R.string.status_listening)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val currentStatus = statusText.text.toString()
                if (currentStatus == getString(R.string.status_listening) ||
                    currentStatus == getString(R.string.status_stopping)
                ) {
                    statusText.setText(R.string.status_ready)
                }
            }
        }
    }

    override fun onPartialResult(text: String) {
        runOnUiThread {
            transcriptText.text = getString(R.string.recognized_format, text)
        }
    }

    override fun onFinalResult(text: String) {
        runOnUiThread {
            if (!screenActive || isDestroyed || isFinishing) return@runOnUiThread
            weather.cancel()
            transcriptText.text = getString(R.string.recognized_format, text)
            val matchResult = registry.match(text)
            if (matchResult == null) {
                android.util.Log.i("MainActivity", "Match failed for text: $text")
                statusText.text = getString(R.string.command_not_understood, text)
            } else {
                try {
                    android.util.Log.i("MainActivity", "Matched [${matchResult.entry.id}] with vars: ${matchResult.variables}")
                    val execution = executor.execute(matchResult.entry.action, matchResult.variables)
                    if (execution == CommandExecution.COMPLETED) {
                        statusText.text = getString(R.string.status_executed, matchResult.entry.displayName)
                    }
                } catch (e: Exception) {
                    statusText.text = getString(
                        R.string.command_failed,
                        e.message ?: matchResult.entry.displayName,
                    )
                }
            }
        }
    }

    override fun onError(error: Throwable) {
        runOnUiThread {
            listenButton.setText(R.string.listen)
            if (!modelReady) {
                statusText.text = getString(
                    R.string.status_model_error,
                    error.message ?: error.javaClass.simpleName,
                )
            } else {
                statusText.text = getString(
                    R.string.status_audio_error,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun ensureMicrophonePermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            statusText.setText(R.string.status_permission)
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MICROPHONE_PERMISSION_REQUEST,
            )
        }
    }

    private fun startListening() {
        if (!modelReady) return
        weather.cancel()
        transcriptText.setText(R.string.recognized_placeholder)
        speechRecognizer.startListening()
    }

    private companion object {
        const val MICROPHONE_PERMISSION_REQUEST = 1001
    }
}
