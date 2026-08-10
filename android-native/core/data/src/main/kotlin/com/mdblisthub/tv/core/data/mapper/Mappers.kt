package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.database.entity.AddonEntity
import com.mdblisthub.tv.core.database.entity.EpisodeEntity
import com.mdblisthub.tv.core.database.entity.ListEntity
import com.mdblisthub.tv.core.database.entity.MediaDetailEntity
import com.mdblisthub.tv.core.database.entity.MediaEntity
import com.mdblisthub.tv.core.database.entity.ResumeEntity
import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.Episode
import com.mdblisthub.tv.core.model.HubUser
import com.mdblisthub.tv.core.model.MediaDetail
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.MediaList
import com.mdblisthub.tv.core.model.MediaType
import com.mdblisthub.tv.core.model.ResumePoint
import com.mdblisthub.tv.core.model.Review
import com.mdblisthub.tv.core.model.ReviewProvider
import com.mdblisthub.tv.core.model.SeasonSummary
import com.mdblisthub.tv.core.model.TmdbImages
import com.mdblisthub.tv.core.network.HttpClients
import com.mdblisthub.tv.core.network.dto.MdbInfoDto
import com.mdblisthub.tv.core.network.dto.MdbItemDto
import com.mdblisthub.tv.core.network.dto.MdbListDto
import com.mdblisthub.tv.core.network.dto.MdbUserDto
import com.mdblisthub.tv.core.network.dto.OmdbDto
import com.mdblisthub.tv.core.network.dto.PlaybackSessionDto
import com.mdblisthub.tv.core.network.dto.StremioManifestDto
import com.mdblisthub.tv.core.network.dto.TmdbDetailDto
import com.mdblisthub.tv.core.network.dto.TmdbEpisodeDto
import com.mdblisthub.tv.core.network.dto.orNullIfNA
import kotlin.math.roundToInt

// --------------------------------------------------------------- account

fun MdbUserDto.toDomain(): HubUser = HubUser(
    userId = userId,
    username = username,
    name = name,
    avatarUrl = avatarUrl,
    plan = plan,
)

// ----------------------------------------------------------------- lists

fun MdbListDto.toEntity(
    displayName: String,
    position: Int,
    now: Long,
    hidden: Boolean = false,
    itemsFetchedAt: Long = 0,
) = ListEntity(
    id = id,
    name = displayName,
    originalName = name,
    slug = slug,
    description = description,
    mediaType = mediatype,
    itemCount = items,
    dynamic = dynamic,
    private = private,
    hidden = hidden,
    updatedAt = lastUpdatedAt,
    position = position,
    itemsFetchedAt = itemsFetchedAt,
    fetchedAt = now,
)

fun ListEntity.toDomain() = MediaList(
    id = id,
    name = name,
    originalName = originalName,
    slug = slug,
    description = description,
    mediaType = mediaType?.let(MediaType::parse),
    itemCount = itemCount,
    dynamic = dynamic,
    private = private,
    hidden = hidden,
    position = position,
    updatedAt = updatedAt,
)

// ------------------------------------------------------------ media card

fun MdbItemDto.toEntity(now: Long): MediaEntity {
    val type = MediaType.fromMdblist(mediatype)
    return MediaEntity(
        tmdbId = ids?.tmdb ?: id,
        type = type.mdblist,
        imdbId = imdbId ?: ids?.imdb,
        title = title,
        year = releaseYear,
        // Normalised onto POSTER_CARD so every card, whatever its source,
        // downloads the one size the row actually paints.
        posterUrl = TmdbImages.upscale(poster),
        backdropUrl = null,
        genres = genre.orEmpty(),
        runtimeMinutes = runtime?.takeIf { it > 0 },
        score = ratings?.firstOrNull { it.source == "imdb" }?.value?.let { (it * 10).roundToInt() },
        fetchedAt = now,
    )
}

fun MediaEntity.toDomain() = MediaItem(
    tmdbId = tmdbId,
    type = MediaType.parse(type),
    title = title,
    imdbId = imdbId,
    year = year,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    genres = genres,
    runtimeMinutes = runtimeMinutes,
    score = score,
)

// ---------------------------------------------------------- media detail

private val DIRECTOR_JOBS = setOf("Director", "Series Director")
private val WRITER_JOBS = setOf("Screenplay", "Writer", "Story")

/**
 * Folds the three metadata sources into one cache row.
 *
 * TMDB is the spine because it resolves the IMDb id everything else is keyed
 * by; mdblist contributes the aggregated ratings, and OMDb only fills the gaps
 * mdblist left. Any of the three being absent degrades the row rather than
 * failing it — a detail screen with no Metacritic score is still a screen.
 */
fun buildDetailEntity(
    type: MediaType,
    tmdbId: Int,
    tmdb: TmdbDetailDto,
    info: MdbInfoDto?,
    omdb: OmdbDto?,
    now: Long,
    metadataComplete: Boolean = true,
): MediaDetailEntity {
    val credits = tmdb.credits ?: tmdb.aggregateCredits
    val crew = credits?.crew.orEmpty()
    val date = tmdb.date ?: info?.released

    val ratings = RatingsMapper.fromMdblist(info?.ratings)
        .ifEmpty { RatingsMapper.fromOmdb(omdb) }

    // A logo with no text baked in reads best over fanart, and TMDB ranks its
    // artwork by votes — so the best-voted Portuguese or English one wins.
    val logo = tmdb.images?.logos
        ?.sortedByDescending { it.voteAverage }
        ?.firstOrNull { it.language == "pt" || it.language == "en" || it.language == null }

    return MediaDetailEntity(
        tmdbId = tmdbId,
        type = type.mdblist,
        imdbId = tmdb.externalIds?.imdbId ?: info?.ids?.imdb,
        title = tmdb.displayTitle.ifBlank { info?.title.orEmpty() },
        originalTitle = tmdb.displayOriginalTitle,
        tagline = tmdb.tagline?.takeIf { it.isNotBlank() } ?: info?.tagline,
        overview = tmdb.overview?.takeIf { it.isNotBlank() }
            ?: info?.description
            ?: omdb?.plot.orNullIfNA(),
        posterUrl = TmdbImages.url(tmdb.posterPath, TmdbImages.POSTER_LARGE),
        backdropUrl = TmdbImages.url(tmdb.backdropPath, TmdbImages.BACKDROP_FANART),
        logoUrl = TmdbImages.url(logo?.filePath, TmdbImages.LOGO),
        year = date?.take(4)?.toIntOrNull() ?: info?.year,
        releaseDate = date,
        runtimeMinutes = tmdb.minutes ?: info?.runtime,
        seasonCount = tmdb.numberOfSeasons,
        episodeCount = tmdb.numberOfEpisodes,
        status = tmdb.status,
        certification = certificationOf(tmdb) ?: omdb?.rated.orNullIfNA(),
        trailerKey = trailerKeyOf(tmdb),
        budget = tmdb.budget?.takeIf { it > 0 } ?: info?.budget,
        revenue = tmdb.revenue?.takeIf { it > 0 } ?: info?.revenue,
        genres = tmdb.genres.map { it.name },
        directors = crew.filter { it.holds(DIRECTOR_JOBS) }.map { it.name }.distinct().take(3),
        writers = crew.filter { it.holds(WRITER_JOBS) }.map { it.name }.distinct().take(3),
        studios = tmdb.companies.map { it.name }.take(4),
        castMembers = credits?.cast.orEmpty().take(24).map {
            CastMember(
                id = it.id,
                name = it.name,
                character = it.role,
                profileUrl = TmdbImages.url(it.profilePath, TmdbImages.PROFILE),
            )
        },
        ratings = ratings,
        seasons = tmdb.seasons
            .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
            .map {
                SeasonSummary(
                    seasonNumber = it.seasonNumber,
                    name = it.name,
                    episodeCount = it.episodeCount,
                    airDate = it.airDate,
                    posterUrl = TmdbImages.url(it.posterPath, TmdbImages.POSTER_CARD),
                )
            },
        recommendations = tmdb.recommendations?.results.orEmpty().take(12).map { result ->
            val recType = result.mediaType?.let(MediaType::fromTmdb) ?: type
            MediaItem(
                tmdbId = result.id,
                type = recType,
                title = result.title ?: result.name.orEmpty(),
                year = (result.releaseDate ?: result.firstAirDate)?.take(4)?.toIntOrNull(),
                posterUrl = TmdbImages.url(result.posterPath, TmdbImages.POSTER_CARD),
                backdropUrl = TmdbImages.url(result.backdropPath, TmdbImages.BACKDROP_FANART),
                score = (result.voteAverage * 10).roundToInt().takeIf { it > 0 },
            )
        },
        reviews = info?.reviews.orEmpty().mapNotNull { review ->
            review.content.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Review(
                author = review.author.ifBlank { "Anônimo" },
                content = review.content,
                rating = review.rating,
                // mdblist's `provider_id`: 1 = Trakt, 2 = TMDB.
                provider = if (review.providerId == 1) ReviewProvider.TRAKT else ReviewProvider.TMDB,
                updatedAt = review.updatedAt,
            )
        },
        metadataComplete = metadataComplete,
        fetchedAt = now,
    )
}

/** Brazilian rating first, then the US one, then whatever exists. */
private fun certificationOf(tmdb: TmdbDetailDto): String? {
    tmdb.contentRatings?.results?.let { list ->
        (list.firstOrNull { it.country == "BR" } ?: list.firstOrNull { it.country == "US" })
            ?.rating?.takeIf { it.isNotBlank() }
            ?.let { return it }
    }
    tmdb.releaseDates?.results?.let { list ->
        (list.firstOrNull { it.country == "BR" } ?: list.firstOrNull { it.country == "US" })
            ?.releaseDates?.firstNotNullOfOrNull { it.certification.takeIf(String::isNotBlank) }
            ?.let { return it }
    }
    return null
}

private fun trailerKeyOf(tmdb: TmdbDetailDto): String? {
    val videos = tmdb.videos?.results.orEmpty()
    return videos.firstOrNull { it.site == "YouTube" && it.type == "Trailer" && it.official }?.key
        ?: videos.firstOrNull { it.site == "YouTube" }?.key
}

fun MediaDetailEntity.toDomain() = MediaDetail(
    tmdbId = tmdbId,
    type = MediaType.parse(type),
    title = title,
    imdbId = imdbId,
    originalTitle = originalTitle,
    tagline = tagline,
    overview = overview,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    logoUrl = logoUrl,
    year = year,
    releaseDate = releaseDate,
    runtimeMinutes = runtimeMinutes,
    seasonCount = seasonCount,
    episodeCount = episodeCount,
    status = status,
    genres = genres,
    cast = castMembers,
    directors = directors,
    writers = writers,
    studios = studios,
    certification = certification,
    trailerKey = trailerKey,
    ratings = ratings,
    seasons = seasons,
    recommendations = recommendations,
    reviews = reviews,
    budget = budget,
    revenue = revenue,
)

// -------------------------------------------------------------- episodes

fun TmdbEpisodeDto.toEntity(showTmdbId: Int, now: Long) = EpisodeEntity(
    showTmdbId = showTmdbId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    id = id,
    name = name,
    overview = overview?.takeIf { it.isNotBlank() },
    stillUrl = TmdbImages.url(stillPath, TmdbImages.STILL),
    airDate = airDate,
    runtimeMinutes = runtime?.takeIf { it > 0 },
    voteAverage = voteAverage.takeIf { it > 0 },
    fetchedAt = now,
)

fun EpisodeEntity.toDomain() = Episode(
    id = id,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    name = name,
    overview = overview,
    stillUrl = stillUrl,
    airDate = airDate,
    runtimeMinutes = runtimeMinutes,
    voteAverage = voteAverage,
)

// ---------------------------------------------------------------- addons

/**
 * `manifestJson` is filled by re-encoding the parsed DTO, not by keeping the
 * response body around — a couple of extra allocations against not having to
 * thread a raw string through Retrofit's typed call. Round-tripping through
 * the same `@Serializable` shape the network layer already validated the
 * response against is what keeps the sync payload well-formed.
 */
fun StremioManifestDto.toEntity(base: String, now: Long) = AddonEntity(
    base = base,
    manifestJson = HttpClients.json.encodeToString(StremioManifestDto.serializer(), this),
    addonId = id,
    name = name,
    description = description,
    logoUrl = logo,
    version = version,
    types = types,
    resources = resources.map { it.name }.filter { it.isNotBlank() },
    idPrefixes = idPrefixes,
    configurable = behaviorHints?.configurable == true,
    addedAt = now,
)

fun AddonEntity.toDomain() = Addon(
    base = base,
    id = addonId,
    name = name,
    description = description,
    logoUrl = logoUrl,
    version = version,
    types = types,
    resources = resources,
    idPrefixes = idPrefixes,
    configurable = configurable,
    addedAt = addedAt,
)

// -------------------------------------------------------------- playback

/**
 * Flattens a `sync/playback` entry into the "continue watching" row.
 *
 * mdblist stores the progress captured at `updated_at` and expects clients to
 * carry it forward — a session left playing on another device would otherwise
 * show a point long past. A paused session is frozen by definition, so only a
 * running one drifts.
 */
fun PlaybackSessionDto.toResumeEntity(now: Long): ResumeEntity? {
    val isEpisode = type == "episode"
    val parent = if (isEpisode) show else movie
    val ids = parent?.ids

    val tmdbId = ids?.tmdb
    val imdbId = ids?.imdb
    if (tmdbId == null && imdbId == null) return null

    val mediaType = if (isEpisode) MediaType.SHOW else MediaType.MOVIE
    val seasonNumber = episode?.season
    val episodeNumber = episode?.number ?: episode?.episode

    val updatedTs = updatedAtTs
    val runtimeMinutes = runtime?.takeIf { it > 0 }

    val live = if (pausedAt != null || updatedTs == null || runtimeMinutes == null) {
        progress
    } else {
        val base = progressAtUpdate ?: progress
        val elapsedMinutes = (now / 1000.0 - updatedTs) / 60.0
        base + (elapsedMinutes / runtimeMinutes) * 100.0
    }

    return ResumeEntity(
        key = "${mediaType.mdblist}:${tmdbId ?: imdbId}:${seasonNumber ?: 0}:${episodeNumber ?: 0}",
        type = mediaType.mdblist,
        tmdbId = tmdbId,
        imdbId = imdbId,
        title = parent.title ?: "Sem título",
        posterUrl = null,
        backdropUrl = null,
        score = null,
        season = seasonNumber,
        episode = episodeNumber,
        progress = ((live * 10).roundToInt() / 10.0).coerceIn(0.0, 100.0).toFloat(),
        updatedAt = updatedAt,
        fetchedAt = now,
    )
}

fun ResumeEntity.toDomain() = ResumePoint(
    type = MediaType.parse(type),
    tmdbId = tmdbId,
    imdbId = imdbId,
    title = title,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    score = score,
    season = season,
    episode = episode,
    progress = progress,
    updatedAt = updatedAt,
)
