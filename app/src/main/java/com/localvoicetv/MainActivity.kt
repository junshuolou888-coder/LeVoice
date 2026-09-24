package com.localvoicetv

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity(), SherpaSpeechRecognizer.Listener, RemoteVoiceKeys.Listener {
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
    private var remoteKeyRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.STV_INTEGRATION) {
            val service = android.content.Intent("com.localvoicetv.PREPARE_VOICE")
                .setClassName(this, "com.stv.voice.WindowService")
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            finish()
            return
        }
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
        findViewById<Button>(R.id.weatherSettingsButton).visibility = android.view.View.GONE

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
        if (BuildConfig.STV_INTEGRATION) return
        screenActive = true
        RemoteVoiceKeys.attach(this)
    }

    override fun onStop() {
        if (BuildConfig.STV_INTEGRATION) { super.onStop(); return }
        screenActive = false
        RemoteVoiceKeys.detach(this)
        if (remoteKeyRecording) {
            remoteKeyRecording = false
            speechRecognizer.stopListening()
        }
        weather.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        if (BuildConfig.STV_INTEGRATION) { super.onDestroy(); return }
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
            findViewById<TextView>(R.id.modelDescriptionText).text =
                getString(R.string.model_description, speechRecognizer.modelDescription)
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
        if (BuildConfig.DEBUG || BuildConfig.STV_INTEGRATION) android.util.Log.d("VoiceRecognition", "partial=$text")
        runOnUiThread {
            transcriptText.text = getString(R.string.recognized_format, text)
        }
    }

    override fun onFinalResult(text: String) {
        if (BuildConfig.DEBUG || BuildConfig.STV_INTEGRATION) android.util.Log.d("VoiceRecognition", "final=$text")
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
                        statusText.text = CommandFeedback.success(matchResult)
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

    override fun onRemoteVoiceKey(down: Boolean) {
        if (down) {
            if (!screenActive || speechRecognizer.isListening()) return
            if (!modelReady) {
                android.widget.Toast.makeText(this, "模型正在加载，准备就绪后请再按语音键", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            // Permission dialogs cannot preserve a physical press; let the user retry afterward.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                statusText.setText(R.string.status_permission)
                return
            }
            remoteKeyRecording = true
            startListening()
        } else if (remoteKeyRecording) {
            remoteKeyRecording = false
            speechRecognizer.stopListening()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemoteVoiceKeys.accepts(event.keyCode) &&
            (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)) {
            if (event.repeatCount == 0) onRemoteVoiceKey(event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
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
