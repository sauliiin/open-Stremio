package com.mdblisthub.tv.core.database

import androidx.room.TypeConverter
import com.mdblisthub.tv.core.model.CastMember
import com.mdblisthub.tv.core.model.MediaItem
import com.mdblisthub.tv.core.model.RatingBadge
import com.mdblisthub.tv.core.model.Review
import com.mdblisthub.tv.core.model.SeasonSummary
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The composite columns are stored as JSON rather than normalised into their
 * own tables.
 *
 * Nothing queries into a cast list or a ratings array — they are read whole
 * with the row that owns them and painted. Normalising would buy joins nobody
 * needs and cost four extra writes per hydrated title.
 */
internal val cacheJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String = cacheJson.encodeToString(value)

    @TypeConverter
    fun toStringList(value: String): List<String> = decode(value, emptyList())

    @TypeConverter
    fun fromCast(value: List<CastMember>): String = cacheJson.encodeToString(value)

    @TypeConverter
    fun toCast(value: String): List<CastMember> = decode(value, emptyList())

    @TypeConverter
    fun fromRatings(value: List<RatingBadge>): String = cacheJson.encodeToString(value)

    @TypeConverter
    fun toRatings(value: String): List<RatingBadge> = decode(value, emptyList())

    @TypeConverter
    fun fromSeasons(value: List<SeasonSummary>): String = cacheJson.encodeToString(value)

    @TypeConverter
    fun toSeasons(value: String): List<SeasonSummary> = decode(value, emptyList())

    @TypeConverter
    fun fromItems(value: List<MediaItem>): String = cacheJson.encodeToString(value)

    @TypeConverter
    fun toItems(value: String): List<MediaItem> = decode(value, emptyList())

    @TypeConverter
    fun fromReviews(value: List<Review>): String = cacheJson.encodeToString(value)

    @TypeConverter
    fun toReviews(value: String): List<Review> = decode(value, emptyList())

    /**
     * A cache row that no longer parses is a row written by an older build,
     * not a bug to crash on — the worker will rewrite it on the next pass.
     */
    private inline fun <reified T> decode(value: String, fallback: T): T =
        runCatching { cacheJson.decodeFromString<T>(value) }.getOrDefault(fallback)
}
