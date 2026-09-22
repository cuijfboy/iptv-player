package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/04 P3-2: 搜索索引与匹配（含中英文/大小写）. */
class SearchTextTest {

    @Test
    fun `normalization folds case, separators and full-width forms`() {
        assertThat(SearchText.normalize("CCTV1 综合")).isEqualTo("cctv1综合")
        assertThat(SearchText.normalize("CCTV-1")).isEqualTo("cctv1")
        assertThat(SearchText.normalize("ＣＣＴＶ－１")).isEqualTo("cctv1")
        assertThat(SearchText.normalize("  湖南 卫视  ")).isEqualTo("湖南卫视")
        assertThat(SearchText.normalize("央视/新闻")).isEqualTo("央视新闻")
    }

    @Test
    fun `quality ranks equality over prefix over substring`() {
        assertThat(SearchText.quality("cctv1", "cctv1")).isEqualTo(0)
        assertThat(SearchText.quality("cctv1综合", "cctv1")).isEqualTo(1)
        assertThat(SearchText.quality("cctv1综合", "综合")).isEqualTo(2)
        assertThat(SearchText.quality("cctv1综合", "cctv2")).isNull()
        assertThat(SearchText.quality("cctv1", "")).isNull()
    }

    @Test
    fun `chinese substrings match wherever they appear`() {
        val key = SearchText.normalize("CCTV1 新闻综合")
        assertThat(SearchText.quality(key, SearchText.normalize("新闻"))).isEqualTo(2)
        assertThat(SearchText.quality(key, SearchText.normalize("cctv1"))).isEqualTo(1)
    }
}
