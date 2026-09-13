package com.redurbabat.feedback.pairing

import android.os.Build
import com.redurbabat.feedback.BuildConfig
import com.redurbabat.feedback.protocol.ProtocolConstants

data class DeviceMetadata(
    val deviceName: String,
    val platform: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
)

object AndroidDeviceMetadataProvider {
    fun current(): DeviceMetadata {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val rawName = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android-Gerät" }
        return DeviceMetadata(
            deviceName = rawName.take(ProtocolConstants.DEVICE_NAME_MAX),
            platform = "android",
            osVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { Build.VERSION.SDK_INT.toString() },
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}
