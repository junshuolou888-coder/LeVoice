package com.localvoicetv

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity(), SherpaSpeechRecognizer.Listener {
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var listenButton: Button
    private lateinit var settingsButton: Button
    private lateinit var speechRecognizer: SherpaSpeechRecognizer

    @Volatile
    private var modelReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        transcriptText = findViewById(R.id.transcriptText)
        listenButton = findViewById(R.id.listenButton)
        settingsButton = findViewById(R.id.settingsButton)

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
            executeCommand(VoiceCommand.OPEN_SETTINGS)
        }

        speechRecognizer = SherpaSpeechRecognizer(applicationContext, this)
        speechRecognizer.initialize()
    }

    override fun onDestroy() {
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
            modelReady = true
            listenButton.isEnabled = true
            statusText.setText(R.string.status_ready)
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
            transcriptText.text = getString(R.string.recognized_format, text)
            val command = VoiceCommandParser.parse(text)
            if (command == null) {
                statusText.text = getString(R.string.command_not_understood, text)
            } else {
                executeCommand(command)
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
        transcriptText.setText(R.string.recognized_placeholder)
        speechRecognizer.startListening()
    }

    private fun executeCommand(command: VoiceCommand) {
        try {
            when (command) {
                VoiceCommand.OPEN_NETWORK_SETTINGS ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))

                VoiceCommand.OPEN_BLUETOOTH_SETTINGS ->
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

                VoiceCommand.OPEN_SOUND_SETTINGS ->
                    startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))

                VoiceCommand.OPEN_DISPLAY_SETTINGS ->
                    startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))

                VoiceCommand.OPEN_APP_SETTINGS ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS))

                VoiceCommand.OPEN_SETTINGS ->
                    startActivity(Intent(Settings.ACTION_SETTINGS))

                VoiceCommand.GO_HOME -> {
                    val homeIntent = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(homeIntent)
                }

                VoiceCommand.VOLUME_UP ->
                    adjustVolume(AudioManager.ADJUST_RAISE)

                VoiceCommand.VOLUME_DOWN ->
                    adjustVolume(AudioManager.ADJUST_LOWER)

                VoiceCommand.TOGGLE_MUTE ->
                    adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE)
            }
            statusText.text = getString(R.string.status_executed, command.displayName())
        } catch (error: ActivityNotFoundException) {
            statusText.text = getString(
                R.string.command_failed,
                error.message ?: command.displayName(),
            )
        } catch (error: SecurityException) {
            statusText.text = getString(
                R.string.command_failed,
                error.message ?: command.displayName(),
            )
        }
    }

    private fun adjustVolume(direction: Int) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun VoiceCommand.displayName(): String {
        val resource = when (this) {
            VoiceCommand.OPEN_NETWORK_SETTINGS -> R.string.command_open_network
            VoiceCommand.OPEN_BLUETOOTH_SETTINGS -> R.string.command_open_bluetooth
            VoiceCommand.OPEN_SOUND_SETTINGS -> R.string.command_open_sound
            VoiceCommand.OPEN_DISPLAY_SETTINGS -> R.string.command_open_display
            VoiceCommand.OPEN_APP_SETTINGS -> R.string.command_open_apps
            VoiceCommand.OPEN_SETTINGS -> R.string.command_open_settings
            VoiceCommand.GO_HOME -> R.string.command_home
            VoiceCommand.VOLUME_UP -> R.string.command_volume_up
            VoiceCommand.VOLUME_DOWN -> R.string.command_volume_down
            VoiceCommand.TOGGLE_MUTE -> R.string.command_mute
        }
        return getString(resource)
    }

    private companion object {
        const val MICROPHONE_PERMISSION_REQUEST = 1001
    }
}
