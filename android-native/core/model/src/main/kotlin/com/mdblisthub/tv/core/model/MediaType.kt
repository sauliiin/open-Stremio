package com.mdblisthub.tv.core.model

import kotlinx.serialization.Serializable

/**
 * The one type distinction the whole app turns on, and the three vocabularies
 * it has to speak: mdblist/Trakt say `movie`/`show`, TMDB says `movie`/`tv`,
 * and the Stremio protocol says `movie`/`series`. Keeping the mapping here is
 * what stops each service from inventing its own conversion.
 */
@Serializable
enum class MediaType(val mdblist: String, val tmdb: String, val stremio: String) {
    MOVIE("movie", "movie", "movie"),
    SHOW("show", "tv", "series");

    companion object {
        fun fromMdblist(value: String?): MediaType =
            if (value.equals("show", true)) SHOW else MOVIE

        fun fromTmdb(value: String?): MediaType =
            if (value.equals("tv", true)) SHOW else MOVIE

        fun fromStremio(value: String?): MediaType =
            if (value.equals("series", true)) SHOW else MOVIE

        /** Parses whichever spelling turns up, defaulting to a film. */
        fun parse(value: String?): MediaType = when (value?.lowercase()) {
            "show", "tv", "series" -> SHOW
            else -> MOVIE
        }
    }
}
