package ilab.iptv.player.core.data.mapper

import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.source.normalize.NormalizedChannel
import ilab.iptv.player.core.source.normalize.NormalizedPlaylist

/**
 * docs/02 §5.2's middle arrow: normalized playlist → domain model. Deliberately a pure object so it
 * is unit-tested without Android, and deliberately the **only** place that assigns ids in P1-2.
 *
 * Id policy (in-memory stage): a channel's id is `firstSeenIndex + 1` over the normalized list order
 * and a stream's id is a global running counter, because docs/02 §5.1 gets ids from Room's
 * `AUTOINCREMENT`. That makes ids stable for one load and *only* for one load — they are not
 * identity across refreshes, which is exactly what P2-1 has to fix with the real upsert
 * (`UNIQUE(name_key, group_key)` is the stable key; the id is not).
 *
 * Fields the pipeline cannot know yet stay at their "empty" values instead of being guessed:
 * `quality` / `videoCodec` / `audioCodec` / `width` / `height` come from deep validation (P2-3) and
 * `score` / `priority` / health from the scorer and the probe loop, so all of them are 0/null here.
 */
object ChannelMapper {

    fun toDomain(playlist: NormalizedPlaylist): MappedCatalog {
        val streams = ArrayList<Stream>(playlist.entries.size)
        var nextStreamId = 1L
        val channels = ArrayList<Channel>(playlist.channels.size)

        playlist.channels.forEachIndexed { index, normalized ->
            val channelId = (index + 1).toLong()
            val channelStreams = normalized.streams.map { stream ->
                Stream(
                    id = nextStreamId++,
                    channelId = channelId,
                    url = stream.url,
                    urlHash = stream.urlHash,
                    userAgent = stream.entry.userAgent,
                    referrer = stream.entry.referrer,
                    sourceId = stream.entry.sourceId,
                    quality = null,
                    videoCodec = null,
                    audioCodec = null,
                    width = 0,
                    height = 0,
                    score = 0,
                    priority = 0,
                    lastOkAtMs = null,
                    lastCheckAtMs = null,
                    failCount = 0,
                    lastError = null,
                    disabled = false,
                )
            }
            channels += toChannel(channelId, normalized, channelStreams)
            streams += channelStreams
        }

        return MappedCatalog(channels = channels, streams = streams)
    }

    private fun toChannel(id: Long, normalized: NormalizedChannel, streams: List<Stream>): Channel {
        val entries = normalized.streams.map { it.entry }
        return Channel(
            id = id,
            name = normalized.name,
            tvgId = entries.firstNotNullOfOrNull { it.tvgId?.takeIf(String::isNotBlank) },
            group = ChannelGrouping.classify(normalized.groupTitle),
            logoUrl = entries.firstNotNullOfOrNull { it.logo?.takeIf(String::isNotBlank) },
            // D12 tier 2: the source's tvg-chno. A channel's streams may disagree; the first one that
            // declares a number wins, which keeps the choice deterministic for a given source order.
            channelNo = entries.firstNotNullOfOrNull { it.channelNo?.takeIf { number -> number > 0 } },
            favorite = false,
            hidden = false,
            sortOrder = 0,
            epgChannelId = null,
            epgMatch = EpgMatchType.NONE,
            streamCount = streams.size,
            nameKey = normalized.nameKey,
            groupKey = normalized.groupKey,
            groupTitle = normalized.groupTitle,
            createdAtMs = 0,
            updatedAtMs = 0,
        )
    }
}

/** One load's channels and streams; ids inside are consistent with each other. */
data class MappedCatalog(val channels: List<Channel>, val streams: List<Stream>)
