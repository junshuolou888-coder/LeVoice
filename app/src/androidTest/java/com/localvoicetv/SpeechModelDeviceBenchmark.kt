package com.localvoicetv

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.*
import java.io.File

/** Uses reference WAVs, never the microphone; compare runtime on the actual TV. */
internal object SpeechModelDeviceBenchmark {
    fun run(context: Context, profile: String): String {
        require(profile in setOf("baseline", "2023", "2025")) { "Unknown profile: $profile" }
        val reference = File(context.getExternalFilesDir(null), "speech-models/zipformer-zh-2025/test_wavs")
        val selected = SpeechModelFiles.profiles.firstOrNull { it.id == profile }
        val base = File(context.getExternalFilesDir(null), selected?.directory ?: "speech-models/zipformer-zh-2025")
        val bundled = profile == BuildConfig.SPEECH_MODEL
        require(profile != "baseline" || bundled) { "Build baseline APK to benchmark baseline" }
        if (selected != null && !bundled) SpeechModelFiles.checkValid(base, selected.hashes)
        val modelDir = if (bundled) "speech-model" else base.path
        val baseline = profile == "baseline"
        val legacy = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile"
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
            modelConfig = OnlineModelConfig(
                transducer = if (baseline) OnlineTransducerModelConfig(
                    encoder = "$legacy/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$legacy/decoder-epoch-99-avg-1.onnx",
                    joiner = "$legacy/joiner-epoch-99-avg-1.int8.onnx",
                ) else OnlineTransducerModelConfig(
                    encoder = "$modelDir/encoder.int8.onnx",
                    decoder = "$modelDir/decoder.onnx",
                    joiner = "$modelDir/joiner.int8.onnx",
                ),
                tokens = if (baseline) "$legacy/tokens.txt" else "$modelDir/tokens.txt",
                modelType = selected?.modelType ?: "zipformer",
                modelingUnit = "cjkchar",
                numThreads = 2,
            ),
            decodingMethod = if (baseline) "modified_beam_search" else "greedy_search", maxActivePaths = 4,
            hotwordsScore = 8f, enableEndpoint = false,
        )
        val begin = SystemClock.elapsedRealtime()
        val recognizer = OnlineRecognizer(if (bundled) context.assets else null, config)
        val output = StringBuilder("profile=$profile loadMs=${SystemClock.elapsedRealtime() - begin}\n")
        try {
            val waves = reference.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.name }.orEmpty()
            check(waves.isNotEmpty()) { "Reference WAVs missing" }
            for (file in waves) {
                val wave = WaveReader.readWave(file.path)
                val stream = recognizer.createStream("")
                val start = SystemClock.elapsedRealtime()
                try {
                    var offset = 0
                    var maxChunkDecodeMs = 0L
                    val chunk = wave.sampleRate / 10
                    while (offset < wave.samples.size) {
                        val end = minOf(offset + chunk, wave.samples.size)
                        stream.acceptWaveform(wave.samples.copyOfRange(offset, end), wave.sampleRate)
                        val chunkStart = SystemClock.elapsedRealtime()
                        while (recognizer.isReady(stream)) recognizer.decode(stream)
                        maxChunkDecodeMs = maxOf(maxChunkDecodeMs, SystemClock.elapsedRealtime() - chunkStart)
                        offset = end
                    }
                    stream.acceptWaveform(FloatArray(wave.sampleRate / 2), wave.sampleRate)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text
                    check(text.isNotBlank()) { "Empty recognition for ${file.name}" }
                    val ms = SystemClock.elapsedRealtime() - start
                    val duration = wave.samples.size.toDouble() / wave.sampleRate
                    output.append("${file.name}: audioSeconds=$duration decodeMs=$ms RTF=${ms / 1000.0 / duration} maxChunkMs=$maxChunkDecodeMs text=$text\n")
                } finally { stream.release() }
            }
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            output.append("PSS_KB=${memory.totalPss}\n")
            return output.toString()
        } finally { recognizer.release() }
    }
}
