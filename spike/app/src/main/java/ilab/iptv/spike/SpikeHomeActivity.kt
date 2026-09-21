package ilab.iptv.spike

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.json.JSONArray
import org.json.JSONObject

/**
 * S6 fixture + the manual control surface for the other spikes.
 *
 * Cold start (docs/02 §8.5): start = Process.getStartUptimeMillis(), end = first frame of the home
 * screen, and optionally end = first rendered video frame when `autoplayIdx` is supplied.
 *
 * adb:
 *   am start -W -n ilab.iptv.player.spike/.SpikeHomeActivity -e autoplayIdx 1 -e tag cold1
 *   am start -n ilab.iptv.player.spike/.SpikeHomeActivity -e probe true -e finish true
 */
@OptIn(UnstableApi::class)
class SpikeHomeActivity : Activity() {

    private var coldLogged = false
    private var autoplayIdx = -1
    private var tag = "home"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        autoplayIdx = (intent.getStringExtra("autoplayIdx") ?: "-1").toInt()
        tag = intent.getStringExtra("tag") ?: "home"

        setContentView(buildUi())

        val processStart = Process.getStartUptimeMillis()
        Log.i(SpikeIo.TAG, "COLD_PROCESS_START tag=$tag processStartUptimeMs=$processStart")
        window.decorView.viewTreeObserver.addOnPreDrawListener(object :
            android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (coldLogged) return true
                coldLogged = true
                val now = SystemClock.uptimeMillis()
                Log.i(SpikeIo.TAG, "COLD_HOME_FIRST_FRAME tag=$tag " +
                    "sinceProcessStartMs=${now - processStart}")
                reportFullyDrawn()
                Log.i(SpikeIo.TAG, "COLD_FULLY_DRAWN tag=$tag " +
                    "sinceProcessStartMs=${SystemClock.uptimeMillis() - processStart}")
                if (autoplayIdx > 0) {
                    startActivity(Intent(this@SpikeHomeActivity, PlaybackSpikeActivity::class.java)
                        .putExtra("mode", "s1")
                        .putExtra("onlyIdx", autoplayIdx.toString())
                        .putExtra("coldStart", "true")
                        .putExtra("tag", "cold$tag"))
                }
                return true
            }
        })

        if (intent.getStringExtra("probe") == "true") {
            dumpDevice()
            if (intent.getStringExtra("finish") == "true") finish()
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        root.addView(TextView(this).apply {
            text = "IPTV Spike fixture (throwaway)"
            textSize = 22f
        })
        fun button(label: String, intent: Intent) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { startActivity(intent) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button("S1 · Media3 20 频道", playback("s1", "s1manual"))
        button("S2 · AAC 软解", playback("s2", "s2aac", extra = mapOf("s2key" to "aac")))
        button("S2 · MP2 软解", playback("s2", "s2mp2", extra = mapOf("s2key" to "mp2")))
        button("S2 · AC3 软解", playback("s2", "s2ac3", extra = mapOf("s2key" to "ac3")))
        button("S2 · AC3 透传", playback("s2", "s2ac3pt", extra = mapOf("s2key" to "ac3",
            "passthrough" to "true")))
        button("S5 · 换台 30 次(复用)", playback("s5", "s5reuse",
            extra = mapOf("switches" to "30")))
        button("S5 · 换台 10 次(重建)", playback("s5", "s5recreate",
            extra = mapOf("switches" to "10", "recreate" to "true")))
        button("S3 · EPG 网格 658x6h", Intent(this, EpgGridSpikeActivity::class.java)
            .putExtra("tag", "manual"))
        button("设备能力探测", Intent(this, SpikeHomeActivity::class.java)
            .putExtra("probe", "true"))
        root.addView(ScrollView(this).apply {
            addView(TextView(this@SpikeHomeActivity).apply {
                text = "结果写至 /sdcard/Android/data/ilab.iptv.player.spike/files/ 并打 SPIKE 标签日志"
                gravity = Gravity.START
            })
        })
        return root
    }

    private fun playback(mode: String, runTag: String, extra: Map<String, String> = emptyMap()):
        Intent {
        val intent = Intent(this, PlaybackSpikeActivity::class.java)
            .putExtra("mode", mode)
            .putExtra("tag", runTag)
        extra.forEach { (k, v) -> intent.putExtra(k, v) }
        return intent
    }

    /** Device capability probe: what this TV can actually decode, and what it can pass through. */
    private fun dumpDevice() {
        val json = JSONObject()
            .put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abilist", Build.SUPPORTED_ABIS.toList().toString())

        val decoders = JSONArray()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            val types = info.supportedTypes.filter { it.startsWith("audio/") || it.startsWith("video/") }
            if (types.isEmpty()) continue
            decoders.put(JSONObject()
                .put("name", info.name)
                .put("hardwareAccelerated", info.isHardwareAccelerated)
                .put("softwareOnly", info.isSoftwareOnly)
                .put("types", types.toList().toString()))
        }
        json.put("decoders", decoders)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = JSONArray()
        for (d in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val entry = JSONObject()
                .put("type", d.type)
                .put("typeName", deviceTypeName(d.type))
                .put("productName", d.productName?.toString() ?: "")
                .put("isSink", d.isSink)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                entry.put("encodings", d.encodings.toList().toString())
                entry.put("sampleRates", d.sampleRates.toList().toString())
                entry.put("channelCounts", d.channelCounts.toList().toString())
            }
            devices.put(entry)
        }
        json.put("audioOutputDevices", devices)

        val caps = AudioCapabilities.getCapabilities(this)
        json.put("audioCapabilitiesToString", caps.toString())
        val passthrough = JSONObject()
        val probes = mapOf(
            "aac 2ch" to Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setChannelCount(2).setSampleRate(48_000).build(),
            "mp2 2ch" to Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG)
                .setChannelCount(2).setSampleRate(48_000).build(),
            "ac3 6ch" to Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setChannelCount(6).setSampleRate(48_000).build(),
            "ac3 2ch" to Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setChannelCount(2).setSampleRate(48_000).build(),
            "eac3 6ch" to Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3)
                .setChannelCount(6).setSampleRate(48_000).build(),
        )
        for ((label, format) in probes) {
            passthrough.put(label, caps.isPassthroughPlaybackSupported(format))
        }
        json.put("passthroughSupportedByMedia3", passthrough)

        SpikeIo.write(this, "device.json", json.toString(1))
        Log.i(SpikeIo.TAG, "DEVICE_PROBE ${json.toString()}")
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "line_analog"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line_digital"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
        else -> "type_$type"
    }
}
