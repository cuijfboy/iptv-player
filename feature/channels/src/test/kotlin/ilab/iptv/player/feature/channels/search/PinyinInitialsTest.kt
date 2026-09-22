package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/04 P3-2: 拼音首字母映射（种子表）— the letters the remote can actually type. */
class PinyinInitialsTest {

    @Test
    fun `chinese names reduce to their initials`() {
        assertThat(PinyinInitials.initialsOf("新闻联播")).isEqualTo("xwlb")
        assertThat(PinyinInitials.initialsOf("湖南卫视")).isEqualTo("hnws")
        assertThat(PinyinInitials.initialsOf("中央电视台")).isEqualTo("zydst")
    }

    @Test
    fun `latin and digits are kept as typed`() {
        assertThat(PinyinInitials.initialsOf("CCTV1 综合")).isEqualTo("cctv1zh")
        assertThat(PinyinInitials.initialsOf("CCTV-5")).isEqualTo("cctv5")
    }

    @Test
    fun `characters outside the seeded table are skipped, not guessed`() {
        // 囍 is not in the table: it contributes nothing, and the other characters still work.
        assertThat(PinyinInitials.initialsOf("囍新闻")).isEqualTo("xw")
        assertThat(PinyinInitials.initialsOf("!!!")).isEmpty()
    }

    @Test
    fun `the table covers the channel families this product ships`() {
        assertThat(PinyinInitials.initialsOf("北京卫视")).isEqualTo("bjws")
        assertThat(PinyinInitials.initialsOf("浙江卫视")).isEqualTo("zjws")
        assertThat(PinyinInitials.initialsOf("东方卫视")).isEqualTo("dfws")
        assertThat(PinyinInitials.size()).isGreaterThan(100)
    }
}
