package ilab.iptv.player.core.player

import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.EngineCapability
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.Test

/** docs/02 §7.1 selection: first factory whose caps contain the required set. */
class DefaultEngineSelectorTest {

    private val device = DeviceProfile(
        abi = "armeabi-v7a",
        sdk = 31,
        ramMb = 3_072,
        audioPassthrough = setOf(MimeTypes.AUDIO_AC3),
        maxWidth = 1_920,
        maxHeight = 1_080,
        maxFrameRate = 60f,
    )

    private val media3 = fakeFactory(Media3EngineFactory.ID, setOf(EngineCapability.HLS, EngineCapability.HTTP_TS))
    private val extra = fakeFactory("extra", setOf(EngineCapability.RTSP))
    private val selector = DefaultEngineSelector(listOf(media3, extra))

    @Test
    fun `picks the first factory that covers the requirement`() {
        assertThat(selector.select(setOf(EngineCapability.HLS), device)).isSameInstanceAs(media3)
        assertThat(selector.select(setOf(EngineCapability.HTTP_TS), device)).isSameInstanceAs(media3)
    }

    @Test
    fun `an empty requirement takes the first registered factory`() {
        assertThat(selector.select(emptySet(), device)).isSameInstanceAs(media3)
    }

    @Test
    fun `a partial cover is not a match`() {
        assertThat(selector.selectOrNull(setOf(EngineCapability.HLS, EngineCapability.RTSP), device)).isNull()
    }

    @Test
    fun `no match throws instead of silently downgrading`() {
        val empty = DefaultEngineSelector(emptyList())
        val thrown = runCatching { empty.select(setOf(EngineCapability.HLS), device) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `registered exposes the factory list`() {
        assertThat(selector.registered()).containsExactly(media3, extra).inOrder()
    }

    private fun fakeFactory(id: String, caps: Set<EngineCapability>) = object : PlayerEngineFactory {
        override val id: String = id
        override val caps: Set<EngineCapability> = caps
        override fun create(dispatcher: CoroutineDispatcher): PlayerEngine = error("not needed in the unit test")
    }
}
