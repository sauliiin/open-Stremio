package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/** Why a stream can or cannot reach the player. */
@Serializable
enum class StreamKind {
    /** A URL ExoPlayer can open directly — the only kind this app plays. */
    DIRECT,

    /** An `infoHash` with no debrid resolution behind it. */
    TORRENT,

    /** The addon pointed at a web page, not a media file. */
    EXTERNAL,
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
) {
    val playable: Boolean get() = kind == StreamKind.DIRECT && !url.isNullOrBlank()
}

/** The result of fanning a stream request out over every installed addon. */
@Serializable
data class StreamQuery(
    val streams: List<PlayableStream> = emptyList(),
    val queried: Int = 0,
    val failed: Int = 0,
)

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
