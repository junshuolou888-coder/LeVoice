package com.localvoicetv

import android.media.AudioDeviceInfo
import android.os.Build

/** This firmware exposes the remote's BLE audio as a built-in microphone at "bottom". */
internal object StvAudioInput {
    fun select(devices: List<AudioDeviceInfo>): AudioDeviceInfo {
        val builtIn = devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val candidates = if (Build.VERSION.SDK_INT >= 28) {
            builtIn.filter { it.address == "bottom" }
        } else builtIn
        return candidates.singleOrNull()
            ?: error("未找到唯一的遥控器录音设备，请检查电视蓝牙连接")
    }
}
