package com.localvoicetv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

/** Handles only voice/assistant keys; does not read window content or other keys. */
class RemoteVoiceKeyService : AccessibilityService() {
    override fun onServiceConnected() {
        Log.i("RemoteVoiceKey", "Key service connected")
    }

    public override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!RemoteVoiceKeys.accepts(event.keyCode)) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
        val down = event.action == KeyEvent.ACTION_DOWN
        Log.i("RemoteVoiceKey", "Voice key ${if (down) "down" else "up"}")
        if (!RemoteVoiceKeys.dispatch(down) && down) {
            // A cold start opens the screen; it never starts a delayed recording after key-up.
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { RemoteVoiceKeys.dispatch(false) }
    override fun onDestroy() {
        RemoteVoiceKeys.dispatch(false)
        super.onDestroy()
    }
}

internal object RemoteVoiceKeys {
    interface Listener { fun onRemoteVoiceKey(down: Boolean) }
    private var listener = WeakReference<Listener>(null)
    var hardwarePressed = false
        private set
    private var hardwareDelivered = false
    fun accepts(code: Int) = code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_ASSIST
    fun attach(value: Listener) {
        listener = WeakReference(value)
        deliverHardwarePress()
    }
    fun detach(value: Listener) {
        if (listener.get() === value) {
            endHardwarePress()
            listener.clear()
        }
    }
    // Called on the main thread by the OEM service and the Activity lifecycle.
    // A pending down is delivered once only; a release before screen creation cancels it.
    fun beginHardwarePress(): Boolean {
        if (hardwarePressed) return false
        hardwarePressed = true
        hardwareDelivered = false
        deliverHardwarePress()
        return true
    }
    fun endHardwarePress() {
        val notify = hardwarePressed && hardwareDelivered
        hardwarePressed = false
        hardwareDelivered = false
        if (notify) dispatch(false)
    }
    private fun deliverHardwarePress() {
        if (hardwarePressed && !hardwareDelivered && listener.get() != null) {
            hardwareDelivered = true
            dispatch(true)
        }
    }
    fun dispatch(down: Boolean): Boolean {
        val target = listener.get() ?: return false
        target.onRemoteVoiceKey(down)
        return true
    }
}
