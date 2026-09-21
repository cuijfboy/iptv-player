package ilab.iptv.player.core.ui.import

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import org.junit.Test

/**
 * BUG-20260922-013 fixed the *dialog*, but the thing that decides what the drop-folder step shows is
 * this model — and "no files" vs "three files" is exactly the pair QA had to tell apart on a TV. The
 * cases below (none / one / several) are the whole decision table.
 */
class ImportPickerModelTest {

    private val emptyMessage =
        "没有找到可用文件。\n\n把 .m3u / .m3u8 / .txt 播放列表放到：\n/app/files/playlists"

    private fun candidate(name: String) = ImportCandidate(
        path = "/app/files/playlists/$name",
        name = name,
        sizeBytes = 1_024,
        modifiedAtMs = 1_790_008_509_875L,
    )

    @Test
    fun `no candidates shows the explanation with its drop-folder path, not an empty list`() {
        val content = ImportPickerModel.pickContent(emptyList(), emptyMessage)

        assertThat(content).isEqualTo(ImportPickContent.Empty(emptyMessage))
    }

    @Test
    fun `one candidate becomes one row`() {
        val content = ImportPickerModel.pickContent(
            candidates = listOf(candidate("real-list.m3u")),
            emptyMessage = emptyMessage,
            rowLabel = { it.name },
        )

        assertThat(content).isEqualTo(ImportPickContent.Files(listOf("real-list.m3u")))
    }

    @Test
    fun `several candidates keep the order they were listed in`() {
        val content = ImportPickerModel.pickContent(
            candidates = listOf(
                candidate("a-list.txt"),
                candidate("b-list.m3u"),
                candidate("c-list.m3u8"),
            ),
            emptyMessage = emptyMessage,
            rowLabel = { it.name },
        )

        assertThat(content).isEqualTo(
            ImportPickContent.Files(listOf("a-list.txt", "b-list.m3u", "c-list.m3u8")),
        )
    }

    @Test
    fun `the default row label is the shared picker format`() {
        val content = ImportPickerModel.pickContent(listOf(candidate("list.m3u")), emptyMessage)

        assertThat((content as ImportPickContent.Files).rows.single())
            .isEqualTo(ImportCandidateLabel.describe(candidate("list.m3u")))
    }

    @Test
    fun `the two entrances are listed picker-first so one index maps to the same action everywhere`() {
        assertThat(ImportEntrance.entries.toList())
            .containsExactly(ImportEntrance.SystemPicker, ImportEntrance.DropFolder)
            .inOrder()
    }
}
