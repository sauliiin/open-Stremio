package com.mdblisthub.tv.core.data.mapper

import com.mdblisthub.tv.core.model.RatingBadge
import com.mdblisthub.tv.core.model.RatingTone
import com.mdblisthub.tv.core.network.dto.MdbRatingDto
import com.mdblisthub.tv.core.network.dto.OmdbDto
import com.mdblisthub.tv.core.network.dto.orNullIfNA
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Normalises the aggregators mdblist relays into badges the screen can paint
 * without knowing anything about the source's scale — IMDb is out of 10,
 * Letterboxd out of 5, Roger Ebert out of 4, the rest percentages.
 */
object RatingsMapper {

    private val ptBR = Locale.forLanguageTag("pt-BR")

    private data class Spec(
        val label: String,
        val tone: RatingTone,
        val order: Int,
        val display: (Double) -> String,
        val score: (Double) -> Double,
    )

    private fun oneDecimal(value: Double) = String.format(ptBR, "%.1f", value)
    private fun percent(value: Double) = "${value.roundToInt()}%"

    private val SOURCES: Map<String, Spec> = mapOf(
        "imdb" to Spec("IMDb", RatingTone.IMDB, 1, ::oneDecimal) { it * 10 },
        "tomatoes" to Spec("Tomatometer", RatingTone.RT_FRESH, 2, ::percent) { it },
        "popcorn" to Spec("RT Público", RatingTone.RT_FRESH, 3, ::percent) { it },
        "metacritic" to Spec("Metacritic", RatingTone.METACRITIC, 4, { "${it.roundToInt()}" }) { it },
        "letterboxd" to Spec("Letterboxd", RatingTone.LETTERBOXD, 5, { "${oneDecimal(it)}/5" }) { it / 5 * 100 },
        "trakt" to Spec("Trakt", RatingTone.TRAKT, 6, ::percent) { it },
        "tmdb" to Spec("TMDB", RatingTone.TMDB, 7, ::percent) { it },
        "metacriticuser" to Spec("Metacritic Users", RatingTone.METACRITIC, 8, ::oneDecimal) { it * 10 },
        "rogerebert" to Spec("Roger Ebert", RatingTone.NEUTRAL, 9, { "${oneDecimal(it)}/4" }) { it / 4 * 100 },
        "myanimelist" to Spec("MyAnimeList", RatingTone.NEUTRAL, 10, ::oneDecimal) { it * 10 },
    )

    fun fromMdblist(ratings: List<MdbRatingDto>?): List<RatingBadge> {
        if (ratings.isNullOrEmpty()) return emptyList()

        return ratings
            .mapNotNull { rating ->
                val spec = SOURCES[rating.source] ?: return@mapNotNull null
                val value = rating.value ?: return@mapNotNull null

                val tone = if (spec.tone == RatingTone.RT_FRESH && !isFresh(rating, value)) {
                    RatingTone.RT_ROTTEN
                } else {
                    spec.tone
                }

                spec.order to RatingBadge(
                    key = rating.source,
                    label = spec.label,
                    display = spec.display(value),
                    score = clamp(rating.score ?: spec.score(value)),
                    votes = rating.votes,
                    tone = tone,
                )
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** The fallback when mdblist has nothing on a title but OMDb answered. */
    fun fromOmdb(omdb: OmdbDto?): List<RatingBadge> {
        if (omdb == null || !omdb.ok) return emptyList()
        val badges = mutableListOf<RatingBadge>()

        omdb.imdbRating.orNullIfNA()?.toDoubleOrNull()?.let { value ->
            badges += RatingBadge(
                key = "imdb",
                label = "IMDb",
                display = oneDecimal(value),
                score = clamp(value * 10),
                votes = omdb.imdbVotes.orNullIfNA()?.filter { it.isDigit() }?.toLongOrNull(),
                tone = RatingTone.IMDB,
            )
        }

        omdb.ratings.firstOrNull { it.source == "Rotten Tomatoes" }
            ?.value?.removeSuffix("%")?.toDoubleOrNull()
            ?.let { value ->
                badges += RatingBadge(
                    key = "tomatoes",
                    label = "Tomatometer",
                    display = percent(value),
                    score = clamp(value),
                    votes = null,
                    tone = if (value >= 60) RatingTone.RT_FRESH else RatingTone.RT_ROTTEN,
                )
            }

        omdb.metascore.orNullIfNA()?.toDoubleOrNull()?.let { value ->
            badges += RatingBadge(
                key = "metacritic",
                label = "Metacritic",
                display = "${value.roundToInt()}",
                score = clamp(value),
                votes = null,
                tone = RatingTone.METACRITIC,
            )
        }

        return badges
    }

    private fun isFresh(rating: MdbRatingDto, value: Double): Boolean =
        rating.fresh == 1 || (rating.fresh == null && value >= 60)

    private fun clamp(score: Double): Int = score.roundToInt().coerceIn(0, 100)
}
