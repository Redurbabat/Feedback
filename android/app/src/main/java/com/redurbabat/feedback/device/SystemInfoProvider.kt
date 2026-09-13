package com.redurbabat.feedback.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.redurbabat.feedback.BuildConfig
import org.json.JSONObject

/** Non-sensitive system information allowed by protocol/PROTOCOL.md section 8.2. */
data class SystemInfoSnapshot(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersion: String,
    val batteryPercent: Int,
    val charging: Boolean,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
    val networkType: String,
    val deviceTime: String,
    val lastAgentActivity: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("osVersion", osVersion)
        .put("sdkInt", sdkInt)
        .put("appVersion", appVersion)
        .put("batteryPercent", batteryPercent)
        .put("charging", charging)
        .put("storageTotalBytes", storageTotalBytes)
        .put("storageFreeBytes", storageFreeBytes)
        .put("networkType", networkType)
        .put("deviceTime", deviceTime)
        .put("lastAgentActivity", lastAgentActivity)
}

class SystemInfoProvider(private val context: Context) {
    fun collect(nowEpochMillis: Long, lastAgentActivityEpochMillis: Long): SystemInfoSnapshot {
        val battery = batteryState()
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        return SystemInfoSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty().take(64).ifBlank { "Android" },
            model = Build.MODEL.orEmpty().take(128).ifBlank { "Android device" },
            osVersion = Build.VERSION.RELEASE.orEmpty().take(32).ifBlank {
                Build.VERSION.SDK_INT.toString()
            },
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME.take(32),
            batteryPercent = battery.first,
            charging = battery.second,
            storageTotalBytes = storage.totalBytes.coerceAtLeast(0L),
            storageFreeBytes = storage.availableBytes.coerceAtLeast(0L),
            networkType = networkType(),
            deviceTime = formatUtc(nowEpochMillis),
            lastAgentActivity = formatUtc(lastAgentActivityEpochMillis),
        )
    }

    private fun batteryState(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            0
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    private fun networkType(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "other"
        val network = manager.activeNetwork ?: return "none"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun formatUtc(epochMillis: Long): String {
        val instant = java.util.Date(epochMillis)
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formatter.format(instant)
    }
}
