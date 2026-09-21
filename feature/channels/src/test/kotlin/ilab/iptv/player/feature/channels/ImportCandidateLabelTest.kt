package ilab.iptv.player.feature.channels

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.playlist.ImportCandidate
import java.util.TimeZone
import org.junit.Test

/**
 * The picker row for one file. Small, but it is the only thing telling two same-named playlists
 * apart, so the size and the timestamp must both be there and both be readable.
 */
class ImportCandidateLabelTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun `the row carries the name, a readable size and a local timestamp`() {
        val candidate = ImportCandidate(
            path = "/app/files/playlists/list.m3u",
            name = "list.m3u",
            sizeBytes = 12_595,
            // 2026-09-21T16:35:09Z == 2026-09-22 00:35 in Asia/Shanghai.
            modifiedAtMs = 1_790_008_509_875L,
        )

        assertThat(ImportCandidateLabel.describe(candidate, zone)).isEqualTo("list.m3u · 12.3 KB · 09-22 00:35")
    }

    @Test
    fun `sizes use binary units at every scale`() {
        assertThat(ImportCandidateLabel.size(0)).isEqualTo("0 B")
        assertThat(ImportCandidateLabel.size(999)).isEqualTo("999 B")
        assertThat(ImportCandidateLabel.size(1024)).isEqualTo("1.0 KB")
        assertThat(ImportCandidateLabel.size(1536)).isEqualTo("1.5 KB")
        assertThat(ImportCandidateLabel.size(1024L * 1024L)).isEqualTo("1.0 MB")
        assertThat(ImportCandidateLabel.size(4_500_000)).isEqualTo("4.3 MB")
    }
}
