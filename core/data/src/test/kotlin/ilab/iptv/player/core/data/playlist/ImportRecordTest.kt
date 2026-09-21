package ilab.iptv.player.core.data.playlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The remembered-import file is hand-written JSON, so the two things that matter are: it round-trips
 * whatever a file name can contain, and every way of being broken reads as "no remembered import"
 * rather than as a crash at start-up.
 */
class ImportRecordTest {

    private val record = ImportRecord(
        name = "我的 列表 \"quoted\" \\ back\nslash.m3u",
        sourceId = "local:我的 列表.m3u",
        copiedPath = "/app/files/imports/list.m3u",
        sizeBytes = 123456L,
        importedAtMs = 1_700_000_000_000L,
        formatLabel = "m3u",
        channels = 658,
        streams = 670,
    )

    @Test
    fun `round trip keeps every field, including quotes and newlines in the name`() {
        assertThat(ImportRecord.decode(record.encode())).isEqualTo(record)
    }

    @Test
    fun `the encoded record is a single line`() {
        assertThat(record.encode()).doesNotContain("\n")
    }

    @Test
    fun `broken input decodes to null instead of throwing`() {
        val broken = listOf(
            "",
            "{ not json",
            "[]",
            "{}",
            """{"version":"1"}""",
            """{"version":"2","name":"x","sourceId":"s","copiedPath":"p","sizeBytes":"1","importedAtMs":"2"}""",
            """{"version":"1","name":"","sourceId":"s","copiedPath":"p","sizeBytes":"1","importedAtMs":"2"}""",
            """{"version":"1","name":"x","sourceId":"s","copiedPath":"p","sizeBytes":"nan","importedAtMs":"2"}""",
            """{"version":"1","name":"x","sourceId":"s","copiedPath":"p","sizeBytes":"-5","importedAtMs":"2"}""",
        )

        for (json in broken) {
            assertThat(ImportRecord.decode(json)).isNull()
        }
    }

    @Test
    fun `a record without the optional counters still decodes`() {
        val json =
            """{"version":"1","name":"x.m3u","sourceId":"local:x.m3u","copiedPath":"/p/x.m3u",""" +
                """"sizeBytes":"10","importedAtMs":"1700000000000"}"""

        val decoded = ImportRecord.decode(json)

        assertThat(decoded?.name).isEqualTo("x.m3u")
        assertThat(decoded?.formatLabel).isEqualTo("unknown")
        assertThat(decoded?.channels).isEqualTo(0)
    }
}
