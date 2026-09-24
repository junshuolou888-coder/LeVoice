package com.localvoicetv

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One capture producer, one decoder consumer; close retains every queued sample. */
internal class PcmQueue(capacity: Int = 320) {
    private val chunks = ArrayBlockingQueue<ShortArray>(capacity)
    @Volatile private var closed = false
    @Volatile private var failure: Throwable? = null
    val pendingSamples: Int get() = chunks.sumOf { it.size }

    fun offer(samples: ShortArray) {
        check(!closed) { "录音已结束" }
        check(chunks.offer(samples)) { "语音处理跟不上，请说短一点后再试" }
    }
    fun close(error: Throwable? = null) { failure = error; closed = true }
    fun take(): ShortArray? {
        while (true) {
            failure?.let { throw it }
            chunks.poll(20, TimeUnit.MILLISECONDS)?.let { return it }
            if (closed && chunks.isEmpty()) {
                failure?.let { throw it }
                return null
            }
        }
    }
}
