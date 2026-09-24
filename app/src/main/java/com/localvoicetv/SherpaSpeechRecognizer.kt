package com.localvoicetv

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class SherpaSpeechRecognizer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onModelReady()
        fun onListeningChanged(isListening: Boolean)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: Throwable)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val listening = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    @Volatile private var stopRequestedAt = 0L

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    private var hotwords: String = ""
    private var hotwordsScore: Float = 8.0f
    private var useEnhancedModel = false

    @Volatile
    var modelDescription: String = "中文轻量模型"
        private set

    fun initialize(hotwords: String, hotwordsScore: Float) {
        this.hotwords = hotwords
        this.hotwordsScore = hotwordsScore
        executor.execute {
            try {
                val started = SystemClock.elapsedRealtime()
                val enhanced = SpeechModelFiles.profiles.firstOrNull { it.id == BuildConfig.SPEECH_MODEL }
                useEnhancedModel = enhanced != null
                val modelDir = if (useEnhancedModel) "speech-model" else SpeechModelFiles.LEGACY
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/" + if (useEnhancedModel) "encoder.int8.onnx" else "encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/" + if (useEnhancedModel) "decoder.onnx" else "decoder-epoch-99-avg-1.onnx",
                        joiner = "$modelDir/" + if (useEnhancedModel) "joiner.int8.onnx" else "joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = min(4, max(1, Runtime.getRuntime().availableProcessors() / 2)),
                    debug = false,
                    modelType = enhanced?.modelType ?: "zipformer",
                    modelingUnit = "cjkchar",
                )
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80,
                        dither = 0.0f,
                    ),
                    modelConfig = modelConfig,
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 2.4f, 0.0f),
                        rule2 = EndpointRule(true, 0.9f, 0.0f),
                        rule3 = EndpointRule(false, 0.0f, 12.0f),
                    ),
                    enableEndpoint = true,
                    // Bundled Zipformer models use the upstream greedy decoder without command hotwords.
                    decodingMethod = if (useEnhancedModel) "greedy_search" else "modified_beam_search",
                    maxActivePaths = 4,
                    hotwordsScore = this.hotwordsScore,
                )
                recognizer = OnlineRecognizer(
                    assetManager = context.assets,
                    config = config,
                )
                modelDescription = enhanced?.description ?: "中文轻量模型 · 14M"
                Log.i("VoiceRecognition", "Model ready: $modelDescription; loadMs=${SystemClock.elapsedRealtime() - started}")
                listener.onModelReady()
            } catch (error: Throwable) {
                listener.onError(error)
            }
        }
    }

    fun startListening() {
        if (recognizer == null) {
            listener.onError(IllegalStateException("语音模型尚未加载完成"))
            listener.onListeningChanged(false)
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(SecurityException("没有麦克风权限"))
            listener.onListeningChanged(false)
            return
        }
        if (!busy.compareAndSet(false, true)) return
        stopRequestedAt = 0L
        listening.set(true)

        listener.onListeningChanged(true)
        executor.execute(::recordAndRecognize)
    }

    fun stopListening() {
        if (listening.getAndSet(false)) stopRequestedAt = SystemClock.elapsedRealtime()
        // Capture uses non-blocking reads and drains the hardware buffer itself.
        // Never stop AudioRecord here: that would discard audio waiting behind the decoder.
    }

    fun isListening(): Boolean = busy.get()

    fun release() {
        stopListening()
        executor.execute {
            recognizer?.release()
            recognizer = null
        }
        executor.shutdown()
    }

    private fun recordAndRecognize() {
        val currentRecognizer = recognizer ?: run {
            listening.set(false)
            busy.set(false)
            listener.onListeningChanged(false)
            return
        }
        val stream = currentRecognizer.createStream(if (useEnhancedModel) "" else hotwords)
        var recorder: AudioRecord? = null
        var finalText = ""
        var capture: Thread? = null
        val queue = PcmQueue()
        var decodedSamples = 0L
        var decodeMs = 0L

        try {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            require(minBufferBytes > 0) { "设备不支持 16 kHz 单声道录音" }

            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                // Streaming encoder work arrives in bursts; retain audio while a chunk decodes.
                // Reading still uses 100 ms chunks, so this capacity adds no fixed waiting time.
                .setBufferSizeInBytes(max(minBufferBytes * 2, SAMPLE_RATE * 2 * 2))
                .build()

            require(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "麦克风初始化失败"
            }

            // The default MIC route can prefer a silent USB receiver over the TV's BLE HAL.
            val remoteInput = if (BuildConfig.STV_INTEGRATION) {
                val manager = context.getSystemService(AudioManager::class.java)
                StvAudioInput.select(manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList())
            } else null
            if (remoteInput != null) {
                check(recorder.setPreferredDevice(remoteInput)) { "系统未接受遥控器录音设备" }
                Log.i("VoiceRecognition", "Requested remote input: id=${remoteInput.id} type=${remoteInput.type}")
            }
            if (!listening.get()) return
            recorder.startRecording()
            val input = recorder
            capture = Thread({
                var captureFailure: Throwable? = null
                var capturedSamples = 0L
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val pcm = ShortArray(CHUNK_SAMPLES)
                    var buffered = 0
                    var levelAt = SystemClock.elapsedRealtime()
                    var squares = 0.0
                    var levelSamples = 0L
                    var peak = 0
                    var loggedInputId: Int? = null
                    var drainStartedAt = 0L
                    Log.d("VoiceRecognition", "Microphone started: 16000Hz mono PCM16; independent capture")
                    while (true) {
                        val draining = !listening.get()
                        if (draining && drainStartedAt == 0L) drainStartedAt = SystemClock.elapsedRealtime()
                        // Drain only already available PCM. A continuously producing HAL cannot keep us alive.
                        if (draining && SystemClock.elapsedRealtime() - drainStartedAt > 250) break
                        val offset = buffered
                        val count = input.read(pcm, offset, pcm.size - offset, AudioRecord.READ_NON_BLOCKING)
                        if (count < 0) {
                            if (draining) break
                            error("读取麦克风失败，错误码：$count")
                        }
                        if (count == 0) {
                            if (draining) break
                            Thread.sleep(5)
                            continue
                        }
                        val routed = input.routedDevice
                        if (routed != null) {
                            check(remoteInput == null || routed.id == remoteInput.id) { "录音未连接到遥控器" }
                            if (loggedInputId != routed.id) {
                                Log.i("VoiceRecognition", "Actual input: id=${routed.id} type=${routed.type} name=${routed.productName}")
                                loggedInputId = routed.id
                            }
                        }
                        buffered += count
                        if (buffered == pcm.size) { queue.offer(pcm.copyOf()); buffered = 0 }
                        capturedSamples += count
                        for (i in offset until offset + count) {
                            val value = pcm[i].toInt()
                            squares += value.toDouble() * value
                            peak = max(peak, kotlin.math.abs(value))
                        }
                        levelSamples += count
                        if (SystemClock.elapsedRealtime() - levelAt >= 1000) {
                            Log.d("VoiceRecognition", "Audio level: samples=$levelSamples rms=${kotlin.math.sqrt(squares / levelSamples).toInt()} peak=$peak queuedMs=${queue.pendingSamples * 1000L / SAMPLE_RATE}")
                            levelAt = SystemClock.elapsedRealtime(); squares = 0.0; levelSamples = 0; peak = 0
                        }
                    }
                    if (buffered > 0) queue.offer(pcm.copyOf(buffered))
                } catch (e: Throwable) { captureFailure = e }
                finally {
                    try { input.stop() } catch (_: IllegalStateException) { }
                    queue.close(captureFailure)
                    Log.i("VoiceRecognition", "Capture ended: audioMs=${capturedSamples * 1000 / SAMPLE_RATE} queuedMs=${queue.pendingSamples * 1000L / SAMPLE_RATE}")
                }
            }, "VoiceCapture").also { it.start() }
            var previousText = ""
            while (true) {
                val pcm = queue.take() ?: break
                val count = pcm.size
                decodedSamples += count
                val decodeStarted = SystemClock.elapsedRealtime()
                val samples = FloatArray(count) { index -> pcm[index] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)

                while (currentRecognizer.isReady(stream)) {
                    currentRecognizer.decode(stream)
                }

                decodeMs += SystemClock.elapsedRealtime() - decodeStarted
                val text = currentRecognizer.getResult(stream).text.trim()
                if (text.isNotEmpty() && text != previousText) {
                    previousText = text
                    listener.onPartialResult(text)
                }

                if (!BuildConfig.STV_INTEGRATION && currentRecognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        finalText = text
                        listening.set(false)
                        break
                    }
                    currentRecognizer.reset(stream)
                }
            }

            if (finalText.isEmpty()) {
                val flushStarted = SystemClock.elapsedRealtime()
                // Same tail padding as the offline model benchmark, with no wall-clock sleep.
                stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
                stream.inputFinished()
                while (currentRecognizer.isReady(stream)) {
                    currentRecognizer.decode(stream)
                }
                finalText = currentRecognizer.getResult(stream).text.trim()
                decodeMs += SystemClock.elapsedRealtime() - flushStarted
            }

            Log.i("VoiceRecognition", "Decode ended: audioMs=${decodedSamples * 1000 / SAMPLE_RATE} decodeMs=$decodeMs afterReleaseMs=${if (stopRequestedAt == 0L) 0 else SystemClock.elapsedRealtime() - stopRequestedAt}")
            if (finalText.isNotEmpty()) {
                listener.onFinalResult(finalText)
            }
        } catch (error: Throwable) {
            listener.onError(error)
        } finally {
            listening.set(false)
            capture?.join()
            busy.set(false)
            try {
                recorder?.takeIf {
                    it.recordingState == AudioRecord.RECORDSTATE_RECORDING
                }?.stop()
            } catch (_: IllegalStateException) {
                // Already stopped.
            }
            recorder?.release()
            stream.release()
            listener.onListeningChanged(false)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = 1_600
    }
}
