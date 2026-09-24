package com.stv.voice

import android.content.Intent
import android.os.Looper
import com.localvoicetv.SherpaSpeechRecognizer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class WindowServiceTest {
    private val originalEngine = WindowService.engineFactory
    private val originalFeedback = WindowService.feedbackFactory
    private lateinit var listener: SherpaSpeechRecognizer.Listener
    private var starts = 0
    private var stops = 0
    private var releases = 0
    private val messages = mutableListOf<String>()
    private lateinit var controller: org.robolectric.android.controller.ServiceController<WindowService>
    private lateinit var service: WindowService
    @Before fun setup() {
        WindowService.engineFactory = { _, callbacks ->
            listener = callbacks
            object : VoiceEngine {
                override fun initialize() {}
                override fun start() { starts++ }
                override fun stop() { stops++ }
                override fun release() { releases++ }
            }
        }
        WindowService.feedbackFactory = {
            object : VoiceFeedback {
                override fun show(text: String, duration: Long) { messages += text }
                override fun hide() { messages += "HIDDEN" }
            }
        }
        controller = Robolectric.buildService(WindowService::class.java)
        service = controller.create().get()
    }
    @After fun cleanup() {
        controller.destroy()
        WindowService.engineFactory = originalEngine
        WindowService.feedbackFactory = originalFeedback
    }
    private fun send(action: String?) = service.onStartCommand(action?.let { Intent(it) }, 0, 1)
    private fun flush() { shadowOf(Looper.getMainLooper()).idle() }
    private fun ready() { listener.onModelReady(); flush() }

    @Test fun coldStartNeverRecordsAfterReleaseOrOpensActivity() {
        send(WindowService.OPEN_MIC)
        send(WindowService.CLOSE_MIC)
        ready()
        assertEquals(0, starts)
        assertNull(shadowOf(service).nextStartedActivity)
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }
    @Test fun repeatedDownStartsOnceAndReleaseStops() {
        ready()
        send(WindowService.OPEN_MIC)
        send(WindowService.OPEN_MIC)
        send(WindowService.CLOSE_MIC)
        send(WindowService.CLOSE_MIC)
        assertEquals(1, starts)
        assertEquals(1, stops)
        assertNull(shadowOf(service).nextStartedActivity)
    }
    @Test fun lostReleaseStopsAtTimeout() {
        ready(); send(WindowService.OPEN_MIC)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WindowService.MAX_PRESS_MS))
        assertEquals(1, stops)
    }
    @Test fun pendingFinalBlocksNewCaptureAndResultIsNotOverwrittenByStop() {
        ready(); send(WindowService.OPEN_MIC); send(WindowService.CLOSE_MIC)
        send(WindowService.OPEN_MIC)
        assertEquals(1, starts)
        listener.onFinalResult("一个不存在的指令测试")
        listener.onListeningChanged(false)
        flush()
        assertTrue(messages.last().contains("请换个说法"))
        assertFalse(messages.last().contains("没有听清"))
    }
    @Test fun orphanReleaseDoesNotStartAnything() {
        send(WindowService.CLOSE_MIC)
        assertEquals(android.app.Service.START_NOT_STICKY, send(null))
        assertEquals(0, starts)
        assertEquals(0, stops)
    }
    @Test fun idleServiceStopsAndDestroyReleasesEngine() {
        ready()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WindowService.IDLE_MS))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
    @Test fun callbacksAfterDestroyCannotDisplayOrExecute() {
        service.onDestroy()
        val count = messages.size
        listener.onFinalResult("返回主页")
        listener.onModelReady()
        flush()
        assertEquals(count, messages.size)
        assertNull(shadowOf(service).nextStartedActivity)
        assertEquals(1, releases)
        // onDestroy is idempotent, including controller cleanup.
    }

    @Test fun screenOffStopsCaptureAndDiscardsLateFinal() {
        ready(); send(WindowService.OPEN_MIC)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        flush()
        val count = messages.size
        listener.onFinalResult("返回主页")
        listener.onListeningChanged(false)
        flush()
        assertEquals(1, stops)
        assertEquals(count, messages.size)
        assertNull(shadowOf(service).nextStartedActivity)
    }
}
