package com.wearsic.app.media

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings

object AudioOutputHelper {

    data class OutputInfo(
        val name: String,
        val isBluetooth: Boolean
    )

    fun getCurrentOutputInfo(context: Context): OutputInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return OutputInfo("Watch Speaker", false)

        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }

        for (device in devices) {
            if (device == null) continue
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                    val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
                    return OutputInfo(productName ?: "Bluetooth Audio", true)
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET -> {
                    return OutputInfo("Headphones", true)
                }
                else -> Unit
            }
        }
        return OutputInfo("Watch Speaker", false)
    }

    fun createBluetoothSettingsIntent(): Intent {
        return Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }
}
