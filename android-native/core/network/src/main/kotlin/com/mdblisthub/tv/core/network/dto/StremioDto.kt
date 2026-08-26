package com.mdblisthub.tv.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StremioManifestDto(
    val id: String = "",
    val name: String = "",
    val version: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val types: List<String> = emptyList(),
    /**
     * Either a bare string (`"stream"`) or an object with its own type and id
     * filters. Only the name is needed here, so both are read as JSON and
     * flattened by the mapper.
     */
    val resources: List<StremioResourceDto> = emptyList(),
    val catalogs: List<StremioCatalogDescriptorDto> = emptyList(),
    @SerialName("idPrefixes") val idPrefixes: List<String> = emptyList(),
    val behaviorHints: StremioAddonHintsDto? = null,
)

@Serializable
data class StremioAddonHintsDto(
    val configurable: Boolean = false,
    /**
     * Set by an addon that answers on its unconfigured URL as well, to say
     * that this particular answer is the placeholder one — no catalogs, no
     * streams — and that the address the configuration page hands back is
     * what should have been installed.
     */
    val configurationRequired: Boolean = false,
)

@Serializable
data class StremioCatalogDescriptorDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
)

@Serializable
data class StremioCatalogResponseDto(val metas: List<StremioMetaDto> = emptyList())

@Serializable
data class StremioMetaDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
)

/**
 * A `meta` answer. Only the folder case is modelled: the app reads its own
 * metadata from TMDB, so the one thing an addon's meta is needed for is the
 * [StremioMetaDetailDto.videos] list behind an entry that is a container
 * rather than a title — a channel's line-up, most of all.
 */
@Serializable
data class StremioMetaResponseDto(val meta: StremioMetaDetailDto? = null)

@Serializable
data class StremioMetaDetailDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val videos: List<StremioVideoDto> = emptyList(),
)

/**
 * One entry of a container's [StremioMetaDetailDto.videos].
 *
 * `season` and `episode` are deliberately **not** read here, and that is the
 * whole trap in this shape: on a channel they number the slot in that
 * channel's own running order — 1, 2, 3 — while the episode being scheduled
 * is named only inside [id], as `tt0412142:1:11`. Trusting the fields would
 * have every channel playing episode 1 of season 1 twelve times over.
 */
@Serializable
data class StremioVideoDto(
    val id: String = "",
    val title: String? = null,
    val name: String? = null,
    val released: String? = null,
    val thumbnail: String? = null,
)

@Serializable(with = StremioResourceSerializer::class)
data class StremioResourceDto(val name: String)

@Serializable
data class StremioStreamsDto(val streams: List<StremioStreamDto> = emptyList())

@Serializable
data class StremioStreamDto(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val externalUrl: String? = null,
    val behaviorHints: StremioStreamHintsDto? = null,
)

@Serializable
data class StremioStreamHintsDto(
    val filename: String? = null,
    val videoSize: Long? = null,
    val notWebReady: Boolean = false,
    /** Headers the source needs; some debrid links 403 without them. */
    val proxyHeaders: StremioProxyHeadersDto? = null,
)

@Serializable
data class StremioProxyHeadersDto(
    val request: Map<String, String> = emptyMap(),
)

@Serializable
data class StremioSubtitlesDto(val subtitles: List<StremioSubtitleDto> = emptyList())

@Serializable
data class StremioSubtitleDto(
    val id: String? = null,
    val url: String = "",
    val lang: String? = null,
    @SerialName("SubEncoding") val encoding: String? = null,
    /**
     * The Stremio subtitle protocol only guarantees `id`/`url`/`lang` — a
     * release name is not part of the spec. Some addons send one anyway,
     * under whichever of these keys that particular author picked; every
     * candidate is optional and most responses will have none of them.
     * See [SubtitleMatcher][com.mdblisthub.tv.core.data.mapper.SubtitleMatcher]
     * for what this enables when one is present.
     */
    val title: String? = null,
    val release: String? = null,
    @SerialName("SubFileName") val subFileName: String? = null,
)
