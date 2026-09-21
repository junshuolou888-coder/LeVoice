package com.localvoicetv

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    private var hotwords: String = ""
    private var hotwordsScore: Float = 8.0f

    fun initialize(hotwords: String, hotwordsScore: Float) {
        this.hotwords = hotwords
        this.hotwordsScore = hotwordsScore
        executor.execute {
            try {
                val modelDir =
                    "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile"
                val modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$modelDir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$modelDir/decoder-epoch-99-avg-1.onnx",
                        joiner = "$modelDir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = min(4, max(1, Runtime.getRuntime().availableProcessors() / 2)),
                    debug = false,
                    modelType = "zipformer",
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
                    decodingMethod = "modified_beam_search",
                    maxActivePaths = 4,
                    hotwordsScore = this.hotwordsScore,
                )
                recognizer = OnlineRecognizer(
                    assetManager = context.assets,
                    config = config,
                )
                listener.onModelReady()
            } catch (error: Throwable) {
                listener.onError(error)
            }
        }
    }

    fun startListening() {
        if (recognizer == null) {
            listener.onError(IllegalStateException("语音模型尚未加载完成"))
            return
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.onError(SecurityException("没有麦克风权限"))
            return
        }
        if (!listening.compareAndSet(false, true)) return

        listener.onListeningChanged(true)
        executor.execute(::recordAndRecognize)
    }

    fun stopListening() {
        listening.compareAndSet(true, false)
    }

    fun isListening(): Boolean = listening.get()

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
            listener.onListeningChanged(false)
            return
        }
        val stream = currentRecognizer.createStream(hotwords)
        var recorder: AudioRecord? = null
        var finalText = ""

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
                .setBufferSizeInBytes(max(minBufferBytes * 2, CHUNK_SAMPLES * 4))
                .build()

            require(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "麦克风初始化失败"
            }

            recorder.startRecording()

            val pcm = ShortArray(CHUNK_SAMPLES)
            var previousText = ""

            while (listening.get()) {
                val count = recorder.read(pcm, 0, pcm.size)
                if (count <= 0) {
                    if (listening.get()) error("读取麦克风失败，错误码：$count")
                    break
                }

                val samples = FloatArray(count) { index -> pcm[index] / 32768.0f }
                stream.acceptWaveform(samples, SAMPLE_RATE)

                while (currentRecognizer.isReady(stream)) {
                    currentRecognizer.decode(stream)
                }

                val text = currentRecognizer.getResult(stream).text.trim()
                if (text.isNotEmpty() && text != previousText) {
                    previousText = text
                    listener.onPartialResult(text)
                }

                if (currentRecognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        finalText = text
                        listening.set(false)
                        break
                    }
                    currentRecognizer.reset(stream)
                }
            }

            if (finalText.isEmpty()) {
                stream.inputFinished()
                while (currentRecognizer.isReady(stream)) {
                    currentRecognizer.decode(stream)
                }
                finalText = currentRecognizer.getResult(stream).text.trim()
            }

            if (finalText.isNotEmpty()) {
                listener.onFinalResult(finalText)
            }
        } catch (error: Throwable) {
            listener.onError(error)
        } finally {
            listening.set(false)
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
