package com.localvoicetv

import android.media.AudioDeviceInfo
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StvAudioInputTest {
    private fun device(type: Int, address: String): AudioDeviceInfo {
        val device = AudioDeviceInfoBuilder.newBuilder().setType(type).build()
        val port = ReflectionHelpers.getField<Any>(device, "mPort")
        ReflectionHelpers.setField(port, "mAddress", address)
        return device
    }

    @Test fun usbReceiverDoesNotOverrideRemoteInput() {
        val usb = device(AudioDeviceInfo.TYPE_USB_DEVICE, "card=1;device=0;")
        val remote = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom")
        val otherMic = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "back")
        assertSame(remote, StvAudioInput.select(listOf(usb, otherMic, remote)))
        assertSame(remote, StvAudioInput.select(listOf(remote, usb, otherMic)))
    }

    @Test(expected = IllegalStateException::class)
    fun missingRemoteDoesNotSilentlyFallBackToUsb() {
        StvAudioInput.select(listOf(device(AudioDeviceInfo.TYPE_USB_DEVICE, "card=1;device=0;")))
    }

    @Test(expected = IllegalStateException::class)
    fun ambiguousRemoteDevicesAreRejected() {
        StvAudioInput.select(List(2) { device(AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom") })
    }
}
