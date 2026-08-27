package com.mdblisthub.tv.core.network

import com.mdblisthub.tv.core.network.dto.TmdbDetailDto
import com.mdblisthub.tv.core.network.dto.TmdbPageDto
import com.mdblisthub.tv.core.network.dto.TmdbPersonDto
import com.mdblisthub.tv.core.network.dto.TmdbSeasonDto
import com.mdblisthub.tv.core.network.dto.TmdbFindDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    /**
     * One request for the whole detail screen. `append_to_response` is what
     * keeps this to a single round trip instead of seven — which on a TV box
     * over Wi-Fi is the difference between a screen that opens and one that
     * assembles itself in front of you.
     */
    @GET("{type}/{id}")
    suspend fun detail(
        @Path("type") type: String,
        @Path("id") tmdbId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("append_to_response") append: String,
        @Query("include_image_language") imageLanguage: String,
        @Query("include_video_language") videoLanguage: String,
    ): TmdbDetailDto

    @GET("tv/{id}/season/{season}")
    suspend fun season(
        @Path("id") tmdbId: Int,
        @Path("season") seasonNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
    ): TmdbSeasonDto

    /** Titles TMDB pairs with this one — the "porque você assistiu" rows. */
    @GET("{type}/{id}/recommendations")
    suspend fun recommendations(
        @Path("type") type: String,
        @Path("id") tmdbId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
    ): TmdbPageDto

    /**
     * The other neighbourhood around a title, merged with [recommendations].
     *
     * Not a spare copy of it: TMDB derives `/recommendations` from what
     * audiences went on to watch and `/similar` from the title's own keywords
     * and genres, so the two answer with overlapping but genuinely different
     * sets. Asking both is what widens the candidate pool without reaching
     * for page two of either — the second page of one endpoint is that
     * endpoint's weaker half, whereas this is a first page of comparable
     * standing.
     */
    @GET("{type}/{id}/similar")
    suspend fun similar(
        @Path("type") type: String,
        @Path("id") tmdbId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
    ): TmdbPageDto

    /** Global movie picks used when there is no watch history to personalize from. */
    @GET("movie/popular")
    suspend fun popularMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("page") page: Int = 1,
    ): TmdbPageDto

    @GET("search/multi")
    suspend fun search(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("query") query: String,
        @Query("include_adult") includeAdult: Boolean,
        @Query("page") page: Int,
    ): TmdbPageDto

    @GET("find/{externalId}")
    suspend fun findByImdb(
        @Path("externalId") imdbId: String,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("external_source") externalSource: String = "imdb_id",
    ): TmdbFindDto

    /** Fallback source for the cast popup's bio, and the only source for a person's birthday. */
    @GET("person/{id}")
    suspend fun person(
        @Path("id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
    ): TmdbPersonDto

    @GET("search/keyword")
    suspend fun searchKeyword(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1,
    ): com.mdblisthub.tv.core.network.dto.TmdbKeywordPageDto

    @GET("discover/movie")
    suspend fun discoverMovie(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("with_keywords") keywords: String,
        @Query("page") page: Int = 1,
    ): TmdbPageDto

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String,
        @Query("with_keywords") keywords: String,
        @Query("page") page: Int = 1,
    ): TmdbPageDto

    companion object {
        const val DETAIL_APPEND =
            "credits,aggregate_credits,external_ids,videos,recommendations,images,content_ratings,release_dates,reviews"

        /** `null` is TMDB's spelling for "artwork with no text on it". */
        const val IMAGE_LANGUAGES = "pt,en,null"

        const val VIDEO_LANGUAGES = "pt,en,null"
    }
}
