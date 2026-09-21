package ilab.iptv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Keeps `testDebugUnitTest` meaningful until real tests land (P1). */
class PlaceholderTest {
    @Test
    fun moduleGraphIsWired() {
        assertThat(ilab.iptv.player.core.model.ChannelGroup.entries).isNotEmpty()
    }
}
