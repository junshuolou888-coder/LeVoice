package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcmQueueTest {
    @Test fun releaseKeepsAllCapturedAudioInOrderIncludingTail() {
        val queue = PcmQueue(4)
        queue.offer(shortArrayOf(1, 2)); queue.offer(shortArrayOf(3))
        queue.close()
        assertArrayEquals(shortArrayOf(1, 2), queue.take())
        assertArrayEquals(shortArrayOf(3), queue.take())
        assertNull(queue.take())
    }
    @Test fun slowDecoderDoesNotBlockCaptureAndOverflowIsExplicit() {
        val queue = PcmQueue(2)
        queue.offer(shortArrayOf(1)); queue.offer(shortArrayOf(2))
        assertThrows(IllegalStateException::class.java) { queue.offer(shortArrayOf(3)) }
        assertArrayEquals(shortArrayOf(1), queue.take())
        assertArrayEquals(shortArrayOf(2), queue.take())
    }
    @Test fun emptyCaptureCloseWakesWaitingDecoder() {
        val queue = PcmQueue()
        val done = CountDownLatch(1)
        val thread = Thread { if (queue.take() == null) done.countDown() }.also { it.start() }
        queue.close()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        thread.join()
    }
    @Test fun captureFailureIsNotTreatedAsSuccessfulPartialSpeech() {
        val queue = PcmQueue()
        queue.offer(shortArrayOf(1))
        queue.close(IllegalStateException("capture failed"))
        assertThrows(IllegalStateException::class.java) { queue.take() }
    }
}
