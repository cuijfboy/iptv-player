package ilab.iptv.player.core.domain.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.SourceKind
import org.junit.Test

/**
 * The pure subscription rules (P2-6 正篇 item 6: "URL 校验与去重").
 *
 * These are the answers the form gives, so each case below is one a user can actually hit: a URL with
 * a capital host, a fragment someone copied from a browser, the same list typed twice with different
 * capitalisation, a `rtsp://` line that the transport cannot fetch at all.
 */
class SubscriptionRulesTest {

    @Test
    fun `normalization lowercases scheme and host, drops the default port and the fragment`() {
        assertThat(SubscriptionRules.normalizeUrl("HTTPS://Example.COM:443/list.m3u#frag"))
            .isEqualTo("https://example.com/list.m3u")
        assertThat(SubscriptionRules.normalizeUrl("http://Example.com:80/a//b"))
            .isEqualTo("http://example.com/a/b")
    }

    @Test
    fun `a non-default port and a query string survive`() {
        assertThat(SubscriptionRules.normalizeUrl("http://example.com:8080/list.m3u?fmt=m3u"))
            .isEqualTo("http://example.com:8080/list.m3u?fmt=m3u")
    }

    @Test
    fun `a Lan portal without a dot is accepted`() {
        assertThat(SubscriptionRules.normalizeUrl("http://tvbox:8080/live.txt"))
            .isEqualTo("http://tvbox:8080/live.txt")
    }

    @Test
    fun `non-http schemes, junk and blank input are rejected`() {
        // rtsp:// throws inside OkHttp (docs/02 §4.0 F2), so accepting it here would fail at fetch time.
        assertThat(SubscriptionRules.normalizeUrl("rtsp://example.com/live")).isNull()
        assertThat(SubscriptionRules.normalizeUrl("file:///sdcard/list.m3u")).isNull()
        assertThat(SubscriptionRules.normalizeUrl("example.com/list.m3u")).isNull()
        assertThat(SubscriptionRules.normalizeUrl("http://")).isNull()
        assertThat(SubscriptionRules.normalizeUrl("http://:8080/list.m3u")).isNull()
        assertThat(SubscriptionRules.normalizeUrl("   ")).isNull()
    }

    @Test
    fun `the id is derived from the normalized url, so one list keeps one id`() {
        val first = SubscriptionRules.idFor("https://example.com/list.m3u")
        val again = SubscriptionRules.idFor("https://example.com/list.m3u")
        val other = SubscriptionRules.idFor("https://example.com/other.m3u")

        assertThat(first).isEqualTo(again)
        assertThat(first).isNotEqualTo(other)
        assertThat(first).startsWith("sub:")
    }

    @Test
    fun `an empty label falls back to the host`() {
        assertThat(SubscriptionRules.labelFor("  ", "https://example.com/live/list.m3u"))
            .isEqualTo("example.com")
        assertThat(SubscriptionRules.labelFor(" 我的源 ", "https://example.com/list.m3u"))
            .isEqualTo("我的源")
    }

    @Test
    fun `the format hint maps onto the frozen SourceKind`() {
        assertThat(SubscriptionRules.kindFor(PlaylistHint.AUTO)).isEqualTo(SourceKind.M3U)
        assertThat(SubscriptionRules.kindFor(PlaylistHint.M3U)).isEqualTo(SourceKind.M3U)
        assertThat(SubscriptionRules.kindFor(PlaylistHint.TXT)).isEqualTo(SourceKind.TXT)
    }

    @Test
    fun `validate rejects the same url on another row`() {
        val existing = listOf(source("sub:a", "https://example.com/list.m3u"))
        val draft = SourceDraft(label = "第二个", url = "HTTPS://Example.com:443/list.m3u", hint = PlaylistHint.AUTO)

        val rejected = SubscriptionRules.validate(draft, existing)

        assertThat(rejected).isInstanceOf(SubscriptionValidation.Rejected::class.java)
        assertThat((rejected as SubscriptionValidation.Rejected).message).contains("已经添加过")
    }

    @Test
    fun `validate lets a row keep its own url while being edited`() {
        val existing = listOf(source("sub:a", "https://example.com/list.m3u"))
        val draft = SourceDraft(label = "改名", url = "https://example.com/list.m3u", hint = PlaylistHint.TXT)

        val accepted = SubscriptionRules.validate(draft, existing, editingId = "sub:a")

        assertThat(accepted).isInstanceOf(SubscriptionValidation.Accepted::class.java)
        assertThat((accepted as SubscriptionValidation.Accepted).label).isEqualTo("改名")
    }

    @Test
    fun `validate rejects a blank or spaced url with a readable message`() {
        val blank = SubscriptionRules.validate(
            SourceDraft(label = "x", url = "  ", hint = PlaylistHint.AUTO),
            emptyList(),
        )
        val spaced = SubscriptionRules.validate(
            SourceDraft(label = "x", url = "https://exa mple.com/list.m3u", hint = PlaylistHint.AUTO),
            emptyList(),
        )

        assertThat((blank as SubscriptionValidation.Rejected).message).contains("请填写订阅地址")
        assertThat((spaced as SubscriptionValidation.Rejected).message).contains("空格")
    }

    @Test
    fun `the stored config is a user row with the normalized url and the chosen switch`() {
        val draft = SourceDraft(
            label = "",
            url = "https://Example.com/list.m3u",
            hint = PlaylistHint.TXT,
            enabled = false,
        )
        val accepted = SubscriptionRules.validate(draft, emptyList()) as SubscriptionValidation.Accepted
        val id = SubscriptionRules.idFor(accepted.normalizedUrl)

        val config = SubscriptionRules.toConfig(draft, id, accepted.normalizedUrl)

        assertThat(config.id).isEqualTo(id)
        assertThat(config.providerId).isEqualTo(id)
        assertThat(config.url).isEqualTo("https://example.com/list.m3u")
        assertThat(config.label).isEqualTo("example.com")
        assertThat(config.kind).isEqualTo(SourceKind.TXT)
        assertThat(config.enabled).isFalse()
        assertThat(config.builtIn).isFalse()
    }

    @Test
    fun `the import owns the channel rows and a refresh may not rewrite them`() {
        assertThat(CatalogPriority.mayRewriteChannels(CatalogWriter.LOCAL_IMPORT)).isTrue()
        assertThat(CatalogPriority.mayRewriteChannels(CatalogWriter.SUBSCRIPTION_REFRESH)).isFalse()
        assertThat(CatalogPriority.rowsAfterBoth()).isEqualTo(CatalogWriter.LOCAL_IMPORT)
    }

    private fun source(id: String, url: String) = ManagedSource(
        id = id,
        label = id,
        url = url,
        kind = SourceKind.M3U,
        enabled = true,
        builtIn = false,
        lastFetchAtMs = null,
        lastResult = null,
        lastFailure = null,
        entryCount = null,
    )
}
