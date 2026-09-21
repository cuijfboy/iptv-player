package ilab.iptv.player.core.model

/**
 * What the device can do, as probed at runtime (docs/02 §4.2, frozen shape).
 *
 * Its values are fed by the S1/S2 findings — codec/resolution caps and the audio formats the HAL
 * accepts — and must never be hard-coded per device model (§4.7). `EngineSelector` takes it as its
 * second argument, which is why P1-3 lands the type.
 */
data class DeviceProfile(
    val abi: String,
    val sdk: Int,
    val ramMb: Int,
    val audioPassthrough: Set<String>,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFrameRate: Float,
)
