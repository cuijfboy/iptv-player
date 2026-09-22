package ilab.iptv.player.feature.settings.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import ilab.iptv.player.core.log.DeviceInfo

/** The platform half of the 设备 block of docs/03 §7.1 — everything that needs a `Context`. */
data class DiagDeviceFacts(
    val deviceModel: String,
    val abi: String,
    val sdk: String,
    val usedMemoryMb: Long,
    val maxMemoryMb: Long,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val network: String,
)

/**
 * Reads the device facts for the overview and the export's `meta.json`.
 *
 * Android-only and therefore not unit-tested (a JVM test cannot answer `StatFs`); everything that
 * *interprets* these numbers lives in [DiagOverview], which is. Memory comes from
 * `core:log`'s [DeviceInfo] so the overview and the device console cannot drift apart.
 */
object AndroidDiagFacts {

    fun read(context: Context): DiagDeviceFacts {
        val storage = storage(context)
        return DiagDeviceFacts(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            abi = Build.SUPPORTED_ABIS.joinToString(","),
            sdk = "${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})",
            usedMemoryMb = DeviceInfo.usedMemoryMb(),
            maxMemoryMb = DeviceInfo.maxMemoryMb(),
            storageFreeBytes = storage.first,
            storageTotalBytes = storage.second,
            network = network(context),
        )
    }

    /** Available / total bytes of the volume the app writes to (external when it exists). */
    private fun storage(context: Context): Pair<Long, Long> {
        val root = context.getExternalFilesDir(null) ?: context.filesDir
        return try {
            val stat = StatFs(root.absolutePath)
            stat.availableBytes to stat.totalBytes
        } catch (t: Throwable) {
            // A device that refuses to stat its own directory still gets a panel, just without sizes.
            0L to 0L
        }
    }

    private fun network(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "未知"
        // `getActiveNetwork` is API 23; a TV on 21/22 keeps the panel and just says so instead of
        // forcing the deprecated `activeNetworkInfo` path into the module.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "未知（API ${Build.VERSION.SDK_INT}）"
        val active = manager.activeNetwork ?: return "未连接"
        val capabilities = manager.getNetworkCapabilities(active) ?: return "未知"
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WiFi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("有线")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("蜂窝")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val validated = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            "已验证"
        } else {
            "未验证"
        }
        return "${transports.ifEmpty { listOf("其他") }.joinToString("+")} / $validated"
    }
}
