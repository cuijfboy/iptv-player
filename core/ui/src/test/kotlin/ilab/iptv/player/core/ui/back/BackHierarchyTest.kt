package ilab.iptv.player.core.ui.back

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * P3-7 item 1's table, as an assertion (docs/02 §8.1 "返回键层级"):
 *
 * | screen | BACK goes to |
 * |---|---|
 * | 浏览页 | 系统（App 根） |
 * | 播放页 / EPG 网格 / 搜索 / 设置 / 源管理 | 浏览页 |
 * | 诊断面板 / 日志控制台 | 设置页 |
 *
 * Every screen of [TvScreen] is asserted individually, so adding a screen without deciding its BACK
 * behaviour fails the suite instead of silently inheriting the framework default.
 */
class BackHierarchyTest {

    @Test
    fun `the browse screen is the root and its back leaves the app`() {
        assertThat(BackHierarchy.parentOf(TvScreen.BROWSE)).isNull()
        assertThat(BackHierarchy.isRoot(TvScreen.BROWSE)).isTrue()
    }

    @Test
    fun `the five screens one step under browse return to browse`() {
        listOf(
            TvScreen.PLAYER,
            TvScreen.EPG_GRID,
            TvScreen.SEARCH,
            TvScreen.SETTINGS,
            TvScreen.SOURCE_MANAGEMENT,
        ).forEach { screen ->
            assertThat(BackHierarchy.parentOf(screen)).isEqualTo(TvScreen.BROWSE)
        }
    }

    @Test
    fun `the two diagnostic screens return to settings, not straight to browse`() {
        assertThat(BackHierarchy.parentOf(TvScreen.DIAGNOSTICS)).isEqualTo(TvScreen.SETTINGS)
        assertThat(BackHierarchy.parentOf(TvScreen.LOG_CONSOLE)).isEqualTo(TvScreen.SETTINGS)
    }

    @Test
    fun `every screen has a decision and the walk ends at the root`() {
        TvScreen.entries.forEach { screen ->
            val stack = BackHierarchy.stackFrom(screen)
            assertThat(stack.first()).isEqualTo(screen)
            assertThat(stack.last()).isEqualTo(TvScreen.BROWSE)
            // No cycles, no screen appearing twice on the way to the root.
            assertThat(stack).containsNoDuplicates()
        }
    }

    @Test
    fun `the deepest screen walks back through settings before browse`() {
        assertThat(BackHierarchy.stackFrom(TvScreen.DIAGNOSTICS))
            .containsExactly(TvScreen.DIAGNOSTICS, TvScreen.SETTINGS, TvScreen.BROWSE)
            .inOrder()
    }
}
