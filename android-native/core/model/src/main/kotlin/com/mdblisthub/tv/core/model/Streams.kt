package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/** Why a stream can or cannot reach the player. */
@Serializable
enum class StreamKind {
    /** A URL ExoPlayer can open directly. */
    DIRECT,

    /**
     * An `infoHash` with no debrid resolution behind it, played through the app's own
     * torrent engine: its [PlayableStream.url] is a loopback address that engine serves.
     */
    TORRENT,

    /** The addon pointed at a web page, not a media file. */
    EXTERNAL,
}

/**
 * How ready a link is to be watched right now, best first — the declaration
 * order is the order the cascade walks them in.
 *
 * Read from the addon's own label, since the protocol has no field for it. An
 * uncached link is not a slower version of a cached one: it asks the debrid
 * service to start fetching the torrent, and what comes back in the meantime is
 * a placeholder clip or a connection that trickles.
 */
@Serializable
enum class SourceReadiness {
    CACHED_REAL_DEBRID,
    CACHED_DEBRID,
    UNKNOWN,

    /**
     * A torrent played from its swarm, with no debrid service. After every link a
     * server already holds, because a swarm is slower and less certain than any of
     * them, and before an uncached debrid link, which plays nothing but a notice until
     * the service has fetched the file.
     */
    PEER_TO_PEER,
    NOT_CACHED,
}

/**
 * A source offered by an installed Stremio addon.
 *
 * Note what is *not* here: the container blocklist the browser build needed.
 * With ExoPlayer embedded, MKV, TS, HLS and DASH all play, so a direct URL is
 * playable — which is the main reason this app exists as a native one.
 */
@Serializable
data class PlayableStream(
    val key: String,
    val addon: String,
    val title: String,
    val detail: String? = null,
    val quality: String? = null,
    val size: String? = null,
    /**
     * `behaviorHints.videoSize`, when the addon sent one — the protocol's own
     * field for the size of *this file*, in bytes.
     *
     * Kept beside [size] rather than replacing it because the two answer
     * different questions: [size] is a label for a human and is scraped from
     * free text, this is a number for a decision. See [sizeBytes].
     */
    val sizeBytesHint: Long? = null,
    val url: String? = null,
    val externalUrl: String? = null,
    val kind: StreamKind = StreamKind.DIRECT,
    val filename: String? = null,
    /**
     * Headers the addon asked for (`behaviorHints.proxyHeaders`). They are
     * handed to the HTTP data source as request headers,
     * since some debrid links 403 without the right user agent or referer.
     */
    val headers: Map<String, String> = emptyMap(),
    val readiness: SourceReadiness = SourceReadiness.UNKNOWN,
    /** For a [StreamKind.TORRENT]: lowercase hex v1 info hash. */
    val infoHash: String? = null,
    /** For a [StreamKind.TORRENT]: the addon's `fileIdx`, the file to play. */
    val fileIndex: Int? = null,
    /** For a [StreamKind.TORRENT]: the addon's `sources`, as tracker URLs. */
    val trackers: List<String> = emptyList(),
    /** Seeders the addon's label reported, for a torrent. Its swarm's health, roughly. */
    val seeders: Int? = null,
) {
    val playable: Boolean get() = kind != StreamKind.EXTERNAL && !url.isNullOrBlank()

    /**
     * Advertised size in bytes — what the probe checks a mirror's real response
     * against.
     *
     * [sizeBytesHint] first, always. Falling back to re-parsing [size] is a
     * last resort because that string was itself scraped out of the addon's
     * free-text label, and the number it yields is whatever figure appeared
     * first in that text — for a season pack, typically the size of the *pack*
     * rather than of the episode the link actually serves. Judging a mirror
     * against that rejects a perfectly good file for being a fraction of
     * something it was never supposed to match.
     */
    val sizeBytes: Long?
        get() {
            sizeBytesHint?.takeIf { it > 0 }?.let { return it }
            val match = STREAM_SIZE.matchEntire(size?.trim().orEmpty()) ?: return null
            val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val multiplier = if (match.groupValues[2].equals("GB", ignoreCase = true)) {
                1024.0 * 1024.0 * 1024.0
            } else {
                1024.0 * 1024.0
            }
            return (amount * multiplier).toLong().takeIf { it > 0 }
        }
}

private val STREAM_SIZE = Regex("""(\d+(?:[.,]\d+)?)\s*(GB|MB)""", RegexOption.IGNORE_CASE)

/**
 * An external subtitle track.
 *
 * ExoPlayer side-loads SRT, VTT, SSA/ASS and TTML from a URL, so unlike the
 * browser build there is no WebVTT conversion step — the URL goes to the
 * engine as-is.
 */
@Serializable
data class SubtitleOption(
    val key: String,
    val addon: String,
    val label: String,
    val lang: String,
    val url: String,
    val encoding: String? = null,
    /**
     * Whatever release-identifying text the addon volunteered — not part of
     * the Stremio subtitle protocol, so usually null. What auto-matching a
     * subtitle to the playing release is built on; see `SubtitleMatcher`.
     */
    val releaseHint: String? = null,
    /**
     * Downloads/uses the source itself reports, where it reports one — the
     * Stremio addon protocol doesn't carry this either, so an addon-sourced
     * option is always 0. `SubtitleMatcher`'s tiebreaker when the token match
     * between two options is equal, most often because neither has a
     * [releaseHint] to compare in the first place.
     */
    val popularity: Int = 0,
)
