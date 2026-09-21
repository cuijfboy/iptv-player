package ilab.iptv.player.core.log

import android.content.Context
import android.os.Build

/**
 * Device / build facts used by the log skeleton (docs/03 §7.1 "设备/应用" block, minimal P0-6
 * subset) and by the `APP_START` event fields. Kept in one place so the console and the startup
 * event cannot drift apart.
 */
object DeviceInfo {

    /** e.g. `Sony BRAVIA_VH21 / Android 12 (API 31) / ABI armeabi-v7a`. */
    fun deviceSummary(): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" / Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        append(" / ABI ").append(Build.SUPPORTED_ABIS.joinToString(","))
    }

    /** e.g. `0.1.0 (1)`; `unknown` if the package cannot be read. */
    @Suppress("DEPRECATION")
    fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (e: Exception) {
        "unknown"
    }

    fun maxMemoryMb(): Long = Runtime.getRuntime().maxMemory() / BYTES_PER_MB

    fun usedMemoryMb(): Long =
        (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / BYTES_PER_MB

    private const val BYTES_PER_MB = 1024L * 1024L
}
