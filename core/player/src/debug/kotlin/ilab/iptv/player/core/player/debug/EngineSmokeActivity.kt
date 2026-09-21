package ilab.iptv.player.core.player.debug

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.PlaybackEvent
import ilab.iptv.player.core.model.PlaybackRequest
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.player.AspectRatioPlan
import ilab.iptv.player.core.player.EngineTuning
import ilab.iptv.player.core.player.Media3Engine
import ilab.iptv.player.core.player.PlaybackEventLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only harness for [Media3Engine] (docs/05 section 12). It drives the production engine - same
 * class, same dispatcher contract, same event stream - from the command line, so the numbers in the
 * P1-3 report come from the real engine rather than from a copy of it.
 *
 * adb:
 *   adb shell am start -n ilab.iptv.player/ilab.iptv.player.core.player.debug.EngineSmokeActivity \
 *     -e tag s1 -e names "a,b,c" -e urls "https://...,https://..." [-e mode smoke|switch] [-e switches 30]
 *
 * Results: `files/engine-<tag>.json` (also readable with `adb shell run-as`) plus the same log lines
 * the production controller will emit (tag `IPTV/PLAYER`).
 */
class EngineSmokeActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var engineThread: HandlerThread
    private lateinit var engine: Media3Engine
    private lateinit var telemetry: PlaybackEventLogger
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var tag = "smoke"
    private var mode = "smoke"
    private var gapMs = 700L
    private var timeoutMs = 15_000L
    private var switches = 0
    private var samples: List<Sample> = emptyList()
    private val results = JSONArray()
    private var aspectRatios = JSONArray()
    private var pssBeforeKb = 0
    private var started = false

    @Volatile
    private var surfaceValid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tag = intent.getStringExtra("tag") ?: "smoke"
        mode = intent.getStringExtra("mode") ?: "smoke"
        gapMs = (intent.getStringExtra("gapMs") ?: "700").toLong()
        timeoutMs = (intent.getStringExtra("timeoutMs") ?: "15000").toLong()
        switches = (intent.getStringExtra("switches") ?: "0").toInt()
        samples = parseSamples()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)

        engineThread = HandlerThread("engine-smoke").apply { start() }
        val dispatcher = Handler(engineThread.looper).asCoroutineDispatcher()
        engine = Media3Engine(applicationContext, dispatcher, EngineTuning.LIVE_DEFAULT)
        telemetry = PlaybackEventLogger(LogcatLogger(), sessionId = "play-smoke-$tag")
        Log.i(TAG, "SMOKE_START tag=$tag mode=$mode samples=${samples.size} switches=$switches " +
            "caps=${engine.capabilities.map { it.name }.sorted()}")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        engine.attach(holder.surface)
        surfaceValid = true
        Log.i(TAG, "SURFACE_CREATED valid=${holder.surface.isValid} started=$started")
        if (!started) {
            started = true
            scope.launch { run() }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "SURFACE_CHANGED ${width}x$height valid=${holder.surface.isValid} format=$format")
        surfaceValid = holder.surface.isValid
        if (started) engine.attach(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "SURFACE_DESTROYED")
        surfaceValid = false
        engine.attach(null)
    }

    private suspend fun run() {
        scope.launch {
            // The production controller logs these events; the harness logs them the same way.
            engine.events.collect { event -> telemetry.onEvent(engine.id, event, engine.snapshot()) }
        }
        // The TV tears down and re-creates the SurfaceView surface while the window settles. Wait for
        // one that stays alive for SURFACE_SETTLE_MS so the numbers measure the engine, not that churn
        // (the engine itself is correct: with no surface there is no frame, so no first frame is
        // reported until one exists).
        awaitStableSurface()
        pssBeforeKb = pssKb()

        val plan = if (mode == "switch" && switches > 0) cycle(switches) else samples
        plan.forEachIndexed { index, sample -> runSample(sample, index + 1) }

        recordAspectRatios()
        finishRun()
    }

    private suspend fun awaitStableSurface() {
        while (!surfaceValid) delay(100)
        var stableSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - stableSince < SURFACE_SETTLE_MS) {
            delay(100)
            if (!surfaceValid) {
                while (!surfaceValid) delay(100)
                stableSince = SystemClock.uptimeMillis()
            }
        }
    }

    private suspend fun runSample(sample: Sample, runIndex: Int) {
        val request = PlaybackRequest(
            channelId = sample.channelId,
            stream = sample.toStream(),
            timeoutMs = timeoutMs,
            preferPassthrough = intent.getStringExtra("passthrough") != "false",
            sessionId = "play-smoke-$tag",
        )
        telemetry.onPrepareStart(engine.id, request, attempt = 1)

        val firstFrame = scope.async {
            withTimeoutOrNull(timeoutMs + 5_000) {
                engine.events.first { it is PlaybackEvent.FirstFrame } as PlaybackEvent.FirstFrame
            }
        }
        val startedAt = SystemClock.uptimeMillis()
        val prepared = engine.prepare(request)
        val readyMs = SystemClock.uptimeMillis() - startedAt
        val snapshot = engine.snapshot()

        val entry = JSONObject()
            .put("run", runIndex)
            .put("name", sample.name)
            .put("host", hostOf(sample.url))
            .put("readyMs", readyMs)
            .put("engineState", engine.state.value.name)
            .put("vcodec", snapshot.videoCodec)
            .put("acodec", snapshot.audioCodec)
            .put("w", snapshot.width)
            .put("h", snapshot.height)
            .put("audioPath", snapshot.audioPath.name)
            .put("audioTracks", engine.audioTracks().size)
            .put("selectedAudioTrack", engine.selectedAudioTrackId())

        when (prepared) {
            is AppResult.Ok -> {
                val frame = firstFrame.await()
                entry.put("outcome", "ok")
                    .put("firstFrameMs", frame?.costMs ?: -1)
                    .put("isLive", prepared.value.isLive)
                    .put("preparedStreamId", prepared.value.streamId)
            }

            is AppResult.Err -> {
                firstFrame.cancel()
                entry.put("outcome", "error")
                    .put("failure", prepared.error.failure.name)
                    .put("httpStatus", prepared.error.httpStatus)
                    .put("detail", prepared.error.detail)
                telemetry.onPrepareFail(engine.id, prepared.error, attempt = 1)
            }
        }
        results.put(entry)
        Log.i(TAG, "SMOKE_SAMPLE $entry")

        if (mode == "switch") {
            // S5 path through the production engine: stop() then prepare() on the same instance.
            engine.stop()
        }
        delay(gapMs)
    }

    private fun recordAspectRatios() {
        val plans = JSONArray()
        AspectRatioMode.entries.forEach { ratio ->
            engine.setAspectRatio(ratio)
            val plan = AspectRatioPlan.of(ratio)
            plans.put(
                JSONObject()
                    .put("mode", ratio.name)
                    .put("applied", engine.aspectRatio().name)
                    .put("scaleMode", plan.scaleMode.name)
                    .put("fixedAspectRatio", plan.fixedAspectRatio ?: JSONObject.NULL),
            )
        }
        aspectRatios = plans
    }

    private suspend fun finishRun() {
        val pssAfterKb = pssKb()
        val summary = JSONObject()
            .put("tag", tag)
            .put("mode", mode)
            .put("device", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
            .put("engineId", engine.id)
            .put("capabilities", JSONArray(engine.capabilities.map { it.name }.sorted()))
            .put("entries", results.length())
            .put("pssBeforeKb", pssBeforeKb)
            .put("pssAfterKb", pssAfterKb)
            .put("pssDeltaKb", pssAfterKb - pssBeforeKb)
            .put("aspectRatios", aspectRatios)
            .put("samples", results)
        val file = File(filesDir, "engine-$tag.json")
        file.writeText(summary.toString(1))
        Log.i(TAG, "SMOKE_DONE tag=$tag entries=${results.length()} " +
            "pssDeltaKb=${pssAfterKb - pssBeforeKb} file=${file.absolutePath}")

        engine.release()
        telemetry.onEngineRelease(engine.id)
        delay(300)
        engineThread.quitSafely()
        finish()
    }

    private fun parseSamples(): List<Sample> {
        val urls = (intent.getStringExtra("urls") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val names = (intent.getStringExtra("names") ?: "").split(",").map { it.trim() }
        val audioCodecs = (intent.getStringExtra("acodecs") ?: "").split(",").map { it.trim() }
        return urls.mapIndexed { index, url ->
            Sample(
                channelId = (index + 1).toLong(),
                name = names.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "sample-${index + 1}",
                url = url,
                audioCodecHint = audioCodecs.getOrNull(index)?.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun cycle(count: Int): List<Sample> {
        if (samples.isEmpty()) return emptyList()
        return (0 until count).map { samples[it % samples.size] }
    }

    private fun pssKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { engine.release() }
        engineThread.quitSafely()
    }

    private data class Sample(
        val channelId: Long,
        val name: String,
        val url: String,
        val audioCodecHint: String?,
    ) {
        fun toStream() = Stream(
            id = channelId,
            channelId = channelId,
            url = url,
            userAgent = null,
            referrer = null,
            sourceId = "smoke",
            quality = null,
            videoCodec = null,
            audioCodec = audioCodecHint,
            width = 0,
            height = 0,
            score = 0,
            priority = 0,
            lastOkAtMs = null,
            lastCheckAtMs = null,
            failCount = 0,
            lastError = null,
            disabled = false,
        )
    }

    /** Minimal `Logger` for the harness: same tag/format the logcat sink uses (docs/03 section 5). */
    private class LogcatLogger : Logger {
        private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        override fun log(event: LogEvent) =
            emit(levelName(event.level), event.category, event.code, event.message, event.fields)

        override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            emit("V", category, code, message, fields)

        override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            emit("D", category, code, message, fields)

        override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            emit("I", category, code, message, fields)

        override fun w(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = emit("W", category, code, message, fields)

        override fun e(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = emit("E", category, code, message, fields)

        override fun flush(timeoutMs: Long) = Unit

        private fun levelName(level: LogLevel) = when (level) {
            LogLevel.VERBOSE -> "V"
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
            LogLevel.FATAL -> "F"
        }

        private fun emit(level: String, category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
            val body = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
            Log.println(priorityOf(level), "IPTV/$category", "${time.format(Date())} $code $message $body")
        }

        private fun priorityOf(level: String) = when (level) {
            "V" -> Log.VERBOSE
            "D" -> Log.DEBUG
            "I" -> Log.INFO
            "W" -> Log.WARN
            "E" -> Log.ERROR
            else -> Log.ASSERT
        }
    }

    companion object {
        private const val TAG = "IPTV/SMOKE"
        private const val SURFACE_SETTLE_MS = 800L

        /** Keeps real stream URLs out of the result file - only the host is reported (docs/03 section 11). */
        fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: "?" }.getOrDefault("?")
    }
}
