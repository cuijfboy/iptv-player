package ilab.iptv.player.core.player

import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.EngineCapability

/**
 * docs/02 §7.1: "按 `capabilities ⊇ required` 选第一个可用工厂；默认仅注册 Media3".
 *
 * The frozen `EngineSelector.select` signature returns a non-null factory, but §7.1 also requires
 * "找不到满足能力的引擎 → `AppError.capability(...)`, 不静默降级". Callers therefore use
 * [selectOrNull] and turn `null` into that error; [select] exists only to satisfy the frozen
 * interface and throws rather than silently picking a lesser engine.
 */
class DefaultEngineSelector(factories: List<PlayerEngineFactory>) : EngineSelector {

    private val factories: List<PlayerEngineFactory> = factories.toList()

    override fun select(required: Set<EngineCapability>, device: DeviceProfile): PlayerEngineFactory =
        selectOrNull(required, device)
            ?: throw NoSuchElementException("no registered engine satisfies $required (device=${device.abi}/sdk${device.sdk})")

    fun selectOrNull(required: Set<EngineCapability>, device: DeviceProfile): PlayerEngineFactory? {
        // `device` is part of the frozen signature; today the factory set is static (Media3 only),
        // so the device narrows the runtime capabilities instead of the factory list.
        return factories.firstOrNull { it.caps.containsAll(required) }
    }

    fun registered(): List<PlayerEngineFactory> = factories
}
