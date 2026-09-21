package ilab.iptv.player.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import ilab.iptv.player.core.model.EngineCapability

/**
 * Device capability probe (docs/02 §7.4: "capabilities 必须先经 AudioCapabilities +
 * AudioManager.getDevices() 探测后发布"). It reuses the S2 findings as-is — no new probing
 * behaviour was invented for P1-3.
 */
@OptIn(UnstableApi::class)
object Media3Capabilities {

    /** What Media3Engine can attempt before the device tells it otherwise (docs/02 §7.1). */
    val BASE: Set<EngineCapability> = setOf(
        EngineCapability.HLS,
        EngineCapability.HTTP_TS,
        EngineCapability.AUDIO_TRACK_SELECT,
        EngineCapability.ASPECT_RATIO,
    )

    /**
     * Compressed formats the HAL accepts untouched (S2: AC3/EAC3 both true on this device).
     * `internal` on purpose: `AudioCapabilities` is an unstable media3 type, so it must not leak
     * into this module's public API (lint `UnsafeOptInUsageError`).
     */
    internal fun passthroughFormats(capabilities: AudioCapabilities): Set<String> {
        val formats = mutableSetOf<String>()
        if (capabilities.isPassthroughPlaybackSupported(ac3())) formats += MimeTypes.AUDIO_AC3
        if (capabilities.isPassthroughPlaybackSupported(eac3())) formats += MimeTypes.AUDIO_E_AC3
        return formats
    }

    internal fun of(context: Context): Set<EngineCapability> =
        capsOf(passthroughFormats(AudioCapabilities.getCapabilities(context)))

    /** Pure half of the probe — the part a unit test can pin down. */
    fun capsOf(passthrough: Set<String>): Set<EngineCapability> {
        val caps = BASE.toMutableSet()
        if (MimeTypes.AUDIO_AC3 in passthrough || MimeTypes.AUDIO_E_AC3 in passthrough) {
            caps += EngineCapability.AC3_PASSTHROUGH
        }
        return caps
    }

    // `AudioCapabilities.isPassthroughPlaybackSupported` takes a media3 `Format` (verified against
    // the 1.6.1 AAR), not an `AudioFormat` — hence the plain Format builders below.
    private fun ac3(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AC3)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

    private fun eac3(): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()
}
