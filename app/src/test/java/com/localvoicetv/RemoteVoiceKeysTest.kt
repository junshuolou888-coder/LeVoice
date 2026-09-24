package com.localvoicetv

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RemoteVoiceKeysTest {
    private val events = mutableListOf<Boolean>()
    private val listener = object : RemoteVoiceKeys.Listener {
        override fun onRemoteVoiceKey(down: Boolean) { events += down }
    }
    @After fun cleanup() { RemoteVoiceKeys.detach(listener) }

    @Test fun holdingVoiceKeyStartsOnceAndReleasingStops() {
        RemoteVoiceKeys.attach(listener)
        val service = Robolectric.buildService(RemoteVoiceKeyService::class.java).get()
        assertTrue(service.onKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST, 0)))
        assertTrue(service.onKeyEvent(KeyEvent(0, 200, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST, 1)))
        assertTrue(service.onKeyEvent(KeyEvent(0, 300, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOICE_ASSIST, 0)))
        assertEquals(listOf(true, false), events)
    }

    @Test fun navigationAndVolumeKeysAreNotConsumed() {
        RemoteVoiceKeys.attach(listener)
        val service = Robolectric.buildService(RemoteVoiceKeyService::class.java).get()
        for (key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertFalse(service.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)))
        }
        assertTrue(events.isEmpty())
    }

    @Test fun disconnectedScreenCannotReceiveRecordingRequest() {
        RemoteVoiceKeys.attach(listener)
        RemoteVoiceKeys.detach(listener)
        assertFalse(RemoteVoiceKeys.dispatch(true))
        assertTrue(events.isEmpty())
    }
}
