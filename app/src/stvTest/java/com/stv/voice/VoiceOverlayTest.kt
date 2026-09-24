package com.stv.voice

import android.content.Context
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceOverlayTest {
    @Test fun passiveBottomRightOverlayUpdatesAndDismissesWithoutTakingFocus() {
        val context = RuntimeEnvironment.getApplication()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val windows = Shadow.extract<ShadowWindowManagerImpl>(wm)
        val overlay = VoiceOverlay(context)
        overlay.show("正在听…", 1000)
        val view = windows.views.single() as TextView
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals(Gravity.BOTTOM or Gravity.RIGHT, params.gravity)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        overlay.show("识别结果", 2000)
        assertEquals(1, windows.views.size)
        assertEquals("识别结果", view.text.toString())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertEquals(1, windows.views.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
        assertTrue(windows.views.isEmpty())
    }
}
