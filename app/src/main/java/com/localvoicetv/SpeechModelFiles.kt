package com.localvoicetv

import java.io.File
import java.security.MessageDigest

/** Only the pinned, complete model may reach the native ONNX loader. */
internal object SpeechModelFiles {
    const val LEGACY = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23-mobile"
    data class Profile(val id: String, val description: String, val modelType: String, val hashes: Map<String, String>) {
        val directory get() = "speech-models/zipformer-zh-$id"
    }
    val profiles = listOf(
        Profile("2023", "中文平衡模型 · Zipformer 2023 INT8", "", mapOf(
            "encoder.int8.onnx" to "1870fad66d2d7b0d9ea8045859ddd32621d505d363b3242a9de13183809751cb",
            "decoder.onnx" to "67d37eedeb20aca87ea41682849649c174601b45e3dbdf16c2170de7f3b4b89d",
            "joiner.int8.onnx" to "7c00afc5450290d0e0df2bda461a20c6891110b9f111291396ed57559db8b4e9",
            "tokens.txt" to "6722bd1585f46f84456b29c3550a343a3cc375b971645773c02ed8e0b4e2405c",
        )),
        Profile("2025", "中文增强模型 · Zipformer 2025 INT8", "zipformer2", mapOf(
            "encoder.int8.onnx" to "5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f",
            "decoder.onnx" to "06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e",
            "joiner.int8.onnx" to "b34584dc6f561089e1d747fedebb3765f2caa72c927ef54d7ca55e5ae40a814b",
            "tokens.txt" to "6193c7ea1c96d0d9a1e9652789b40d13a8a913b434a5451e93158f5a09fd6652",
        )),
    )

    internal fun checkValid(directory: File, expected: Map<String, String>) {
        for ((name, hash) in expected) {
            val digest = MessageDigest.getInstance("SHA-256")
            File(directory, name).inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == hash) { "Model checksum mismatch: $name" }
        }
    }
}
